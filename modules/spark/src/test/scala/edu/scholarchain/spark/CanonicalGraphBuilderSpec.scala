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
      policyVersion = "v1",
      lineage = TestFixtures.lineage("run-test", "1.0.0", "2024-01-01T00:00:00Z")
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
      policyVersion = "v1",
      lineage = TestFixtures.lineage("run-test", "1.0.0", "2024-01-01T00:00:00Z")
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
      policyVersion = "v1",
      lineage = TestFixtures.lineage("run-test", "1.0.0", "2024-01-01T00:00:00Z")
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
      policyVersion = "v1",
      lineage = TestFixtures.lineage("run-test", "1.0.0", "2024-01-01T00:00:00Z")
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
      policyVersion = "v1",
      lineage = TestFixtures.lineage("run-test", "1.0.0", "2024-01-01T00:00:00Z")
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
      policyVersion = "v1",
      lineage = TestFixtures.lineage("my-run", "2.0.0", "2024-06-15T12:00:00Z")
    )

    val edge = edges.collect().head
    edge.provenance.sourceDataset shouldBe "test"
    edge.provenance.pipelineRunId shouldBe "my-run"
    edge.provenance.schemaVersion shouldBe "2.0.0"
    edge.provenance.transformationStep shouldBe Some(CanonicalGraphBuilder.EdgeStep)
    edge.provenance.producer shouldBe Some("spark-analytics/CanonicalGraphBuilder")
  }

  test("edge provenance carries the input lineage and the policy-derived edge ID") {
    val lineage = Lineage(
      "spark-run",
      "1.0.0",
      "ts",
      sourceDataset = "dblp",
      upstreamRunIds = Vector("er-rs-1", "rs-1"),
      policyVersion = Some("er-mention-v1-uncalibrated")
    )
    val pairs = sc.parallelize(
      Seq(CoauthorPairCandidate("m1", "m2", "w1", 2020, TestFixtures.mkProv("pair:m1:m2")))
    )
    val edge = CanonicalGraphBuilder
      .buildFromMapping(sc, pairs, sc.emptyRDD, 2000, 2030, "er-mention-v1-uncalibrated", lineage)
      .collect()
      .head
    edge.provenance.sourceDataset shouldBe "dblp"
    edge.provenance.sourceDataset should not be "spark-analytics"
    edge.provenance.upstreamRunIds shouldBe Vector("er-rs-1", "rs-1")
    edge.provenance.policyVersion shouldBe Some("er-mention-v1-uncalibrated")
    edge.provenance.sourceRecordId shouldBe
      IdGen.coauthorEdgeId("m1", "m2", 2000, 2030, "er-mention-v1-uncalibrated")
  }

  test("both endpoints merged across two works aggregate to jointPublicationCount 2") {
    // Work w1: a1 + b1; work w2: a2 + b2. ER accepts a1~a2 and b1~b2, so the two mention-level
    // pairs collapse onto one researcher pair that shares two distinct works.
    val pairs = sc.parallelize(
      Seq(
        CoauthorPairCandidate("m:a1", "m:b1", "w1", 2018, TestFixtures.mkProv("p1")),
        CoauthorPairCandidate("m:a2", "m:b2", "w2", 2021, TestFixtures.mkProv("p2"))
      )
    )
    def accept(a: String, b: String) =
      MatchDecision(a, b, Map.empty, MatchOutcome.Accepted, 0.99, Vector.empty, "v1", "er")
    val accepted = sc.parallelize(Seq(accept("m:a1", "m:a2"), accept("m:b2", "m:b1")))
    val edges = CanonicalGraphBuilder
      .build(sc, pairs, accepted, 2000, 2030, "v1", TestFixtures.lineage())
      .collect()
    edges should have size 1
    val e = edges.head
    (e.researcherA, e.researcherB) shouldBe (("m:a1", "m:b1"))
    e.jointPublicationCount shouldBe 2
    e.representativeWorkIds shouldBe Vector("w1", "w2")
    e.firstCollaborationYear shouldBe 2018
    e.lastCollaborationYear shouldBe 2021
  }

  test("researcher display names: most frequent raw name across merged mentions, blanks unknown") {
    def m(id: String, name: String) =
      AuthorMention(id, name, name.toLowerCase, 0, None, None, None, id, TestFixtures.mkProv(id))
    val teams = sc.parallelize(
      Seq(
        PublicationTeam(
          "w1",
          2020,
          Vector(m("m:1", "J. Smith"), m("m:9", "  ")),
          2,
          TestFixtures.mkProv("w1")
        ),
        PublicationTeam("w2", 2021, Vector(m("m:2", "John Smith")), 1, TestFixtures.mkProv("w2")),
        PublicationTeam("w3", 2022, Vector(m("m:3", "John Smith")), 1, TestFixtures.mkProv("w3")),
        PublicationTeam("w4", 1900, Vector(m("m:4", "Out Of Window")), 1, TestFixtures.mkProv("w4"))
      )
    )
    val mapping = sc.parallelize(Seq("m:2" -> "m:1", "m:3" -> "m:1", "m:1" -> "m:1"))
    CanonicalGraphBuilder
      .researcherDisplayNames(teams, mapping, 2000, 2030)
      .collect()
      .toMap shouldBe Map("m:1" -> "John Smith")
  }
