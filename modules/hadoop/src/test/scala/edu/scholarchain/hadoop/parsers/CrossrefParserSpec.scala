package edu.scholarchain.hadoop.parsers

import edu.scholarchain.core.SourceType
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CrossrefParserSpec extends AnyFunSuite with Matchers:

  private val runId     = "test-run-001"
  private val schemaVer = "1.0.0"
  private val ts        = "2024-01-01T00:00:00Z"

  test("parse valid Crossref work") {
    val json = ujson.Obj(
      "source_id" -> "crossref:10.1109/TSE.2023.0001234",
      "doi"       -> "10.1109/TSE.2023.0001234",
      "title"     -> "Scalable Graph Partitioning",
      "year"      -> 2023,
      "authors" -> ujson.Arr(
        ujson.Obj(
          "given_name"  -> "Liang",
          "family_name" -> "Chen",
          "orcid"       -> "0000-0003-4567-8901",
          "affiliations" -> ujson.Arr(
            "Westfield Institute"
          ),
        ),
        ujson.Obj(
          "given_name"  -> "Rebecca",
          "family_name" -> "Williams",
          "orcid"       -> ujson.Null,
          "affiliations" -> ujson.Arr(
            "Northgate University"
          ),
        ),
      ),
      "venue" -> ujson.Obj(
        "name"      -> "IEEE TSE",
        "publisher" -> "IEEE",
      ),
    ).toString()

    val result =
      CrossrefParser.parse(json, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (work, mentions) = result.toOption.get

    work.source shouldBe SourceType.Crossref
    work.title shouldBe "Scalable Graph Partitioning"
    work.year shouldBe 2023
    work.doi shouldBe Some("10.1109/tse.2023.0001234")
    mentions.size shouldBe 2
    mentions(0).rawName shouldBe "Liang Chen"
    mentions(0).orcid shouldBe Some("0000-0003-4567-8901")
    mentions(0).affiliationRaw shouldBe Some("Westfield Institute")
    mentions(1).rawName shouldBe "Rebecca Williams"
    mentions(1).orcid shouldBe None
  }

  test("parse missing title returns Left") {
    val json = ujson.Obj(
      "source_id" -> "crossref:10.1234/test",
      "doi"       -> "10.1234/test",
      "year"      -> 2023,
    ).toString()

    val result =
      CrossrefParser.parse(json, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MISSING_TITLE"
  }

  test("parse malformed JSON returns Left") {
    val result =
      CrossrefParser.parse("{bad", runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MALFORMED_JSON"
  }

  test("parse empty authors list returns Left with MISSING_AUTHORS") {
    val json = ujson.Obj(
      "source_id" -> "crossref:10.1234/noauth",
      "doi"       -> "10.1234/noauth",
      "title"     -> "No Authors Paper",
      "year"      -> 2023,
      "authors"   -> ujson.Arr(),
    ).toString()

    val result =
      CrossrefParser.parse(json, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MISSING_AUTHORS"
  }

  test("parse missing authors field returns Left with MISSING_AUTHORS") {
    val json = ujson.Obj(
      "source_id" -> "crossref:10.1234/nofield",
      "doi"       -> "10.1234/nofield",
      "title"     -> "No Authors Field",
      "year"      -> 2023,
    ).toString()

    val result =
      CrossrefParser.parse(json, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MISSING_AUTHORS"
  }

  test("parse preserves provenance") {
    val json = ujson.Obj(
      "source_id" -> "crossref:10.1234/prov",
      "doi"       -> "10.1234/prov",
      "title"     -> "Provenance Test",
      "year"      -> 2023,
      "authors" -> ujson.Arr(
        ujson.Obj(
          "given_name"  -> "Test",
          "family_name" -> "Author",
        )
      ),
    ).toString()

    val result =
      CrossrefParser.parse(json, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (work, _) = result.toOption.get
    work.provenance.sourceDataset shouldBe "crossref"
    work.provenance.pipelineRunId shouldBe runId
  }

  test("parse missing source_id returns Left") {
    val json = ujson.Obj(
      "title" -> "Test",
      "year"  -> 2023,
    ).toString()

    val result =
      CrossrefParser.parse(json, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MISSING_SOURCE_ID"
  }
