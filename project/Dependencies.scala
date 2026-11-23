import sbt._

/**
 * Single version ledger for every external dependency used in ScholarChain.
 *
 * Rules:
 *   - Every version is pinned (no "latest", no ranges).
 *   - Hadoop and Spark deps are Provided (the runtime supplies them).
 *   - Test-only libraries live in Test scope.
 *   - Logging implementation (logback) is Runtime scope.
 *   - JSON library: uPickle everywhere.
 *   - Test framework: ScalaTest everywhere.
 *   - Scala 2.13 libraries consumed from Scala 3 use CrossVersion.for3Use2_13.
 */
object Dependencies {

  // ---------------------------------------------------------------------------
  // Version constants
  // ---------------------------------------------------------------------------
  object V {
    val scala3 = "3.3.4"

    // JSON
    val upickle = "4.0.2"

    // Config
    val typesafeConfig = "1.4.3"

    // Logging
    val slf4j   = "2.0.16"
    val logback = "1.5.12"

    // Hadoop & Spark (provided at runtime)
    val hadoop = "3.3.6"
    val spark  = "3.5.3"

    // AWS SDK
    val awsSdk2 = "2.29.26"

    // HTTP (services module) — cask is simple and Scala-3-native
    val cask = "0.9.4"

    // Testing
    val scalatest     = "3.2.19"
    val scalatestPlus = "3.2.19.0" // scalacheck integration
  }

  // ---------------------------------------------------------------------------
  // Library groups
  // ---------------------------------------------------------------------------

  val upickle = Seq(
    "com.lihaoyi" %% "upickle" % V.upickle
  )

  val typesafeConfig = Seq(
    "com.typesafe" % "config" % V.typesafeConfig
  )

  val logging = Seq(
    "org.slf4j"      % "slf4j-api"       % V.slf4j,
    "ch.qos.logback" % "logback-classic" % V.logback % Runtime
  )

  val hadoopProvided = Seq(
    ("org.apache.hadoop" % "hadoop-client" % V.hadoop % Provided)
      .exclude("org.slf4j", "slf4j-reload4j")
      .exclude("org.slf4j", "slf4j-log4j12")
  )

  val sparkProvided = Seq(
    ("org.apache.spark" %% "spark-core" % V.spark % Provided)
      .cross(CrossVersion.for3Use2_13)
      .exclude("org.slf4j", "slf4j-log4j12"),
    ("org.apache.spark" %% "spark-sql" % V.spark % Provided)
      .cross(CrossVersion.for3Use2_13)
      .exclude("org.slf4j", "slf4j-log4j12"),
    ("org.apache.spark" %% "spark-graphx" % V.spark % Provided)
      .cross(CrossVersion.for3Use2_13)
      .exclude("org.slf4j", "slf4j-log4j12")
  )

  val awsS3 = Seq(
    "software.amazon.awssdk" % "s3"       % V.awsSdk2,
    "software.amazon.awssdk" % "dynamodb" % V.awsSdk2,
    "software.amazon.awssdk" % "neptune"  % V.awsSdk2,
    // Explicit SigV4 signing deps for raw HTTP calls to Neptune (neptune-db)
    // and S3 Vectors (s3vectors). Same SDK version; previously transitive via s3.
    "software.amazon.awssdk" % "auth"          % V.awsSdk2,
    "software.amazon.awssdk" % "http-auth-aws" % V.awsSdk2,
    "software.amazon.awssdk" % "regions"       % V.awsSdk2,
    // Provides SdkHttpFullRequest / SdkHttpRequest used with AwsV4HttpSigner.
    "software.amazon.awssdk" % "http-client-spi" % V.awsSdk2
  )

  val awsBedrock = Seq(
    "software.amazon.awssdk" % "bedrockruntime" % V.awsSdk2
  )

  val cask = Seq(
    "com.lihaoyi" %% "cask" % V.cask
  )

  val scalatest = Seq(
    "org.scalatest" %% "scalatest" % V.scalatest % Test
  )
}
