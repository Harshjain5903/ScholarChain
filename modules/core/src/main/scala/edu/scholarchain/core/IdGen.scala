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
   * Deterministic researcher ID preferring ORCID, then OpenAlex, then a
   * SHA-256 hash of the fallback name.
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
   * Deterministic coauthor-edge ID from an ordered researcher pair, time
   * window, and policy version.
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

  /** Non-deterministic run ID based on a random UUID. */
  def runId(): String =
    s"run:${UUID.randomUUID().toString}"
