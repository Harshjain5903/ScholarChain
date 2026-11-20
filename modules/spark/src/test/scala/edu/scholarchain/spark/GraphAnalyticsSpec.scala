package edu.scholarchain.spark

import edu.scholarchain.core.*

class GraphAnalyticsSpec extends SparkTestBase:

  private lazy val edgesRdd = sc.parallelize(TestFixtures.allEdges)
  private lazy val allIds   = sc.parallelize(TestFixtures.allResearcherIds)

  private lazy val (graph, mapping) =
    GraphUtils.buildGraphWithIsolates(sc, edgesRdd, allIds)

  // ---- Collaboration metrics ----

  test("distinct coauthor count matches expected values") {
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
    val map = metrics.map(m => (m.researcherId, m)).collectAsMap()
    map("R1").distinctCoauthorCount shouldBe 2
    map("R2").distinctCoauthorCount shouldBe 3
    map("R3").distinctCoauthorCount shouldBe 2
    map("R4").distinctCoauthorCount shouldBe 2
    map("R5").distinctCoauthorCount shouldBe 1
  }

  test("weighted degree matches expected values") {
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
    val map = metrics.map(m => (m.researcherId, m)).collectAsMap()
    // R1: edges to R2(2) + R3(1) = 3.0
    map("R1").weightedDegree shouldBe 3.0
    // R2: edges to R1(2) + R3(1) + R4(1) = 4.0
    map("R2").weightedDegree shouldBe 4.0
  }

  test("team-size statistics: max and average coauthors per paper") {
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
    val map = metrics.map(m => (m.researcherId, m)).collectAsMap()
    // R1: works w1(1 coauthor), w2(1), w3(2) -> max=2, avg=4/3
    map("R1").maximumCoauthorsOnOnePaper shouldBe 2
    map("R1").averageCoauthorsPerPaper shouldBe (4.0 / 3.0 +- 0.01)
  }

  // ---- Connected components ----

  test("connected components: number and sizes") {
    val ccResult = ConnectedComponentsJob.compute(graph, mapping)
    ccResult.componentCount shouldBe 2
    ccResult.singletonCount shouldBe 1
  }

  test("connected components: largest component fraction") {
    val ccResult = ConnectedComponentsJob.compute(graph, mapping)
    // 5 out of 6 researchers in largest component
    ccResult.largestComponentFraction shouldBe (5.0 / 6.0 +- 0.01)
  }

  test("connected components: R6 is in its own singleton component") {
    val ccResult    = ConnectedComponentsJob.compute(graph, mapping)
    val assignments = ccResult.assignments.collect()
    val r6Component = assignments.find(_._1 == "R6").map(_._2)
    r6Component shouldBe defined
    // R6 component has size 1
    assignments.find(_._1 == "R6").map(_._3) shouldBe Some(1L)
  }

  // ---- Shortest paths ----

  test("shortest path between connected pair R1-R5") {
    val results = ShortestPathJob.compute(
      graph,
      mapping,
      Seq(("R1", "R5"))
    )
    results.length shouldBe 1
    results.head.pathLength shouldBe 3
    results.head.pathVertices.head shouldBe "R1"
    results.head.pathVertices.last shouldBe "R5"
  }

  test("no path between disconnected pair R1-R6") {
    val results = ShortestPathJob.compute(
      graph,
      mapping,
      Seq(("R1", "R6"))
    )
    results shouldBe empty
  }

  test("shortest path between adjacent pair R1-R2") {
    val results = ShortestPathJob.compute(
      graph,
      mapping,
      Seq(("R1", "R2"))
    )
    results.length shouldBe 1
    results.head.pathLength shouldBe 1
  }

  // ---- Community detection ----

  test("community detection produces valid assignments for all vertices") {
    val communityResult = CommunityDetectionJob.compute(graph, mapping)
    val assignments     = communityResult.assignments.collect()
    val assignedIds     = assignments.map(_._1).toSet
    TestFixtures.allResearcherIds.foreach { rid =>
      assignedIds should contain(rid)
    }
  }

  test("community detection: community sizes sum to total vertices") {
    val communityResult = CommunityDetectionJob.compute(graph, mapping)
    val assignments     = communityResult.assignments.collect()
    assignments.length shouldBe TestFixtures.allResearcherIds.length
  }

  // ---- Bridge analysis ----

  test("bridge vertex R2 has higher bridge score than leaf R5") {
    val communityAssignments = sc.parallelize(
      TestFixtures.allResearcherIds.map(r => (r, s"comm:$r"))
    )
    val bridgeResult = BridgeAnalysisJob.compute(
      graph,
      mapping,
      communityAssignments,
      sampleCount = 10,
      maxGraphSize = 1000,
      seed = 42L
    )
    val scores = bridgeResult.scores.collectAsMap()
    scores("R2") should be > scores("R5")
  }

  test("cross-community degree is computed for bridge vertex") {
    val communityAssignments = sc.parallelize(
      Seq(
        ("R1", "commA"),
        ("R2", "commA"),
        ("R3", "commA"),
        ("R4", "commB"),
        ("R5", "commB"),
        ("R6", "commC")
      )
    )
    val bridgeResult = BridgeAnalysisJob.compute(
      graph,
      mapping,
      communityAssignments,
      sampleCount = 10,
      maxGraphSize = 1000,
      seed = 42L
    )
    val crossComm = bridgeResult.crossCommunityDegrees.collectAsMap()
    // R2 has neighbors in commA (R1, R3) and commB (R4) -> cross-community degree >= 1
    crossComm.getOrElse("R2", 0) should be >= 1
  }

  // ---- K-core decomposition ----

  test("triangle R1-R2-R3 has coreness 2") {
    val coreness = KCoreJob.compute(graph, mapping).collectAsMap()
    coreness("R1") shouldBe 2
    coreness("R2") shouldBe 2
    coreness("R3") shouldBe 2
  }

  test("leaf vertex R5 has coreness 1") {
    val coreness = KCoreJob.compute(graph, mapping).collectAsMap()
    coreness("R5") shouldBe 1
  }

  test("isolated vertex R6 has coreness 0") {
    val coreness = KCoreJob.compute(graph, mapping).collectAsMap()
    coreness("R6") shouldBe 0
  }

  // ---- Empty and single-node graphs ----

  test("empty graph: all jobs handle gracefully") {
    val emptyEdges = sc.emptyRDD[CoauthorEdge]
    val singleId   = sc.parallelize(Seq("alone"))
    val (emptyGraph, emptyMapping) =
      GraphUtils.buildGraphWithIsolates(sc, emptyEdges, singleId)

    val ccResult = ConnectedComponentsJob.compute(emptyGraph, emptyMapping)
    ccResult.componentCount shouldBe 1
    ccResult.singletonCount shouldBe 1

    val coreness = KCoreJob.compute(emptyGraph, emptyMapping).collectAsMap()
    coreness("alone") shouldBe 0

    val paths = ShortestPathJob.compute(emptyGraph, emptyMapping, Seq(("alone", "other")))
    paths shouldBe empty
  }

  test("single edge graph: connected components") {
    val singleEdge = sc.parallelize(Seq(TestFixtures.edgeR1R2))
    val ids        = sc.parallelize(Seq("R1", "R2"))
    val (g, m)     = GraphUtils.buildGraphWithIsolates(sc, singleEdge, ids)
    val ccResult   = ConnectedComponentsJob.compute(g, m)
    ccResult.componentCount shouldBe 1
    ccResult.singletonCount shouldBe 0
  }
