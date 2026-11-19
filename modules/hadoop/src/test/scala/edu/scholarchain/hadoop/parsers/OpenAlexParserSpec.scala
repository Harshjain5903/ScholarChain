package edu.scholarchain.hadoop.parsers

import edu.scholarchain.core.SourceType
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class OpenAlexParserSpec extends AnyFunSuite with Matchers:

  private val runId     = "test-run-001"
  private val schemaVer = "1.0.0"
  private val ts        = "2024-01-01T00:00:00Z"

  test("parse valid OpenAlex work") {
    val json = ujson
      .Obj(
        "source_id" -> "openalex:W3194827561",
        "doi"       -> "10.1109/TSE.2023.0001234",
        "title"     -> "Scalable Graph Partitioning",
        "year"      -> 2023,
        "authors" -> ujson.Arr(
          ujson.Obj(
            "name"        -> "Liang Chen",
            "openalex_id" -> "A4012983745",
            "orcid"       -> "0000-0003-4567-8901",
            "position"    -> "first",
            "affiliations" -> ujson.Arr(
              ujson.Obj("name" -> "Westfield Institute")
            )
          ),
          ujson.Obj(
            "name"        -> "Rebecca Williams",
            "openalex_id" -> "A2987654321",
            "orcid"       -> ujson.Null,
            "position"    -> "last",
            "affiliations" -> ujson.Arr(
              ujson.Obj("name" -> "Northgate University")
            )
          )
        ),
        "venue" -> ujson.Obj("name" -> "IEEE TSE")
      )
      .toString()

    val result =
      OpenAlexParser.parse(json, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (work, mentions) = result.toOption.get

    work.source shouldBe SourceType.OpenAlex
    work.title shouldBe "Scalable Graph Partitioning"
    work.year shouldBe 2023
    work.doi shouldBe Some("10.1109/tse.2023.0001234")
    mentions.size shouldBe 2
    mentions(0).orcid shouldBe Some("0000-0003-4567-8901")
    mentions(0).openAlexId shouldBe Some("A4012983745")
    mentions(0).affiliationRaw shouldBe Some("Westfield Institute")
    mentions(1).orcid shouldBe None
  }

  test("parse missing DOI still succeeds with authors") {
    val json = ujson
      .Obj(
        "source_id" -> "openalex:W999",
        "title"     -> "Test Title",
        "year"      -> 2023,
        "authors" -> ujson.Arr(
          ujson.Obj(
            "name"        -> "Test Author",
            "openalex_id" -> "A123"
          )
        )
      )
      .toString()

    val result =
      OpenAlexParser.parse(json, runId, schemaVer, ts)
    result.isRight shouldBe true
    result.toOption.get._1.doi shouldBe None
  }

  test("parse empty authors list returns Left with MISSING_AUTHORS") {
    val json = ujson
      .Obj(
        "source_id" -> "openalex:W998",
        "title"     -> "No Authors Paper",
        "year"      -> 2023,
        "authors"   -> ujson.Arr()
      )
      .toString()

    val result =
      OpenAlexParser.parse(json, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MISSING_AUTHORS"
  }

  test("parse missing authors field returns Left with MISSING_AUTHORS") {
    val json = ujson
      .Obj(
        "source_id" -> "openalex:W997",
        "title"     -> "No Authors Field",
        "year"      -> 2023
      )
      .toString()

    val result =
      OpenAlexParser.parse(json, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MISSING_AUTHORS"
  }

  test("parse malformed JSON returns Left") {
    val result =
      OpenAlexParser.parse("{bad json", runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MALFORMED_JSON"
  }

  test("parse missing title returns Left") {
    val json = ujson
      .Obj(
        "source_id" -> "openalex:W999",
        "year"      -> 2023
      )
      .toString()

    val result =
      OpenAlexParser.parse(json, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MISSING_TITLE"
  }

  test("parse missing source_id returns Left") {
    val json = ujson
      .Obj(
        "title" -> "Test",
        "year"  -> 2023
      )
      .toString()

    val result =
      OpenAlexParser.parse(json, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MISSING_SOURCE_ID"
  }

  test("parse preserves provenance") {
    val json = ujson
      .Obj(
        "source_id" -> "openalex:W123",
        "title"     -> "Test",
        "year"      -> 2023,
        "authors" -> ujson.Arr(
          ujson.Obj(
            "name"        -> "Test Author",
            "openalex_id" -> "A456"
          )
        )
      )
      .toString()

    val result =
      OpenAlexParser.parse(json, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (work, _) = result.toOption.get
    work.provenance.sourceDataset shouldBe "openalex"
    work.provenance.pipelineRunId shouldBe runId
  }
