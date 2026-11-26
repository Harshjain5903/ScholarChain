package edu.scholarchain.hadoop.parsers

import edu.scholarchain.core.SourceType
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.io.Source

/**
 * The parsers read the RAW shapes of the fetched datasets. The Crossref and OpenAlex fixtures under
 * `src/test/resources/real/` are real API records (public bibliographic metadata; Crossref
 * reference lists and OpenAlex bulky fields such as abstract indexes were removed to keep them
 * small). No real ORCID record has been fetched yet, so the ORCID fixture is built from the
 * documented v3.0 `/record` shape with an invented person.
 */
class RealShapeParsersSpec extends AnyFunSuite with Matchers:

  private val ts = "2026-11-24T00:00:00Z"

  private def resource(name: String): String =
    val s = Source.fromResource(s"real/$name", getClass.getClassLoader)
    try s.mkString
    finally s.close()

  test("real Crossref work: message wrapper, title array, issued date, author ORCID URLs") {
    val (work, mentions) =
      CrossrefParser.parse(resource("crossref-work.json"), "r-cr", "1.0.0", ts).toOption.get
    work.source shouldBe SourceType.Crossref
    work.sourceRecordId shouldBe "crossref:10.1109/tfuzz.2021.3050857"
    work.workId shouldBe "work:crossref:10.1109/tfuzz.2021.3050857"
    work.doi shouldBe Some("10.1109/tfuzz.2021.3050857")
    work.year shouldBe 2022
    work.title should startWith("Functional Fuzzy System")
    work.venueId shouldBe Some("ieee transactions on fuzzy systems")
    mentions.map(_.rawName) shouldBe Vector("Dongjiao Ge", "Xiao-Jun Zeng")
    mentions.flatMap(_.orcid) shouldBe Vector("0000-0003-2302-6695", "0000-0002-2320-2495")
    mentions.head.affiliationRaw.exists(_.contains("Oxford")) shouldBe true
    work.provenance.sourceDataset shouldBe "crossref"
    work.provenance.transformationStep shouldBe Some("crossref-normalization")
    work.provenance.producer shouldBe Some("hadoop/J02c-crossref-normalization")
  }

  test("real Crossref work without authors is rejected with MISSING_AUTHORS and a DOI source id") {
    val raw = resource("crossref-no-author.json")
    CrossrefParser.parse(raw, "r", "1.0.0", ts).left.toOption.get._1 shouldBe "MISSING_AUTHORS"
    CrossrefParser.flatten(ujson.read(raw).obj)("source_id").str should startWith("crossref:10.")
  }

  test("the flat Crossref shape and a bare (unwrapped) work are still accepted") {
    val bare = ujson.read(resource("crossref-work.json"))("message").toString
    CrossrefParser.parse(bare, "r", "1.0.0", ts).isRight shouldBe true
  }

  test("real OpenAlex chunk splits into its works and each parses") {
    val parts = OpenAlexParser.splitRecords(resource("openalex-chunk.json"))
    parts.size shouldBe 2
    val parsed   = parts.map(OpenAlexParser.parse(_, "r-oa", "1.0.0", ts).toOption.get)
    val (w1, m1) = parsed.head
    w1.source shouldBe SourceType.OpenAlex
    w1.workId shouldBe "work:openalex:W3157926221"
    w1.sourceRecordId shouldBe "openalex:W3157926221"
    w1.doi shouldBe Some("10.1002/aisy.202000275")
    w1.year shouldBe 2021
    w1.venueId shouldBe Some("advanced intelligent systems")
    m1.map(_.openAlexId.get) shouldBe Vector("A5019610247", "A5030447242", "A5004070252")
    m1.flatMap(_.orcid) shouldBe Vector(
      "0000-0002-0221-1560",
      "0000-0001-6036-7371",
      "0000-0002-3079-8880"
    )
    m1.head.affiliationRaw shouldBe Some("University of Birmingham")
    parsed(1)._2.head.orcid shouldBe None
    w1.provenance.producer shouldBe Some("hadoop/J02a-openalex-normalization")
  }

  test("OpenAlex splitRecords leaves a single work and unparseable text untouched") {
    OpenAlexParser.splitRecords("""{"source_id":"openalex:W1"}""").size shouldBe 1
    OpenAlexParser.splitRecords("not json") shouldBe Vector("not json")
    OpenAlexParser.splitRecords("""{"results":[]}""") shouldBe Vector.empty
  }

  private val orcidRecord =
    """{"orcid-identifier":{"uri":"https://orcid.org/0000-0002-1825-0097","path":"0000-0002-1825-0097","host":"orcid.org"},
      | "person":{"name":{"given-names":{"value":"Josiah"},"family-name":{"value":"Carberry"},"credit-name":null},
      |  "other-names":{"other-name":[{"content":"J. Carberry"}]}},
      | "activities-summary":{"employments":{"affiliation-group":[{"summaries":[
      |  {"employment-summary":{"organization":{"name":"Brown University"}}}]}]}}}""".stripMargin

  test("ORCID v3 /record shape is adapted") {
    val (mention, researcher) = OrcidParser.parse(orcidRecord, "r-or", "1.0.0", ts).toOption.get
    mention.orcid shouldBe Some("0000-0002-1825-0097")
    mention.rawName shouldBe "Josiah Carberry"
    mention.affiliationRaw shouldBe Some("Brown University")
    researcher.alternateNames shouldBe Vector("J. Carberry")
    mention.provenance.sourceRecordId shouldBe "orcid:0000-0002-1825-0097"
    mention.provenance.producer shouldBe Some("hadoop/J02b-orcid-normalization")
  }

  test("ORCID record with a private name is rejected MISSING_NAME") {
    val priv =
      """{"orcid-identifier":{"path":"0000-0002-1825-0097"},"person":{"name":null}}"""
    OrcidParser.parse(priv, "r", "1.0.0", ts).left.toOption.get._1 shouldBe "MISSING_NAME"
  }
