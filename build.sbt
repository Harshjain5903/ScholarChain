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
// Shared assembly settings for job jars (hadoop, entityResolution, spark and
// the standalone variants)
//
// - One merge strategy for every job assembly.
// - Main-Class guard: if `assembly / mainClass` is set, the class must be a
//   compiled main class of this project or of a project it depends on,
//   otherwise `assembly` fails. This prevents shipping a jar whose manifest
//   names a class that does not exist (breaks `hadoop jar`, `java -jar`).
// - `assembly / mainClass` must always be set explicitly: when it is None,
//   sbt-assembly falls back to `Compile / packageBin / packageOptions`, which
//   carries the auto-discovered `Compile / mainClass` whenever a module has
//   exactly one main class (this is why the spark jar used to carry
//   Main-Class edu.scholarchain.spark.ScholarChainPipeline despite None).
// ---------------------------------------------------------------------------
lazy val jobAssemblyMergeStrategy: String => sbtassembly.MergeStrategy = {
  case PathList("META-INF", "services", _*) => MergeStrategy.concat
  case PathList("META-INF", _*)              => MergeStrategy.discard
  case "module-info.class"                   => MergeStrategy.discard
  case "reference.conf"                      => MergeStrategy.concat
  case _                                     => MergeStrategy.first
}

lazy val verifyAssemblyMainClass = taskKey[Unit](
  "Fail if assembly / mainClass names a class that is not a compiled main class of this project or its dependencies"
)

lazy val dockerEntrypointWrapper = taskKey[File](
  "Generate the services container entrypoint wrapper (default API main, or a main-class argument)"
)

lazy val jobAssemblySettings = Seq(
  assembly / assemblyMergeStrategy := jobAssemblyMergeStrategy,
  verifyAssemblyMainClass := {
    val declared = (assembly / mainClass).value
    val discovered = discoveredMainClasses
      .all(ScopeFilter(inDependencies(ThisProject), inConfigurations(Compile)))
      .value
      .flatten
      .distinct
      .sorted
    val project = thisProject.value.id
    declared.foreach { cls =>
      if (!discovered.contains(cls))
        throw new MessageOnlyException(
          s"[$project] assembly / mainClass = $cls, but no such main class was compiled. " +
            s"Discovered main classes: ${if (discovered.isEmpty) "<none>" else discovered.mkString(", ")}"
        )
    }
  },
  assembly := assembly.dependsOn(verifyAssemblyMainClass).value
)

// JVM module opens Spark needs on Java 17+ (same list as Spark's JavaModuleOptions).
// Written into the standalone Spark manifest as `Add-Opens` (honoured by `java -jar`).
lazy val sparkJavaOpens = Seq(
  "java.base/java.lang",
  "java.base/java.lang.invoke",
  "java.base/java.lang.reflect",
  "java.base/java.io",
  "java.base/java.net",
  "java.base/java.nio",
  "java.base/java.util",
  "java.base/java.util.concurrent",
  "java.base/java.util.concurrent.atomic",
  "java.base/jdk.internal.ref",
  "java.base/sun.nio.ch",
  "java.base/sun.nio.cs",
  "java.base/sun.security.action",
  "java.base/sun.util.calendar"
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
    // Assembly for EMR (`hadoop jar <jar> <job-name> <args>`); Hadoop is Provided.
    jobAssemblySettings,
    assembly / mainClass := Some("edu.scholarchain.hadoop.driver.HadoopJobDriver")
  )

