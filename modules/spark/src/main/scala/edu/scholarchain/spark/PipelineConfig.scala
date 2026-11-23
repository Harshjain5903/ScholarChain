package edu.scholarchain.spark

import edu.scholarchain.core.IdGen

/**
 * Configuration for the ScholarChain Spark analytics pipeline.
 *
 * Input and output locations have no defaults: they must be supplied explicitly (for example
 * `file:///...` for a local run or `s3://<bucket>/...` on EMR). No bucket name is compiled into the
 * pipeline.
 *
 * @param hadoopOutputPath
 *   root of the Hadoop export; the pipeline reads `<root>/pair_candidates` and `<root>/teams`
 * @param matchDecisionsPath
 *   entity-resolution MatchDecision JSON lines
 * @param outputPath
 *   root under which every pipeline output is written
 * @param numPartitions
 *   target partition count for loaded inputs, shuffles and the GraphX edge partitioning
 * @param checkpointDir
 *   reliable checkpoint directory for iterative algorithms; when absent,
 *   `<outputPath>/_checkpoints` is used (see [[effectiveCheckpointDir]])
 * @param pregelCheckpointInterval
 *   GraphX Pregel checkpoint interval in supersteps (`spark.graphx.pregel.checkpointInterval`);
 *   values <= 0 disable Pregel checkpointing
 * @param keepCheckpoints
 *   when false the run's checkpoint subdirectory is deleted after a successful run
 * @param sparkMaster
 *   explicit master; when absent the master supplied by `spark-submit` is used, falling back to
 *   `local[*]` only when no master is configured at all
 * @param runTimestamp
 *   provenance timestamp; when absent the wall-clock start time is used. Fixing it makes reruns
 *   byte-comparable.
 * @param pathPairs
 *   explicit (source, target) researcher pairs for shortest paths
 * @param pathSampleCount
 *   number of deterministic pairs selected automatically when `pathPairs` is empty
 * @param pathPairsFile
 *   optional file of tab-separated `source<TAB>target` lines, appended to `pathPairs`
 * @param writeNeptuneCsv
 *   emit Neptune openCypher bulk-load CSV in addition to Parquet
 */
case class PipelineConfig(
  hadoopOutputPath: String,
  matchDecisionsPath: String,
  outputPath: String,
  windowStartYear: Int,
  windowEndYear: Int,
  runId: String,
  numPartitions: Int,
  betweennessSampleCount: Int,
  schemaVersion: String,
  policyVersion: String,
  maxBetweennessGraphSize: Long,
  checkpointDir: Option[String] = None,
  pregelCheckpointInterval: Int = PipelineConfig.Defaults.pregelCheckpointInterval,
  keepCheckpoints: Boolean = false,
  sparkMaster: Option[String] = None,
  runTimestamp: Option[String] = None,
  pathPairs: Seq[(String, String)] = Seq.empty,
  pathPairsFile: Option[String] = None,
  pathSampleCount: Int = PipelineConfig.Defaults.pathSampleCount,
  maxPathHops: Int = PipelineConfig.Defaults.maxPathHops,
  temporalWindowSize: Int = PipelineConfig.Defaults.temporalWindowSize,
  temporalStep: Int = PipelineConfig.Defaults.temporalStep,
  writeNeptuneCsv: Boolean = true
):
  require(hadoopOutputPath.nonEmpty, "hadoop-output must not be empty")
  require(matchDecisionsPath.nonEmpty, "match-decisions must not be empty")
  require(outputPath.nonEmpty, "output must not be empty")

  require(
    windowStartYear <= windowEndYear,
    s"window-start ($windowStartYear) must be <= window-end ($windowEndYear)"
  )
  require(numPartitions > 0, s"partitions must be > 0, got $numPartitions")
  require(betweennessSampleCount >= 0, "betweenness-samples must be >= 0")
  require(pathSampleCount >= 0, "path-sample-count must be >= 0")
  require(maxPathHops > 0, "max-path-hops must be > 0")
  require(temporalWindowSize > 0, "temporal-window-size must be > 0")
  require(temporalStep > 0, "temporal-step must be > 0")

  private def root(p: String): String = p.stripSuffix("/")

  /** `<hadoop-output>/pair_candidates`. */
  def pairCandidatesPath: String = s"${root(hadoopOutputPath)}/pair_candidates"

  /** `<hadoop-output>/teams` (PublicationTeam JSON lines). */
  def teamsPath: String = s"${root(hadoopOutputPath)}/teams"

  /** The checkpoint directory actually used: the explicit one or `<output>/_checkpoints`. */
  def effectiveCheckpointDir: String =
    checkpointDir.getOrElse(s"${root(outputPath)}/_checkpoints")

