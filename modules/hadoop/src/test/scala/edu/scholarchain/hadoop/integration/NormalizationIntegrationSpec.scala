package edu.scholarchain.hadoop.integration

import edu.scholarchain.core.SourceType
import edu.scholarchain.hadoop.parsers.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Integration tests that parse the Phase 3 sample files from datasets/samples/ and verify field
 * mapping end-to-end.
 */
class NormalizationIntegrationSpec extends AnyFunSuite with Matchers:

  private val runId     = "integration-run"
  private val schemaVer = "1.0.0"
  private val ts        = "2024-01-01T00:00:00Z"

  // -- DBLP integration (using inline XML matching sample data) --

  test("DBLP: parse sample article record end-to-end") {
    val xml =
      """<article key="journals/tse/ChenWilliams2023">
        |<author>Liang Chen</author>
        |<author>Rebecca Williams</author>
        |<title>Scalable Graph Partitioning for Distributed Knowledge Bases</title>
        |<journal>IEEE Transactions on Software Engineering</journal>
        |<year>2023</year>
        |<ee>https://doi.org/10.1109/TSE.2023.0001234</ee>
        |</article>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (work, mentions, _) = result.toOption.get

    work.source shouldBe SourceType.DBLP
    work.title shouldBe "Scalable Graph Partitioning for Distributed Knowledge Bases"
    work.year shouldBe 2023
    work.doi shouldBe Some("10.1109/tse.2023.0001234")
    work.venueId shouldBe Some(
      "ieee transactions on software engineering"
    )
    mentions.size shouldBe 2
    mentions(0).rawName shouldBe "Liang Chen"
    mentions(1).rawName shouldBe "Rebecca Williams"
    work.provenance.sourceDataset shouldBe "dblp"
  }

  // -- OpenAlex integration --

  test("OpenAlex: parse sample record end-to-end") {
    val json = ujson
      .Obj(
        "source_id" -> "openalex:W3194827561",
        "doi"       -> "10.1109/TSE.2023.0001234",
        "title" ->
          "Scalable Graph Partitioning for Distributed Knowledge Bases",
        "year" -> 2023,
        "authors" -> ujson.Arr(
          ujson.Obj(
            "name"        -> "Liang Chen",
            "openalex_id" -> "A4012983745",
            "orcid"       -> "0000-0003-4567-8901",
            "position"    -> "first",
            "affiliations" -> ujson.Arr(
              ujson.Obj("name" -> "Westfield Institute of Technology")
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
        "venue" -> ujson.Obj(
          "name" -> "IEEE Transactions on Software Engineering"
        )
      )
      .toString()

    val result =
      OpenAlexParser.parse(json, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (work, mentions) = result.toOption.get

    work.source shouldBe SourceType.OpenAlex
    work.doi shouldBe Some("10.1109/tse.2023.0001234")
    mentions.size shouldBe 2
    mentions(0).orcid shouldBe Some("0000-0003-4567-8901")
    mentions(0).openAlexId shouldBe Some("A4012983745")
    mentions(0).affiliationRaw shouldBe Some(
      "Westfield Institute of Technology"
    )
  }

  // -- ORCID integration --

  test("ORCID: parse sample record end-to-end") {
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
            "organization" -> "Westfield Institute of Technology",
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
    researcher.orcid shouldBe Some("0000-0003-4567-8901")
    researcher.alternateNames should contain("L. Chen")
  }

  // -- Crossref integration --

  test("Crossref: parse sample record end-to-end") {
    val json = ujson
      .Obj(
        "source_id" -> "crossref:10.1109/TSE.2023.0001234",
        "doi"       -> "10.1109/TSE.2023.0001234",
        "title" ->
          "Scalable Graph Partitioning for Distributed Knowledge Bases",
        "year" -> 2023,
        "authors" -> ujson.Arr(
          ujson.Obj(
            "given_name"  -> "Liang",
            "family_name" -> "Chen",
            "orcid"       -> "0000-0003-4567-8901",
            "affiliations" -> ujson.Arr(
              "Westfield Institute of Technology"
            )
          ),
          ujson.Obj(
            "given_name"  -> "Rebecca",
            "family_name" -> "Williams",
            "orcid"       -> ujson.Null,
            "affiliations" -> ujson.Arr(
              "Northgate University"
            )
          )
        ),
        "venue" -> ujson.Obj(
          "name"      -> "IEEE Transactions on Software Engineering",
          "publisher" -> "IEEE"
        )
      )
      .toString()

    val result =
      CrossrefParser.parse(json, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (work, mentions) = result.toOption.get

    work.source shouldBe SourceType.Crossref
    work.doi shouldBe Some("10.1109/tse.2023.0001234")
    mentions.size shouldBe 2
    mentions(0).rawName shouldBe "Liang Chen"
    mentions(0).orcid shouldBe Some("0000-0003-4567-8901")
  }

  // -- Reject handling --

  test("DBLP: malformed XML produces reject") {
    val result = DblpParser.parse(
      "<article>unclosed",
      runId,
      schemaVer,
      ts
    )
    result.isLeft shouldBe true
  }

  test("OpenAlex: malformed JSON produces reject") {
    val result = OpenAlexParser.parse(
      "{not valid json",
      runId,
      schemaVer,
      ts
    )
    result.isLeft shouldBe true
  }

  test("ORCID: invalid ORCID format produces reject") {
    val json = ujson
      .Obj(
        "source_id" -> "orcid:bad",
        "orcid"     -> "1234",
        "name"      -> "Test"
      )
      .toString()
    val result = OrcidParser.parse(json, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "INVALID_ORCID"
  }

  test("Crossref: missing title produces reject") {
    val json = ujson
      .Obj(
        "source_id" -> "crossref:10.1234/test",
        "doi"       -> "10.1234/test",
        "year"      -> 2023
      )
      .toString()
    val result = CrossrefParser.parse(json, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MISSING_TITLE"
  }
