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
 *
 * Lineage: Hadoop records come from run `hadoop-fixture-run` with source dataset `dblp`; the ER
 * decisions come from run `er-run` with policy `er-test` and name their Hadoop input run with an
 * `input_run:hadoop-fixture-run` reason code. A `work_records/` export supplies the title and venue
 * of W1 only.
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

  private def decision(
    a: String,
    b: String,
    outcome: MatchOutcome,
    policy: String = "er-test",
    inputRun: Option[String] = Some("hadoop-fixture-run")
  ) =
    MatchDecision(
      a,
      b,
      Map("name_similarity" -> 1.0),
      outcome,
      0.9,
      Vector("r") ++ inputRun.map(r => s"input_run:$r"),
      policy,
      "er-run"
    )

  private val defaultDecisions = Seq(
    decision("m:W1:0", "m:W2:0", MatchOutcome.Accepted),
    decision("m:W1:1", "m:W4:0", MatchOutcome.Rejected)
  )

  private lazy val inputRoot: Path = writeInputs(defaultDecisions)

  private def writeInputs(decisions: Seq[MatchDecision]): Path =
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
    val wr = Files.createDirectories(root.resolve("export/work_records"))
    val w1 = WorkRecord(
      "work:W1",
      SourceType.DBLP,
      "W1",
      "Graphs, \"Quoted\" and Commas",
      "graphs quoted and commas",
      2020,
      Some("3dv"),
      None,
      None,
      teams.head.authorMentions,
      None,
      prov("W1")
    )
    Files.writeString(wr.resolve("part-m-00002"), upickle.default.write(w1))
    val md = Files.createDirectories(root.resolve("match-decisions"))
    Files.writeString(
      md.resolve("part-00000"),
      decisions.map(d => upickle.default.write(d)).mkString("\n")
    )
    root

  private def config(out: Path, partitions: Int, extra: String*): PipelineConfig =
    configFor(inputRoot, out, partitions, extra*)

  private def configFor(root: Path, out: Path, partitions: Int, extra: String*): PipelineConfig =
    PipelineConfig
      .parse(argsFor(root, out, partitions, extra*))
      .fold(e => fail(e), identity)

  private def argsFor(root: Path, out: Path, partitions: Int, extra: String*): Seq[String] =
    Seq(
      "--hadoop-output",
      root.resolve("export").toUri.toString,
      "--match-decisions",
      root.resolve("match-decisions").toUri.toString,
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
      "100",
      "--git-sha",
      "0123456789abcdef0123456789abcdef01234567",
      "--git-dirty",
      "true"
    ) ++ extra

  private def freshOut(): Path =
    val dir = Files.createTempDirectory("pipeline-e2e-out")
    Files.delete(dir) // the pipeline must create its own output root
    dir

  private lazy val ckptRoot: Path = Files.createTempDirectory("pipeline-e2e-ckpt")
  private lazy val outA: Path     = freshOut()

  private lazy val argvA: Seq[String] = argsFor(inputRoot, outA, 1)

  private lazy val statsA: ujson.Obj =
    sc.setCheckpointDir(ckptRoot.toUri.toString)
    ScholarChainPipeline.run(spark, config(outA, 1), argvA)

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

  /** Rows of one Neptune CSV dataset as header -> value maps (empty fields read as null). */
  private def csvRows(out: Path, subdir: String): Seq[Map[String, String]] =
    val df = spark.read
      .option("header", "true")
      .option("quote", "\"")
      .option("escape", "\"")
      .csv(out.resolve(subdir).toString)
    df.collect().toSeq.map(r => df.columns.map(c => c -> r.getAs[String](c)).toMap)

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

  // ---- Lineage, ER policy and run manifest (review findings N44, N52, M_r, manifest).

  private def provOf(json: String): Provenance = upickle.default.read[Provenance](json)

  test("edge, metrics and AUTHORED provenance carry input lineage, not 'spark-analytics'") {
    statsA
    val edgeProv =
      provOf(spark.read.parquet(outA.resolve("edges").toString).first().getAs[String]("provenance"))
    edgeProv.sourceDataset shouldBe "dblp"
    edgeProv.transformationStep shouldBe Some(CanonicalGraphBuilder.EdgeStep)
    edgeProv.producer shouldBe Some("spark-analytics/CanonicalGraphBuilder")
    edgeProv.upstreamRunIds shouldBe Vector("er-run", "hadoop-fixture-run")
    edgeProv.policyVersion shouldBe Some("er-test")
    edgeProv.pipelineRunId shouldBe "e2e"

    val metricProv = provOf(
      spark.read.parquet(outA.resolve("metrics").toString).first().getAs[String]("provenance")
    )
    metricProv.sourceDataset shouldBe "dblp"
    metricProv.producer shouldBe Some("spark-analytics/CollaborationMetricsJob")
    metricProv.upstreamRunIds shouldBe Vector("er-run", "hadoop-fixture-run")
    metricProv.policyVersion shouldBe Some("er-test")

    val authored = csvRows(outA, NeptuneBulkLoadExport.Dir.AuthoredRelationships)
    authored.foreach { r =>
      val p = provOf(r(s"${GraphSchema.PropertyKey.Provenance}:String"))
      p.sourceDataset shouldBe "dblp"
      p.producer shouldBe Some("spark-analytics/NeptuneBulkLoadExport")
      p.policyVersion shouldBe Some("er-test")
    }
    val lineage = statsA("lineage")
    lineage("erPolicyVersion").str shouldBe "er-test"
    lineage("erLinkage").str shouldBe InputLineage.Linkage.Verified
    lineage("edgeSourceDataset").str shouldBe "dblp"
  }

  test("coauthor relationship IDs use the ER policy derived from the decisions") {
    statsA
    val ids = csvRows(outA, NeptuneBulkLoadExport.Dir.CoauthorRelationships).map(_(":ID")).toSet
    ids should contain(IdGen.coauthorEdgeId("m:W1:0", "m:W1:1", 2010, 2024, "er-test"))
    ids should not contain IdGen.coauthorEdgeId("m:W1:0", "m:W1:1", 2010, 2024, "v1")
    csvRows(outA, NeptuneBulkLoadExport.Dir.CoauthorRelationships).map(_(":TYPE")).toSet shouldBe
      Set(GraphSchema.EdgeType.Coauthor)
  }

  test("Neptune nodes carry displayName, title, year and venue only where the inputs have them") {
    statsA
    val Key = GraphSchema.PropertyKey
    val researchers =
      csvRows(outA, NeptuneBulkLoadExport.Dir.ResearcherNodes).map(r => r(":ID") -> r).toMap
    // Mention names in the fixture are the mention IDs themselves.
    researchers("m:W1:0")(s"${Key.DisplayName}:String") shouldBe "m:W1:0"
    val works = csvRows(outA, NeptuneBulkLoadExport.Dir.WorkNodes).map(r => r(":ID") -> r).toMap
    works("work:W1")(s"${Key.Title}:String") shouldBe "Graphs, \"Quoted\" and Commas"
    works("work:W1")(s"${Key.Venue}:String") shouldBe "3dv"
    works("work:W1")(s"${Key.Year}:Int") shouldBe "2020"
    works("work:W2")(s"${Key.Title}:String") shouldBe null
    works("work:W2")(s"${Key.Venue}:String") shouldBe null
    works("work:W2")(s"${Key.Year}:Int") shouldBe "2021"
  }

  test("metrics export maximumTeamWorkIds and bridgeScoreMethod") {
    statsA
    val rows = spark.read
      .parquet(outA.resolve("metrics").toString)
      .collect()
      .map(r => r.getAs[String]("researcherId") -> r)
      .toMap
    // Merged m:W1:0: W1 (2 coauthors) and W2 (1 coauthor) -> M_r = 2 on W1.
    rows("m:W1:0").getAs[Int]("maximumCoauthorsOnOnePaper") shouldBe 2
    rows("m:W1:0").getSeq[String](rows("m:W1:0").fieldIndex("maximumTeamWorkIds")) shouldBe
      Seq("work:W1")
    rows("m:W3:0").getSeq[String](rows("m:W3:0").fieldIndex("maximumTeamWorkIds")) shouldBe empty
    rows.values.map(_.getAs[String]("bridgeScoreMethod")).toSet shouldBe Set(
      BridgeScoreMethod.Exact
    )
    statsA("bridges")("method").str shouldBe BridgeScoreMethod.Exact
  }

  test("the manifest records code, argv, inputs, lineage, algorithms and output counts") {
    statsA
    val m   = ujson.read(sc.textFile(outA.resolve("manifest").toUri.toString).collect().mkString)
    val run = m("run")
    run("code")("gitSha").str shouldBe "0123456789abcdef0123456789abcdef01234567"
    run("code")("gitDirty").bool shouldBe true
    run("code")("source").str shouldBe "flag"
    run("jar")("sha256") shouldBe ujson.Null
    run("argv").arr.map(_.str).toSeq shouldBe argvA
    run("config")("policyVersion").str shouldBe "er-test"
    run("config")("betweennessSeed").num shouldBe 42.0
    run("erPolicyVersion").str shouldBe "er-test"
    run("upstreamRunIds").arr.map(_.str).toSeq shouldBe Seq("er-run", "hadoop-fixture-run")
    val files = run("inputs")("files").arr.map(f => f("path").str -> f("sha256").str).toMap
    files.keys.map(_.split('/').takeRight(2).mkString("/")).toSet shouldBe Set(
      "pair_candidates/part-m-00001.gz",
      "teams/part-m-00000",
      "work_records/part-m-00002",
      "match-decisions/part-00000"
    )
    val teamsFile = inputRoot.resolve("export/teams/part-m-00000")
    val expected  = RunManifest.sha256(Files.newInputStream(teamsFile))
    files.collectFirst { case (p, h) if p.endsWith("teams/part-m-00000") => h } shouldBe Some(
      expected
    )
    run("betweenness")("method").str shouldBe BridgeScoreMethod.Exact
    run("betweenness")("betweennessSeed").num shouldBe 42.0
    run("kcore")("converged").bool shouldBe true
    run("kcore")("iterations").num should be > 0.0
    run("labelPropagation")("converged").bool shouldBe true
    run("outputCounts")("edges").num shouldBe 5.0
    run("outputCounts")("metrics").num shouldBe 7.0
    run("outputCounts")("neptune")("workNodes").num shouldBe 4.0
    statsA("kcore")("converged").bool shouldBe true
    statsA("communities")("converged").bool shouldBe true
  }

  private def failsFast(decisions: Seq[MatchDecision], extra: String*): String =
    val out = freshOut()
    val ex = intercept[IllegalArgumentException](
      ScholarChainPipeline.run(spark, configFor(writeInputs(decisions), out, 1, extra*))
    )
    Files.exists(out) shouldBe false // validated before any output is written
    ex.getMessage

  test("a --policy-version that disagrees with the decisions fails fast") {
    failsFast(defaultDecisions, "--policy-version", "v1") should include(
      "--policy-version 'v1' disagrees"
    )
  }

  test("decisions from two ER policies are refused") {
    failsFast(
      defaultDecisions :+ decision("m:W2:1", "m:W4:1", MatchOutcome.Rejected, policy = "er-other")
    ) should include("mixes 2 ER policy versions")
  }

  test("decisions produced from another Hadoop run fail fast") {
    failsFast(
      defaultDecisions.map(d => d.copy(reasonCodes = Vector("input_run:some-other-run")))
    ) should include("Hadoop run(s) some-other-run")
  }

  test("decisions that do not name their Hadoop run fail unless explicitly allowed") {
    val unlinked = defaultDecisions.map(d => d.copy(reasonCodes = Vector("r")))
    failsFast(unlinked) should include("--allow-unlinked-er-input true")
    val out = freshOut()
    val s = ScholarChainPipeline.run(
      spark,
      configFor(
        writeInputs(unlinked),
        out,
        2,
        "--allow-unlinked-er-input",
        "true",
        "--neptune-csv",
        "false"
      )
    )
    s("lineage")("erLinkage").str shouldBe InputLineage.Linkage.UnverifiedAllowed
  }

  test("a run above the betweenness size cap reports not-computed, not measured zeros") {
    val out = freshOut()
    val s = ScholarChainPipeline.run(
      spark,
      config(out, 2, "--max-betweenness-graph-size", "3", "--neptune-csv", "false")
    )
    s("bridges")("method").str shouldBe BridgeScoreMethod.NotComputed
    s("bridges")("betweennessComputed").bool shouldBe false
    s("bridges")("topBetweenness").arr shouldBe empty
    val summaries = spark.read
      .parquet(out.resolve("summaries").toString)
      .filter("entityType = 'community-summary'")
      .collect()
      .map(_.getAs[String]("summary"))
    summaries should not be empty
    summaries.foreach(_ should include("betweenness was not computed"))
    spark.read
      .parquet(out.resolve("metrics").toString)
      .collect()
      .map(_.getAs[String]("bridgeScoreMethod"))
      .toSet shouldBe Set(BridgeScoreMethod.NotComputed)
  }

  test("k-core hitting its iteration cap fails the run instead of publishing coreness") {
    val out = freshOut()
    val ex = intercept[IllegalStateException](
      ScholarChainPipeline.run(spark, config(out, 1, "--kcore-max-iterations", "1"))
    )
    ex.getMessage should include("did not converge within 1 iterations")
    Files.exists(out.resolve("metrics")) shouldBe false
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
