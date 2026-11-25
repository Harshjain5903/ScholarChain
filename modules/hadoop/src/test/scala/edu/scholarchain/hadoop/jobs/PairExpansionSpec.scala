package edu.scholarchain.hadoop.jobs

import edu.scholarchain.core.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PairExpansionSpec extends AnyFunSuite with Matchers:

  private val prov = Provenance(
    sourceDataset = "test",
    sourceRecordId = "test:1",
    pipelineRunId = "run-1",
    schemaVersion = "1.0.0",
    timestamp = "2024-01-01T00:00:00Z"
  )

  private def mkMention(
    id: String,
    pos: Int
  ): AuthorMention =
    AuthorMention(
      mentionId = id,
      rawName = s"Author $pos",
      normalizedName = s"author $pos",
      position = pos,
      orcid = None,
      openAlexId = None,
      affiliationRaw = None,
      sourceRecordId = "test:1",
      provenance = prov
    )

  private def mkTeam(
    mentions: Vector[AuthorMention]
  ): PublicationTeam =
    PublicationTeam(
      workId = "work:test:1",
      year = 2023,
      authorMentions = mentions,
      teamSize = mentions.size,
      provenance = prov
    )

  test("2 authors produce 1 pair") {
    val team = mkTeam(
      Vector(mkMention("m:a", 0), mkMention("m:b", 1))
    )
    val pairs = PairExpansionJob.expandPairs(
      team,
      "run-1",
      "1.0.0",
      "ts"
    )
    pairs should have size 1
    // Verify canonical ordering
    pairs.head.mentionA should be < pairs.head.mentionB
  }

  test("3 authors produce 3 pairs") {
    val team = mkTeam(
      Vector(
        mkMention("m:a", 0),
        mkMention("m:b", 1),
        mkMention("m:c", 2)
      )
    )
    val pairs = PairExpansionJob.expandPairs(
      team,
      "run-1",
      "1.0.0",
      "ts"
    )
    pairs should have size 3
  }

  test("5 authors produce 10 pairs") {
    val mentions = (0 until 5).map { i =>
      mkMention(s"m:${('a' + i).toChar}", i)
    }.toVector
    val team = mkTeam(mentions)
    val pairs = PairExpansionJob.expandPairs(
      team,
      "run-1",
      "1.0.0",
      "ts"
    )
    pairs should have size 10
  }

  test("1 author produces 0 pairs") {
    val team = mkTeam(Vector(mkMention("m:solo", 0)))
    val pairs = PairExpansionJob.expandPairs(
      team,
      "run-1",
      "1.0.0",
      "ts"
    )
    pairs shouldBe empty
  }

  test("0 authors produces 0 pairs") {
    // Cannot create PublicationTeam with 0 authors and teamSize 0
    // because teamSize must equal authorMentions.size, so test
    // with an empty team:
    val team = PublicationTeam(
      workId = "work:test:1",
      year = 2023,
      authorMentions = Vector.empty,
      teamSize = 0,
      provenance = prov
    )
    val pairs = PairExpansionJob.expandPairs(
      team,
      "run-1",
      "1.0.0",
      "ts"
    )
    pairs shouldBe empty
  }

  test("pairs are lexicographically ordered (mentionA < mentionB)") {
    val team = mkTeam(
      Vector(
        mkMention("m:z", 0),
        mkMention("m:a", 1)
      )
    )
    val pairs = PairExpansionJob.expandPairs(
      team,
      "run-1",
      "1.0.0",
      "ts"
    )
    pairs should have size 1
    pairs.head.mentionA shouldBe "m:a"
    pairs.head.mentionB shouldBe "m:z"
  }

  test("pairs carry correct workId and year") {
    val team = mkTeam(
      Vector(mkMention("m:x", 0), mkMention("m:y", 1))
    )
    val pairs = PairExpansionJob.expandPairs(
      team,
      "run-1",
      "1.0.0",
      "ts"
    )
    pairs.head.workId shouldBe "work:test:1"
    pairs.head.year shouldBe 2023
  }

  test("100 authors produce 4950 pairs all canonically ordered") {
    val mentions = (0 until 100).map { i =>
      mkMention(s"m:author-${"%03d".format(i)}", i)
    }.toVector
    val team = mkTeam(mentions)
    val pairs = PairExpansionJob.expandPairs(
      team,
      "run-1",
      "1.0.0",
      "ts"
    )
    pairs should have size 4950
    // All pairs must be canonically ordered
    pairs.foreach { p =>
      withClue(s"pair (${p.mentionA}, ${p.mentionB})") {
        p.mentionA should be < p.mentionB
      }
    }
  }

  test("pairs carry provenance") {
    val team = mkTeam(
      Vector(mkMention("m:p", 0), mkMention("m:q", 1))
    )
    val pairs = PairExpansionJob.expandPairs(
      team,
      "my-run",
      "2.0.0",
      "2024-06-15"
    )
    pairs.head.provenance.pipelineRunId shouldBe "my-run"
    pairs.head.provenance.schemaVersion shouldBe "2.0.0"
  }

  test("N44: pairs carry the input team's source dataset, not the pipeline name") {
    val teamProv = prov.copy(
      sourceDataset = "dblp",
      sourceRecordId = "dblp:conf/x/K1",
      pipelineRunId = "upstream-run"
    )
    val team = mkTeam(Vector(mkMention("m:p", 0), mkMention("m:q", 1))).copy(provenance = teamProv)
    val pair = PairExpansionJob.expandPairs(team, "my-run", "1.0.0", "2026-11-23T00:00:00Z").head
    pair.provenance.sourceDataset shouldBe "dblp"
    pair.provenance.sourceDataset should not be "scholarchain"
    pair.provenance.sourceRecordId shouldBe "dblp:conf/x/K1"
    pair.provenance.pipelineRunId shouldBe "my-run"
    pair.provenance.timestamp shouldBe "2026-11-23T00:00:00Z"
    pair.provenance.transformationStep shouldBe Some("pair-expansion")
    pair.provenance.producer shouldBe Some("hadoop/J07-pair-expansion")
    pair.provenance.upstreamRunIds shouldBe Vector("upstream-run")
  }

  test("N56: pairs list upstream run IDs once and never their own run ID") {
    val teamProv = prov.copy(
      sourceDataset = "dblp",
      sourceRecordId = "dblp:conf/x/K1",
      pipelineRunId = "j06-run",
      upstreamRunIds = Vector("j01-run", "my-run")
    )
    val team = mkTeam(Vector(mkMention("m:p", 0), mkMention("m:q", 1))).copy(provenance = teamProv)
    val pair = PairExpansionJob.expandPairs(team, "my-run", "1.0.0", "2026-11-23T00:00:00Z").head
    pair.provenance.upstreamRunIds shouldBe Vector("j01-run", "j06-run")
  }
