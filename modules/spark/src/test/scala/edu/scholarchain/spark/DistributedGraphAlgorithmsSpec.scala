package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.rdd.RDD

/**
 * Feature tests for the distributed (no driver-side graph collection) implementations of the vertex
 * mapping, bridge analysis and k-core decomposition, using small hand-checkable graphs.
 *
 * Betweenness normalisation under test: sum over ordered (source, target) pairs, no division by 2,
 * endpoints excluded, scaled by n / k.
 */
class DistributedGraphAlgorithmsSpec extends SparkTestBase:

  private val Tolerance = 1e-9

  private def edge(a: String, b: String, weight: Int = 1): CoauthorEdge =
    CoauthorEdge(a, b, weight, 2020, 2020, Vector(s"w-$a-$b"), TestFixtures.mkProv(s"e:$a:$b"))

  private def edgesOf(pairs: (String, String)*): Seq[CoauthorEdge] =
    pairs.map { case (a, b) => edge(a, b) }

  private def build(
    edges: Seq[CoauthorEdge],
    isolates: Seq[String] = Seq.empty,
    partitions: Int = 2
  ) =
    val edgeRdd: RDD[CoauthorEdge] = sc.parallelize(edges, partitions)
    val idRdd: RDD[String]         = sc.parallelize(isolates, partitions)
    GraphUtils.buildGraphWithIsolates(sc, edgeRdd, idRdd)

  private def exactBetweenness(
    edges: Seq[CoauthorEdge],
    partitions: Int = 2
  ): Map[String, Double] =
    val (graph, mapping) = build(edges, partitions = partitions)
    val noCommunities    = sc.emptyRDD[(String, String)]
    BridgeAnalysisJob
      .compute(graph, mapping, noCommunities, sampleCount = 1000, maxGraphSize = 1000L, seed = 1L)
      .scores
      .collect()
      .toMap

  private def assertScores(actual: Map[String, Double], expected: Map[String, Double]): Unit =
    actual.keySet shouldBe expected.keySet
    expected.foreach { case (rid, value) =>
      withClue(s"betweenness($rid): ") {
        actual(rid) shouldBe (value +- Tolerance)
      }
    }

  private def coreness(edges: Seq[CoauthorEdge], isolates: Seq[String] = Seq.empty) =
    val (graph, mapping) = build(edges, isolates)
    KCoreJob.compute(graph, mapping).collect().toMap

  // Hand-checkable graphs.
  private val p4 = edgesOf("a" -> "b", "b" -> "c", "c" -> "d")
  private val p5 = edgesOf("a" -> "b", "b" -> "c", "c" -> "d", "d" -> "e")
  private val c4 = edgesOf("a" -> "b", "b" -> "c", "c" -> "d", "d" -> "a")

  /** Triangles {A,B,C} and {D,E,F} joined through bridge vertex X (C - X - D). */
  private val twoTrianglesWithBridge = edgesOf(
    "A" -> "B",
    "B" -> "C",
    "A" -> "C",
    "C" -> "X",
    "X" -> "D",
    "D" -> "E",
    "E" -> "F",
    "D" -> "F"
  )

  /** Triangles {A,B,X} and {C,D,X} sharing vertex X. */
  private val bowtie =
    edgesOf("A" -> "B", "B" -> "X", "A" -> "X", "C" -> "D", "D" -> "X", "C" -> "X")

  // ---------------------------------------------------------------------------
  // Vertex mapping and graph construction
  // ---------------------------------------------------------------------------

  test("mapping round-trip: every endpoint and isolate gets a unique vertex ID") {
    val edges            = edgesOf("r1" -> "r2", "r2" -> "r3", "r3" -> "r1", "r3" -> "r4")
    val isolates         = Seq("iso1", "iso2", "r1")
    val (graph, mapping) = build(edges, isolates, partitions = 3)

    val ids = mapping.ids.collect()
    val expectedResearchers =
      (edges.flatMap(e => Seq(e.researcherA, e.researcherB)) ++ isolates).toSet
    ids.map(_._1).toSet shouldBe expectedResearchers
    ids.length shouldBe expectedResearchers.size
    ids.map(_._2).distinct.length shouldBe ids.length

    // Vertex attribute equals the researcher ID assigned to that vertex ID.
    graph.vertices.collect().toMap shouldBe ids.map(_.swap).toMap

    // Every coauthor edge appears in both directions between the right researchers.
    val triplets = graph.triplets.map(t => (t.srcAttr, t.dstAttr, t.attr)).collect()
    triplets.length shouldBe 2 * edges.size
    triplets.map(t => (t._1, t._2)).toSet shouldBe
      edges.flatMap(e => Seq((e.researcherA, e.researcherB), (e.researcherB, e.researcherA))).toSet
  }

  test("mapping assignment is stable across recomputation") {
    val (_, mapping) = build(p5, Seq("z"), partitions = 4)
    val first        = mapping.ids.collect().toMap
    val second       = mapping.ids.map(identity).collect().toMap
    first shouldBe second
  }

  test("buildVertexMapping + buildGraph preserve edge weights") {
    val edgeRdd = sc.parallelize(Seq(edge("x", "y", weight = 7)))
    val mapping = GraphUtils.buildVertexMapping(edgeRdd)
    val graph   = GraphUtils.buildGraph(sc, edgeRdd, mapping)
    graph.triplets.map(t => (t.srcAttr, t.dstAttr, t.attr)).collect().toSet shouldBe
      Set(("x", "y", 7), ("y", "x", 7))
  }

  test("bounded resolve returns only requested researchers and vertex IDs") {
    val (_, mapping) = build(p5)
    val resolved     = mapping.resolve(Seq("a", "e", "missing"))
    resolved.keySet shouldBe Set("a", "e")
    mapping.resolveVertexIds(resolved.values) shouldBe resolved.map(_.swap)
    mapping.resolve(Seq.empty) shouldBe empty
    mapping.toLong.get("c") shouldBe mapping.resolve(Seq("c")).get("c")
    mapping.toLong.get("missing") shouldBe None
  }

  // ---------------------------------------------------------------------------
  // Betweenness (distributed Brandes)
  // ---------------------------------------------------------------------------

  test("exact betweenness on path P4 matches hand-computed Brandes values") {
    assertScores(exactBetweenness(p4), Map("a" -> 0.0, "b" -> 4.0, "c" -> 4.0, "d" -> 0.0))
  }

  test("exact betweenness on path P5 matches hand-computed Brandes values") {
    assertScores(
      exactBetweenness(p5),
      Map("a" -> 0.0, "b" -> 6.0, "c" -> 8.0, "d" -> 6.0, "e" -> 0.0)
    )
  }

  test("exact betweenness on cycle C4 splits credit across equal shortest paths") {
    assertScores(exactBetweenness(c4), Map("a" -> 1.0, "b" -> 1.0, "c" -> 1.0, "d" -> 1.0))
  }

  test("exact betweenness on two triangles joined by a bridge vertex") {
    assertScores(
      exactBetweenness(twoTrianglesWithBridge),
      Map(
        "A" -> 0.0,
        "B" -> 0.0,
        "C" -> 16.0,
        "X" -> 18.0,
        "D" -> 16.0,
        "E" -> 0.0,
        "F" -> 0.0
      )
    )
  }

  test("exact betweenness on bowtie: shared vertex carries all cross traffic") {
    assertScores(
      exactBetweenness(bowtie),
      Map("A" -> 0.0, "B" -> 0.0, "C" -> 0.0, "D" -> 0.0, "X" -> 8.0)
    )
  }

  test("exact betweenness is identical under different partition counts") {
    val one  = exactBetweenness(twoTrianglesWithBridge, partitions = 1)
    val many = exactBetweenness(twoTrianglesWithBridge, partitions = 5)
    assertScores(many, one)
  }

  test("exact betweenness matches a local reference Brandes on a generated graph") {
    val rng      = new scala.util.Random(11L)
    val vertices = (0 until 24).map(i => s"v$i")
    val pairs = for
      i <- vertices.indices
      j <- (i + 1) until vertices.size
      if rng.nextDouble() < 0.15
    yield (vertices(i), vertices(j))
    val edges            = pairs.map { case (a, b) => edge(a, b) }
    val expected         = DistributedGraphAlgorithmsSpec.referenceBetweenness(vertices, pairs)
    val (graph, mapping) = build(edges, vertices, partitions = 3)
    val actual = BridgeAnalysisJob
      .compute(graph, mapping, sc.emptyRDD, sampleCount = 100, maxGraphSize = 1000L)
      .scores
      .collect()
      .toMap
    assertScores(actual, expected)
  }

  test("sampled betweenness is deterministic for a fixed seed and scaled by n / k") {
    val (graph, mapping) = build(twoTrianglesWithBridge)
    def run(seed: Long) = BridgeAnalysisJob
      .compute(graph, mapping, sc.emptyRDD, sampleCount = 3, maxGraphSize = 1000L, seed = seed)
      .scores
      .collect()
      .toMap
    val first  = run(7L)
    val second = run(7L)
    first shouldBe second
    first.keySet shouldBe Set("A", "B", "C", "X", "D", "E", "F")
    // Each per-source dependency is a multiple of 1 on this graph (unique shortest paths), so
    // every sampled score is a non-negative multiple of n / k = 7 / 3.
    first.values.foreach { v =>
      v should be >= 0.0
      val multiple = v / (7.0 / 3.0)
      multiple shouldBe (math.rint(multiple) +- 1e-6)
    }
    // Leaves of the triangles never lie strictly inside a shortest path.
    Seq("A", "B", "E", "F").foreach(rid => first(rid) shouldBe 0.0)
  }

  test("maxGraphSize fallback yields 0.0 for every vertex and keeps cross-community degree") {
    val (graph, mapping) = build(p4, Seq("iso"))
    val assignments      = sc.parallelize(Seq("a" -> "X", "b" -> "X", "c" -> "Y", "d" -> "Y"))
    val result = BridgeAnalysisJob.compute(
      graph,
      mapping,
      assignments,
      sampleCount = 10,
      maxGraphSize = 2L,
      seed = 42L
    )
    result.scores.collect().toMap shouldBe
      Map("a" -> 0.0, "b" -> 0.0, "c" -> 0.0, "d" -> 0.0, "iso" -> 0.0)
    result.crossCommunityDegrees.collect().toMap shouldBe
      Map("a" -> 1, "b" -> 2, "c" -> 2, "d" -> 1)
  }

  test("betweenness on a graph without edges is 0.0 for every vertex") {
    val (graph, mapping) = build(Seq.empty, Seq("p", "q"))
    BridgeAnalysisJob
      .compute(graph, mapping, sc.emptyRDD, sampleCount = 5, maxGraphSize = 100L)
      .scores
      .collect()
      .toMap shouldBe Map("p" -> 0.0, "q" -> 0.0)
  }

  // ---------------------------------------------------------------------------
  // Cross-community degree
  // ---------------------------------------------------------------------------

  test("cross-community degree counts distinct neighbour communities, unassigned use own ID") {
    // P4 a-b-c-d (d unassigned), star z-{l1,l2,l3} (z unassigned), isolate iso.
    val edges            = p4 ++ edgesOf("z" -> "l1", "z" -> "l2", "z" -> "l3")
    val (graph, mapping) = build(edges, Seq("iso"))
    val assignments = sc.parallelize(
      Seq(
        "a"   -> "X",
        "b"   -> "X",
        "c"   -> "Y",
        "l1"  -> "P",
        "l2"  -> "P",
        "l3"  -> "Q",
        "iso" -> "X"
      )
    )
    val degrees =
      BridgeAnalysisJob.computeCrossCommunityDegree(graph, mapping, assignments).collect().toMap
    degrees shouldBe Map(
      "a"  -> 1, // {X}
      "b"  -> 2, // {X, Y}
      "c"  -> 2, // {X, "d"}
      "d"  -> 1, // {Y}
      "z"  -> 2, // {P, Q}: two leaves share P
      "l1" -> 1, // {"z"}
      "l2" -> 1,
      "l3" -> 1
    )
  }

  // ---------------------------------------------------------------------------
  // K-core decomposition (distributed peeling)
  // ---------------------------------------------------------------------------

  test("coreness of complete graph K4 is 3 for every vertex") {
    val k4 = edgesOf("a" -> "b", "a" -> "c", "a" -> "d", "b" -> "c", "b" -> "d", "c" -> "d")
    coreness(k4) shouldBe Map("a" -> 3, "b" -> 3, "c" -> 3, "d" -> 3)
  }

  test("coreness of a star: center and leaves are 1") {
    val star = edgesOf("s" -> "l1", "s" -> "l2", "s" -> "l3", "s" -> "l4")
    coreness(star) shouldBe Map("s" -> 1, "l1" -> 1, "l2" -> 1, "l3" -> 1, "l4" -> 1)
  }

  test("coreness of a triangle with a pendant vertex") {
    val g = edgesOf("a" -> "b", "b" -> "c", "a" -> "c", "c" -> "p")
    coreness(g) shouldBe Map("a" -> 2, "b" -> 2, "c" -> 2, "p" -> 1)
  }

  test("coreness of an isolated vertex is 0") {
    coreness(edgesOf("a" -> "b"), Seq("iso")) shouldBe Map("a" -> 1, "b" -> 1, "iso" -> 0)
  }

  test("coreness of an empty graph is empty") {
    val emptyEdges = sc.emptyRDD[CoauthorEdge]
    val mapping    = GraphUtils.buildVertexMapping(emptyEdges)
    val graph      = GraphUtils.buildGraph(sc, emptyEdges, mapping)
    KCoreJob.compute(graph, mapping).collect() shouldBe empty
  }

  test("coreness cascades: K4 with a pendant path, and a long path") {
    val g = edgesOf(
      "a" -> "b",
      "a" -> "c",
      "a" -> "d",
      "b" -> "c",
      "b" -> "d",
      "c" -> "d",
      "d" -> "e",
      "e" -> "f"
    )
    coreness(g) shouldBe Map("a" -> 3, "b" -> 3, "c" -> 3, "d" -> 3, "e" -> 1, "f" -> 1)
    coreness(p5) shouldBe Map("a" -> 1, "b" -> 1, "c" -> 1, "d" -> 1, "e" -> 1)
  }

  test("coreness matches a local reference peeling on a generated graph") {
    val rng      = new scala.util.Random(5L)
    val vertices = (0 until 30).map(i => s"v$i")
    val pairs = for
      i <- vertices.indices
      j <- (i + 1) until vertices.size
      if rng.nextDouble() < 0.2
    yield (vertices(i), vertices(j))
    val expected = DistributedGraphAlgorithmsSpec.referenceCoreness(vertices, pairs)
    coreness(pairs.map { case (a, b) => edge(a, b) }, vertices) shouldBe expected
  }

  // Declared last: setting a checkpoint directory switches every later call in this suite from
  // localCheckpoint to reliable checkpointing.
  test("algorithms give the same results with a reliable checkpoint directory") {
    val dir = java.nio.file.Files.createTempDirectory("spark-ckpt").toString
    sc.setCheckpointDir(dir)
    assertScores(
      exactBetweenness(twoTrianglesWithBridge),
      Map(
        "A" -> 0.0,
        "B" -> 0.0,
        "C" -> 16.0,
        "X" -> 18.0,
        "D" -> 16.0,
        "E" -> 0.0,
        "F" -> 0.0
      )
    )
    val g = edgesOf("a" -> "b", "b" -> "c", "a" -> "c", "c" -> "p")
    coreness(g, Seq("iso")) shouldBe Map("a" -> 2, "b" -> 2, "c" -> 2, "p" -> 1, "iso" -> 0)
  }

