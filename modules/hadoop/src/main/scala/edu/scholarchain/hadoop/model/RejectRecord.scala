package edu.scholarchain.hadoop.model

import upickle.default.*
import edu.scholarchain.core.Provenance

/**
 * A record that was rejected during ingestion or normalization.
 * Carries the original content, reason code, and provenance.
 */
case class RejectRecord(
    sourceRecordId: String,
    reasonCode: String,
    reasonMessage: String,
    originalContent: String,
    jobName: String,
    provenance: Provenance
) derives ReadWriter

/** Standard reason codes for rejected records. */
object RejectReasons:
  val MalformedXml      = "MALFORMED_XML"
  val MalformedJson     = "MALFORMED_JSON"
  val MissingTitle      = "MISSING_TITLE"
  val MissingYear       = "MISSING_YEAR"
  val MissingAuthors    = "MISSING_AUTHORS"
  val MissingDoi        = "MISSING_DOI"
  val MissingSourceId   = "MISSING_SOURCE_ID"
  val InvalidOrcid      = "INVALID_ORCID"
  val InvalidYear       = "INVALID_YEAR"
  val MissingName       = "MISSING_NAME"
  val MissingFamilyName = "MISSING_FAMILY_NAME"