// ---------------------------------------------------------------------------
// entityResolution — blocking, matching, calibration; depends on core
// ---------------------------------------------------------------------------
lazy val entityResolution = (project in file("modules/entity-resolution"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-entity-resolution",
    // Plain-JVM job jar (no Hadoop/Spark); runs with `java -jar` on EMR or ECS.
    jobAssemblySettings,
    assembly / mainClass := Some("edu.scholarchain.entityresolution.EntityResolutionRunner")
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
    // Assembly for EMR (`spark-submit`); Spark/Hadoop are Provided.
    jobAssemblySettings,
    assembly / mainClass := Some("edu.scholarchain.spark.ScholarChainPipeline")
  )

// ---------------------------------------------------------------------------
// Standalone (non-EMR) job jars, for container runtimes such as ECS Fargate.
//
// These projects have no sources of their own: they depend on the job module
// and bundle Hadoop (or Spark + its Hadoop client) plus hadoop-aws and the
// AWS SDK v1 bundle, so `s3a://` paths work with only a JRE on the image.
// They are not aggregated by root (no tests; `check` is unaffected) and are
// built by the `packageStandaloneJobs` alias.
// ---------------------------------------------------------------------------
// Shared resources (logback.xml: WARN root, INFO for edu.scholarchain) for both jars.
lazy val standaloneResources = Def.setting(
  (ThisBuild / baseDirectory).value / "packaging" / "standalone-resources"
)

lazy val hadoopStandalone = (project in file("packaging/hadoop-standalone"))
  .dependsOn(hadoop)
  .settings(
    name := "scholarchain-hadoop-standalone",
    publish / skip := true,
    libraryDependencies ++= hadoopStandaloneRuntime ++ logging,
    Compile / unmanagedResourceDirectories += standaloneResources.value,
    jobAssemblySettings,
    assembly / mainClass := Some("edu.scholarchain.hadoop.driver.HadoopJobDriver")
  )

lazy val sparkStandalone = (project in file("packaging/spark-standalone"))
  .dependsOn(sparkAnalytics)
  .settings(
    name := "scholarchain-spark-standalone",
    publish / skip := true,
    libraryDependencies ++= sparkStandaloneRuntime ++ logging,
    excludeDependencies += "org.scala-lang.modules" % "scala-xml_2.13",
    // One SLF4J provider (logback, as in every other module); Spark's log4j2
    // binding would otherwise compete with it.
    excludeDependencies += "org.apache.logging.log4j" % "log4j-slf4j2-impl",
    Compile / unmanagedResourceDirectories += standaloneResources.value,
    jobAssemblySettings,
    assembly / mainClass := Some("edu.scholarchain.spark.ScholarChainPipeline"),
    assembly / packageOptions += Package.ManifestAttributes(
      "Add-Opens" -> sparkJavaOpens.mkString(" ")
    )
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

    // Pin the default main so the start script keeps working once the module
    // has more than one main class (for example EmbeddingWorker).
    Compile / mainClass := Some("edu.scholarchain.services.scholarChainServer"),

    // --- Multi-main container entrypoint ---
    // The image is runnable both as the REST API and as any other main class:
    //   docker run img                                   -> REST API (default main)
    //   docker run img edu.scholarchain.services.X a b   -> runs main class X with args a b
    //   docker run img -main edu.scholarchain.services.X -> start-script form, passed through
    //   docker run img --some-flag                       -> passed to the default main
    // ECS: command = ["edu.scholarchain.services.EmbeddingWorker"]
    // The wrapper is generated by the build (no file under modules/ is needed).
    dockerEntrypointWrapper := {
      val app = executableScriptName.value
      val out = (Compile / target).value / "docker-entrypoint" / "docker-entrypoint"
      IO.write(
        out,
        s"""#!/bin/sh
           |# Generated by build.sbt (services/dockerEntrypointWrapper). Do not edit.
           |# A first argument that looks like a fully qualified class name (contains a
           |# dot, does not start with '-') selects that main class via the sbt-native-
           |# packager start script's -main option. Anything else is passed through.
           |set -e
           |APP="$$(dirname "$$0")/$app"
           |case "$${1:-}" in
           |  -*|"") exec "$$APP" "$$@" ;;
           |  [A-Za-z_]*.[A-Za-z_]*)
           |    MAIN_CLASS="$$1"
           |    shift
           |    exec "$$APP" -main "$$MAIN_CLASS" "$$@" ;;
           |  *) exec "$$APP" "$$@" ;;
           |esac
           |""".stripMargin
      )
      out.setExecutable(true, false)
      out
    },
    Universal / mappings += dockerEntrypointWrapper.value -> "bin/docker-entrypoint",
    dockerEntrypoint := Seq(s"${(Docker / defaultLinuxInstallLocation).value}/bin/docker-entrypoint"),
    dockerCmd        := Seq.empty,

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
//
// fmt         Reformat all sources (main + test) with scalafmt.
// check       Repository definition of done: format verification, test
//             compilation, all tests, then markTestRun (writes
//             .claude/.last-test-run only if every module's tests pass).
// packageJobs Build the EMR job fat jars (Hadoop/Spark Provided). Each job
//             assembly first verifies that its declared Main-Class was
//             compiled (verifyAssemblyMainClass).
//               hadoop/assembly           -> modules/hadoop/target/scala-*/scholarchain-hadoop-assembly-<ver>.jar
//                                            Main-Class edu.scholarchain.hadoop.driver.HadoopJobDriver
//               entityResolution/assembly -> modules/entity-resolution/target/scala-*/scholarchain-entity-resolution-assembly-<ver>.jar
//                                            Main-Class edu.scholarchain.entityresolution.EntityResolutionRunner
//               sparkAnalytics/assembly   -> modules/spark/target/scala-*/scholarchain-spark-assembly-<ver>.jar
//                                            Main-Class edu.scholarchain.spark.ScholarChainPipeline
// packageStandaloneJobs
//             Build the non-EMR job jars (Hadoop/Spark, hadoop-aws and the
//             AWS SDK v1 bundle included; run with `java -jar` on a JRE image).
//               hadoopStandalone/assembly -> packaging/hadoop-standalone/target/scala-*/scholarchain-hadoop-standalone-assembly-<ver>.jar
//               sparkStandalone/assembly  -> packaging/spark-standalone/target/scala-*/scholarchain-spark-standalone-assembly-<ver>.jar
// dockerLocal Build the services image into the local Docker daemon (no push).
//             Image entrypoint is bin/docker-entrypoint: no args runs the
//             REST API; a first arg that is a class name runs that main class.
// ---------------------------------------------------------------------------
addCommandAlias("fmt",         "scalafmtAll")
addCommandAlias("check",      ";scalafmtCheckAll ;Test/compile ;test ;markTestRun")
addCommandAlias("packageJobs", ";hadoop/assembly ;entityResolution/assembly ;sparkAnalytics/assembly")
addCommandAlias("packageStandaloneJobs", ";hadoopStandalone/assembly ;sparkStandalone/assembly")
addCommandAlias("dockerLocal", "services/Docker/publishLocal")
