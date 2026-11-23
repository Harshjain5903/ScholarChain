package edu.scholarchain.hadoop.parsers

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Regression tests for DTD-declared named entities in real DBLP records. Before the fix, 75 of the
 * first 500 records of the DBLP 10K development selection were rejected as MALFORMED_XML
 * ("The entity "oslash" was referenced, but not declared").
 */
class DblpEntitiesSpec extends AnyFunSuite with Matchers:

  private val runId     = "test-run-entities"
  private val schemaVer = "1.0.0"
  private val ts        = "2026-11-23T00:00:00Z"

  /** Verbatim DBLP record (CC0) that was rejected before the fix. */
  private val realRecord =
    """<inproceedings mdate="2021-05-19" key="conf/igarss/SobjaergS03a">
      |<author orcid="0000-0001-6914-8597">Sten Schmidl S&oslash;bj&aelig;rg</author>
      |<author>Niels Skou</author>
      |<title>An airborne campaign measuring wind signatures from the sea surface using an L-band polarimetric radiometer.</title>
      |<pages>2738-2740</pages>
      |<year>2003</year>
      |<booktitle>IGARSS</booktitle>
      |<ee>https://doi.org/10.1109/IGARSS.2003.1294569</ee>
      |<crossref>conf/igarss/2003</crossref>
      |<url>db/conf/igarss/igarss2003.html#SobjaergS03a</url>
      |</inproceedings>""".stripMargin

  test("entity table covers the 65 general entities declared by dblp.dtd") {
    DblpEntities.CodePoints.size shouldBe 65
    DblpEntities.CodePoints("eacute") shouldBe 233
    DblpEntities.CodePoints("Ouml") shouldBe 214
    DblpEntities.CodePoints("szlig") shouldBe 223
    DblpEntities.CodePoints("reg") shouldBe 174
    DblpEntities.CodePoints("micro") shouldBe 181
    DblpEntities.CodePoints("times") shouldBe 215
    DblpEntities.CodePoints("yuml") shouldBe 255
  }

  test("resolveNamedEntities rewrites DTD entities to numeric references") {
    DblpEntities.resolveNamedEntities("Jos&eacute; M&uuml;ller") shouldBe "Jos&#233; M&#252;ller"
  }

  test("resolveNamedEntities leaves XML predefined, numeric and unknown entities untouched") {
    val s = "a &amp; b &lt; c &#8734; &apos;x&apos; &quot;y&quot; &bogus;"
    DblpEntities.resolveNamedEntities(s) shouldBe s
  }

  test("resolveNamedEntities is the identity on strings without '&'") {
    DblpEntities.resolveNamedEntities("plain <b>text</b>") shouldBe "plain <b>text</b>"
  }

  test("real DBLP record with &oslash; and &aelig; parses (regression)") {
    val result = DblpParser.parse(realRecord, runId, schemaVer, ts)
    result.isRight shouldBe true
    val (work, mentions, recordType) = result.toOption.get
    recordType shouldBe "inproceedings"
    work.sourceRecordId shouldBe "dblp:conf/igarss/SobjaergS03a"
    work.year shouldBe 2003
    work.doi shouldBe Some("10.1109/igarss.2003.1294569")
    mentions.map(_.rawName) shouldBe Vector("Sten Schmidl Søbjærg", "Niels Skou")
  }

  test("entities in titles are decoded and normalized") {
    val xml =
      """<article key="journals/x/Test1">
        |<author>Fran&ccedil;ois Cr&eacute;peau</author>
        |<author>J&ouml;rg M&uuml;ller</author>
        |<title>Caf&eacute; &amp; Na&iuml;ve &times; Stra&szlig;e</title>
        |<journal>J</journal>
        |<year>2020</year>
        |</article>""".stripMargin
    val (work, mentions, _) = DblpParser.parse(xml, runId, schemaVer, ts).toOption.get
    work.title shouldBe "Café & Naïve × Straße"
    mentions(0).rawName shouldBe "François Crépeau"
    mentions(0).normalizedName shouldBe "francois crepeau"
    mentions(1).normalizedName shouldBe "jorg muller"
  }

  test("an entity not declared by dblp.dtd is still rejected as MALFORMED_XML") {
    val xml =
      """<article key="journals/x/Bad1">
        |<author>A &notdeclared; B</author>
        |<author>C D</author>
        |<title>T</title>
        |<year>2020</year>
        |</article>""".stripMargin
    val result = DblpParser.parse(xml, runId, schemaVer, ts)
    result.isLeft shouldBe true
    result.left.toOption.get._1 shouldBe "MALFORMED_XML"
  }
