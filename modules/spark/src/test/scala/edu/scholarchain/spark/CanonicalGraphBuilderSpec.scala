package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import org.apache.spark.sql.SparkSession

class CanonicalGraphBuilderSpec extends SparkTestBase:

  test("self-pairs are dropped after entity resolution") {
    val pairCandidates =
      sc.parallelize(TestFixtures.pairCandidatesWithSelfPair)
    val matchDecisions =
      sc.parallelize(TestFixtures.matchDecisionsSelfPair)

    val edges = CanonicalGraphBuilder.build(
      sc,
      pairCandidates,
      matchDecisions,
      windowStartYear = 2000,
      windowEndYear = 2030,
      runId = "run-test",
      schemaVersion = "1.0.0",
      policyVersion = "v1",
      timestamp = "2024-01-01T00:00:00Z"
    )

    val collected = edges.collect()
    collected.foreach { e =>
      e.researcherA should not be e.researcherB
    }
    collected.length shouldBe 1
  }

  test("duplicate pairs are aggregated correctly") {
    val mapping = sc.parallelize(
      Seq(
        ("m1", "R1"),
        ("m2", "R2"),
        ("m3", "R3"),
        ("m4", "R4"),
        ("m5", "R5")
      )
    )
    val pairCandidates = sc.parallelize(TestFixtures.pairCandidates)

    val edges = CanonicalGraphBuilder.buildFromMapping(
      sc,
      pairCandidates,
      mapping,
      windowStartYear = 2000,
      windowEndYear = 2030,
      runId = "run-test",
      schemaVersion = "1.0.0",
      policyVersion = "v1",
      timestamp = "2024-01-01T00:00:00Z"
    )

    val collected = edges.collect()
    val r1r2 = collected.find(e =>
      (e.researcherA == "R1" && e.researcherB == "R2") ||
        (e.researcherA == "R2" && e.researcherB == "R1")
    )
    r1r2 shouldBe defined
    r1r2.get.jointPublicationCount shouldBe 2
    r1r2.get.firstCollaborationYear shouldBe 2018
    r1r2.get.lastCollaborationYear shouldBe 2020
  }

  test("researcherA < researcherB ordering is maintained") {
    val mapping = sc.parallelize(
      Seq(
        ("m1", "ZZZ"),
        ("m2", "AAA")
      )
    )
    val pairs = sc.parallelize(
      Seq(
        CoauthorPairCandidate("m1", "m2", "w1", 2020, TestFixtures.mkProv("pair:m1:m2"))
      )
    )

    val edges = CanonicalGraphBuilder.buildFromMapping(
      sc,
      pairs,
      mapping,
      windowStartYear = 2000,
      windowEndYear = 2030,
      runId = "run-test",
      schemaVersion = "1.0.0",
      policyVersion = "v1",
      timestamp = "2024-01-01T00:00:00Z"
    )

    val collected = edges.collect()
    collected.length shouldBe 1
    collected.head.researcherA shouldBe "AAA"
    collected.head.researcherB shouldBe "ZZZ"
  }

  test("edges outside time window are excluded") {
    val mapping = sc.parallelize(
      Seq(
        ("m1", "R1"),
        ("m2", "R2"),
        ("m3", "R3"),
        ("m4", "R4"),
        ("m5", "R5")
      )
    )
    val pairCandidates = sc.parallelize(TestFixtures.pairCandidates)

    val edges = CanonicalGraphBuilder.buildFromMapping(
      sc,
      pairCandidates,
      mapping,
      windowStartYear = 2020,
      windowEndYear = 2021,
      runId = "run-test",
      schemaVersion = "1.0.0",
      policyVersion = "v1",
      timestamp = "2024-01-01T00:00:00Z"
    )

    val collected = edges.collect()
    collected.foreach { e =>
      e.firstCollaborationYear should be >= 2020
      e.lastCollaborationYear should be <= 2021
    }
    collected.exists(e => e.researcherA == "R1" && e.researcherB == "R3") shouldBe false
  }

  test("empty pair candidates produce empty edges") {
    val emptyPairs   = sc.emptyRDD[CoauthorPairCandidate]
    val emptyMatches = sc.emptyRDD[MatchDecision]

    val edges = CanonicalGraphBuilder.build(
      sc,
      emptyPairs,
      emptyMatches,
      windowStartYear = 2000,
      windowEndYear = 2030,
      runId = "run-test",
      schemaVersion = "1.0.0",
      policyVersion = "v1",
      timestamp = "2024-01-01T00:00:00Z"
    )

    edges.count() shouldBe 0
  }

  test("provenance is populated on output edges") {
    val mapping = sc.parallelize(Seq(("m1", "R1"), ("m2", "R2")))
    val pairs = sc.parallelize(
      Seq(
        CoauthorPairCandidate("m1", "m2", "w1", 2020, TestFixtures.mkProv("pair:m1:m2"))
      )
    )

    val edges = CanonicalGraphBuilder.buildFromMapping(
      sc,
      pairs,
      mapping,
      windowStartYear = 2000,
      windowEndYear = 2030,
      runId = "my-run",
      schemaVersion = "2.0.0",
      policyVersion = "v1",
      timestamp = "2024-06-15T12:00:00Z"
    )

    val edge = edges.collect().head
    edge.provenance.sourceDataset shouldBe "spark-analytics"
    edge.provenance.pipelineRunId shouldBe "my-run"
    edge.provenance.schemaVersion shouldBe "2.0.0"
  }
