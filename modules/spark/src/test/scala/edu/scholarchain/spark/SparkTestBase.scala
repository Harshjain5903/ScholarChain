package edu.scholarchain.spark

import org.apache.spark.sql.SparkSession
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll

/** Shared SparkSession for all Spark tests. Each test suite gets a local-mode session. */
trait SparkTestBase extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  @transient lazy val spark: SparkSession =
    SparkSessionFactory.create(
      appName = getClass.getSimpleName,
      master = "local[*]",
      extraConfigs = Map(
        "spark.sql.shuffle.partitions" -> "2",
        "spark.default.parallelism"    -> "2"
      )
    )

  lazy val sc = spark.sparkContext

  override def afterAll(): Unit =
    spark.stop()
    super.afterAll()
