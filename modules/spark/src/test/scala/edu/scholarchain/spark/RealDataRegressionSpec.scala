package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.rdd.RDD

/**
 * Regression and feature tests for behaviour found or changed while running the pipeline on the
 * LOCAL REAL-DATA DBLP development slice (rs-20261123-dblp10k-first500): community detection on
 * collaboration cliques, deterministic canonical IDs, team-size based authorships, window-accurate
 * temporal metrics, and partition-independent results.
 */
class RealDataRegressionSpec extends SparkTestBase:

  private def prov(id: String) = TestFixtures.mkProv(id)

  private def edge(a: String, b: String, works: Int = 1): CoauthorEdge =
    CoauthorEdge(a, b, works, 2020, 2020, Vector(s"w-$a-$b"), prov(s"e:$a:$b"))

  private def clique(prefix: String, n: Int): Seq[CoauthorEdge] =
    for
      i <- 0 until n
      j <- (i + 1) until n
    yield edge(s"$prefix$i", s"$prefix$j")

  private def communities(
    edges: Seq[CoauthorEdge],
    isolates: Seq[String] = Seq.empty,
    parts: Int = 2
  ) =
    val (graph, mapping) =
      GraphUtils.buildGraphWithIsolates(
        sc,
        sc.parallelize(edges, parts),
        sc.parallelize(isolates, parts)
      )
    CommunityDetectionJob.compute(graph, mapping)

  private def decision(a: String, b: String, outcome: MatchOutcome) =
    MatchDecision(a, b, Map.empty, outcome, 0.9, Vector.empty, "p", "r")

  // ---- Community detection (real data: 625 communities over 498 components before the fix).

  test("a single collaboration clique is one community (GraphX LPA oscillated and split it)") {
    val result = communities(clique("c", 6))
    result.communityCount shouldBe 1L
    result.assignments.map(_._2).distinct().collect() shouldBe Array("community:c0")
  }

  test("a single coauthor edge is one community, an isolate is its own community") {
    val result = communities(Seq(edge("p", "q")), isolates = Seq("z"))
    result.assignments.collect().map { case (r, c, n) => r -> (c, n) }.toMap shouldBe Map(
      "p" -> ("community:p", 2L),
      "q" -> ("community:p", 2L),
      "z" -> ("community:z", 1L)
    )
  }

  test("disjoint cliques never yield more communities than connected components") {
    val edges  = clique("a", 13) ++ clique("b", 2) ++ clique("c", 3) ++ clique("d", 5)
    val result = communities(edges)
    result.communityCount shouldBe 4L
    val (graph, mapping) = GraphUtils.buildGraphWithIsolates(sc, sc.parallelize(edges), sc.emptyRDD)
    ConnectedComponentsJob.compute(graph, mapping).componentCount shouldBe 4L
  }

  test("two cliques joined by a merged researcher keep their cores as communities") {
    // Shape of the real merges: one merged mention links two otherwise separate author teams.
    val edges = clique("x", 4) ++ clique("y", 4) ++ Seq(edge("m", "x0"), edge("m", "y0"))
    val byResearcher =
      communities(edges).assignments.collect().map { case (r, c, _) => r -> c }.toMap
    (0 until 4).map(i => byResearcher(s"x$i")).distinct should have size 1
    (0 until 4).map(i => byResearcher(s"y$i")).distinct should have size 1
  }

  test("community assignments do not depend on partitioning") {
    val rng = new scala.util.Random(11L)
    val vs  = (0 until 40).map(i => f"v$i%02d")
    val edges = for
      i <- vs.indices
      j <- (i + 1) until vs.size
      if rng.nextDouble() < 0.08
    yield edge(vs(i), vs(j))
    def run(parts: Int) =
      communities(edges, isolates = vs, parts = parts).assignments.collect().sortBy(_._1).toSeq
    run(1) shouldBe run(5)
  }

  test(
    "connected component IDs are named by the smallest member and do not depend on partitioning"
  ) {
    val edges = Seq(edge("b", "c"), edge("c", "d"), edge("x", "y"))
    def run(parts: Int) =
      val (graph, mapping) = GraphUtils.buildGraphWithIsolates(
        sc,
        sc.parallelize(edges, parts),
        sc.parallelize(Seq("z"), parts)
      )
      val result = ConnectedComponentsJob.compute(graph, mapping)
      (result.assignments.collect().sortBy(_._1).toSeq, result.largestComponentId)
    val (one, largest) = run(1)
    one shouldBe Seq(
      ("b", "component:b", 3L),
      ("c", "component:b", 3L),
      ("d", "component:b", 3L),
      ("x", "component:x", 2L),
      ("y", "component:x", 2L),
      ("z", "component:z", 1L)
    )
    largest shouldBe Some("component:b")
    run(4)._1 shouldBe one
  }

  // ---- Canonicalization.

  test(
    "canonical ID is the lexicographically smallest mention of a merged group, chains included"
  ) {
    val accepted = sc.parallelize(
      Seq(
        decision("m:9", "m:5", MatchOutcome.Accepted),
        decision("m:5", "m:7", MatchOutcome.Accepted),
        decision("m:1", "m:1", MatchOutcome.Accepted)
      )
    )
    CanonicalGraphBuilder.matchedMentionMapping(accepted).collect().toMap shouldBe Map(
      "m:9" -> "m:5",
      "m:5" -> "m:5",
      "m:7" -> "m:5"
    )
    val all = sc.parallelize(Seq("m:5", "m:7", "m:9", "m:3"))
    CanonicalGraphBuilder.buildMentionMapping(sc, accepted, all).collect().toMap shouldBe Map(
      "m:9" -> "m:5",
      "m:5" -> "m:5",
      "m:7" -> "m:5",
      "m:3" -> "m:3"
    )
  }

  test("only accepted decisions merge; representativeWorkIds are sorted and distinct") {
    val pairs = sc.parallelize(
      Seq(
        CoauthorPairCandidate("m:a1", "m:b", "w9", 2020, prov("p1")),
        CoauthorPairCandidate("m:a2", "m:b", "w1", 2018, prov("p2")),
        CoauthorPairCandidate("m:a2", "m:c", "w1", 2018, prov("p3"))
      )
    )
    val decisions = Seq(
      decision("m:a1", "m:a2", MatchOutcome.Accepted),
      decision("m:b", "m:c", MatchOutcome.Rejected)
    )
    val accepted = sc.parallelize(decisions.filter(_.decision == MatchOutcome.Accepted))
    val edges = CanonicalGraphBuilder
      .build(sc, pairs, accepted, 2000, 2030, "run", "1.0.0", "v1", "ts")
      .collect()
      .sortBy(e => (e.researcherA, e.researcherB))
    edges.map(e => (e.researcherA, e.researcherB, e.jointPublicationCount)) shouldBe Array(
      ("m:a1", "m:b", 2),
      ("m:a1", "m:c", 1)
    )
    edges.head.representativeWorkIds shouldBe Vector("w1", "w9")
    edges.head.firstCollaborationYear shouldBe 2018
    edges.head.lastCollaborationYear shouldBe 2020
  }

  test("canonical authorships count distinct works and team size after merges, isolates included") {
    def mention(id: String, pos: Int) =
      AuthorMention(id, id, id, pos, None, None, None, "src", prov(id))
    val teams = sc.parallelize(
      Seq(
        PublicationTeam(
          "w1",
          2020,
          Vector(mention("m:a1", 0), mention("m:b", 1), mention("m:c", 2)),
          3,
          prov("w1")
        ),
        PublicationTeam("w2", 2021, Vector(mention("m:a2", 0), mention("m:d", 1)), 2, prov("w2")),
        PublicationTeam("w3", 2019, Vector(mention("m:solo", 0)), 1, prov("w3")),
        PublicationTeam("w4", 1970, Vector(mention("m:old", 0), mention("m:b", 1)), 2, prov("w4"))
      )
    )
    val mapping = sc.parallelize(Seq("m:a2" -> "m:a1", "m:a1" -> "m:a1"))
    val result = CanonicalGraphBuilder
      .canonicalAuthorships(teams, mapping, 2000, 2030)
      .collect()
      .map(a => (a.researcherId, a.workId, a.year, a.coauthorsOnPaper))
      .sorted
    result shouldBe Array(
      ("m:a1", "w1", 2020, 2),
      ("m:a1", "w2", 2021, 1),
      ("m:b", "w1", 2020, 2),
      ("m:c", "w1", 2020, 2),
      ("m:d", "w2", 2021, 1),
      ("m:solo", "w3", 2019, 0)
    )
  }

  // ---- Temporal metrics.

  test("temporal metrics count only collaborations inside each window") {
    import CanonicalGraphBuilder.{CanonicalAuthorship, CanonicalPair}
    val pairs = sc.parallelize(
      Seq(
        CanonicalPair("a", "b", "w1", 2001),
        CanonicalPair("a", "b", "w2", 2020),
        CanonicalPair("a", "b", "w3", 2021),
        CanonicalPair("a", "c", "w3", 2021)
      )
    )
    val authorships = sc.parallelize(
      Seq(
        CanonicalAuthorship("a", "w1", 2001, 1),
        CanonicalAuthorship("b", "w1", 2001, 1),
        CanonicalAuthorship("a", "w2", 2020, 1),
        CanonicalAuthorship("b", "w2", 2020, 1),
        CanonicalAuthorship("a", "w3", 2021, 2),
        CanonicalAuthorship("b", "w3", 2021, 2),
        CanonicalAuthorship("c", "w3", 2021, 2)
      )
    )
    val windows = TemporalAnalysisJob.generateWindows(2000, 2024, 5, 5)
    val snaps = TemporalAnalysisJob
      .computeFromPairs(sc, pairs, authorships, windows, "run", "1.0.0", "ts")
      .collect()
      .map(s =>
        (
          (s.researcherId, s.windowStartYear),
          (s.publicationCount, s.distinctCoauthorCount, s.weightedDegree)
        )
      )
      .toMap
    // a-b collaborated in 2001 and 2020-2021 only: nothing in 2005-2019.
    snaps.keySet.filter(_._1 == "a") shouldBe Set(("a", 2000), ("a", 2020))
    snaps(("a", 2000)) shouldBe ((1, 1, 1.0))
    snaps(("a", 2020)) shouldBe ((2, 2, 3.0))
    snaps(("c", 2020)) shouldBe ((1, 1, 1.0))
  }

  // ---- Shortest paths and betweenness.

  test("shortest paths are deterministic and bounded; unreachable pairs are absent") {
    val edges = Seq(edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d"), edge("d", "e"))
    val (graph, mapping) =
      GraphUtils.buildGraphWithIsolates(sc, sc.parallelize(edges, 3), sc.parallelize(Seq("z")))
    val pairs = Seq("a" -> "e", "a" -> "z", "e" -> "a")
    val first = ShortestPathJob.compute(graph, mapping, pairs)
    first.map(p => (p.sourceResearcherId, p.targetResearcherId, p.pathLength)) shouldBe Seq(
      ("a", "e", 3),
      ("e", "a", 3)
    )
    first.foreach { p =>
      p.pathVertices.head shouldBe p.sourceResearcherId
      p.pathVertices.last shouldBe p.targetResearcherId
      p.pathVertices.zip(p.pathVertices.tail).foreach { case (u, v) =>
        edges.exists(e => Set(e.researcherA, e.researcherB) == Set(u, v)) shouldBe true
      }
    }
    (1 to 3).foreach(_ => ShortestPathJob.compute(graph, mapping, pairs) shouldBe first)
    ShortestPathJob.compute(graph, mapping, pairs, maxHops = 2) shouldBe empty
  }

  test("path pair selection is deterministic within the requested component") {
    def m(rid: String, w: Double, comp: String) =
      ResearcherMetrics(rid, 1, 1, 1, 1.0, 1, w, Some(comp), None, 0.0, 1, 2000, 2030, prov(rid))
    val metrics = sc.parallelize(
      Seq(m("a", 5, "c1"), m("b", 5, "c1"), m("c", 3, "c1"), m("d", 1, "c1"), m("e", 9, "c2")),
      3
    )
    ShortestPathJob.selectPairs(metrics, Some("c1"), 2) shouldBe Seq("a" -> "c", "b" -> "d")
    ShortestPathJob.selectPairs(metrics, None, 2) shouldBe empty
    ShortestPathJob.selectPairs(metrics, Some("c1"), 0) shouldBe empty
  }

  test("sampled betweenness does not depend on partitioning") {
    val rng = new scala.util.Random(3L)
    val vs  = (0 until 30).map(i => f"v$i%02d")
    val edges = for
      i <- vs.indices
      j <- (i + 1) until vs.size
      if rng.nextDouble() < 0.12
    yield edge(vs(i), vs(j))
    def run(parts: Int): Map[String, Double] =
      val (graph, mapping) =
        GraphUtils.buildGraphWithIsolates(
          sc,
          sc.parallelize(edges, parts),
          sc.parallelize(vs, parts)
        )
      BridgeAnalysisJob
        .compute(graph, mapping, sc.emptyRDD[(String, String)], sampleCount = 7, seed = 9L)
        .scores
        .collect()
        .toMap
    val one  = run(1)
    val many = run(4)
    one.keySet shouldBe many.keySet
    one.foreach { case (r, s) => many(r) shouldBe (s +- 1e-9) }
  }

  // ---- Data loading.

  test("loader reads gzip part files, skips blank lines and Hadoop side files, and repartitions") {
    val dir = java.nio.file.Files.createTempDirectory("loader-gz")
    val gz = new java.util.zip.GZIPOutputStream(
      java.nio.file.Files.newOutputStream(dir.resolve("part-m-00000.gz"))
    )
    val lines = Seq(
      upickle.default.write(CoauthorPairCandidate("m:a", "m:b", "w1", 2020, prov("x"))),
      "",
      upickle.default.write(CoauthorPairCandidate("m:b", "m:c", "w2", 2021, prov("y")))
    )
    gz.write(lines.mkString("\n").getBytes("UTF-8"))
    gz.close()
    java.nio.file.Files.writeString(dir.resolve("_SUCCESS"), "")
    val rdd: RDD[CoauthorPairCandidate] =
      DataLoader.loadCoauthorPairCandidates(spark, dir.toUri.toString, Some(3))
    rdd.getNumPartitions shouldBe 3
    rdd.map(_.workId).collect().sorted shouldBe Array("w1", "w2")
  }
