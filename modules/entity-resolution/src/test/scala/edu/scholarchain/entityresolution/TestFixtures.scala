package edu.scholarchain.entityresolution

import edu.scholarchain.core.Provenance

/** Shared test fixtures for entity resolution tests. */
object TestFixtures:

  val testProvenance: Provenance = Provenance(
    sourceDataset = "test",
    sourceRecordId = "test-record-1",
    pipelineRunId = "test-run-1",
    schemaVersion = "1.0",
    timestamp = "2026-01-01T00:00:00Z"
  )

  def makePair(
    sourceId: String = "src-1",
    targetId: String = "tgt-1",
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
    precomputedFeatures: Map[String, Double] = Map.empty
  ): CandidatePair = CandidatePair(
    sourceId = sourceId,
    targetId = targetId,
    sourceTitle = sourceTitle,
    targetTitle = targetTitle,
    sourceYear = sourceYear,
    targetYear = targetYear,
    sourceDoi = sourceDoi,
    targetDoi = targetDoi,
    sourceArxivId = sourceArxivId,
    targetArxivId = targetArxivId,
    sourceOrcid = sourceOrcid,
    targetOrcid = targetOrcid,
    sourceOpenAlexId = sourceOpenAlexId,
    targetOpenAlexId = targetOpenAlexId,
    sourceSemanticScholarId = sourceSemanticScholarId,
    targetSemanticScholarId = targetSemanticScholarId,
    sourceAuthors = sourceAuthors,
    targetAuthors = targetAuthors,
    sourceVenue = sourceVenue,
    targetVenue = targetVenue,
    sourceAffiliation = sourceAffiliation,
    targetAffiliation = targetAffiliation,
    precomputedFeatures = precomputedFeatures,
    provenance = testProvenance
  )
