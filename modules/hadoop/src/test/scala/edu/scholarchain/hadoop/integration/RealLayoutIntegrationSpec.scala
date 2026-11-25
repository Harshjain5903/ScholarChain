package edu.scholarchain.hadoop.integration

import edu.scholarchain.core.{SourceType, WorkRecord}
import edu.scholarchain.hadoop.parsers.{CrossrefParser, OpenAlexParser}
import edu.scholarchain.hadoop.driver.HadoopJobDriver
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.apache.hadoop.conf.Configuration
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.io.Source
import scala.jdk.CollectionConverters.*
import upickle.default.*

/**
 * Runs the Crossref, OpenAlex and ORCID normalizers over the real input LAYOUTS (a directory of
 * one-work-per-file JSON; a directory of multi-work API responses) through the driver.
 */
class RealLayoutIntegrationSpec extends AnyFunSuite with Matchers:

  private def resource(name: String): String =
    val s = Source.fromResource(s"real/$name", getClass.getClassLoader)
    try s.mkString
    finally s.close()

  private def run(job: String, in: Path, out: Path, counters: Path): Int =
    HadoopJobDriver.run(
      Array(
        job,
        "-Dmapreduce.framework.name=local",
        "-Dscholarchain.pipeline.run-id=rs-test-" + job,
        "-Dscholarchain.run.timestamp=2026-11-24T00:00:00Z",
        s"-Dscholarchain.counters.output=$counters",
        in.toUri.toString,
        out.toUri.toString
      ),
      new Configuration()
    )

  private def lines(out: Path): Vector[String] =
    Files
      .list(out)
      .iterator()
      .asScala
      .filter(_.getFileName.toString.startsWith("part-"))
      .toVector
      .sortBy(_.toString)
      .flatMap(p => Files.readString(p).linesIterator.filter(_.trim.nonEmpty).toVector)

  private def counter(file: Path, group: String, name: String): Long =
    ujson.read(Files.readString(file))("counters")(group)(name).num.toLong

  test("Crossref normalizer reads a directory of one-work-per-file JSON, deterministically") {
    val tmp = Files.createTempDirectory("crossref-layout")
    val in  = Files.createDirectories(tmp.resolve("in"))
    Files.writeString(in.resolve("a.json"), resource("crossref-work.json"))
    Files.writeString(in.resolve("b.json"), resource("crossref-no-author.json"))
    val c1 = tmp.resolve("c1.json")
    val c2 = tmp.resolve("c2.json")
    run("crossref-normalization", in, tmp.resolve("o1"), c1) shouldBe 0
    run("crossref-normalization", in, tmp.resolve("o2"), c2) shouldBe 0
    counter(c1, "ScholarChain", "input_files") shouldBe 2
    counter(c1, "ScholarChain", "input_records") shouldBe 2
    counter(c1, "ScholarChain", "accepted") shouldBe 1
    counter(c1, "ScholarChain", "rejected") shouldBe 1
    counter(c1, "RejectReasons", "MISSING_AUTHORS") shouldBe 1
    val out = lines(tmp.resolve("o1"))
    out.size shouldBe 1
    read[WorkRecord](out.head).workId shouldBe "work:crossref:10.1109/tfuzz.2021.3050857"
    lines(tmp.resolve("o2")) shouldBe out
  }

  test("OpenAlex normalizer expands a multi-work API response file into one record per work") {
    val tmp = Files.createTempDirectory("openalex-layout")
    val in  = Files.createDirectories(tmp.resolve("in"))
    Files.writeString(in.resolve("oa-chunk-aaaa.json"), resource("openalex-chunk.json"))
    val c = tmp.resolve("c.json")
    run("openalex-normalization", in, tmp.resolve("o"), c) shouldBe 0
    counter(c, "ScholarChain", "input_files") shouldBe 1
    counter(c, "ScholarChain", "input_records") shouldBe 2
    counter(c, "ScholarChain", "accepted") shouldBe 2
    lines(tmp.resolve("o")).map(read[WorkRecord](_).workId) shouldBe
      Vector("work:openalex:W3157926221", "work:openalex:W4384928371")
  }

  test("json-lines layout is still supported") {
    val tmp = Files.createTempDirectory("jsonl-layout")
    val in  = Files.createDirectories(tmp.resolve("in"))
    val flat = ujson.Obj(
      "source_id" -> "openalex:W9",
      "title"     -> "T",
      "year"      -> 2020,
      "authors"   -> ujson.Arr(ujson.Obj("name" -> "A B"))
    )
    Files.writeString(in.resolve("x.jsonl"), ujson.write(flat) + "\n" + ujson.write(flat) + "\n")
    val c = tmp.resolve("c.json")
    HadoopJobDriver.run(
      Array(
        "openalex-normalization",
        "-Dscholarchain.input.layout=json-lines",
        s"-Dscholarchain.counters.output=$c",
        in.toUri.toString,
        tmp.resolve("o").toUri.toString
      ),
      new Configuration()
    ) shouldBe 0
    counter(c, "ScholarChain", "input_records") shouldBe 2
  }

  test("ORCID normalizer fails cleanly on an empty input directory and writes nothing") {
    val tmp = Files.createTempDirectory("orcid-empty")
    val in  = Files.createDirectories(tmp.resolve("in"))
    run("orcid-normalization", in, tmp.resolve("o"), tmp.resolve("c.json")) shouldBe 3
    Files.exists(tmp.resolve("o")) shouldBe false
  }

  test("an invalid input layout is rejected before submission") {
    val tmp = Files.createTempDirectory("bad-layout")
    val in  = Files.createDirectories(tmp.resolve("in"))
    Files.writeString(in.resolve("a.json"), "{}")
    HadoopJobDriver.run(
      Array(
        "orcid-normalization",
        "-Dscholarchain.input.layout=xml",
        in.toUri.toString,
        tmp.resolve("o").toUri.toString
      ),
      new Configuration()
    ) shouldBe 2
  }

  test("blocking reports true block sizes, dropped members and the largest block") {
    val tmp = Files.createTempDirectory("blocking-counters")
    val in  = Files.createDirectories(tmp.resolve("in"))
    def work(id: String, title: String, authorName: String) =
      ujson.write(
        ujson.Obj(
          "workId"          -> s"work:dblp:$id",
          "source"          -> "DBLP",
          "sourceRecordId"  -> s"dblp:$id",
          "title"           -> title,
          "normalizedTitle" -> title.toLowerCase,
          "year"            -> 2020,
          "venueId"         -> ujson.Null,
          "doi"             -> ujson.Null,
          "arxivId"         -> ujson.Null,
          "authors" -> ujson.Arr(
            ujson.Obj(
              "mentionId"      -> s"mention:dblp:$id:0",
              "rawName"        -> authorName,
              "normalizedName" -> authorName.toLowerCase,
              "position"       -> 0,
              "orcid"          -> ujson.Null,
              "openAlexId"     -> ujson.Null,
              "affiliationRaw" -> ujson.Null,
              "sourceRecordId" -> s"dblp:$id",
              "provenance" -> ujson.Obj(
                "sourceDataset"  -> "dblp",
                "sourceRecordId" -> s"dblp:$id",
                "pipelineRunId"  -> "r",
                "schemaVersion"  -> "1.0.0",
                "timestamp"      -> "2026-11-24T00:00:00Z"
              )
            )
          ),
          "abstractTextRef" -> ujson.Null,
          "provenance" -> ujson.Obj(
            "sourceDataset"  -> "dblp",
            "sourceRecordId" -> s"dblp:$id",
            "pipelineRunId"  -> "r",
            "schemaVersion"  -> "1.0.0",
            "timestamp"      -> "2026-11-24T00:00:00Z"
          )
        )
      )
    // Three works share the author block "author:smith:j"; titles differ so no title block forms.
    Files.writeString(
      in.resolve("w.jsonl"),
      Seq(
        work("a", "alpha topic here", "John Smith"),
        work("b", "beta subject there", "Jane Smith"),
        work("c", "gamma matter elsewhere", "Jim Smith")
      ).mkString("\n") + "\n"
    )
    val c = tmp.resolve("c.json")
    HadoopJobDriver.run(
      Array(
        "blocking",
        "-Dscholarchain.blocking.max-block-size=2",
        s"-Dscholarchain.counters.output=$c",
        in.toUri.toString,
        tmp.resolve("o").toUri.toString
      ),
      new Configuration()
    ) shouldBe 0
    counter(c, "Blocking", "largest_block_size") shouldBe 3
    counter(c, "Blocking", "oversize_blocks") shouldBe 1
    counter(c, "Blocking", "dropped_records") shouldBe 1
    counter(c, "BlockSizes", "size_le_0000004") shouldBe 1
  }

  test("identifier extraction reports DOI matches of DBLP works against Crossref and OpenAlex") {
    val tmp = Files.createTempDirectory("j03-links")
    val in1 = Files.createDirectories(tmp.resolve("in1"))
    val in2 = Files.createDirectories(tmp.resolve("in2"))
    val (cr, _) =
      CrossrefParser.parse(resource("crossref-work.json"), "r", "1.0.0", "2026-11-24T00:00:00Z").toOption.get
    val oaRaw = OpenAlexParser.splitRecords(resource("openalex-chunk.json")).head
    val (oa0, _) =
      OpenAlexParser.parse(oaRaw, "r", "1.0.0", "2026-11-24T00:00:00Z").toOption.get
    // DBLP twins of the Crossref work and of the first OpenAlex work, plus an unmatched DBLP work.
    def dblp(id: String, w: WorkRecord) =
      w.copy(
        workId = s"work:dblp:$id",
        source = SourceType.DBLP,
        sourceRecordId = s"dblp:$id",
        authors = w.authors.map(_.copy(orcid = None))
      )
    val oa  = oa0.copy(authors = oa0.authors)
    val lone = dblp("lone", cr).copy(doi = Some("10.9999/none"))
    Files.writeString(
      in1.resolve("d.jsonl"),
      Seq(dblp("x", cr), dblp("y", oa), lone).map(write(_)).mkString("\n") + "\n"
    )
    Files.writeString(in2.resolve("e.jsonl"), Seq(cr, oa).map(write(_)).mkString("\n") + "\n")
    val c = tmp.resolve("c.json")
    HadoopJobDriver.run(
      Array(
        "identifier-extraction",
        s"-Dscholarchain.counters.output=$c",
        s"${in1.toUri},${in2.toUri}",
        tmp.resolve("o").toUri.toString
      ),
      new Configuration()
    ) shouldBe 0
    counter(c, "ScholarChain", "input_records") shouldBe 5
    counter(c, "IdentifierExtraction", "dblp_works_crossref_doi_match") shouldBe 1
    counter(c, "IdentifierExtraction", "dblp_works_openalex_doi_match") shouldBe 1
    counter(c, "IdentifierExtraction", "dblp_works_any_doi_match") shouldBe 2
    counter(c, "IdentifierExtraction", "doi_cross_source") shouldBe 2
  }

  test("ORCID records flow through normalization into the exact-identifier join") {
    val tmp  = Files.createTempDirectory("orcid-link")
    val orIn = Files.createDirectories(tmp.resolve("orcid"))
    // Invented profile for an ORCID that the real Crossref fixture work also carries.
    Files.writeString(
      orIn.resolve("0000-0003-2302-6695.json"),
      """{"orcid-identifier":{"path":"0000-0003-2302-6695"},
        |"person":{"name":{"given-names":{"value":"D."},"family-name":{"value":"Ge"}}}}""".stripMargin
    )
    val oc = tmp.resolve("oc.json")
    run("orcid-normalization", orIn, tmp.resolve("orcid-out"), oc) shouldBe 0
    counter(oc, "ScholarChain", "accepted") shouldBe 1
    val (cr, _) =
      CrossrefParser.parse(resource("crossref-work.json"), "r", "1.0.0", "2026-11-24T00:00:00Z").toOption.get
    val wIn = Files.createDirectories(tmp.resolve("works"))
    Files.writeString(wIn.resolve("w.jsonl"), write(cr) + "\n")
    val c = tmp.resolve("c.json")
    HadoopJobDriver.run(
      Array(
        "identifier-extraction",
        s"-Dscholarchain.counters.output=$c",
        s"${wIn.toUri},${tmp.resolve("orcid-out").toUri}",
        tmp.resolve("o").toUri.toString
      ),
      new Configuration()
    ) shouldBe 0
    counter(c, "IdentifierExtraction", "orcid_cross_source") shouldBe 1
    counter(c, "IdentifierExtraction", "skipped_researcher_records") shouldBe 1
    lines(tmp.resolve("o")).filter(_.startsWith("orcid:0000-0003-2302-6695")).head should
      (include("orcid:0000-0003-2302-6695") and include("crossref:"))
  }
