package edu.scholarchain.core

import upickle.default.*

/** A single author mention extracted from a source record. */
case class AuthorMention(
  mentionId: String,
  rawName: String,
  normalizedName: String,
  position: Int,
  orcid: Option[String],
  openAlexId: Option[String],
  affiliationRaw: Option[String],
  sourceRecordId: String,
  provenance: Provenance
) derives ReadWriter:
  require(position >= 0, s"AuthorMention position must be non-negative, got $position")

/** A resolved researcher identity after entity resolution. */
case class CanonicalResearcher(
  researcherId: String,
  displayName: String,
  orcid: Option[String],
  openAlexId: Option[String],
  semanticScholarId: Option[String],
  alternateNames: Vector[String],
  provenance: Provenance
) derives ReadWriter

/** Links a resolved researcher to a work with confidence. */
case class AuthorshipRecord(
  researcherId: String,
  workId: String,
  authorPosition: Int,
  confidence: Double,
  provenance: Provenance
) derives ReadWriter:

  require(
    confidence >= 0.0 && confidence <= 1.0,
    s"confidence must be in [0.0, 1.0], got $confidence"
  )