object PipelineConfig:

  /** Non-location defaults. Locations deliberately have none. */
  object Defaults:
    val windowStartYear: Int          = 1900
    val windowEndYear: Int            = 2100
    val numPartitions: Int            = 200
    val betweennessSampleCount: Int   = 50
    val schemaVersion: String         = "1.0.0"
    val policyVersion: String         = "v1"
    val maxBetweennessGraphSize: Long = 100000L
    val pregelCheckpointInterval: Int = 10
    val pathSampleCount: Int          = 5
    val maxPathHops: Int              = 10
    val temporalWindowSize: Int       = 5
    val temporalStep: Int             = 5

  /** Every accepted `--flag`; each takes exactly one value. */
  val KnownFlags: Set[String] = Set(
    "hadoop-output",
    "match-decisions",
    "output",
    "window-start",
    "window-end",
    "run-id",
    "partitions",
    "betweenness-samples",
    "schema-version",
    "policy-version",
    "max-betweenness-graph-size",
    "checkpoint-dir",
    "pregel-checkpoint-interval",
    "keep-checkpoints",
    "master",
    "run-timestamp",
    "path-pairs",
    "path-pairs-file",
    "path-sample-count",
    "max-path-hops",
    "temporal-window-size",
    "temporal-step",
    "neptune-csv"
  )

  val RequiredFlags: Vector[String] = Vector("hadoop-output", "match-decisions", "output")

  val Usage: String =
    "Usage: ScholarChainPipeline --hadoop-output <uri> --match-decisions <uri> --output <uri> " +
      "[--window-start Y] [--window-end Y] [--run-id ID] [--partitions N] " +
      "[--checkpoint-dir <uri>] [--pregel-checkpoint-interval N] [--keep-checkpoints true|false] " +
      "[--master M] [--run-timestamp ISO8601] [--betweenness-samples K] " +
      "[--max-betweenness-graph-size N] [--path-pairs src|tgt,src|tgt] [--path-pairs-file <uri>] " +
      "[--path-sample-count N] [--max-path-hops N] [--temporal-window-size N] [--temporal-step N] " +
      "[--neptune-csv true|false] [--schema-version V] [--policy-version V]"

  /**
   * Parse `--flag value` arguments strictly: an unknown flag, a flag without a value, a repeated
   * flag, a missing required location or an unparsable number is an error (Left), never silently
   * ignored or replaced by a default.
   */
  def parse(args: Seq[String]): Either[String, PipelineConfig] =
    def loop(rest: List[String], acc: Map[String, String]): Either[String, Map[String, String]] =
      rest match
        case Nil => Right(acc)
        case flag :: tail if !flag.startsWith("--") =>
          Left(s"unexpected argument '$flag'")
        case flag :: tail =>
          val key = flag.stripPrefix("--")
          if !KnownFlags.contains(key) then Left(s"unknown flag '--$key'")
          else if acc.contains(key) then Left(s"flag '--$key' given more than once")
          else
            tail match
              case value :: more if !value.startsWith("--") => loop(more, acc.updated(key, value))
              case _                                        => Left(s"flag '--$key' needs a value")

    def int(m: Map[String, String], k: String, d: Int): Either[String, Int] =
      m.get(k).fold(Right(d))(v => v.toIntOption.toRight(s"--$k expects an integer, got '$v'"))
    def long(m: Map[String, String], k: String, d: Long): Either[String, Long] =
      m.get(k).fold(Right(d))(v => v.toLongOption.toRight(s"--$k expects an integer, got '$v'"))
    def bool(m: Map[String, String], k: String, d: Boolean): Either[String, Boolean] =
      m.get(k).fold(Right(d))(v => v.toBooleanOption.toRight(s"--$k expects true|false, got '$v'"))

    for
      m     <- loop(args.toList, Map.empty)
      _     <- RequiredFlags.find(k => !m.contains(k)).map(k => s"missing required --$k").toLeft(())
      start <- int(m, "window-start", Defaults.windowStartYear)
      end   <- int(m, "window-end", Defaults.windowEndYear)
      parts <- int(m, "partitions", Defaults.numPartitions)
      samples   <- int(m, "betweenness-samples", Defaults.betweennessSampleCount)
      maxBetw   <- long(m, "max-betweenness-graph-size", Defaults.maxBetweennessGraphSize)
      ckptEvery <- int(m, "pregel-checkpoint-interval", Defaults.pregelCheckpointInterval)
      keepCkpt  <- bool(m, "keep-checkpoints", false)
      pathCount <- int(m, "path-sample-count", Defaults.pathSampleCount)
      maxHops   <- int(m, "max-path-hops", Defaults.maxPathHops)
      tSize     <- int(m, "temporal-window-size", Defaults.temporalWindowSize)
      tStep     <- int(m, "temporal-step", Defaults.temporalStep)
      neptune   <- bool(m, "neptune-csv", true)
      pairs     <- m.get("path-pairs").fold(Right(Seq.empty))(parsePathPairs)
      config <-
        try
          Right(
            PipelineConfig(
              hadoopOutputPath = m("hadoop-output"),
              matchDecisionsPath = m("match-decisions"),
              outputPath = m("output"),
              windowStartYear = start,
              windowEndYear = end,
              runId = m.getOrElse("run-id", IdGen.runId()),
              numPartitions = parts,
              betweennessSampleCount = samples,
              schemaVersion = m.getOrElse("schema-version", Defaults.schemaVersion),
              policyVersion = m.getOrElse("policy-version", Defaults.policyVersion),
              maxBetweennessGraphSize = maxBetw,
              checkpointDir = m.get("checkpoint-dir"),
              pregelCheckpointInterval = ckptEvery,
              keepCheckpoints = keepCkpt,
              sparkMaster = m.get("master"),
              runTimestamp = m.get("run-timestamp"),
              pathPairs = pairs,
              pathPairsFile = m.get("path-pairs-file"),
              pathSampleCount = pathCount,
              maxPathHops = maxHops,
              temporalWindowSize = tSize,
              temporalStep = tStep,
              writeNeptuneCsv = neptune
            )
          )
        catch
          case e: IllegalArgumentException => Left(e.getMessage.stripPrefix("requirement failed: "))
    yield config

  /** Parse `src|tgt,src|tgt`; both sides must be non-empty. */
  def parsePathPairs(spec: String): Either[String, Seq[(String, String)]] =
    val items = spec.split(',').toSeq.map(_.trim).filter(_.nonEmpty)
    val parsed = items.map { item =>
      item.split('|') match
        case Array(s, t) if s.trim.nonEmpty && t.trim.nonEmpty => Right((s.trim, t.trim))
        case _ => Left(s"--path-pairs entry '$item' must be 'source|target'")
    }
    parsed.collectFirst { case Left(e) => e }.toLeft(parsed.collect { case Right(p) => p })
