package edu.scholarchain.spark

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

/** Factory to create a configured SparkSession. */
object SparkSessionFactory:

  /** Fallback master used only when neither the caller nor `spark-submit` configured one. */
  val LocalFallbackMaster: String = "local[*]"

  /** GraphX Pregel checkpoint interval key (read by Pregel via `PeriodicGraphCheckpointer`). */
  val PregelCheckpointIntervalKey: String = "spark.graphx.pregel.checkpointInterval"

  /**
   * Create a SparkSession with the given app name and master. Additional configuration pairs can be
   * supplied to tune the session. Intended for tests and local tools; production entry points use
   * [[forPipeline]], which does not override the cluster master.
   */
  def create(
    appName: String,
    master: String = LocalFallbackMaster,
    extraConfigs: Map[String, String] = Map.empty
  ): SparkSession =
    build(appName, Some(master), baseConfigs(master) ++ extraConfigs)

  /**
   * The master the pipeline should use: an explicit `--master` wins; otherwise the master already
   * present in the Spark configuration (set by `spark-submit` on EMR/YARN) is kept by returning
   * None; `local[*]` is used only when no master is configured anywhere.
   */
  def resolveMaster(explicit: Option[String], submittedMaster: Option[String]): Option[String] =
    explicit.orElse(if submittedMaster.isDefined then None else Some(LocalFallbackMaster))

  /** True for `local`, `local[N]`, `local[*]`, `local[N,F]` and `local-cluster[...]` masters. */
  def isLocalMaster(master: String): Boolean = master.startsWith("local")

  private def baseConfigs(effectiveMaster: String): Map[String, String] =
    val common = Map("spark.serializer" -> "org.apache.spark.serializer.KryoSerializer")
    // Binding the driver to localhost is only valid when executors run in the same JVM; the UI is
    // disabled for local runs only (cluster runs keep it for the history server).
    if isLocalMaster(effectiveMaster) then
      common ++ Map("spark.driver.host" -> "localhost", "spark.ui.enabled" -> "false")
    else common

  /**
   * Session settings for a pipeline run: partitioning, the Pregel checkpoint interval and the
   * local-only settings when the effective master is local.
   */
  def pipelineConfigs(config: PipelineConfig, effectiveMaster: String): Map[String, String] =
    val parts = config.numPartitions.toString
    val ckpt  = if config.pregelCheckpointInterval > 0 then config.pregelCheckpointInterval else -1
    baseConfigs(effectiveMaster) ++ Map(
      "spark.default.parallelism"    -> parts,
      "spark.sql.shuffle.partitions" -> parts,
      PregelCheckpointIntervalKey    -> ckpt.toString
    )

  /**
   * Create the session for a pipeline run and configure its reliable checkpoint directory
   * ([[PipelineConfig.effectiveCheckpointDir]]) so that GraphX Pregel and the iterative jobs
   * truncate lineage with reliable (not executor-local) checkpoints.
   */
  def forPipeline(appName: String, config: PipelineConfig): SparkSession =
    val submitted = new SparkConf().getOption("spark.master")
    val master    = resolveMaster(config.sparkMaster, submitted)
    val effective = master.orElse(submitted).getOrElse(LocalFallbackMaster)
    val spark     = build(appName, master, pipelineConfigs(config, effective))
    spark.sparkContext.setCheckpointDir(config.effectiveCheckpointDir)
    spark

  private def build(
    appName: String,
    master: Option[String],
    configs: Map[String, String]
  ): SparkSession =
    val builder    = SparkSession.builder().appName(appName)
    val withMaster = master.fold(builder)(m => builder.master(m))
    configs.foldLeft(withMaster) { case (b, (k, v)) => b.config(k, v) }.getOrCreate()
