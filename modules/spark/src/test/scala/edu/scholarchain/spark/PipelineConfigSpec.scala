package edu.scholarchain.spark

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Feature tests for strict pipeline argument parsing and distributed-session settings. */
class PipelineConfigSpec extends AnyFunSuite with Matchers:

  private val required = Seq(
    "--hadoop-output",
    "file:///in/export",
    "--match-decisions",
    "file:///in/md",
    "--output",
    "file:///out/spark/"
  )

  private def parsed(extra: String*): PipelineConfig =
    PipelineConfig.parse(required ++ extra) match
      case Right(c)  => c
      case Left(err) => fail(s"expected a config, got error: $err")

  private def error(args: String*): String =
    PipelineConfig.parse(args) match
      case Left(err) => err
      case Right(c)  => fail(s"expected an error, got $c")

  test("input and output locations are required; no bucket is compiled in") {
    error("--hadoop-output", "a", "--match-decisions", "b") shouldBe "missing required --output"
    error("--output", "o", "--match-decisions", "b") shouldBe "missing required --hadoop-output"
    val c = parsed()
    Seq(c.hadoopOutputPath, c.matchDecisionsPath, c.outputPath, c.effectiveCheckpointDir)
      .foreach(_ should not include "s3://scholarchain")
  }

  test("unknown, repeated, valueless and positional arguments are rejected, not ignored") {
    error((required :+ "--partitons" :+ "4")*) shouldBe "unknown flag '--partitons'"
    error((required ++ Seq("--output", "x"))*) shouldBe "flag '--output' given more than once"
    error((required :+ "--run-id")*) shouldBe "flag '--run-id' needs a value"
    error((required ++ Seq("--run-id", "--partitions", "3"))*) shouldBe
      "flag '--run-id' needs a value"
    error(("stray" +: required)*) shouldBe "unexpected argument 'stray'"
  }

  test("numeric and boolean flags are validated") {
    error((required ++ Seq("--partitions", "four"))*) should include("--partitions expects")
    error((required ++ Seq("--partitions", "0"))*) should include("partitions must be > 0")
    error((required ++ Seq("--window-start", "2030", "--window-end", "2020"))*) should include(
      "window-start (2030) must be <= window-end (2020)"
    )
    error((required ++ Seq("--neptune-csv", "yes"))*) should include("--neptune-csv expects")
  }

  test("all pipeline flags are parsed") {
    val c = parsed(
      "--window-start",
      "1980",
      "--window-end",
      "2026",
      "--run-id",
      "rs-1",
      "--partitions",
      "8",
      "--checkpoint-dir",
      "s3://some-bucket/ckpt",
      "--pregel-checkpoint-interval",
      "4",
      "--keep-checkpoints",
      "true",
      "--master",
      "local[2]",
      "--run-timestamp",
      "2026-11-23T00:00:00Z",
      "--path-pairs",
      "a|b, c|d",
      "--path-sample-count",
      "3",
      "--max-path-hops",
      "6",
      "--temporal-window-size",
      "10",
      "--temporal-step",
      "2",
      "--neptune-csv",
      "false",
      "--betweenness-samples",
      "2000"
    )
    c.windowStartYear shouldBe 1980
    c.windowEndYear shouldBe 2026
    c.runId shouldBe "rs-1"
    c.numPartitions shouldBe 8
    c.checkpointDir shouldBe Some("s3://some-bucket/ckpt")
    c.effectiveCheckpointDir shouldBe "s3://some-bucket/ckpt"
    c.pregelCheckpointInterval shouldBe 4
    c.keepCheckpoints shouldBe true
    c.sparkMaster shouldBe Some("local[2]")
    c.runTimestamp shouldBe Some("2026-11-23T00:00:00Z")
    c.pathPairs shouldBe Seq("a" -> "b", "c" -> "d")
    c.pathSampleCount shouldBe 3
    c.maxPathHops shouldBe 6
    c.temporalWindowSize shouldBe 10
    c.temporalStep shouldBe 2
    c.writeNeptuneCsv shouldBe false
    c.betweennessSampleCount shouldBe 2000
  }

  test("derived input paths and the default checkpoint directory follow the given roots") {
    val c = parsed()
    c.pairCandidatesPath shouldBe "file:///in/export/pair_candidates"
    c.teamsPath shouldBe "file:///in/export/teams"
    c.checkpointDir shouldBe None
    c.effectiveCheckpointDir shouldBe "file:///out/spark/_checkpoints"
    c.sparkMaster shouldBe None
    c.writeNeptuneCsv shouldBe true
  }

  test("malformed path pairs are rejected") {
    PipelineConfig.parsePathPairs("a|b,c") shouldBe Left(
      "--path-pairs entry 'c' must be 'source|target'"
    )
    PipelineConfig.parsePathPairs("a|") should matchPattern { case Left(_) => }
  }

  test("an explicit master wins; a spark-submit master is not overridden; local is a fallback") {
    SparkSessionFactory.resolveMaster(Some("local[2]"), Some("yarn")) shouldBe Some("local[2]")
    SparkSessionFactory.resolveMaster(None, Some("yarn")) shouldBe None
    SparkSessionFactory.resolveMaster(None, None) shouldBe Some("local[*]")
  }

  test("cluster sessions do not bind the driver to localhost; local sessions do") {
    val c       = parsed("--partitions", "16", "--pregel-checkpoint-interval", "5")
    val cluster = SparkSessionFactory.pipelineConfigs(c, "yarn")
    cluster.get("spark.driver.host") shouldBe None
    cluster.get("spark.ui.enabled") shouldBe None
    cluster("spark.default.parallelism") shouldBe "16"
    cluster("spark.sql.shuffle.partitions") shouldBe "16"
    cluster(SparkSessionFactory.PregelCheckpointIntervalKey) shouldBe "5"

    val local = SparkSessionFactory.pipelineConfigs(c, "local[*]")
    local("spark.driver.host") shouldBe "localhost"
    local("spark.ui.enabled") shouldBe "false"
  }

  test("a non-positive Pregel checkpoint interval disables Pregel checkpointing") {
    val c = parsed("--pregel-checkpoint-interval", "0")
    SparkSessionFactory.pipelineConfigs(c, "yarn")(
      SparkSessionFactory.PregelCheckpointIntervalKey
    ) shouldBe "-1"
  }

  test("ER policy is not defaulted: absent unless --policy-version is given") {
    parsed().policyVersion shouldBe None
    parsed("--policy-version", "er-mention-v1-uncalibrated").policyVersion shouldBe
      Some("er-mention-v1-uncalibrated")
  }

  test("betweenness seed, k-core cap/interval, LPA limit and manifest flags are parsed") {
    val d = parsed()
    d.betweennessSeed shouldBe 42L
    d.kcoreMaxIterations shouldBe KCoreJob.DefaultMaxIterations
    d.kcoreCheckpointInterval shouldBe KCoreJob.DefaultCheckpointInterval
    d.lpaMaxIterations shouldBe CommunityDetectionJob.DefaultMaxIterations
    d.allowUnlinkedErInput shouldBe false
    d.inputChecksums shouldBe true
    d.gitSha shouldBe None
    d.gitDirty shouldBe None
    val c = parsed(
      "--betweenness-seed",
      "7",
      "--kcore-max-iterations",
      "50",
      "--kcore-checkpoint-interval",
      "5",
      "--lpa-max-iterations",
      "30",
      "--allow-unlinked-er-input",
      "true",
      "--input-checksums",
      "false",
      "--git-sha",
      "abc123",
      "--git-dirty",
      "true"
    )
    c.betweennessSeed shouldBe 7L
    c.kcoreMaxIterations shouldBe 50
    c.kcoreCheckpointInterval shouldBe 5
    c.lpaMaxIterations shouldBe 30
    c.allowUnlinkedErInput shouldBe true
    c.inputChecksums shouldBe false
    c.gitSha shouldBe Some("abc123")
    c.gitDirty shouldBe Some(true)
    error((required ++ Seq("--kcore-max-iterations", "0"))*) should include(
      "kcore-max-iterations must be > 0"
    )
    error((required ++ Seq("--kcore-checkpoint-interval", "0"))*) should include(
      "kcore-checkpoint-interval must be > 0"
    )
    error((required ++ Seq("--git-dirty", "maybe"))*) should include("--git-dirty expects")
  }
