package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.rdd.RDD

class AdversarialSparkSpec extends SparkTestBase:

  private val testProv = TestFixtures.testProv

  private def mkProv(recordId: String): Provenance =
    testProv.copy(sourceRecordId = recordId)

  // --- 1. Empty graph (no edges) ---

  test("ConnectedComponentsJob handles empty graph with no edges gracefully") {
    val emptyEdges: RDD[CoauthorEdge] = sc.emptyRDD[CoauthorEdge]
    val singleNode: RDD[String]       = sc.parallelize(Seq("R1"))

    val (graph, mapping) =
      GraphUtils.buildGraphWithIsolates(sc, emptyEdges, singleNode)
    val result = ConnectedComponentsJob.compute(graph, mapping)

    result.componentCount shouldBe 1L
    result.singletonCount shouldBe 1L
    result.largestComponentFraction shouldBe 1.0
  }

  // --- 2. Single disconnected node ---

  test("GraphUtils builds graph with isolated vertex that has no edges") {
    val emptyEdges: RDD[CoauthorEdge] = sc.emptyRDD[CoauthorEdge]
    val isolated: RDD[String]         = sc.parallelize(Seq("ISOLATED"))

    val (graph, mapping) =
      GraphUtils.buildGraphWithIsolates(sc, emptyEdges, isolated)

    graph.vertices.count() shouldBe 1L
    graph.edges.count() shouldBe 0L
    mapping.toLong should contain key "ISOLATED"
  }

  // --- 3. Self-loop edge (same researcher both sides) ---

  test("CoauthorPairCandidate rejects self-pairs at construction time") {
    an[IllegalArgumentException] should be thrownBy {
      CoauthorPairCandidate("m1", "m1", "w1", 2020, mkProv("self-pair"))
    }
  }

  test("CanonicalGraphBuilder drops self-pairs after mention resolution") {
    // m1 and m1b map to the same canonical ID -> creates a self-pair
    val pairCandidates: RDD[CoauthorPairCandidate] =
      sc.parallelize(TestFixtures.pairCandidatesWithSelfPair)

    val mentionMapping: RDD[(String, String)] = sc.parallelize(
      Seq(
        "m1"  -> "canonical-A",
        "m1b" -> "canonical-A",
        "m2"  -> "canonical-B"
      )
    )

    val edges = CanonicalGraphBuilder.buildFromMapping(
      sc,
      pairCandidates,
      mentionMapping,
      windowStartYear = 2000,
      windowEndYear = 2030,
      runId = "run-test",
      schemaVersion = "1.0.0",
      policyVersion = "v1",
      timestamp = "2026-01-01T00:00:00Z"
    )

    // The self-pair (m1, m1b) -> (canonical-A, canonical-A) should be dropped
    val selfEdges = edges.filter(e => e.researcherA == e.researcherB).count()
    selfEdges shouldBe 0L

    // The valid pair (m1, m2) -> (canonical-A, canonical-B) should remain
    edges.count() shouldBe 1L
  }

  // --- 4. Known triangle graph ---

  test("triangle graph has degree 2 for each node, component size 3, and shortest path 1") {
    val edgeAB = CoauthorEdge("A", "B", 1, 2020, 2020, Vector("w1"), mkProv("e:A:B"))
    val edgeBC = CoauthorEdge("B", "C", 1, 2020, 2020, Vector("w2"), mkProv("e:B:C"))
    val edgeAC = CoauthorEdge("A", "C", 1, 2020, 2020, Vector("w3"), mkProv("e:A:C"))

    val edges: RDD[CoauthorEdge] = sc.parallelize(Seq(edgeAB, edgeBC, edgeAC))
    val mapping                  = GraphUtils.buildVertexMapping(edges)
    val graph                    = GraphUtils.buildGraph(sc, edges, mapping)

    // Connected components
    val cc = ConnectedComponentsJob.compute(graph, mapping)
    cc.componentCount shouldBe 1L
    cc.singletonCount shouldBe 0L

    val assignments = cc.assignments.collect()
    assignments.length shouldBe 3
    // All in same component
    assignments.map(_._2).distinct.length shouldBe 1
    // Component size = 3 for all
    assignments.foreach(_._3 shouldBe 3L)

    // Shortest path from A to C should be 1 (direct edge)
    val pathResults = ShortestPathJob.compute(graph, mapping, Seq(("A", "C")))
    pathResults should have size 1
    pathResults.head.pathLength shouldBe 1
  }

  // --- 5. Large clique (5 nodes) ---

  test("5-node clique has 10 edges, all in one component, all with degree 4") {
    val nodes = Seq("N1", "N2", "N3", "N4", "N5")
    val cliqueEdges = for
      i <- nodes.indices
      j <- (i + 1) until nodes.size
    yield CoauthorEdge(
      nodes(i),
      nodes(j),
      1,
      2020,
      2020,
      Vector(s"w-${nodes(i)}-${nodes(j)}"),
      mkProv(s"e:${nodes(i)}:${nodes(j)}")
    )

    cliqueEdges.size shouldBe 10

    val edgesRDD: RDD[CoauthorEdge] = sc.parallelize(cliqueEdges)
    val mapping                     = GraphUtils.buildVertexMapping(edgesRDD)
    val graph                       = GraphUtils.buildGraph(sc, edgesRDD, mapping)

    // All in one component
    val cc = ConnectedComponentsJob.compute(graph, mapping)
    cc.componentCount shouldBe 1L
    cc.singletonCount shouldBe 0L
    cc.largestComponentFraction shouldBe 1.0

    // Shortest path between any pair should be 1
    val pathResults = ShortestPathJob.compute(graph, mapping, Seq(("N1", "N5")))
    pathResults should have size 1
    pathResults.head.pathLength shouldBe 1
  }

  // --- 6. CollaborationMetrics for a graph with no edges ---

  test("CollaborationMetricsJob returns zero metrics for researcher with no coauthors") {
    val emptyEdges: RDD[CoauthorEdge]                            = sc.emptyRDD[CoauthorEdge]
    val authorships: RDD[CollaborationMetricsJob.AuthorshipInfo] = sc.emptyRDD

    val metrics = CollaborationMetricsJob.compute(
      sc,
      emptyEdges,
      authorships,
      windowStartYear = 2000,
      windowEndYear = 2030,
      runId = "run-test",
      schemaVersion = "1.0.0",
      timestamp = "2026-01-01T00:00:00Z"
    )

    metrics.count() shouldBe 0L
  }
