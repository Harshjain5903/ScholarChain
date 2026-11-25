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
      TestFixtures.lineage()
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
      TestFixtures.lineage()
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
      TestFixtures.lineage()
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

  test("withComponents fills componentId and componentSize from the CC output by join") {
    val ccResult = ConnectedComponentsJob.compute(graph, mapping)
    val base = CollaborationMetricsJob.compute(
      sc,
      edgesRdd,
      sc.parallelize(TestFixtures.authorships),
      2000,
      2030,
      TestFixtures.lineage()
    )
    // R6 has no authorships or edges in the fixture; give it metrics so the singleton is covered.
    val r6 = base
      .first()
      .copy(researcherId = "R6", maximumCoauthorsOnOnePaper = 0, maximumTeamWorkIds = Vector.empty)
    // A researcher without a CC assignment keeps its (absent) component fields.
    val ghost   = r6.copy(researcherId = "R-ghost")
    val metrics = base.union(sc.parallelize(Seq(r6, ghost)))
    val enriched = CollaborationMetricsJob
      .withComponents(metrics, ccResult.assignments)
      .collect()
      .map(m => m.researcherId -> m)
      .toMap
    enriched.keySet shouldBe Set("R1", "R2", "R3", "R4", "R5", "R6", "R-ghost")
    Seq("R1", "R2", "R3", "R4", "R5").foreach { r =>
      enriched(r).componentSize shouldBe Some(5L)
      enriched(r).componentId shouldBe Some("component:R1")
    }
    enriched("R6").componentSize shouldBe Some(1L)
    enriched("R6").componentId shouldBe Some("component:R6")
    enriched("R-ghost").componentSize shouldBe None
    enriched("R-ghost").componentId shouldBe None
    // enrichMetrics delegates to the same join.
    ConnectedComponentsJob
      .enrichMetrics(metrics, ccResult)
      .collect()
      .map(m => m.researcherId -> m.componentSize)
      .toMap shouldBe enriched.view.mapValues(_.componentSize).toMap

    val stats = ConnectedComponentsJob.componentSizeStats(sc.parallelize(enriched.values.toSeq))
    stats.researchers shouldBe 7L
    stats.withComponentSize shouldBe 6L
    stats.largestComponentSize shouldBe 5L
    stats.largestComponentFraction shouldBe (5.0 / 7.0 +- 1e-12)
    stats.singletonCount shouldBe 1L
  }

  test("componentSizeStats of no researchers is all zero") {
    ConnectedComponentsJob.componentSizeStats(sc.emptyRDD[ResearcherMetrics]) shouldBe
      ConnectedComponentsJob.ComponentSizeStats(0L, 0L, 0L, 0.0, 0L)
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

  // ---- M_r evidence (maximumTeamWorkIds).

  test("maximumTeamWorkIds lists the sorted works achieving M_r; empty when M_r is 0") {
    val authorships = sc.parallelize(
      TestFixtures.authorships :+ CollaborationMetricsJob.AuthorshipInfo("R6", "w-solo", 0)
    )
    val map = CollaborationMetricsJob
      .compute(sc, edgesRdd, authorships, 2000, 2030, TestFixtures.lineage())
      .map(m => (m.researcherId, m))
      .collectAsMap()
    map("R1").maximumTeamWorkIds shouldBe Vector("w3")
    map("R2").maximumTeamWorkIds shouldBe Vector("w3")
    // R4 had 1 coauthor on both w4 and w5: a tie keeps both, sorted.
    map("R4").maximumCoauthorsOnOnePaper shouldBe 1
    map("R4").maximumTeamWorkIds shouldBe Vector("w4", "w5")
    map("R6").maximumCoauthorsOnOnePaper shouldBe 0
    map("R6").maximumTeamWorkIds shouldBe empty
  }

  test("maximumTeamWorkIds is capped deterministically at MaxTeamWorkIdsCap") {
    val cap   = CollaborationMetricsJob.MaxTeamWorkIdsCap
    val works = (0 until cap + 7).map(i => f"w$i%03d")
    val authorships = sc.parallelize(
      works.reverse.map(w => CollaborationMetricsJob.AuthorshipInfo("R", w, 3)),
      5
    )
    val m = CollaborationMetricsJob
      .compute(sc, sc.emptyRDD, authorships, 2000, 2030, TestFixtures.lineage())
      .collect()
      .head
    m.maximumCoauthorsOnOnePaper shouldBe 3
    m.maximumTeamWorkIds shouldBe works.take(cap).toVector
  }

  test("metrics provenance carries lineage instead of a hardcoded dataset") {
    val lineage = Lineage("s", "1.0.0", "ts", "dblp", Vector("er", "hd"), Some("er-p"))
    val p = CollaborationMetricsJob
      .compute(sc, edgesRdd, sc.parallelize(TestFixtures.authorships), 2000, 2030, lineage)
      .first()
      .provenance
    p.sourceDataset shouldBe "dblp"
    p.producer shouldBe Some("spark-analytics/CollaborationMetricsJob")
    p.transformationStep shouldBe Some(CollaborationMetricsJob.Step)
    p.upstreamRunIds shouldBe Vector("er", "hd")
    p.policyVersion shouldBe Some("er-p")
  }

  // ---- Betweenness method (N52).

  test("betweenness plan: exact, sampled and not-computed") {
    BridgeAnalysisJob.betweennessPlan(10, 50, 1000) shouldBe ((BridgeScoreMethod.Exact, 10L))
    BridgeAnalysisJob.betweennessPlan(10, 10, 1000) shouldBe ((BridgeScoreMethod.Exact, 10L))
    BridgeAnalysisJob.betweennessPlan(10, 3, 1000) shouldBe ((BridgeScoreMethod.Sampled, 3L))
    BridgeAnalysisJob.betweennessPlan(10, 3, 9) shouldBe ((BridgeScoreMethod.NotComputed, 0L))
    BridgeAnalysisJob.betweennessPlan(10, 0, 1000) shouldBe ((BridgeScoreMethod.NotComputed, 0L))
    BridgeAnalysisJob.betweennessPlan(0, 5, 1000) shouldBe ((BridgeScoreMethod.Exact, 0L))
  }

  private def bridgeMetrics(sampleCount: Int, maxGraphSize: Long, seed: Long = 7L) =
    val result = BridgeAnalysisJob.compute(
      graph,
      mapping,
      sc.emptyRDD[(String, String)],
      sampleCount = sampleCount,
      maxGraphSize = maxGraphSize,
      seed = seed
    )
    val metrics = CollaborationMetricsJob.compute(
      sc,
      edgesRdd,
      sc.parallelize(TestFixtures.authorships),
      2000,
      2030,
      TestFixtures.lineage()
    )
    (
      result,
      BridgeAnalysisJob.enrichMetrics(metrics, result).collect().map(m => m.researcherId -> m).toMap
    )

  test("a graph above maxGraphSize is not-computed with 0.0 placeholders, never silently exact") {
    val (result, metrics) = bridgeMetrics(sampleCount = 50, maxGraphSize = 3)
    result.method shouldBe BridgeScoreMethod.NotComputed
    result.sources shouldBe 0L
    result.vertexCount shouldBe 6L
    metrics.values.foreach { m =>
      m.bridgeScoreMethod shouldBe BridgeScoreMethod.NotComputed
      m.bridgeScore shouldBe 0.0
    }
  }

  test("k >= n is exact and k < n is sampled; the seed is recorded") {
    val (exact, exactMetrics) = bridgeMetrics(sampleCount = 100, maxGraphSize = 1000, seed = 5L)
    exact.method shouldBe BridgeScoreMethod.Exact
    exact.sources shouldBe 6L
    exact.seed shouldBe 5L
    exactMetrics("R2").bridgeScoreMethod shouldBe BridgeScoreMethod.Exact
    exactMetrics("R2").bridgeScore should be > 0.0
    val (sampled, sampledMetrics) = bridgeMetrics(sampleCount = 2, maxGraphSize = 1000)
    sampled.method shouldBe BridgeScoreMethod.Sampled
    sampled.sources shouldBe 2L
    sampledMetrics.values.foreach(_.bridgeScoreMethod shouldBe BridgeScoreMethod.Sampled)
  }

  // ---- K-core iteration cap (no silent wrong coreness).

  private lazy val pathGraph =
    val vs = (0 until 12).map(i => f"p$i%02d")
    val edges = vs.zip(vs.tail).map { case (a, b) =>
      CoauthorEdge(a, b, 1, 2020, 2020, Vector("w"), TestFixtures.mkProv(a))
    }
    GraphUtils.buildGraphWithIsolates(sc, sc.parallelize(edges), sc.emptyRDD)

  test("k-core reports convergence and iterations; periodic checkpoints give the same coreness") {
    val (g, m) = pathGraph
    val every  = KCoreJob.computeWithStatus(g, m, maxIterations = 100, checkpointInterval = 1)
    val sparse = KCoreJob.computeWithStatus(g, m, maxIterations = 100, checkpointInterval = 4)
    every.converged shouldBe true
    sparse.converged shouldBe true
    // A 12-vertex path peels two endpoints per iteration.
    every.iterations shouldBe 6
    sparse.iterations shouldBe 6
    every.coreness.collect().toMap.values.toSet shouldBe Set(1)
    sparse.coreness.collect().toMap shouldBe every.coreness.collect().toMap
  }

  test("k-core hitting the iteration cap is marked not converged and compute fails") {
    val (g, m) = pathGraph
    val capped = KCoreJob.computeWithStatus(g, m, maxIterations = 2, checkpointInterval = 1)
    capped.converged shouldBe false
    capped.iterations shouldBe 2
    // Unpeeled vertices are explicitly unknown (-1), never a guessed coreness.
    capped.coreness.collect().count(_._2 == KCoreJob.Unknown) shouldBe 8
    val ex = intercept[IllegalStateException](KCoreJob.compute(g, m, maxIterations = 2))
    ex.getMessage should include("did not converge within 2 iterations")
  }

  // ---- Label propagation convergence.

  test("label propagation reports convergence, and non-convergence when stopped early") {
    val converged = CommunityDetectionJob.compute(graph, mapping)
    converged.converged shouldBe true
    converged.unstableVertices shouldBe 0L
    converged.maxIterations shouldBe CommunityDetectionJob.DefaultMaxIterations
    // A path needs several supersteps for labels to settle; one superstep cannot converge.
    val (g, m) = pathGraph
    val early  = CommunityDetectionJob.compute(g, m, maxIterations = 1)
    early.converged shouldBe false
    early.unstableVertices should be > 0L
  }
