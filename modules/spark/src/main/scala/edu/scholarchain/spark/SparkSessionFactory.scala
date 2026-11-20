package edu.scholarchain.spark

import org.apache.spark.sql.SparkSession

/** Factory to create a configured SparkSession. */
object SparkSessionFactory:

  /**
   * Create a SparkSession with the given app name and master. Additional configuration pairs can be
   * supplied to tune the session.
   */
  def create(
    appName: String,
    master: String = "local[*]",
    extraConfigs: Map[String, String] = Map.empty
  ): SparkSession =
    val builder = SparkSession
      .builder()
      .appName(appName)
      .master(master)
      .config("spark.ui.enabled", "false")
      .config("spark.driver.host", "localhost")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")

    val withExtras = extraConfigs.foldLeft(builder) { case (b, (k, v)) =>
      b.config(k, v)
    }
    withExtras.getOrCreate()
