package edu.scholarchain.spark

/** Configuration for the ScholarChain Spark analytics pipeline. */
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
  maxBetweennessGraphSize: Long
)

object PipelineConfig:

  /** Reasonable defaults for local or test execution. */
  val Default: PipelineConfig = PipelineConfig(
    hadoopOutputPath = "s3://scholarchain-processed/hadoop",
    matchDecisionsPath = "s3://scholarchain-processed/entity-resolution/match-decisions",
    outputPath = "s3://scholarchain-graph",
    windowStartYear = 1900,
    windowEndYear = 2100,
    runId = "default-run",
    numPartitions = 200,
    betweennessSampleCount = 50,
    schemaVersion = "1.0.0",
    policyVersion = "v1",
    maxBetweennessGraphSize = 100000L
  )
