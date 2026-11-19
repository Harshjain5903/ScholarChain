package edu.scholarchain.entityresolution

import edu.scholarchain.core.Provenance
import upickle.default.*

/**
 * Local input record mirroring the Hadoop MatchCandidate. The entityResolution module does not
 * depend on hadoop, so we define our own input type.
 */
case class MatchCandidateInput(
    sourceId: String,
    targetId: String,
    features: Map[String, Double],
    blockKey: String,
    provenance: Provenance
) derives ReadWriter

/**
 * Extended candidate pair with optional raw fields for deeper feature computation (titles, authors,
 * identifiers).
 */
case class CandidatePair(
    sourceId: String,
    targetId: String,
    sourceTitle: Option[String] = None,
    targetTitle: Option[String] = None,
    sourceYear: Option[Int] = None,
    targetYear: Option[Int] = None,
    sourceDoi: Option[String] = None,
    targetDoi: Option[String] = None,
    sourceArxivId: Option[String] = None,
    targetArxivId: Option[String] = None,
    sourceOrcid: Option[String] = None,
    targetOrcid: Option[String] = None,
    sourceOpenAlexId: Option[String] = None,
    targetOpenAlexId: Option[String] = None,
    sourceSemanticScholarId: Option[String] = None,
    targetSemanticScholarId: Option[String] = None,
    sourceAuthors: Vector[String] = Vector.empty,
    targetAuthors: Vector[String] = Vector.empty,
    sourceVenue: Option[String] = None,
    targetVenue: Option[String] = None,
    sourceAffiliation: Option[String] = None,
    targetAffiliation: Option[String] = None,
    precomputedFeatures: Map[String, Double] = Map.empty,
    provenance: Provenance
) derives ReadWriter
