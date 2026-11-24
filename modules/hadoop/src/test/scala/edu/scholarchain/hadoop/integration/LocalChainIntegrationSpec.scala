package edu.scholarchain.hadoop.integration

import edu.scholarchain.core.{CoauthorPairCandidate, PublicationTeam, WorkRecord}
import edu.scholarchain.hadoop.config.JobConfig
import edu.scholarchain.hadoop.driver.{ExportVerifier, HadoopJobDriver, JobCompletion}
import edu.scholarchain.hadoop.io.RejectWriter
import edu.scholarchain.hadoop.model.RejectRecord
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import org.apache.hadoop.conf.Configuration
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*
import upickle.default.*

/**
 * End-to-end LocalJobRunner (file://) test of the DBLP chain J01 -> J06 -> J07 -> J08 through the
 * command-line driver, asserting the output layout Spark's DataLoader reads
 * (`<hadoop-output>/pair_candidates`), reject placement, and counter reporting.
 */
class LocalChainIntegrationSpec extends AnyFunSuite with Matchers:

  private val dblpXml =
    """<?xml version="1.0" encoding="ISO-8859-1"?>
      |<!DOCTYPE dblp SYSTEM "dblp.dtd">
      |<dblp>
      |<inproceedings mdate="2021-05-19" key="conf/igarss/SobjaergS03a">
      |<author orcid="0000-0001-6914-8597">Sten Schmidl S&oslash;bj&aelig;rg</author>
      |<author>Niels Skou</author>
      |<title>An airborne campaign measuring wind signatures.</title>
      |<year>2003</year>
      |<booktitle>IGARSS</booktitle>
      |<ee>https://doi.org/10.1109/IGARSS.2003.1294569</ee>
      |</inproceedings>
      |<article mdate="2020-01-01" key="journals/x/Three20">
      |<author>Jos&eacute; Garc&iacute;a</author>
      |<author>J&ouml;rg M&uuml;ller</author>
      |<author>Ann Lee</author>
      |<title><i>ftd</i>: Three authors.</title>
      |<year>2020</year>
      |<journal>J</journal>
      |</article>
      |<article mdate="2020-01-01" key="journals/x/Solo20">
      |<author>Solo Author</author>
      |<title>One author.</title>
      |<year>2020</year>
      |<journal>J</journal>
      |</article>
      |<article mdate="2020-01-01" key="journals/x/NoYear">
      |<author>A B</author>
      |<author>C D</author>
      |<title>Missing year.</title>
      |<journal>J</journal>
      |</article>
      |</dblp>
      |""".stripMargin

  private def partFiles(dir: Path): Vector[Path] =
    if !Files.isDirectory(dir) then Vector.empty
    else
      Files
        .list(dir)
        .iterator()
        .asScala
        .filter(p => p.getFileName.toString.startsWith("part-"))
        .toVector
        .sortBy(_.toString)

  private def readLines(dir: Path): Vector[String] =
    partFiles(dir).flatMap { p =>
      val bytes =
        if p.toString.endsWith(".gz") then
          val in = new GZIPInputStream(Files.newInputStream(p))
          try in.readAllBytes()
          finally in.close()
        else Files.readAllBytes(p)
      new String(bytes, StandardCharsets.UTF_8).linesIterator.filter(_.trim.nonEmpty).toVector
    }

  private val FixedTs = "2026-11-23T00:00:00Z"

  private def runJob(
    job: String,
    in: String,
    out: Path,
    counters: Path,
    timestamp: Option[String] = Some(FixedTs)
  ): Int =
    HadoopJobDriver.run(
      (Seq(
        job,
        "-D",
        "mapreduce.framework.name=local",
        "-D",
        "fs.defaultFS=file:///",
        "-D",
        "scholarchain.pipeline.run-id=it-local-chain",
        "-D",
        s"${JobCompletion.CountersOutputKey}=$counters"
      ) ++ timestamp.toSeq.flatMap(t => Seq("-D", s"${JobConfig.RunTimestampKey}=$t")) ++
        Seq(in, out.toString)).toArray,
      new Configuration()
    )

  private def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))

  /** Run J01 -> J06 -> J07 -> J08 under `root`; returns the export directory. */
  private def runChain(root: Path, timestamp: Option[String] = Some(FixedTs)): Path =
    val in = root.resolve("input/dblp.xml")
    Files.createDirectories(in.getParent)
    Files.writeString(in, dblpXml, StandardCharsets.ISO_8859_1)
    val logs  = root.resolve("logs")
    val works = root.resolve("work_records")
    val teams = root.resolve("teams")
    val pairs = root.resolve("pairs")
    val out   = root.resolve("hadoop-output")
    runJob("dblp-extraction", in.toString, works, logs.resolve("j01.json"), timestamp) shouldBe 0
    runJob("team-extraction", works.toString, teams, logs.resolve("j06.json"), timestamp) shouldBe 0
    runJob("pair-expansion", teams.toString, pairs, logs.resolve("j07.json"), timestamp) shouldBe 0
    runJob(
      "graph-export",
      Seq(works, teams, pairs).mkString(","),
      out,
      logs.resolve("j08.json"),
      timestamp
    ) shouldBe 0
    out

  /** Relative path -> bytes of every data file (part files, not CRCs or markers) under `dir`. */
  private def dataFiles(dir: Path): Map[String, Vector[Byte]] =
    Files
      .walk(dir)
      .iterator()
      .asScala
      .filter(Files.isRegularFile(_))
      .filter(_.getFileName.toString.startsWith("part-"))
      .map(p => dir.relativize(p).toString -> Files.readAllBytes(p).toVector)
      .toMap

  private def counter(file: Path, group: String, name: String): Long =
    val c = ujson.read(Files.readString(file))("counters").obj
    c.get(group).flatMap(_.obj.get(name)).map(_.num.toLong).getOrElse(0L)

  test("DBLP chain in local mode produces the Spark DataLoader layout") {
    val root = Files.createTempDirectory("scholarchain-local-chain")
    val in   = root.resolve("input/dblp.xml")
    Files.createDirectories(in.getParent)
    Files.writeString(in, dblpXml, StandardCharsets.ISO_8859_1)
    val logs = root.resolve("logs")

    // J01
    val works = root.resolve("work_records")
    runJob("dblp-extraction", in.toString, works, logs.resolve("j01.json")) shouldBe 0
    val workRecs = readLines(works).map(read[WorkRecord](_))
    workRecs.map(_.sourceRecordId).sorted shouldBe Vector(
      "dblp:conf/igarss/SobjaergS03a",
      "dblp:journals/x/Solo20",
      "dblp:journals/x/Three20"
    )
    workRecs.find(_.sourceRecordId.endsWith("Three20")).get.title shouldBe "ftd: Three authors."
    val rejects = readLines(works.resolve(RejectWriter.RejectDirectory)).map(read[RejectRecord](_))
    rejects.map(r => (r.sourceRecordId, r.reasonCode)) shouldBe Vector(
      ("dblp:journals/x/NoYear", "MISSING_YEAR")
    )
    val j01 = logs.resolve("j01.json")
    counter(j01, "ScholarChain", "input_records") shouldBe 4
    counter(j01, "ScholarChain", "accepted") shouldBe 3
    counter(j01, "ScholarChain", "rejected") shouldBe 1
    counter(j01, "RejectReasons", "MISSING_YEAR") shouldBe 1
    counter(j01, "RejectReasons", "MALFORMED_XML") shouldBe 0

    // J06 -- rejects in work_records/_rejects must not be read as input
    val teams = root.resolve("teams")
    runJob("team-extraction", works.toString, teams, logs.resolve("j06.json")) shouldBe 0
    counter(logs.resolve("j06.json"), "ScholarChain", "input_records") shouldBe 3
    counter(logs.resolve("j06.json"), "ScholarChain", "rejected") shouldBe 0
    counter(logs.resolve("j06.json"), "TeamExtraction", "total_teams") shouldBe 3

    // J07 -- author-paper lines are skipped, not rejected
    val pairs = root.resolve("pairs")
    runJob("pair-expansion", teams.toString, pairs, logs.resolve("j07.json")) shouldBe 0
    val j07 = logs.resolve("j07.json")
    counter(j07, "ScholarChain", "rejected") shouldBe 0
    counter(j07, "PairExpansion", "skipped_author_paper_records") shouldBe 6
    counter(j07, "PairExpansion", "papers_processed") shouldBe 3
    counter(j07, "PairExpansion", "skipped_singleton_teams") shouldBe 1
    counter(j07, "PairExpansion", "total_pairs_emitted") shouldBe 4

    // J08 -- comma-separated inputs, one subdirectory per record type
    val exportDir = root.resolve("hadoop-output")
    runJob(
      "graph-export",
      Seq(works, teams, pairs).mkString(","),
      exportDir,
      logs.resolve("j08.json")
    ) shouldBe 0
    val pc = readLines(exportDir.resolve("pair_candidates")).map(read[CoauthorPairCandidate](_))
    pc.size shouldBe 4
    pc.foreach(p => p.mentionA should be < p.mentionB)
    pc.map(_.workId).distinct.size shouldBe 2
    readLines(exportDir.resolve("work_records")).map(read[WorkRecord](_)).size shouldBe 3
    readLines(exportDir.resolve("teams")).map(read[PublicationTeam](_)).size shouldBe 3
    readLines(exportDir.resolve("author_paper")).size shouldBe 6
    counter(logs.resolve("j08.json"), "GraphExport", "pair_candidates_out") shouldBe 4

    // N44 / fixed timestamp / ORCID: lineage visible in the Spark-consumed export
    pc.foreach { p =>
      p.provenance.sourceDataset shouldBe "dblp"
      p.provenance.timestamp shouldBe FixedTs
      p.provenance.pipelineRunId shouldBe "it-local-chain"
      p.provenance.transformationStep shouldBe Some("pair-expansion")
      p.provenance.producer shouldBe Some("hadoop/J07-pair-expansion")
      p.provenance.sourceRecordId should startWith("dblp:")
    }
    val exportedTeams = readLines(exportDir.resolve("teams")).map(read[PublicationTeam](_))
    exportedTeams.foreach { t =>
      t.provenance.sourceDataset shouldBe "dblp"
      t.provenance.timestamp shouldBe FixedTs
      t.provenance.producer shouldBe Some("hadoop/J06-team-extraction")
    }
    val exportedWorks = readLines(exportDir.resolve("work_records")).map(read[WorkRecord](_))
    exportedWorks.foreach(_.provenance.producer shouldBe Some("hadoop/J01-dblp-extraction"))
    exportedWorks.flatMap(_.authors).flatMap(_.orcid) shouldBe Vector("0000-0001-6914-8597")

    // N49: no stray part files at the export root
    ExportVerifier.unexpectedRootFiles(exportDir.toString) shouldBe Vector.empty
    partFiles(exportDir) shouldBe Vector.empty
    ExportVerifier.verifyExport(exportDir.toString).ok shouldBe true

    // ExportVerifier accepts the export and reports per-type record counts
    val reports = ExportVerifier.verify(exportDir.toString)
    reports.map(r => (r.dir, r.records, r.ok)) shouldBe Vector(
      ("work_records", 3L, true),
      ("teams", 3L, true),
      ("pair_candidates", 4L, true)
    )

    // ...and rejects a corrupted pair_candidates line
    Files.writeString(exportDir.resolve("pair_candidates/part-m-99999"), "{\"not\":\"a pair\"}\n")
    val bad = ExportVerifier.verify(exportDir.toString).find(_.dir == "pair_candidates").get
    bad.ok shouldBe false
    bad.parseFailures shouldBe 1
    ExportVerifier.verify(root.resolve("missing").toString).forall(_.ok) shouldBe false

    Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
  }

  test("fixed run timestamp makes a rerun of the chain byte-reproducible") {
    val r1 = Files.createTempDirectory("scholarchain-repro-1")
    val r2 = Files.createTempDirectory("scholarchain-repro-2")
    val f1 = dataFiles(runChain(r1))
    val f2 = dataFiles(runChain(r2))
    f1.keySet shouldBe f2.keySet
    f1.keySet.exists(_.startsWith("pair_candidates/part-m-")) shouldBe true
    f1.keySet.foreach(k => withClue(k)(f1(k) shouldBe f2(k)))
    // Stage outputs too (not only the export)
    val s1 = Seq("work_records", "teams", "pairs").map(d => dataFiles(r1.resolve(d)))
    val s2 = Seq("work_records", "teams", "pairs").map(d => dataFiles(r2.resolve(d)))
    s1 shouldBe s2
    deleteTree(r1)
    deleteTree(r2)
  }

  test("without a run timestamp, records carry the wall-clock time (default behavior)") {
    val root   = Files.createTempDirectory("scholarchain-no-ts")
    val before = java.time.Instant.now.minusSeconds(1)
    val out    = runChain(root, timestamp = None)
    val pc     = readLines(out.resolve("pair_candidates")).map(read[CoauthorPairCandidate](_))
    pc should not be empty
    pc.foreach { p =>
      java.time.Instant.parse(p.provenance.timestamp).isBefore(before) shouldBe false
    }
    deleteTree(root)
  }

  test("driver rejects an invalid run timestamp with exit code 2 before running the job") {
    val root = Files.createTempDirectory("scholarchain-bad-ts")
    val out  = root.resolve("out")
    runJob(
      "dblp-extraction",
      root.resolve("missing.xml").toString,
      out,
      root.resolve("c.json"),
      Some("not-a-time")
    ) shouldBe 2
    Files.exists(out) shouldBe false
    deleteTree(root)
  }

  test("ExportVerifier flags stray root part files and empty required outputs") {
    val root = Files.createTempDirectory("scholarchain-verify")
    val out  = runChain(root)
    ExportVerifier.verifyExport(out.toString).ok shouldBe true

    // A stray (even empty) root part file is unexpected
    Files.write(out.resolve("part-m-00000.gz"), Array.emptyByteArray)
    val withStray = ExportVerifier.verifyExport(out.toString)
    withStray.unexpectedRootFiles shouldBe Vector("part-m-00000.gz")
    withStray.ok shouldBe false
    ExportVerifier.reportJson(withStray)("unexpectedRootFiles").arr.map(_.str) shouldBe
      Seq("part-m-00000.gz")
    Files.delete(out.resolve("part-m-00000.gz"))

    // An empty required output (part file with no records) is a failure
    val pcDir = out.resolve("pair_candidates")
    Files.list(pcDir).iterator().asScala.toVector.foreach(Files.delete)
    Files.write(pcDir.resolve("part-m-00000"), Array.emptyByteArray)
    val empty = ExportVerifier.verifyExport(out.toString)
    val pcRep = empty.dirs.find(_.dir == "pair_candidates").get
    pcRep.empty shouldBe true
    pcRep.ok shouldBe false
    empty.ok shouldBe false
    deleteTree(root)
  }

  test("driver returns 2 for an unknown or missing job name") {
    HadoopJobDriver.run(Array("no-such-job", "a", "b")) shouldBe 2
    HadoopJobDriver.run(Array.empty) shouldBe 2
  }

  test("driver registers every job by its JobName") {
    HadoopJobDriver.Tools.keySet shouldBe Set(
      "dblp-extraction",
      "crossref-normalization",
      "openalex-normalization",
      "orcid-normalization",
      "identifier-extraction",
      "blocking",
      "feature-construction",
      "team-extraction",
      "pair-expansion",
      "graph-export"
    )
  }
