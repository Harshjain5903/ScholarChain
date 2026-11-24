package edu.scholarchain.spark

import edu.scholarchain.core.*
import java.nio.file.Files

class GraphExportWriterSpec extends SparkTestBase:

  test("writeEdges produces Parquet output") {
    val edgesRdd  = sc.parallelize(TestFixtures.allEdges)
    val outputDir = Files.createTempDirectory("spark-export-edges").toString
    GraphExportWriter.writeEdges(spark, edgesRdd, outputDir)
    val df = spark.read.parquet(s"$outputDir/edges")
    df.count() shouldBe TestFixtures.allEdges.length
  }

  test("writeMetrics produces Parquet output with all fields") {
    val authorships = sc.parallelize(TestFixtures.authorships)
    val edgesRdd    = sc.parallelize(TestFixtures.allEdges)
    val metrics = CollaborationMetricsJob.compute(
      sc,
      edgesRdd,
      authorships,
      2000,
      2030,
      TestFixtures.lineage()
    )
    val outputDir = Files.createTempDirectory("spark-export-metrics").toString
    GraphExportWriter.writeMetrics(spark, metrics, outputDir)
    val df = spark.read.parquet(s"$outputDir/metrics")
    df.columns should contain("researcherId")
    df.columns should contain("weightedDegree")
    df.columns should contain("bridgeScore")
    df.count() should be > 0L
  }

  test("writeManifest produces JSON manifest") {
    val outputDir = Files.createTempDirectory("spark-export-manifest").toString
    GraphExportWriter.writeManifest(spark, outputDir, "run-1", "2024-01-01T00:00:00Z")
    val manifestRdd = sc.textFile(s"$outputDir/manifest")
    val json        = ujson.read(manifestRdd.collect().mkString)
    json("runId").str shouldBe "run-1"
    json("outputs")("edges").str should include("edges")
    json("outputs")("temporal").str should include("temporal")
    json("targets")("s3")("edgesParquet").str should include("edges")
    // Without the openCypher CSV export the manifest must not advertise a Neptune load source.
    json("targets")("neptune")("format").str shouldBe "none"
  }
