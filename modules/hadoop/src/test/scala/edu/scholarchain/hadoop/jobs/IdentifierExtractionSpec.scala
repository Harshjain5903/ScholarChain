package edu.scholarchain.hadoop.jobs

import edu.scholarchain.core.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class IdentifierExtractionSpec extends AnyFunSuite with Matchers:

  private val prov = Provenance(
    sourceDataset = "test",
    sourceRecordId = "test:1",
    pipelineRunId = "run-1",
    schemaVersion = "1.0.0",
    timestamp = "2024-01-01T00:00:00Z",
  )

  private def mkWork(
      doi: Option[String] = None,
      arxivId: Option[String] = None,
      authors: Vector[AuthorMention] = Vector.empty,
  ): WorkRecord =
    WorkRecord(
      workId = "work:test:1",
      source = SourceType.DBLP,
      sourceRecordId = "test:1",
      title = "Test",
      normalizedTitle = "test",
      year = 2023,
      venueId = None,
      doi = doi,
      arxivId = arxivId,
      authors = authors,
      abstractTextRef = None,
      provenance = prov,
    )

  private def mkMention(
      orcid: Option[String] = None,
      openAlexId: Option[String] = None,
  ): AuthorMention =
    AuthorMention(
      mentionId = "mention:test:1:0",
      rawName = "Test Author",
      normalizedName = "test author",
      position = 0,
      orcid = orcid,
      openAlexId = openAlexId,
      affiliationRaw = None,
      sourceRecordId = "test:1",
      provenance = prov,
    )

  test("extract DOI identifier") {
    val work = mkWork(doi = Some("10.1109/TSE.2023.0001234"))
    val ids  = IdentifierExtractionJob.extractIdentifiers(work)
    ids should have size 1
    ids.head._1 shouldBe "doi:10.1109/tse.2023.0001234"
    ids.head._2 shouldBe "test:1"
  }

  test("extract arXiv identifier") {
    val work = mkWork(arxivId = Some("1234.56789"))
    val ids  = IdentifierExtractionJob.extractIdentifiers(work)
    ids should have size 1
    ids.head._1 shouldBe "arxiv:1234.56789"
  }

  test("extract ORCID from author") {
    val work = mkWork(
      authors = Vector(mkMention(orcid = Some("0000-0003-4567-8901")))
    )
    val ids = IdentifierExtractionJob.extractIdentifiers(work)
    ids.exists(_._1 == "orcid:0000-0003-4567-8901") shouldBe true
  }

  test("extract OpenAlex ID from author") {
    val work = mkWork(
      authors = Vector(mkMention(openAlexId = Some("A1234")))
    )
    val ids = IdentifierExtractionJob.extractIdentifiers(work)
    ids.exists(_._1 == "openalex:A1234") shouldBe true
  }

  test("no identifiers returns empty") {
    val work = mkWork()
    val ids  = IdentifierExtractionJob.extractIdentifiers(work)
    ids shouldBe empty
  }

  test("multiple identifiers from one record") {
    val work = mkWork(
      doi = Some("10.1234/test"),
      arxivId = Some("2301.00001"),
      authors = Vector(
        mkMention(
          orcid = Some("0000-0001-2345-6789"),
          openAlexId = Some("A999"),
        )
      ),
    )
    val ids = IdentifierExtractionJob.extractIdentifiers(work)
    ids should have size 4
  }
