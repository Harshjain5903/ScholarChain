package edu.scholarchain.core

import upickle.default.*

/** The outcome of comparing two entity mentions for potential merge. */
case class MatchDecision(
  sourceId: String,
  targetId: String,
  features: Map[String, Double],
  decision: MatchOutcome,
  confidence: Double,
  reasonCodes: Vector[String],
  policyVersion: String,
  runId: String
) derives ReadWriter:

  require(
    confidence >= 0.0 && confidence <= 1.0,
    s"confidence must be in [0.0, 1.0], got $confidence"
  )
