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
      "run",
      "1.0.0",
      "ts"
    )

    val profiles = SemanticContextJob.researcherProfiles(
      sc,
      metrics,
      edgesRdd,
      topN = 3,
      "run",
      "1.0.0",
      "ts"
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
        "run",
        "1.0.0",
        "ts"
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
      "run",
      "1.0.0",
      "ts"
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
      "run",
      "1.0.0",
      "ts"
    )
    val collected = summaries.collect()
    collected.length shouldBe 1
    collected.head.summary should include("R1")
    collected.head.summary should include("R5")
    collected.head.summary should include("3 hops")
  }
