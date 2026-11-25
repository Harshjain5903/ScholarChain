package edu.scholarchain.hadoop.parsers

import edu.scholarchain.core.SourceType
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** N50: no Hadoop parser may emit a work ID that repeats its source segment. */
class WorkIdsSpec extends AnyFunSuite with Matchers:

  private val runId     = "test-run-001"
  private val schemaVer = "1.0.0"
  private val ts        = "2024-01-01T00:00:00Z"

  test("workId strips one source qualifier and leaves bare keys unchanged") {
    WorkIds.workId(SourceType.DBLP, "dblp:conf/x/K1") shouldBe "work:dblp:conf/x/K1"
    WorkIds.workId(SourceType.DBLP, "conf/x/K1") shouldBe "work:dblp:conf/x/K1"
    WorkIds.workId(SourceType.OpenAlex, "openalex:W1") shouldBe "work:openalex:W1"
    WorkIds.workId(SourceType.Crossref, "crossref:10.1/x") shouldBe "work:crossref:10.1/x"
  }

  test("hasDoubledSourceSegment detects work:<src>:<src>: for every source type") {
    WorkIds.hasDoubledSourceSegment("work:dblp:dblp:conf/x/K1") shouldBe true
    WorkIds.hasDoubledSourceSegment("work:openalex:openalex:W1") shouldBe true
    WorkIds.hasDoubledSourceSegment("work:crossref:crossref:10.1/x") shouldBe true
    WorkIds.hasDoubledSourceSegment("work:dblp:conf/x/K1") shouldBe false
    WorkIds.hasDoubledSourceSegment("work:openalex:W1") shouldBe false
  }

  test("no parser emits a work ID with a doubled source segment") {
    val dblp = DblpParser
      .parse(
        """<article key="journals/x/A1"><author>A B</author><title>T</title><year>2020</year></article>""",
        runId,
        schemaVer,
        ts
      )
      .toOption
      .get
      ._1
    val openAlex = OpenAlexParser
      .parse(
        ujson
          .Obj(
            "source_id" -> "openalex:W42",
            "title"     -> "T",
            "year"      -> 2020,
            "authors"   -> ujson.Arr(ujson.Obj("name" -> "A B"))
          )
          .toString,
        runId,
        schemaVer,
        ts
      )
      .toOption
      .get
      ._1
    val crossref = CrossrefParser
      .parse(
        ujson
          .Obj(
            "source_id" -> "crossref:10.1234/abc",
            "doi"       -> "10.1234/abc",
            "title"     -> "T",
            "year"      -> 2020,
            "authors"   -> ujson.Arr(ujson.Obj("given_name" -> "A", "family_name" -> "B"))
          )
          .toString,
        runId,
        schemaVer,
        ts
      )
      .toOption
      .get
      ._1
    val works = Vector(dblp, openAlex, crossref)
    works.map(_.workId) shouldBe Vector(
      "work:dblp:journals/x/A1",
      "work:openalex:W42",
      "work:crossref:10.1234/abc"
    )
    works.map(_.sourceRecordId) shouldBe Vector(
      "dblp:journals/x/A1",
      "openalex:W42",
      "crossref:10.1234/abc"
    )
    works.foreach { w =>
      WorkIds.hasDoubledSourceSegment(w.workId) shouldBe false
      w.workId should not include "dblp:dblp:"
      w.workId should not include "openalex:openalex:"
      w.workId should not include "crossref:crossref:"
    }
  }
