package edu.scholarchain.entityresolution

import upickle.default.*

import java.security.MessageDigest

/** LLM adjudication decision. */
enum AdjudicationDecision:
  case Accept, Reject, Abstain

object AdjudicationDecision:

  given ReadWriter[AdjudicationDecision] =
    readwriter[String].bimap(_.toString, AdjudicationDecision.valueOf(_))

/** Structured input for LLM adjudication. */
case class AdjudicationRequest(
  sourceId: String,
  targetId: String,
  features: Map[String, Double],
  sourceFields: Map[String, String],
  targetFields: Map[String, String],
  policyVersion: String,
  promptVersion: String
) derives ReadWriter

/** Structured output from LLM adjudication. */
case class AdjudicationResponse(
  decision: AdjudicationDecision,
  reasonCodes: Vector[String],
  confidence: Double,
  modelVersion: String
) derives ReadWriter

/** Cache entry for memoizing adjudication results. */
case class AdjudicationCacheEntry(
  inputHash: String,
  promptVersion: String,
  modelVersion: String,
  policyVersion: String,
  response: AdjudicationResponse
) derives ReadWriter

/** Trait for LLM adjudicators -- allows swapping stub with real Bedrock implementation. */
trait LlmAdjudicator:

  /** Adjudicate a candidate pair. */
  def adjudicate(request: AdjudicationRequest): AdjudicationResponse

  /** Look up a cached result. */
  def lookupCache(request: AdjudicationRequest): Option[AdjudicationResponse]

/**
 * Stub adjudicator that always returns Abstain. Real Bedrock integration is deferred to a later
 * phase.
 */
class StubLlmAdjudicator(modelVersion: String = "stub") extends LlmAdjudicator:

  private val cache = scala.collection.concurrent.TrieMap.empty[String, AdjudicationCacheEntry]

  override def adjudicate(request: AdjudicationRequest): AdjudicationResponse =
    lookupCache(request).getOrElse {
      val response = AdjudicationResponse(
        decision = AdjudicationDecision.Abstain,
        reasonCodes = Vector("stub_adjudicator", "no_llm_available"),
        confidence = 0.0,
        modelVersion = modelVersion
      )
      val hash = computeInputHash(request)
      cache.put(
        hash,
        AdjudicationCacheEntry(
          inputHash = hash,
          promptVersion = request.promptVersion,
          modelVersion = modelVersion,
          policyVersion = request.policyVersion,
          response = response
        )
      )
      response
    }

  override def lookupCache(request: AdjudicationRequest): Option[AdjudicationResponse] =
    cache.get(computeInputHash(request)).map(_.response)

  private def computeInputHash(request: AdjudicationRequest): String =
    val featuresStr = request.features.toVector
      .sortBy(_._1)
      .map { case (k, v) => s"$k=$v" }
      .mkString(",")
    val content =
      s"${request.sourceId}|${request.targetId}|${request.policyVersion}|${request.promptVersion}|$featuresStr"
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(content.getBytes("UTF-8")).map("%02x".format(_)).mkString
