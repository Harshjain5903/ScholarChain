package edu.scholarchain.core

import upickle.default.*

/** A single piece of evidence retrieved to support an answer. */
case class EvidenceItem(
  evidenceId: String,
  evidenceType: EvidenceType,
  sourceEntityId: String,
  content: String,
  score: Double,
  provenance: Provenance
) derives ReadWriter:

  require(
    score >= 0.0 && score <= 1.0,
    s"EvidenceItem score must be in [0.0, 1.0], got $score"
  )

/** A scored bundle of evidence items collected for a query. */
case class EvidenceBundle(
  bundleId: String,
  queryId: String,
  items: Vector[EvidenceItem],
  totalScore: Double,
  provenance: Provenance
) derives ReadWriter
