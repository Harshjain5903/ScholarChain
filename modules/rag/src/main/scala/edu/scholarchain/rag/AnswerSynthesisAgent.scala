package edu.scholarchain.rag

import edu.scholarchain.core.*

import scala.util.Try

/**
 * Synthesizes answer claims by prompting an LLM with evidence. Validates LLM output and retries on
 * malformed responses (max 2 retries).
 */
class AnswerSynthesisAgent(
    llm: LlmClient,
    promptVersion: String = "synthesis-v1"
):

  private val maxRetries = 2

  def synthesize(
      queryPlan: QueryPlan,
      evidence: EvidenceBundle,
      resolvedResearchers: Vector[ResolvedResearcher]
  ): Vector[AnswerClaim] =
    if evidence.items.isEmpty then
      return Vector(abstainClaim(queryPlan.queryId))

    val prompt = buildPrompt(queryPlan, evidence, resolvedResearchers)
    attemptGeneration(prompt, queryPlan.queryId, evidence, 0)

  private def attemptGeneration(
      prompt: String,
      queryId: String,
      evidence: EvidenceBundle,
      attempt: Int
  ): Vector[AnswerClaim] =
    val raw    = llm.generate(prompt, 2048)
    val parsed = Try(parseClaims(raw, queryId, evidence))
    parsed.getOrElse {
      if attempt < maxRetries then
        attemptGeneration(prompt, queryId, evidence, attempt + 1)
      else Vector(abstainClaim(queryId))
    }

  private def parseClaims(
      raw: String,
      queryId: String,
      evidence: EvidenceBundle
  ): Vector[AnswerClaim] =
    val json   = ujson.read(raw)
    val arr    = json.arr
    val prov   = makeProv(queryId)
    val eviIds = evidence.items.map(_.evidenceId).toSet

    arr.map { obj =>
      val claimId    = obj("claimId").str
      val statement  = obj("statement").str
      val confidence = math.max(0.0, math.min(1.0, obj("confidence").num))
      val supporting = obj("supportingEvidence").arr.map(_.str).toVector
      val validSupporting = supporting.filter(eviIds.contains)

      AnswerClaim(
        claimId = claimId,
        queryId = queryId,
        statement = statement,
        confidence = confidence,
        supportingEvidence = validSupporting,
        verificationStatus = VerificationStatus.Unsupported,
        provenance = prov
      )
    }.toVector

  private def buildPrompt(
      queryPlan: QueryPlan,
      evidence: EvidenceBundle,
      resolvedResearchers: Vector[ResolvedResearcher]
  ): String =
    val windowStart = queryPlan.filters.getOrElse("yearFrom", "unknown")
    val windowEnd   = queryPlan.filters.getOrElse("yearTo", "unknown")

    val evidenceSummary = evidence.items
      .map(e => s"[${e.evidenceId}] (${e.evidenceType}): ${e.content}")
      .mkString("\n")

    val researcherSummary = resolvedResearchers
      .map(r =>
        s"${r.researcher.displayName} (${r.researcher.researcherId}, confidence=${r.confidence})"
      )
      .mkString("\n")

    s"""You are a scholarly collaboration analyst. Given the evidence below, produce a JSON array of claims about the researchers and their collaboration patterns.
       |
       |QUESTION: ${queryPlan.originalQuery}
       |TIME WINDOW: $windowStart to $windowEnd
       |
       |EVIDENCE:
       |$evidenceSummary
       |
       |RESOLVED RESEARCHERS:
       |$researcherSummary
       |
       |Respond with a JSON array of objects, each with:
       |- "claimId": unique string
       |- "statement": the claim text
       |- "confidence": number 0.0 to 1.0
       |- "supportingEvidence": array of evidence IDs from above
       |- "uncertainty": optional string explaining limitations
       |
       |Rules:
       |- Every numerical claim must cite a specific evidence ID
       |- State uncertainty when identity resolution is ambiguous
       |- Distinguish collaboration breadth (distinct coauthors) from intensity (weighted degree) from team size
       |- Do not invent metrics not present in the evidence
       |- Do not fabricate researcher names, IDs, or publication titles""".stripMargin

  private def abstainClaim(queryId: String): AnswerClaim =
    AnswerClaim(
      claimId = s"abstain-$queryId",
      queryId = queryId,
      statement = "Insufficient evidence to produce claims.",
      confidence = 0.0,
      supportingEvidence = Vector.empty,
      verificationStatus = VerificationStatus.Unsupported,
      provenance = makeProv(queryId)
    )

  private def makeProv(queryId: String): Provenance =
    Provenance(
      sourceDataset = "answer-synthesis",
      sourceRecordId = queryId,
      pipelineRunId = queryId,
      schemaVersion = promptVersion,
      timestamp = java.time.Instant.now().toString
    )
