package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.sql.SparkSession

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * End-to-end feature tests of [[ScholarChainPipeline]] on a FIXTURE laid out like the Hadoop export
 * (`pair_candidates/` gzip part file, `teams/`) plus an entity-resolution `match-decisions/`
 * directory.
 *
 * Fixture (hand-checkable):
 * {{{
 *   W1 2020: m:W1:0, m:W1:1, m:W1:2      pairs (0,1) (0,2) (1,2)
 *   W2 2021: m:W2:0, m:W2:1              pair  (0,1)
 *   W3 2019: m:W3:0 (single author)      no pairs -> isolated researcher
 *   W4 2010: m:W4:0, m:W4:1              pair  (0,1)
 *   Accepted: m:W1:0 ~ m:W2:0  (-> canonical m:W1:0)    Rejected: m:W1:1 ~ m:W4:0
 * }}}
 * 8 mentions - 1 merge = 7 researchers; 5 coauthor edges; components {W1 team + W2:1} (4), {W4
 * pair} (2), {W3:0} (1).
 */
class PipelineEndToEndSpec extends SparkTestBase:

  // Pregel checkpoints every 2 supersteps so the reliable-checkpoint path of GraphX is exercised.
  @transient override lazy val spark: SparkSession =
    SparkSessionFactory.create(
      appName = getClass.getSimpleName,
      master = "local[2]",
      extraConfigs = Map(
        "spark.sql.shuffle.partitions"                  -> "2",
        "spark.default.parallelism"                     -> "2",
        SparkSessionFactory.PregelCheckpointIntervalKey -> "2"
      )
    )

  private val ts = "2026-11-23T00:00:00Z"

  private def prov(id: String) =
    Provenance("dblp", id, "hadoop-fixture-run", "1.0.0", ts)

  private def m(id: String, pos: Int) =
    AuthorMention(id, id, id, pos, None, None, None, id, prov(id))

  private val teams = Seq(
    PublicationTeam(
      "work:W1",
      2020,
      Vector(m("m:W1:0", 0), m("m:W1:1", 1), m("m:W1:2", 2)),
      3,
      prov("W1")
    ),
    PublicationTeam("work:W2", 2021, Vector(m("m:W2:0", 0), m("m:W2:1", 1)), 2, prov("W2")),
    PublicationTeam("work:W3", 2019, Vector(m("m:W3:0", 0)), 1, prov("W3")),
    PublicationTeam("work:W4", 2010, Vector(m("m:W4:0", 0), m("m:W4:1", 1)), 2, prov("W4"))
  )

  private val pairs: Seq[CoauthorPairCandidate] = teams.flatMap { t =>
    for
      i <- t.authorMentions.indices
      j <- (i + 1) until t.authorMentions.size
    yield CoauthorPairCandidate(
      t.authorMentions(i).mentionId,
      t.authorMentions(j).mentionId,
      t.workId,
      t.year,
      prov(t.workId)
    )
  }

  private def decision(a: String, b: String, outcome: MatchOutcome) =
    MatchDecision(
      a,
      b,
      Map("name_similarity" -> 1.0),
      outcome,
      0.9,
      Vector("r"),
      "er-test",
      "er-run"
    )

  private lazy val inputRoot: Path =
    val root = Files.createTempDirectory("pipeline-e2e-in")
    val pc   = Files.createDirectories(root.resolve("export/pair_candidates"))
    val gz =
      new java.util.zip.GZIPOutputStream(Files.newOutputStream(pc.resolve("part-m-00001.gz")))
    gz.write(pairs.map(p => upickle.default.write(p)).mkString("", "\n", "\n").getBytes("UTF-8"))
    gz.close()
    Files.writeString(pc.resolve("_SUCCESS"), "")
    val tm = Files.createDirectories(root.resolve("export/teams"))
    Files.writeString(
      tm.resolve("part-m-00000"),
      teams.map(t => upickle.default.write(t)).mkString("\n")
    )
    val md = Files.createDirectories(root.resolve("match-decisions"))
    Files.writeString(
      md.resolve("part-00000"),
      Seq(
        decision("m:W1:0", "m:W2:0", MatchOutcome.Accepted),
        decision("m:W1:1", "m:W4:0", MatchOutcome.Rejected)
      ).map(d => upickle.default.write(d)).mkString("\n")
    )
    root

  private def config(out: Path, partitions: Int, extra: String*): PipelineConfig =
    PipelineConfig
      .parse(
        Seq(
          "--hadoop-output",
          inputRoot.resolve("export").toUri.toString,
          "--match-decisions",
          inputRoot.resolve("match-decisions").toUri.toString,
          "--output",
          out.toUri.toString,
          "--window-start",
          "2010",
          "--window-end",
          "2024",
          "--run-id",
          "e2e",
          "--partitions",
          partitions.toString,
          "--run-timestamp",
          ts,
          "--betweenness-samples",
          "100"
        ) ++ extra
      )
      .fold(e => fail(e), identity)

  private def freshOut(): Path =
    val dir = Files.createTempDirectory("pipeline-e2e-out")
    Files.delete(dir) // the pipeline must create its own output root
    dir

  private lazy val ckptRoot: Path = Files.createTempDirectory("pipeline-e2e-ckpt")
  private lazy val outA: Path     = freshOut()

  private lazy val statsA: ujson.Obj =
    sc.setCheckpointDir(ckptRoot.toUri.toString)
    ScholarChainPipeline.run(spark, config(outA, 1))

  private def num(v: ujson.Value): Long = v.num.toLong

  test("canonical graph counts follow mentions minus merges and pair candidates") {
    val s = statsA
    num(s("inputs")("pairCandidates")) shouldBe 5L
    num(s("inputs")("teams")) shouldBe 4L
    num(s("inputs")("authorMentionsInWindow")) shouldBe 8L
    num(s("inputs")("matchDecisions")("Accepted")) shouldBe 1L
    num(s("canonicalization")("mentionsMergedAway")) shouldBe 1L
    num(s("graph")("vertices")) shouldBe 7L
    num(s("graph")("coauthorEdges")) shouldBe 5L
    num(s("graph")("graphxDirectedEdges")) shouldBe 10L
    num(s("graph")("researcherMetrics")) shouldBe 7L
    num(s("graph")("publicationCountSum")) shouldBe 8L
  }

  test("analytics stages report hand-checked values") {
    val s = statsA
    num(s("components")("count")) shouldBe 3L
    num(s("components")("largestSize")) shouldBe 4L
    num(s("components")("singletons")) shouldBe 1L
    s("components")("largestId").str shouldBe "component:m:W1:0"
    num(s("communities")("count")) shouldBe 3L
    s("communities")("algorithm").str should include("propagateLabels")
    num(s("kcore")("maxCoreness")) shouldBe 2L
    s("bridges")("betweennessComputed").bool shouldBe true
    // m:W1:0 (merged) is the only vertex on shortest paths between W1 and W2 coauthors:
    // exact Brandes over ordered pairs = 2 * (W2:1 <-> W1:1, W2:1 <-> W1:2) = 4.
    val top = s("bridges")("topBetweenness").arr.head
    top("researcherId").str shouldBe "m:W1:0"
    top("score").num shouldBe 4.0
    num(s("temporal")("windows")) shouldBe 3L
    s("semanticSummaries")("researcher-profile").num shouldBe 7.0
    s("semanticSummaries")("community-summary").num shouldBe 3.0
    s("neptune")("researcherNodes").num shouldBe 7.0
    s("neptune")("workNodes").num shouldBe 4.0
    s("neptune")("coauthorRelationships").num shouldBe 5.0
    s("neptune")("authoredRelationships").num shouldBe 8.0
  }

  test("default path pairs are sampled deterministically from the largest component") {
    val sp = statsA("shortestPaths")
    sp("pairSource").str should startWith("deterministic-sample")
    num(sp("found")) shouldBe num(sp("requested"))
    num(sp("requested")) shouldBe 2L
    sp("paths").arr.foreach(p => p("vertices").arr.head.str shouldBe p("source").str)
  }

  test("every documented output is written and the checkpoint directory was used") {
    statsA
    Seq(
      "edges",
      "metrics",
      "summaries",
      "paths",
      "components",
      "communities",
      "temporal",
      "manifest",
      "run-stats",
      "neptune"
    )
      .foreach(d => Files.isDirectory(outA.resolve(d)) shouldBe true)
    val ckptFiles = Files.walk(ckptRoot).iterator().asScala.count(Files.isRegularFile(_))
    ckptFiles should be > 0
    statsA("config")("pregelCheckpointInterval").str shouldBe "2"
    statsA("config")("checkpointDir").str should include(ckptRoot.getFileName.toString)
  }

  test("explicit path pairs are honoured, including unreachable ones") {
    val out = freshOut()
    val s = ScholarChainPipeline.run(
      spark,
      config(out, 2, "--path-pairs", "m:W2:1|m:W1:2,m:W2:1|m:W3:0", "--neptune-csv", "false")
    )
    val sp = s("shortestPaths")
    sp("pairSource").str shouldBe "explicit"
    num(sp("requested")) shouldBe 2L
    num(sp("found")) shouldBe 1L
    sp("paths").arr.head("vertices").arr.map(_.str).toSeq shouldBe Seq("m:W2:1", "m:W1:0", "m:W1:2")
    s("neptune") shouldBe ujson.Null
    Files.exists(out.resolve("neptune")) shouldBe false
  }

  private def csvLines(out: Path): Seq[String] =
    Files
      .walk(out.resolve("neptune"))
      .iterator()
      .asScala
      .toSeq
      .filter(p => p.getFileName.toString.startsWith("part-"))
      .flatMap(p => Files.readAllLines(p).asScala.drop(1))
      .sorted

  private def parquetRows(out: Path, name: String): Seq[String] =
    spark.read.parquet(out.resolve(name).toString).collect().map(_.toString).toSeq.sorted

  test("outputs do not depend on the partition count (fixed run timestamp)") {
    statsA
    val outB = freshOut()
    ScholarChainPipeline.run(spark, config(outB, 3))
    csvLines(outB) shouldBe csvLines(outA)
    Seq("components", "communities", "edges", "temporal").foreach { name =>
      withClue(name)(parquetRows(outB, name) shouldBe parquetRows(outA, name))
    }
  }

  test("an existing output directory is never overwritten") {
    statsA
    an[Exception] should be thrownBy ScholarChainPipeline.run(spark, config(outA, 1))
  }

  // Declared last: removes this context's checkpoint subdirectory.
  test("checkpoint cleanup deletes only the run's own checkpoint subdirectory") {
    statsA
    val runDir = Path.of(new java.net.URI(sc.getCheckpointDir.get))
    Files.exists(runDir) shouldBe true
    ScholarChainPipeline.cleanupCheckpoints(
      spark,
      config(outA, 1, "--checkpoint-dir", ckptRoot.toUri.toString)
    )
    Files.exists(runDir) shouldBe false
    Files.exists(ckptRoot) shouldBe true
  }
