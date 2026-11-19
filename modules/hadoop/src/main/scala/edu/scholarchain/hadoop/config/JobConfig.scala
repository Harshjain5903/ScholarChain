package edu.scholarchain.hadoop.config

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
