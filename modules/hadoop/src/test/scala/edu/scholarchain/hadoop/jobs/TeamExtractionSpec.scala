package edu.scholarchain.hadoop.jobs

import edu.scholarchain.core.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class TeamExtractionSpec extends AnyFunSuite with Matchers:

  private val prov = Provenance(
    sourceDataset = "test",
    sourceRecordId = "test:1",
    pipelineRunId = "run-1",
    schemaVersion = "1.0.0",
    timestamp = "2024-01-01T00:00:00Z"
  )

  private def mkMention(
    name: String,
    pos: Int
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
      provenance = prov
    )

  private def mkWork(
    authors: Vector[AuthorMention]
  ): WorkRecord =
    WorkRecord(
      workId = "work:test:1",
      source = SourceType.DBLP,
      sourceRecordId = "test:1",
      title = "Test Title",
      normalizedTitle = "test title",
      year = 2023,
      venueId = None,
      doi = None,
      arxivId = None,
      authors = authors,
      abstractTextRef = None,
      provenance = prov
    )

  test("extractTeam standard team") {
    val work = mkWork(
      Vector(
        mkMention("Alice", 0),
        mkMention("Bob", 1),
        mkMention("Carol", 2)
      )
    )
    val team = TeamExtractionJob.extractTeam(
      work,
      "run-1",
      "1.0.0",
      "2024-01-01T00:00:00Z"
    )
    team.workId shouldBe "work:test:1"
    team.year shouldBe 2023
    team.teamSize shouldBe 3
    team.authorMentions.size shouldBe 3
    team.authorMentions(0).rawName shouldBe "Alice"
    team.authorMentions(2).rawName shouldBe "Carol"
  }

  test("extractTeam single author") {
    val work = mkWork(Vector(mkMention("Solo Author", 0)))
    val team = TeamExtractionJob.extractTeam(
      work,
      "run-1",
      "1.0.0",
      "2024-01-01T00:00:00Z"
    )
    team.teamSize shouldBe 1
  }

  test("extractTeam preserves author order") {
    val work = mkWork(
      Vector(
        mkMention("First", 0),
        mkMention("Second", 1),
        mkMention("Third", 2)
      )
    )
    val team = TeamExtractionJob.extractTeam(
      work,
      "run-1",
      "1.0.0",
      "2024-01-01T00:00:00Z"
    )
    team.authorMentions.map(_.rawName) shouldBe Vector(
      "First",
      "Second",
      "Third"
    )
  }

  test("authorPaperJson includes provenance fields") {
    val mention = mkMention("Alice", 0)
    val json = TeamExtractionJob.authorPaperJson(
      mention,
      "work:test:1",
      "my-run",
      "1.0.0",
      "2024-01-01T00:00:00Z",
      "dblp",
      "test:1"
    )
    val parsed = ujson.read(json)
    parsed("mention_id").str shouldBe mention.mentionId
    parsed("work_id").str shouldBe "work:test:1"
    parsed("position").num shouldBe 0.0
    val prov = parsed("provenance")
    prov("sourceDataset").str shouldBe "dblp"
    prov("sourceRecordId").str shouldBe "test:1"
    prov("pipelineRunId").str shouldBe "my-run"
    prov("schemaVersion").str shouldBe "1.0.0"
    prov("timestamp").str shouldBe "2024-01-01T00:00:00Z"
  }

  test("extractTeam provenance") {
    val work = mkWork(Vector(mkMention("Author", 0)))
    val team = TeamExtractionJob.extractTeam(
      work,
      "my-run",
      "2.0.0",
      "2024-06-15T12:00:00Z"
    )
    team.provenance.pipelineRunId shouldBe "my-run"
    team.provenance.schemaVersion shouldBe "2.0.0"
  }
