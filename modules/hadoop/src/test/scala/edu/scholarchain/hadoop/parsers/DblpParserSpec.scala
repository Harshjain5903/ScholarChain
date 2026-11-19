package edu.scholarchain.hadoop.parsers

import edu.scholarchain.core.SourceType
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class DblpParserSpec extends AnyFunSuite with Matchers:

  private val runId     = "test-run-001"
  private val schemaVer = "1.0.0"
  private val ts        = "2024-01-01T00:00:00Z"

  test("parse valid article XML") {
    val xml =
      """<article key="journals/tse/ChenWilliams2023">
        |<author>Liang Chen</author>
        |<author>Rebecca Williams</author>
        |<title>Scalable Graph Partitioning</title>
        |<journal>IEEE TSE</journal>
        |<year>2023</year>
        |<ee>https://doi.org/10.1109/TSE.2023.0001234</ee>
        |</article>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (work, mentions, recordType) = result.toOption.get

    recordType shouldBe "article"
    work.source shouldBe SourceType.DBLP
    work.sourceRecordId shouldBe "dblp:journals/tse/ChenWilliams2023"
    work.title shouldBe "Scalable Graph Partitioning"
    work.year shouldBe 2023
    work.doi shouldBe Some("10.1109/tse.2023.0001234")
    work.authors.size shouldBe 2
    mentions.size shouldBe 2
    mentions(0).rawName shouldBe "Liang Chen"
    mentions(0).position shouldBe 0
    mentions(1).rawName shouldBe "Rebecca Williams"
    mentions(1).position shouldBe 1
  }

  test("parse valid inproceedings XML") {
    val xml =
      """<inproceedings key="conf/sigmod/PatelNguyen2022">
        |<author>Ananya Patel</author>
        |<author>Minh Nguyen</author>
        |<title>Adaptive Index Structures</title>
        |<booktitle>SIGMOD Conference</booktitle>
        |<year>2022</year>
        |</inproceedings>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (work, _, recordType) = result.toOption.get
    recordType shouldBe "inproceedings"
    work.year shouldBe 2022
    work.venueId shouldBe Some("sigmod conference")
  }

  test("parse malformed XML returns Left") {
    val xml    = "<article>not closed"
    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MALFORMED_XML"
  }

  test("parse missing year returns Left") {
    val xml =
      """<article key="test/key1">
        |<author>Test Author</author>
        |<title>Some Title</title>
        |</article>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MISSING_YEAR"
  }

  test("parse missing title returns Left") {
    val xml =
      """<article key="test/key2">
        |<author>Test Author</author>
        |<year>2023</year>
        |</article>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MISSING_TITLE"
  }

  test("parse missing authors returns Left") {
    val xml =
      """<article key="test/key3">
        |<title>Some Title</title>
        |<year>2023</year>
        |</article>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MISSING_AUTHORS"
  }

  test("parse preserves provenance") {
    val xml =
      """<article key="journals/test/Key2023">
        |<author>Test Author</author>
        |<title>Test Title</title>
        |<year>2023</year>
        |</article>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (work, _, _) = result.toOption.get
    work.provenance.sourceDataset shouldBe "dblp"
    work.provenance.pipelineRunId shouldBe runId
    work.provenance.schemaVersion shouldBe schemaVer
  }

  test("parse book XML returns book record type") {
    val xml =
      """<book key="books/acm/Kim95">
        |<author>Won Kim</author>
        |<title>Modern Database Systems</title>
        |<year>1995</year>
        |</book>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (_, _, recordType) = result.toOption.get
    recordType shouldBe "book"
  }

  test("parse proceedings XML returns proceedings record type") {
    val xml =
      """<proceedings key="conf/sigmod/2023">
        |<author>Editor Name</author>
        |<title>Proceedings of SIGMOD 2023</title>
        |<year>2023</year>
        |</proceedings>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (_, _, recordType) = result.toOption.get
    recordType shouldBe "proceedings"
  }

  test("parse incollection XML returns incollection record type") {
    val xml =
      """<incollection key="books/acm/Kim95/chapter1">
        |<author>Chapter Author</author>
        |<title>A Chapter Title</title>
        |<booktitle>Modern Database Systems</booktitle>
        |<year>1995</year>
        |</incollection>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (_, _, recordType) = result.toOption.get
    recordType shouldBe "incollection"
  }

  test("parse phdthesis XML returns phdthesis record type") {
    val xml =
      """<phdthesis key="phd/Smith2020">
        |<author>Jane Smith</author>
        |<title>Advances in Graph Theory</title>
        |<year>2020</year>
        |</phdthesis>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (_, _, recordType) = result.toOption.get
    recordType shouldBe "phdthesis"
  }

  test("parse mastersthesis XML returns mastersthesis record type") {
    val xml =
      """<mastersthesis key="ms/Jones2021">
        |<author>Bob Jones</author>
        |<title>An Introduction to Clustering</title>
        |<year>2021</year>
        |</mastersthesis>""".stripMargin

    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (_, _, recordType) = result.toOption.get
    recordType shouldBe "mastersthesis"
  }

  test("counterForRecordType maps all 7 types correctly") {
    import edu.scholarchain.hadoop.jobs.DblpExtractionJob
    import edu.scholarchain.hadoop.counters.HadoopCounters

    DblpExtractionJob.counterForRecordType("article") shouldBe
      HadoopCounters.ArticleCount
    DblpExtractionJob.counterForRecordType("inproceedings") shouldBe
      HadoopCounters.InproceedingsCount
    DblpExtractionJob.counterForRecordType("proceedings") shouldBe
      HadoopCounters.ProceedingsCount
    DblpExtractionJob.counterForRecordType("book") shouldBe
      HadoopCounters.BookCount
    DblpExtractionJob.counterForRecordType("incollection") shouldBe
      HadoopCounters.IncollectionCount
    DblpExtractionJob.counterForRecordType("phdthesis") shouldBe
      HadoopCounters.PhdthesisCount
    DblpExtractionJob.counterForRecordType("mastersthesis") shouldBe
      HadoopCounters.MastersthesisCount
  }

  test("parse generates deterministic work IDs") {
    val xml =
      """<article key="journals/test/Det2023">
        |<author>Author A</author>
        |<title>Deterministic Test</title>
        |<year>2023</year>
        |</article>""".stripMargin

    val result1    = DblpParser.parse(xml, runId, schemaVer, ts)
    val result2    = DblpParser.parse(xml, runId, schemaVer, ts)
    val (w1, _, _) = result1.toOption.get
    val (w2, _, _) = result2.toOption.get
    w1.workId shouldBe w2.workId
  }