object DistributedGraphAlgorithmsSpec:

  private def adjacencyOf(
    vertices: Seq[String],
    pairs: Seq[(String, String)]
  ): Map[String, Seq[String]] =
    val base = vertices.map(v => v -> Seq.empty[String]).toMap
    pairs.foldLeft(base) { case (acc, (a, b)) =>
      acc.updated(a, acc(a) :+ b).updated(b, acc(b) :+ a)
    }

  /** Textbook single-machine Brandes over all sources; ordered pairs, no division by 2. */
  def referenceBetweenness(
    vertices: Seq[String],
    pairs: Seq[(String, String)]
  ): Map[String, Double] =
    val adj = adjacencyOf(vertices, pairs)
    val bc  = scala.collection.mutable.Map(vertices.map(_ -> 0.0)*)
    vertices.foreach { s =>
      val dist  = scala.collection.mutable.Map(s -> 0)
      val sigma = scala.collection.mutable.Map(vertices.map(_ -> 0.0)*)
      val preds = scala.collection.mutable.Map(vertices.map(_ -> List.empty[String])*)
      val order = scala.collection.mutable.ArrayBuffer.empty[String]
      val queue = scala.collection.mutable.Queue(s)
      sigma(s) = 1.0
      while queue.nonEmpty do
        val v = queue.dequeue()
        order += v
        adj(v).foreach { w =>
          if !dist.contains(w) then
            dist(w) = dist(v) + 1
            queue.enqueue(w)
          if dist(w) == dist(v) + 1 then
            sigma(w) += sigma(v)
            preds(w) = v :: preds(w)
        }
      val delta = scala.collection.mutable.Map(vertices.map(_ -> 0.0)*)
      order.reverseIterator.foreach { w =>
        preds(w).foreach(v => delta(v) += sigma(v) / sigma(w) * (1.0 + delta(w)))
        if w != s then bc(w) += delta(w)
      }
    }
    bc.toMap

  /** Single-machine peeling by repeated minimum-degree removal. */
  def referenceCoreness(vertices: Seq[String], pairs: Seq[(String, String)]): Map[String, Int] =
    val adj    = adjacencyOf(vertices, pairs).view.mapValues(_.toSet).toMap
    val alive  = scala.collection.mutable.Set(vertices*)
    val result = scala.collection.mutable.Map.empty[String, Int]
    var k      = 0
    while alive.nonEmpty do
      val (v, deg) = alive.toSeq.map(v => v -> adj(v).count(alive.contains)).minBy(_._2)
      k = math.max(k, deg)
      result(v) = k
      alive -= v
    result.toMap
