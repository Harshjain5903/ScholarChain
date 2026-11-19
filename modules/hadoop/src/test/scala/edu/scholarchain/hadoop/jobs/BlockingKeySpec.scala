package edu.scholarchain.hadoop.jobs

import edu.scholarchain.core.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class BlockingKeySpec extends AnyFunSuite with Matchers:

  private val prov = Provenance(
    sourceDataset = "test",
    sourceRecordId = "test:1",
    pipelineRunId = "run-1",
    schemaVersion = "1.0.0",
    timestamp = "2024-01-01T00:00:00Z",
  )

  private def mkMention(
      name: String,
      pos: Int,
  ): AuthorMention =
    AuthorMention(
      mentionId = s"mention:test:1:$pos",
      rawName = name,
      normalizedName = name.toLowerCase,
      position = pos,
      orcid = None,
      openAlexId = None,
      affiliationRaw = None,
      sourceRecordId = "test:1",
      provenance = prov,
    )

  private def mkWork(
      authors: Vector[AuthorMention],
      title: String = "Test Title",
      venue: Option[String] = None,
      year: Int = 2023,
  ): WorkRecord =
    WorkRecord(
      workId = "work:test:1",
      source = SourceType.DBLP,
      sourceRecordId = "test:1",
      title = title,
      normalizedTitle = title.toLowerCase,
      year = year,
      venueId = venue,
      doi = None,
      arxivId = None,
      authors = authors,
      abstractTextRef = None,
      provenance = prov,
    )

  test("generates author surname+initial blocking keys") {
    val work = mkWork(
      authors = Vector(
        mkMention("Liang Chen", 0),
        mkMention("Rebecca Williams", 1),
      )
    )
    val keys = BlockingJob.generateBlockingKeys(work)
    keys should contain("author:chen:l")
    keys should contain("author:williams:r")
  }

  test("generates coauthor surname pair blocking keys") {
    val work = mkWork(
      authors = Vector(
        mkMention("Liang Chen", 0),
        mkMention("Rebecca Williams", 1),
      )
    )
    val keys = BlockingJob.generateBlockingKeys(work)
    keys should contain("coauth:chen:williams")
  }

  test("generates title token blocking keys") {
    val work = mkWork(
      authors = Vector(mkMention("Author A", 0)),
      title = "Scalable Graph Partitioning for Distributed Systems",
    )
    val keys = BlockingJob.generateBlockingKeys(work)
    keys.exists(_.startsWith("title:")) shouldBe true
  }

  test("generates year+venue blocking keys") {
    val work = mkWork(
      authors = Vector(mkMention("Author A", 0)),
      venue = Some("ieee tse"),
      year = 2023,
    )
    val keys = BlockingJob.generateBlockingKeys(work)
    keys should contain("yearvenue:2023:ieee tse")
  }

  test("single author no coauthor keys") {
    val work = mkWork(
      authors = Vector(mkMention("Solo Author", 0))
    )
    val keys = BlockingJob.generateBlockingKeys(work)
    keys.exists(_.startsWith("coauth:")) shouldBe false
  }

  test("blocking keys are distinct") {
    val work = mkWork(
      authors = Vector(
        mkMention("John Smith", 0),
        mkMention("John Smith", 1),
      )
    )
    val keys = BlockingJob.generateBlockingKeys(work)
    keys shouldBe keys.distinct
  }

  test("common names produce expected keys") {
    val work = mkWork(
      authors = Vector(
        mkMention("John Smith", 0),
        mkMention("Jane Doe", 1),
        mkMention("Bob Johnson", 2),
      )
    )
    val keys = BlockingJob.generateBlockingKeys(work)
    keys should contain("author:smith:j")
    keys should contain("author:doe:j")
    keys should contain("author:johnson:b")
    // Should have coauthor pairs
    keys.count(_.startsWith("coauth:")) shouldBe 3
  }
