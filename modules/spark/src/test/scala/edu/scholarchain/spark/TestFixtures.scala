package edu.scholarchain.spark

import edu.scholarchain.core.*

/**
 * Shared test fixture data for Spark tests.
 *
 * Graph topology (6 researchers):
 * {{{
 *   R1 -- R2  (2 papers, years 2018 & 2020)
 *   R1 -- R3  (1 paper, year 2019)
 *   R2 -- R3  (1 paper, year 2019)
 *   R2 -- R4  (1 paper, year 2020)
 *   R4 -- R5  (1 paper, year 2021)
 *   R6         (isolated)
 * }}}
 *
 * Expected properties:
 *   - 2 components: {R1,R2,R3,R4,R5} and {R6}
 *   - Triangle: R1-R2-R3 (coreness 2)
 *   - Bridge: R2 connects triangle to R4-R5
 *   - Shortest path R1->R5 = 3 hops (R1-R2-R4-R5)
 *   - No path from any Ri to R6
 */
object TestFixtures:

  val testProv: Provenance = Provenance(
    sourceDataset = "test",
    sourceRecordId = "test:fixture",
    pipelineRunId = "run-test",
    schemaVersion = "1.0.0",
    timestamp = "2024-01-01T00:00:00Z"
  )

  def mkProv(recordId: String): Provenance =
    testProv.copy(sourceRecordId = recordId)

  /** Lineage for jobs under test; the source dataset is the fixtures' `test`. */
  def lineage(
    runId: String = "run",
    schemaVersion: String = "1.0.0",
    timestamp: String = "ts"
  ): Lineage =
    Lineage(runId, schemaVersion, timestamp, sourceDataset = testProv.sourceDataset)

  val edgeR1R2: CoauthorEdge = CoauthorEdge(
    "R1",
    "R2",
    jointPublicationCount = 2,
    firstCollaborationYear = 2018,
    lastCollaborationYear = 2020,
    representativeWorkIds = Vector("w1", "w2"),
    provenance = mkProv("edge:R1:R2")
  )

  val edgeR1R3: CoauthorEdge = CoauthorEdge(
    "R1",
    "R3",
    jointPublicationCount = 1,
    firstCollaborationYear = 2019,
    lastCollaborationYear = 2019,
    representativeWorkIds = Vector("w3"),
    provenance = mkProv("edge:R1:R3")
  )

  val edgeR2R3: CoauthorEdge = CoauthorEdge(
    "R2",
    "R3",
    jointPublicationCount = 1,
    firstCollaborationYear = 2019,
    lastCollaborationYear = 2019,
    representativeWorkIds = Vector("w3"),
    provenance = mkProv("edge:R2:R3")
  )

  val edgeR2R4: CoauthorEdge = CoauthorEdge(
    "R2",
    "R4",
    jointPublicationCount = 1,
    firstCollaborationYear = 2020,
    lastCollaborationYear = 2020,
    representativeWorkIds = Vector("w4"),
    provenance = mkProv("edge:R2:R4")
  )

  val edgeR4R5: CoauthorEdge = CoauthorEdge(
    "R4",
    "R5",
    jointPublicationCount = 1,
    firstCollaborationYear = 2021,
    lastCollaborationYear = 2021,
    representativeWorkIds = Vector("w5"),
    provenance = mkProv("edge:R4:R5")
  )

  val allEdges: Seq[CoauthorEdge] =
    Seq(edgeR1R2, edgeR1R3, edgeR2R3, edgeR2R4, edgeR4R5)

  val allResearcherIds: Seq[String] =
    Seq("R1", "R2", "R3", "R4", "R5", "R6")

  /** Authorship info: (researcherId, workId, coauthorsOnPaper) */
  val authorships: Seq[CollaborationMetricsJob.AuthorshipInfo] = Seq(
    CollaborationMetricsJob.AuthorshipInfo("R1", "w1", 1),
    CollaborationMetricsJob.AuthorshipInfo("R2", "w1", 1),
    CollaborationMetricsJob.AuthorshipInfo("R1", "w2", 1),
    CollaborationMetricsJob.AuthorshipInfo("R2", "w2", 1),
    CollaborationMetricsJob.AuthorshipInfo("R1", "w3", 2),
    CollaborationMetricsJob.AuthorshipInfo("R2", "w3", 2),
    CollaborationMetricsJob.AuthorshipInfo("R3", "w3", 2),
    CollaborationMetricsJob.AuthorshipInfo("R2", "w4", 1),
    CollaborationMetricsJob.AuthorshipInfo("R4", "w4", 1),
    CollaborationMetricsJob.AuthorshipInfo("R4", "w5", 1),
    CollaborationMetricsJob.AuthorshipInfo("R5", "w5", 1)
  )

  /** Pair candidates for testing CanonicalGraphBuilder */
  val pairCandidates: Seq[CoauthorPairCandidate] = Seq(
    CoauthorPairCandidate("m1", "m2", "w1", 2018, mkProv("pair:m1:m2:w1")),
    CoauthorPairCandidate("m1", "m2", "w2", 2020, mkProv("pair:m1:m2:w2")),
    CoauthorPairCandidate("m1", "m3", "w3", 2019, mkProv("pair:m1:m3:w3")),
    CoauthorPairCandidate("m2", "m3", "w3", 2019, mkProv("pair:m2:m3:w3")),
    CoauthorPairCandidate("m2", "m4", "w4", 2020, mkProv("pair:m2:m4:w4")),
    CoauthorPairCandidate("m4", "m5", "w5", 2021, mkProv("pair:m4:m5:w5"))
  )

  /** Match decisions: m1 and m1b are the same person -> creates a self-pair when resolved */
  val matchDecisionsSelfPair: Seq[MatchDecision] = Seq(
    MatchDecision(
      sourceId = "m1",
      targetId = "m1b",
      features = Map("nameScore" -> 0.95),
      decision = MatchOutcome.Accepted,
      confidence = 0.95,
      reasonCodes = Vector("high-name-match"),
      policyVersion = "v1",
      runId = "run-test"
    )
  )

  /** Pair candidates that include a potential self-pair after resolution */
  val pairCandidatesWithSelfPair: Seq[CoauthorPairCandidate] = Seq(
    CoauthorPairCandidate("m1", "m1b", "w1", 2020, mkProv("pair:m1:m1b")),
    CoauthorPairCandidate("m1", "m2", "w2", 2020, mkProv("pair:m1:m2"))
  )
