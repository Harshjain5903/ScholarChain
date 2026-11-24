package edu.scholarchain.hadoop.config

import java.time.Instant
import java.time.format.DateTimeParseException
import org.apache.hadoop.conf.Configuration

/**
 * Job configuration loader that reads ScholarChain-specific settings from the Hadoop Configuration
 * object. All settings use the "scholarchain." prefix to avoid collisions with Hadoop's own
 * configuration keys.
 */
object JobConfig:

  val PipelineRunIdKey = "scholarchain.pipeline.run-id"
  val SnapshotDateKey  = "scholarchain.pipeline.snapshot-date"
  val SchemaVersionKey = "scholarchain.pipeline.schema-version"
  val OutputBaseKey    = "scholarchain.output.base-path"
  val MaxBlockSizeKey  = "scholarchain.blocking.max-block-size"

  val LargeTeamThresholdKey =
    "scholarchain.team.large-team-threshold"
  val CompressOutputKey = "scholarchain.output.compress"

  /**
   * Optional fixed run timestamp (ISO-8601 instant, for example `2026-11-23T00:00:00Z`). When set,
   * every record emitted by every job of the run carries this value as `provenance.timestamp`, so
   * rerunning the same chain over the same input is byte-reproducible. Pass it like any other job
   * option: `HadoopJobDriver <job> -D scholarchain.run.timestamp=2026-11-23T00:00:00Z <in> <out>`.
   * When absent, each record is stamped with the wall-clock time at which it was produced (the
   * original, nondeterministic behavior).
   */
  val RunTimestampKey = "scholarchain.run.timestamp"

  val DefaultSchemaVersion      = "1.0.0"
  val DefaultMaxBlockSize       = 1000
  val DefaultLargeTeamThreshold = 50
  val DefaultSnapshotDate       = "unknown"

  def pipelineRunId(conf: Configuration): String =
    Option(conf.get(PipelineRunIdKey)).getOrElse("run-local")

  def snapshotDate(conf: Configuration): String =
    Option(conf.get(SnapshotDateKey))
      .getOrElse(DefaultSnapshotDate)

  def schemaVersion(conf: Configuration): String =
    Option(conf.get(SchemaVersionKey))
      .getOrElse(DefaultSchemaVersion)

  /**
   * Canonical form of an ISO-8601 instant (`Instant.parse(raw).toString`). Throws
   * `DateTimeParseException` when `raw` is not an ISO-8601 instant.
   */
  def canonicalTimestamp(raw: String): String = Instant.parse(raw.trim).toString

  /** The configured fixed run timestamp, canonicalized; None when unset or blank. */
  def fixedRunTimestamp(conf: Configuration): Option[String] =
    Option(conf.get(RunTimestampKey)).map(_.trim).filter(_.nonEmpty).map(canonicalTimestamp)

  /**
   * The `provenance.timestamp` to stamp on an emitted record: the fixed run timestamp when
   * [[RunTimestampKey]] is set, otherwise the current wall-clock instant.
   */
  def runTimestamp(conf: Configuration): String =
    fixedRunTimestamp(conf).getOrElse(Instant.now.toString)

  /**
   * Validate run-level options before a job is submitted. Returns an error message for an invalid
   * option, None when all options are valid.
   */
  def validateRunOptions(conf: Configuration): Option[String] =
    try
      fixedRunTimestamp(conf)
      None
    catch
      case e: DateTimeParseException =>
        Some(
          s"Invalid $RunTimestampKey '${conf.get(RunTimestampKey)}': expected an ISO-8601 " +
            s"instant such as 2026-11-23T00:00:00Z (${e.getMessage})"
        )

  def outputBase(conf: Configuration): String =
    Option(conf.get(OutputBaseKey))
      .getOrElse("/scholarchain/output")

  def maxBlockSize(conf: Configuration): Int =
    conf.getInt(MaxBlockSizeKey, DefaultMaxBlockSize)

  def largeTeamThreshold(conf: Configuration): Int =
    conf.getInt(
      LargeTeamThresholdKey,
      DefaultLargeTeamThreshold
    )

  /**
   * Build the idempotent output path for a job:
   * output_base/run_id=<run_id>/snapshot_date=<date>/job_name/
   */
  def outputPath(
    conf: Configuration,
    jobName: String
  ): String =
    val base = outputBase(conf)
    val run  = pipelineRunId(conf)
    val snap = snapshotDate(conf)
    s"$base/run_id=$run/snapshot_date=$snap/$jobName"
