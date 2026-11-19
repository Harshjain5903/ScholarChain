package edu.scholarchain.hadoop.parsers

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class OrcidParserSpec extends AnyFunSuite with Matchers:

  private val runId     = "test-run-001"
  private val schemaVer = "1.0.0"
  private val ts        = "2024-01-01T00:00:00Z"

  test("parse valid ORCID researcher") {
    val json = ujson
      .Obj(
        "source_id"     -> "orcid:0000-0003-4567-8901",
        "orcid"         -> "0000-0003-4567-8901",
        "name"          -> "Liang Chen",
        "given_name"    -> "Liang",
        "family_name"   -> "Chen",
        "name_variants" -> ujson.Arr("L. Chen", "Liang C."),
        "affiliations" -> ujson.Arr(
          ujson.Obj(
            "organization" -> "Westfield Institute",
            "department"   -> "CS",
            "role"         -> "Prof",
            "start_year"   -> 2018,
            "end_year"     -> ujson.Null
          )
        )
      )
      .toString()

    val result = OrcidParser.parse(json, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (mention, researcher) = result.toOption.get

    mention.orcid shouldBe Some("0000-0003-4567-8901")
    mention.rawName shouldBe "Liang Chen"
    mention.affiliationRaw shouldBe Some("Westfield Institute")

    researcher.displayName shouldBe "Liang Chen"
    researcher.orcid shouldBe Some("0000-0003-4567-8901")
    researcher.alternateNames shouldBe Vector("L. Chen", "Liang C.")
    researcher.researcherId should startWith("researcher:orcid:")
  }

  test("parse invalid ORCID format returns Left") {
    val json = ujson
      .Obj(
        "source_id" -> "orcid:invalid",
        "orcid"     -> "1234-5678",
        "name"      -> "Test Person"
      )
      .toString()

    val result = OrcidParser.parse(json, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "INVALID_ORCID"
  }

  test("parse missing name returns Left") {
    val json = ujson
      .Obj(
        "source_id" -> "orcid:0000-0001-2345-6789",
        "orcid"     -> "0000-0001-2345-6789"
      )
      .toString()

    val result = OrcidParser.parse(json, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MISSING_NAME"
  }

  test("parse malformed JSON returns Left") {
    val result =
      OrcidParser.parse("{not valid", runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MALFORMED_JSON"
  }

  test("parse preserves provenance") {
    val json = ujson
      .Obj(
        "source_id" -> "orcid:0000-0002-9876-5432",
        "orcid"     -> "0000-0002-9876-5432",
        "name"      -> "Minh Nguyen"
      )
      .toString()

    val result = OrcidParser.parse(json, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (mention, _) = result.toOption.get
    mention.provenance.sourceDataset shouldBe "orcid"
    mention.provenance.pipelineRunId shouldBe runId
  }
