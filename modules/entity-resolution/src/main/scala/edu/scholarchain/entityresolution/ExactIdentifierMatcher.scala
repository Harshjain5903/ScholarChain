package edu.scholarchain.entityresolution

import edu.scholarchain.core.MatchOutcome

/**
 * Exact identifier matching with conflict detection. If two authoritative identifiers agree on one
 * axis but disagree on another, the result is Conflict.
 */
object ExactIdentifierMatcher:

  /** Result of exact identifier comparison. */
  case class ExactMatchResult(
    outcome: MatchOutcome,
    matchedField: Option[String],
    conflictFields: Vector[String],
    reasonCodes: Vector[String]
  )

  /** Check all identifier fields for exact match or conflict. */
  def evaluate(pair: CandidatePair): ExactMatchResult =
    val checks = Vector(
      checkField("doi", pair.sourceDoi, pair.targetDoi, normDoi),
      checkField("arxiv_id", pair.sourceArxivId, pair.targetArxivId, normArxiv),
      checkField("orcid", pair.sourceOrcid, pair.targetOrcid, normOrcid),
      checkField(
        "openalex_id",
        pair.sourceOpenAlexId,
        pair.targetOpenAlexId,
        normGeneric
      ),
      checkField(
        "semantic_scholar_id",
        pair.sourceSemanticScholarId,
        pair.targetSemanticScholarId,
        normGeneric
      )
    )

    val matches   = checks.filter(_.status == FieldStatus.Match)
    val conflicts = checks.filter(_.status == FieldStatus.Conflict)

    if conflicts.nonEmpty then
      ExactMatchResult(
        outcome = MatchOutcome.Conflict,
        matchedField = matches.headOption.map(_.fieldName),
        conflictFields = conflicts.map(_.fieldName),
        reasonCodes = conflicts.map(c => s"conflict:${c.fieldName}") ++
          matches.map(m => s"match:${m.fieldName}")
      )
    else if matches.nonEmpty then
      ExactMatchResult(
        outcome = MatchOutcome.Accepted,
        matchedField = Some(matches.head.fieldName),
        conflictFields = Vector.empty,
        reasonCodes = matches.map(m => s"exact_id_match:${m.fieldName}")
      )
    else
      ExactMatchResult(
        outcome = MatchOutcome.Unresolved,
        matchedField = None,
        conflictFields = Vector.empty,
        reasonCodes = Vector("no_exact_id_available")
      )

  private enum FieldStatus:
    case Match, Conflict, NoData

  private case class FieldCheck(
    fieldName: String,
    status: FieldStatus
  )

  private def normDoi(s: String): Option[String]     = IdentifierNormalizer.normalizeDoi(s)
  private def normArxiv(s: String): Option[String]   = IdentifierNormalizer.normalizeArxivId(s)
  private def normOrcid(s: String): Option[String]   = IdentifierNormalizer.normalizeOrcid(s)
  private def normGeneric(s: String): Option[String] = Some(s.trim.toLowerCase)

  private def checkField(
    name: String,
    a: Option[String],
    b: Option[String],
    normalize: String => Option[String]
  ): FieldCheck =
    val normA = a.flatMap(normalize)
    val normB = b.flatMap(normalize)
    (normA, normB) match
      case (Some(va), Some(vb)) if va == vb => FieldCheck(name, FieldStatus.Match)
      case (Some(va), Some(vb))             => FieldCheck(name, FieldStatus.Conflict)
      case _                                => FieldCheck(name, FieldStatus.NoData)
