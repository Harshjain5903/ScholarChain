package edu.scholarchain.spark

import edu.scholarchain.core.*

class SemanticContextSpec extends SparkTestBase:

  test("researcher profile summary contains expected fields") {
    val edgesRdd = sc.parallelize(TestFixtures.allEdges)
    val mapping  = GraphUtils.buildVertexMapping(edgesRdd)
    val graph    = GraphUtils.buildGraph(sc, edgesRdd, mapping)

    val authorships = sc.parallelize(TestFixtures.authorships)
    val metrics = CollaborationMetricsJob.compute(
      sc,
      edgesRdd,
      authorships,
      2000,
      2030,
      TestFixtures.lineage()
    )

    val profiles = SemanticContextJob.researcherProfiles(
      sc,
      metrics,
      edgesRdd,
      topN = 3,
      TestFixtures.lineage()
    )
    val collected = profiles.collect()
    collected.length should be > 0

    val r1Profile = collected.find(_.entityId == "R1")
    r1Profile shouldBe defined
    r1Profile.get.entityType shouldBe "researcher-profile"
    r1Profile.get.summary should include("R1")
    r1Profile.get.summary should include("publications")
    r1Profile.get.summary should include("coauthors")
    r1Profile.get.provenance.pipelineRunId shouldBe "run"
  }

  test("community summary is generated from real data") {
    val edgesRdd    = sc.parallelize(TestFixtures.allEdges)
    val authorships = sc.parallelize(TestFixtures.authorships)
    val metrics = CollaborationMetricsJob
      .compute(
        sc,
        edgesRdd,
        authorships,
        2000,
        2030,
        TestFixtures.lineage()
      )
      .map(m => m.copy(communityId = Some(s"comm:${m.researcherId.take(2)}")))

    val bridgeScores = sc.parallelize(
      TestFixtures.allResearcherIds.take(5).map(r => (r, 1.0))
    )

    val summaries = SemanticContextJob.communitySummaries(
      sc,
      metrics,
      bridgeScores,
      topBridgeCount = 2,
      BridgeScoreMethod.Exact,
      TestFixtures.lineage()
    )
    val collected = summaries.collect()
    collected.length should be > 0
    collected.foreach { s =>
      s.entityType shouldBe "community-summary"
      s.summary should include("members")
    }
  }

  test("path summary describes the collaboration path") {
    val pathResult = ShortestPathJob.PathResult(
      sourceResearcherId = "R1",
      targetResearcherId = "R5",
      pathLength = 3,
      pathVertices = Vector("R1", "R2", "R4", "R5")
    )
    val summaries = SemanticContextJob.pathSummaries(
      sc,
      Seq(pathResult),
      TestFixtures.lineage()
    )
    val collected = summaries.collect()
    collected.length shouldBe 1
    collected.head.summary should include("R1")
    collected.head.summary should include("R5")
    collected.head.summary should include("3 hops")
  }

  // ---- Bridge labels (real data: 498 of 500 communities named "bridges" with betweenness 0).

  private def communityMetrics =
    CollaborationMetricsJob
      .compute(
        sc,
        sc.parallelize(TestFixtures.allEdges),
        sc.parallelize(TestFixtures.authorships),
        2000,
        2030,
        TestFixtures.lineage()
      )
      .map(m => m.copy(communityId = Some("comm:all")))

  private def summaryFor(scores: Seq[(String, Double)], method: String): String =
    SemanticContextJob
      .communitySummaries(
        sc,
        communityMetrics,
        sc.parallelize(scores),
        topBridgeCount = 3,
        method,
        TestFixtures.lineage()
      )
      .collect()
      .head
      .summary

  test("only researchers with a positive computed betweenness are named as bridges") {
    val s = summaryFor(Seq("R1" -> 0.0, "R2" -> 7.0, "R3" -> 0.0, "R4" -> 4.0), "exact")
    s should include("Key bridge researchers (exact betweenness > 0): R2, R4.")
    s should not include "R1,"
    s should not include "R3"
  }

  test("all-zero betweenness names no bridge researchers and says why") {
    val s = summaryFor(TestFixtures.allResearcherIds.map(_ -> 0.0), BridgeScoreMethod.Exact)
    s should include("No bridge researchers identified: every member has exact betweenness 0.")
    s should not include "Key bridge researchers"
  }

  test("not-computed betweenness names no bridge researchers and says it was not computed") {
    val s = summaryFor(Seq("R2" -> 0.0), BridgeScoreMethod.NotComputed)
    s should include("No bridge researchers identified: betweenness was not computed for this run.")
    SemanticContextJob.bridgeSentence(Seq("R2" -> 5.0), BridgeScoreMethod.NotComputed) should
      include("not computed")
    SemanticContextJob.bridgeSentence(Seq("R2" -> 5.0), BridgeScoreMethod.Sampled) shouldBe
      "Key bridge researchers (sampled betweenness > 0): R2."
  }

  test("summary provenance carries lineage, producer and transformation step") {
    val lineage = Lineage(
      "spark-run",
      "1.0.0",
      "ts",
      "dblp",
      Vector("er-1", "hadoop-1"),
      Some("er-mention-v1-uncalibrated")
    )
    val p = SemanticContextJob
      .pathSummaries(sc, Seq(ShortestPathJob.PathResult("a", "b", 1, Vector("a", "b"))), lineage)
      .collect()
      .head
      .provenance
    p.sourceDataset shouldBe "dblp"
    p.producer shouldBe Some("spark-analytics/SemanticContextJob")
    p.transformationStep shouldBe Some("collaboration-path-summary")
    p.upstreamRunIds shouldBe Vector("er-1", "hadoop-1")
    p.policyVersion shouldBe Some("er-mention-v1-uncalibrated")
  }
