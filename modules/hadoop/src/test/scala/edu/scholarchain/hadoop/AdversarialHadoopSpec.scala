package edu.scholarchain.hadoop

import edu.scholarchain.core.*
import edu.scholarchain.hadoop.jobs.PairExpansionJob
import edu.scholarchain.hadoop.parsers.{CrossrefParser, DblpParser, OpenAlexParser, OrcidParser}
import edu.scholarchain.hadoop.util.NormalizationUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AdversarialHadoopSpec extends AnyFlatSpec with Matchers:

  private val runId     = "adv-test-run"
  private val schemaVer = "1.0.0"
  private val ts        = "2026-01-01T00:00:00Z"

  // --- 1. Malformed XML with unclosed tags ---

  "DblpParser" should "reject malformed XML with unclosed tags" in {
    val xml =
      """<article key="test/unclosed1">
        |<author>Test Author</author>
        |<title>Unclosed Title
        |<year>2023</year>
        |</article>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MALFORMED_XML"
  }

  // --- 2. Truncated XML mid-record ---

  it should "reject truncated XML that cuts off mid-record" in {
    val xml =
      """<article key="test/truncated1">
        |<author>Test Author</au""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MALFORMED_XML"
  }

  // --- 3. Unicode normalization edge cases ---

  it should "normalize accented author names via NFKD decomposition" in {
    val xml =
      """<article key="test/unicode1">
        |<author>Jos&#233; Garc&#237;a-L&#243;pez</author>
        |<title>Diacritics in Names</title>
        |<year>2023</year>
        |</article>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (_, mentions, _) = result.toOption.get
    // The normalized name should have stripped combining marks
    val normalizedName = mentions.head.normalizedName
    normalizedName should not contain '́' // acute accent
    normalizedName should not contain '̀' // grave accent
  }

  it should "handle CJK characters in titles without crashing" in {
    val xml =
      """<article key="test/cjk1">
        |<author>Wei Wang</author>
        |<title>&#x5B66;&#x672F;&#x77E5;&#x8BC6;&#x56FE;&#x8C31;</title>
        |<year>2023</year>
        |</article>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (work, _, _) = result.toOption.get
    work.title should not be empty
  }

  // --- 4. Missing required fields ---

  it should "reject record with no title" in {
    val xml =
      """<article key="test/notitle">
        |<author>Author A</author>
        |<title>   </title>
        |<year>2023</year>
        |</article>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MISSING_TITLE"
  }

  // --- 5. Empty author list ---

  it should "reject record with zero authors" in {
    val xml =
      """<article key="test/noauthors">
        |<title>Orphan Paper</title>
        |<year>2020</year>
        |</article>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MISSING_AUTHORS"
  }

  // --- 6. XML entity declarations and special characters ---

  it should "handle XML entities like &amp; and &lt; in titles" in {
    val xml =
      """<article key="test/entities1">
        |<author>Author A</author>
        |<title>Graphs &amp; Networks: A &lt;Study&gt;</title>
        |<year>2023</year>
        |</article>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (work, _, _) = result.toOption.get
    work.title shouldBe "Graphs & Networks: A <Study>"
  }

  // --- 7. Very large author team (50+ authors) ---

  "PairExpansionJob" should "generate n*(n-1)/2 pairs for a large team of 50 authors" in {
    val n = 50
    val prov = Provenance(
      sourceDataset = "test",
      sourceRecordId = "test:large-team",
      pipelineRunId = runId,
      schemaVersion = schemaVer,
      timestamp = ts
    )

    val mentions = (0 until n).map { i =>
      AuthorMention(
        mentionId = s"mention:test:large:$i",
        rawName = s"Author $i",
        normalizedName = s"author $i",
        position = i,
        orcid = None,
        openAlexId = None,
        affiliationRaw = None,
        sourceRecordId = "test:large-team",
        provenance = prov
      )
    }.toVector

    val team = PublicationTeam(
      workId = "work:test:large",
      year = 2023,
      authorMentions = mentions,
      teamSize = n,
      provenance = prov
    )

    val pairs         = PairExpansionJob.expandPairs(team, runId, schemaVer, ts)
    val expectedCount = n * (n - 1) / 2
    pairs.size shouldBe expectedCount
  }

  // --- 8. Duplicate DOI across sources ---

  "CrossrefParser and OpenAlexParser" should "produce records with same normalized DOI from different sources" in {
    val crossrefJson = ujson.Obj(
      "source_id" -> "crossref:rec1",
      "doi"       -> "https://doi.org/10.1234/EXAMPLE.001",
      "title"     -> "Shared Paper Title",
      "year"      -> 2023,
      "authors" -> ujson.Arr(
        ujson.Obj("given_name" -> "Alice", "family_name" -> "Smith")
      )
    )

    val openAlexJson = ujson.Obj(
      "source_id" -> "openalex:W999",
      "doi"       -> "10.1234/example.001",
      "title"     -> "Shared Paper Title",
      "year"      -> 2023,
      "authors" -> ujson.Arr(
        ujson.Obj("name" -> "Alice Smith")
      )
    )

    val crossrefResult =
      CrossrefParser.parse(ujson.write(crossrefJson), runId, schemaVer, ts)
    val openAlexResult =
      OpenAlexParser.parse(ujson.write(openAlexJson), runId, schemaVer, ts)

    crossrefResult.isRight shouldBe true
    openAlexResult.isRight shouldBe true

    val crossrefDoi = crossrefResult.toOption.get._1.doi
    val openAlexDoi = openAlexResult.toOption.get._1.doi

    // Both should normalize to the same DOI
    crossrefDoi shouldBe openAlexDoi
    crossrefDoi shouldBe Some("10.1234/example.001")
  }

  // --- 9. Invalid year format ---

  "DblpParser" should "reject record with non-numeric year" in {
    val xml =
      """<article key="test/badyear">
        |<author>Author A</author>
        |<title>Bad Year Paper</title>
        |<year>TBD</year>
        |</article>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "INVALID_YEAR"
  }

  // --- 10. Unsupported element type ---

  it should "reject unsupported XML element types" in {
    val xml =
      """<tutorial key="test/tutorial1">
        |<author>Author A</author>
        |<title>Tutorial Title</title>
        |<year>2023</year>
        |</tutorial>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "UNSUPPORTED_TYPE"
  }
