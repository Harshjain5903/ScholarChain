package edu.scholarchain.core

import java.security.MessageDigest
import java.util.UUID

/** Deterministic, stable identifier generators for ScholarChain entities. */
object IdGen:

  /** Compute the lowercase hex SHA-256 digest of the input string. */
  private def sha256hex(input: String): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(input.getBytes("UTF-8")).map("%02x".format(_)).mkString

  /** Deterministic work ID from source system and source record ID. */
  def workId(source: SourceType, sourceRecordId: String): String =
    s"work:${source.toString.toLowerCase}:$sourceRecordId"

  /**
   * Deterministic researcher ID preferring ORCID, then OpenAlex, then a SHA-256 hash of the
   * fallback name.
   */
  def researcherId(
    orcid: Option[String],
    openAlexId: Option[String],
    fallbackName: String
  ): String =
    orcid
      .map(id => s"researcher:orcid:$id")
      .orElse(openAlexId.map(id => s"researcher:openalex:$id"))
      .getOrElse {
        require(
          fallbackName.nonEmpty,
          "fallbackName must not be empty when no ORCID or OpenAlex ID is provided"
        )
        s"researcher:sc:${sha256hex(fallbackName)}"
      }

  /**
   * Deterministic coauthor-edge ID from an ordered researcher pair, time window, and policy
   * version.
   */
  def coauthorEdgeId(
    researcherA: String,
    researcherB: String,
    windowStart: Int,
    windowEnd: Int,
    policyVersion: String
  ): String =
    val (a, b) =
      if researcherA <= researcherB then (researcherA, researcherB)
      else (researcherB, researcherA)
    s"coauthor-edge:${sha256hex(s"$a|$b|$windowStart-$windowEnd|$policyVersion")}"

  /** Deterministic chunk ID for a text fragment. */
  def chunkId(
    entityId: String,
    sourceVersion: String,
    docType: String,
    textHash: String
  ): String =
    s"chunk:${sha256hex(s"$entityId|$sourceVersion|$docType|$textHash")}"

  /** Prefix of every evidence ID. */
  val EvidenceIdPrefix: String = "evidence:"

  /** Width of the zero-padded ordinal suffix in an evidence ID. */
  val EvidenceOrdinalWidth: Int = 4

  /** Largest ordinal representable in an evidence ID. */
  val MaxEvidenceOrdinal: Int = 9999

  /**
   * Deterministic evidence ID for the `ordinal`-th evidence item of a query.
   *
   * Format: `evidence:<queryId>:<ordinal>` where `<ordinal>` is a zero-padded, 4-digit decimal in
   * `[0, 9999]`. Example: `evidenceId("q-42", 7)` gives `evidence:q-42:0007`.
   *
   * The ordinal is always the final `:`-separated segment, so a `queryId` may itself contain colons
   * and still round-trip through [[parseEvidenceId]]. Zero-padding makes lexicographic order equal
   * to ordinal order within one query.
   *
   * @throws IllegalArgumentException
   *   if `queryId` is empty or blank, or `ordinal` is outside `[0, 9999]`
   */
  def evidenceId(queryId: String, ordinal: Int): String =
    require(queryId.trim.nonEmpty, "queryId must not be empty")
    require(
      ordinal >= 0 && ordinal <= MaxEvidenceOrdinal,
      s"ordinal must be in [0, $MaxEvidenceOrdinal], got $ordinal"
    )
    s"$EvidenceIdPrefix$queryId:${s"%0${EvidenceOrdinalWidth}d".format(ordinal)}"

  /**
   * Inverse of [[evidenceId]]. Returns `Some((queryId, ordinal))` for a well-formed evidence ID, or
   * `None` otherwise.
   */
  def parseEvidenceId(id: String): Option[(String, Int)] =
    if !id.startsWith(EvidenceIdPrefix) then None
    else
      val body = id.substring(EvidenceIdPrefix.length)
      val sep  = body.lastIndexOf(':')
      if sep <= 0 then None
      else
        val queryId = body.substring(0, sep)
        val digits  = body.substring(sep + 1)
        if queryId.trim.isEmpty || digits.length != EvidenceOrdinalWidth ||
          !digits.forall(c => c >= '0' && c <= '9')
        then None
        else Some((queryId, digits.toInt))

  /** Non-deterministic run ID based on a random UUID. */
  def runId(): String =
    s"run:${UUID.randomUUID().toString}"
