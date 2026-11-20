import Dependencies._

// ---------------------------------------------------------------------------
// Global settings
// ---------------------------------------------------------------------------
ThisBuild / organization := "edu.scholarchain"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := V.scala3

// Fail on warnings in CI; override locally with env var if needed.
ThisBuild / scalacOptions ++= Seq(
  "-encoding", "utf-8",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xfatal-warnings"
)

// Allow upickle eviction (cask 0.9.x depends on older upickle; our pinned version wins)
ThisBuild / libraryDependencySchemes += "com.lihaoyi" %% "upickle" % VersionScheme.Always

// ---------------------------------------------------------------------------
// markTestRun — writes timestamp only after ALL tests pass
// ---------------------------------------------------------------------------
lazy val markTestRun = taskKey[Unit](
  "Write .claude/.last-test-run after all tests pass"
)

// ---------------------------------------------------------------------------
// Root project — aggregates all modules, no sources of its own
// ---------------------------------------------------------------------------
lazy val root = (project in file("."))
  .aggregate(core, hadoop, entityResolution, sparkAnalytics, storage, rag, services)
  .settings(
    name := "scholarchain",
    publish / skip := true,

    markTestRun := {
      // Depend on every sub-project's test task so this runs only on success.
      (core / Test / test).value
      (hadoop / Test / test).value
      (entityResolution / Test / test).value
      (sparkAnalytics / Test / test).value
      (storage / Test / test).value
      (rag / Test / test).value
      (services / Test / test).value

      val marker = baseDirectory.value / ".claude" / ".last-test-run"
      IO.write(marker, java.time.Instant.now.toString + "\n")
      streams.value.log.info(s"Wrote test-run marker: $marker")
    }
  )

// ---------------------------------------------------------------------------
// Shared settings
// ---------------------------------------------------------------------------
lazy val commonSettings = Seq(
  libraryDependencies ++= typesafeConfig ++ logging ++ upickle ++ scalatest
)

// ---------------------------------------------------------------------------
// core — shared domain records, stable IDs, serialization, config
// ---------------------------------------------------------------------------
lazy val core = (project in file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "scholarchain-core"
  )

// ---------------------------------------------------------------------------
// hadoop — MapReduce jobs; depends on core
// ---------------------------------------------------------------------------
lazy val hadoop = (project in file("modules/hadoop"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-hadoop",
    libraryDependencies ++= hadoopProvided,
    // Assembly for submitting fat JARs to Hadoop/EMR
    assembly / mainClass := None,
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*)              => MergeStrategy.discard
      case "reference.conf"                      => MergeStrategy.concat
      case _                                     => MergeStrategy.first
    }
  )

// ---------------------------------------------------------------------------
// entityResolution — blocking, matching, calibration; depends on core
// ---------------------------------------------------------------------------
lazy val entityResolution = (project in file("modules/entity-resolution"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-entity-resolution"
  )

// ---------------------------------------------------------------------------
// sparkAnalytics — graph construction and analytics; depends on core
// ---------------------------------------------------------------------------
lazy val sparkAnalytics = (project in file("modules/spark"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-spark",
    libraryDependencies ++= sparkProvided,
    // Spark 2.13 brings scala-xml_2.13 which conflicts with scala-xml_3; exclude it
    excludeDependencies += "org.scala-lang.modules" % "scala-xml_2.13",
    Test / fork := true,
    Test / javaOptions ++= Seq(
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED"
    ),
    assembly / mainClass := None,
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*)              => MergeStrategy.discard
      case "reference.conf"                      => MergeStrategy.concat
      case _                                     => MergeStrategy.first
    }
  )

// ---------------------------------------------------------------------------
// storage — S3, Neptune, DynamoDB, S3 Vectors adapters; depends on core
// ---------------------------------------------------------------------------
lazy val storage = (project in file("modules/storage"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-storage",
    libraryDependencies ++= awsS3
  )

// ---------------------------------------------------------------------------
// rag — query plans, retrieval, evidence, prompts; depends on core, storage
// ---------------------------------------------------------------------------
lazy val rag = (project in file("modules/rag"))
  .dependsOn(core, storage)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-rag",
    libraryDependencies ++= awsBedrock
  )

// ---------------------------------------------------------------------------
// services — REST routes, health, runtime composition; depends on core, rag, storage
// ---------------------------------------------------------------------------
lazy val services = (project in file("modules/services"))
  .dependsOn(core, rag, storage)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-services",
    libraryDependencies ++= cask,

    // --- Docker packaging ---
    Docker / packageName := "scholarchain-services",
    Docker / version     := version.value,
    dockerBaseImage      := "eclipse-temurin:21.0.5_11-jre-jammy",
    Docker / daemonUser  := "appuser",

    // Percentage-based memory: use 75% of container memory for heap
    dockerEnvVars := Map(
      "JAVA_OPTS" -> "-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"
    ),

    // Add non-root user via docker commands
    dockerCommands := dockerCommands.value.flatMap {
      case cmd @ com.typesafe.sbt.packager.docker.Cmd("USER", _*) =>
        Seq(
          com.typesafe.sbt.packager.docker.Cmd("RUN", "addgroup --system appgroup && adduser --system --ingroup appgroup appuser"),
          cmd
        )
      case other => Seq(other)
    }
  )

// ---------------------------------------------------------------------------
// Command aliases
// ---------------------------------------------------------------------------
addCommandAlias("fmt",         "scalafmtAll")
addCommandAlias("check",      ";scalafmtCheckAll ;Test/compile ;test ;markTestRun")
addCommandAlias("packageJobs", ";hadoop/assembly ;sparkAnalytics/assembly")
addCommandAlias("dockerLocal", "services/Docker/publishLocal")
