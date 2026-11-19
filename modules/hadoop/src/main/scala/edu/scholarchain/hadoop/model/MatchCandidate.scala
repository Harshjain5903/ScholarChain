package edu.scholarchain.hadoop.model

import upickle.default.*
import edu.scholarchain.core.Provenance

/**
 * An intermediate candidate pair with computed features, prior to any accept/reject decision. The
 * entity-resolution module makes the final match decision; this record only carries deterministic
 * features.
 */
case class MatchCandidate(
  sourceId: String,
  targetId: String,
  features: Map[String, Double],
  blockKey: String,
  provenance: Provenance
) derives ReadWriter
