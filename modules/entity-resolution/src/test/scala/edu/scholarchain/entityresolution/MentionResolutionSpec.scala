package edu.scholarchain.entityresolution

import edu.scholarchain.core.{AuthorMention, MatchOutcome, SourceType, WorkRecord}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object MentionFixtures:

  /** Build a DBLP-shaped WorkRecord whose mention ids follow mention:dblp:<key>:<pos>. */
  def work(
    key: String,
    names: Vector[String],
    year: Int = 2020,
    venue: Option[String] = Some("aaai"),
    title: String = "A Study of Things.",
    orcids: Map[Int, String] = Map.empty
  ): WorkRecord =
    val p = TestFixtures.testProvenance
    WorkRecord(
      workId = s"work:dblp:dblp:$key",
      source = SourceType.DBLP,
      sourceRecordId = s"dblp:$key",
      title = title,
      normalizedTitle = IdentifierNormalizer.normalizeTitle(title),
      year = year,
      venueId = venue,
      doi = None,
      arxivId = None,
      authors = names.zipWithIndex.map { (n, i) =>
        AuthorMention(
          mentionId = s"mention:dblp:$key:$i",
          rawName = n,
          normalizedName = n.toLowerCase,
          position = i,
          orcid = orcids.get(i),
          openAlexId = None,
          affiliationRaw = None,
          sourceRecordId = s"dblp:$key",
          provenance = p
        )
      },
      abstractTextRef = None,
      provenance = p
    )

  def mentions(works: WorkRecord*): Vector[MentionContext] =
    works.toVector.flatMap(MentionContext.fromWork)

  def resolver: MentionResolver =
    MentionResolver(MentionResolver.mentionPolicy, StubLlmAdjudicator(), llmEnabled = false, "r1")

  def resolveAll(works: WorkRecord*): Vector[edu.scholarchain.core.MatchDecision] =
    MentionBlocker.block(mentions(works*), 200).pairs.map(resolver.resolve)

class MentionResolutionSpec extends AnyFlatSpec with Matchers {
  import MentionFixtures.*

  "MentionContext.fromWork" should "keep the DBLP suffix apart and list coauthors" in {
    val ms = mentions(work("conf/a/X20", Vector("Wei Wang 0001", "Ana Lima")))
    ms.map(_.mentionId) shouldBe Vector("mention:dblp:conf/a/X20:0", "mention:dblp:conf/a/X20:1")
    ms(0).normalizedName shouldBe "wei wang"
    ms(0).dblpHomonymSuffix shouldBe Some("0001")
    ms(0).coauthorNames shouldBe Set("ana lima")
    ms(1).dblpHomonymSuffix shouldBe None
  }

  "MentionBlocker" should "block on surname and first initial, skipping same-work pairs" in {
    val r = MentionBlocker.block(
      mentions(
        work("conf/a/A1", Vector("Wei Wang", "W. Wang")),
        work("conf/a/A2", Vector("Wei Wang 0002")),
        work("conf/a/A3", Vector("Bo Li"))
      ),
      200
    )
    r.blockSizes("name:wang:w") shouldBe 3
    r.sameWorkPairsSkipped shouldBe 1
    r.pairs.size shouldBe 2
    r.pairs.foreach(p => p.source.mentionId should be < p.target.mentionId)
    r.pairs.map(_.blockKey).toSet shouldBe Set("name:wang:w")
  }

  it should "skip blocks larger than the cap and report them" in {
    val ws = (1 to 4).map(i => work(s"conf/a/B$i", Vector("Li Chen")))
    val r  = MentionBlocker.block(mentions(ws*), 3)
    r.pairs shouldBe empty
    r.oversizedBlocks shouldBe Map("name:chen:l" -> 4)
  }

  "MentionResolver" should "accept same name with a shared coauthor" in {
    val ds = resolveAll(
      work("conf/a/C1", Vector("Marlon Dumas", "Ana Lima"), year = 2019),
      work("conf/b/C2", Vector("Marlon Dumas", "Ana Lima"), year = 2021, venue = Some("bpm"))
    ).filter(_.reasonCodes.contains("block:name:dumas:m"))
    ds.size shouldBe 1
    ds.head.decision shouldBe MatchOutcome.Accepted
    ds.head.sourceId shouldBe "mention:dblp:conf/a/C1:0"
    ds.head.targetId shouldBe "mention:dblp:conf/b/C2:0"
    ds.head.features("coauthor_overlap") shouldBe 1.0
    ds.head.policyVersion shouldBe "er-mention-v1-uncalibrated"
  }

  it should "leave same name without corroborating evidence Unresolved (llm disabled)" in {
    val ds = resolveAll(
      work("conf/a/D1", Vector("Rui Chen", "X Y"), year = 2005, venue = Some("icde")),
      work("conf/b/D2", Vector("Rui Chen", "P Q"), year = 2020, venue = Some("chi"))
    ).filter(_.reasonCodes.contains("block:name:chen:r"))
    ds.map(_.decision) shouldBe Vector(MatchOutcome.Unresolved)
    ds.head.reasonCodes should contain("llm_disabled")
  }

  it should "reject different first names that share a block" in {
    val ds = resolveAll(
      work("conf/a/E1", Vector("Rui Chen"), year = 2005, venue = Some("icde")),
      work("conf/b/E2", Vector("Rong Chen"), year = 2020, venue = Some("chi"))
    )
    ds.map(_.decision) shouldBe Vector(MatchOutcome.Rejected)
  }

  it should "reject DBLP homonyms with different suffixes even with shared coauthors" in {
    val ds = resolveAll(
      work("conf/a/F1", Vector("Song Wang 0013", "Ana Lima")),
      work("conf/a/F2", Vector("Song Wang 0007", "Ana Lima"))
    ).filter(_.reasonCodes.contains("block:name:wang:s"))
    ds.map(_.decision) shouldBe Vector(MatchOutcome.Rejected)
    ds.head.reasonCodes should contain("dblp_homonym_suffix_mismatch")
    ds.head.confidence shouldBe 0.0
  }

  it should "accept identical DBLP person keys (same base name and suffix)" in {
    val ds = resolveAll(
      work("conf/a/G1", Vector("Yanzhi Wang 0001"), year = 2010, venue = Some("dac")),
      work("conf/b/G2", Vector("Yanzhi Wang 0001"), year = 2023, venue = Some("aaai"))
    )
    ds.map(_.decision) shouldBe Vector(MatchOutcome.Accepted)
    ds.head.reasonCodes should contain("path:dblp_person_key")
  }

  it should "downgrade a score-accept to Unresolved when only one side carries a suffix" in {
    val ds = resolveAll(
      work("conf/a/H1", Vector("Bo Dong 0001", "Ana Lima")),
      work("conf/a/H2", Vector("Bo Dong", "Ana Lima"))
    ).filter(_.reasonCodes.contains("block:name:dong:b"))
    ds.map(_.decision) shouldBe Vector(MatchOutcome.Unresolved)
    ds.head.reasonCodes should contain("dblp_homonym_one_sided")
  }

  it should "use ORCID first: equal accepts, different rejects" in {
    val same = resolveAll(
      work("conf/a/I1", Vector("Jo Park"), orcids = Map(0 -> "0000-0002-1825-0097")),
      work("conf/b/I2", Vector("J. Park"), orcids = Map(0 -> "https://orcid.org/0000-0002-1825-0097"))
    )
    same.map(_.decision) shouldBe Vector(MatchOutcome.Accepted)
    same.head.reasonCodes should contain("exact_id_match:orcid")

    val diff = resolveAll(
      work("conf/a/J1", Vector("Jo Park", "Ana Lima"), orcids = Map(0 -> "0000-0002-1825-0097")),
      work("conf/a/J2", Vector("Jo Park", "Ana Lima"), orcids = Map(0 -> "0000-0001-5109-3700"))
    ).filter(_.reasonCodes.contains("block:name:park:j"))
    diff.map(_.decision) shouldBe Vector(MatchOutcome.Rejected)
    diff.head.reasonCodes should contain("orcid_mismatch")
  }

  it should "route Unresolved pairs to the adjudicator only when enabled (stub abstains)" in {
    val pairs = MentionBlocker
      .block(
        mentions(
          work("conf/a/K1", Vector("Rui Chen"), year = 2005, venue = Some("icde")),
          work("conf/b/K2", Vector("Rui Chen"), year = 2020, venue = Some("chi"))
        ),
        200
      )
      .pairs
    val enabled =
      MentionResolver(MentionResolver.mentionPolicy, StubLlmAdjudicator(), llmEnabled = true, "r")
    val d = enabled.resolve(pairs.head)
    d.decision shouldBe MatchOutcome.Unresolved
    d.reasonCodes should contain("path:llm_adjudicated")
    d.reasonCodes should contain("llm_decision:Abstain")
  }

  it should "reject incompatible given names even with same venue and year" in {
    val ds = resolveAll(
      work("conf/a/L1", Vector("Yupeng Zhang"), year = 2023),
      work("conf/a/L2", Vector("Yifei Zhang"), year = 2023)
    )
    ds.map(_.decision) shouldBe Vector(MatchOutcome.Rejected)
    ds.head.reasonCodes should contain("given_name_incompatible")
    ds.head.reasonCodes should contain("path:name_gate")
  }

  it should "not gate an initial against a full given name" in {
    val ds = resolveAll(
      work("conf/a/M1", Vector("J. Smith"), year = 2023),
      work("conf/a/M2", Vector("John Smith"), year = 2023)
    )
    ds.head.reasonCodes should not contain "given_name_incompatible"
  }

  "givenNamesCompatible" should "accept equal, initial-prefix and concatenated given names" in {
    MentionResolver.givenNamesCompatible("wei wang", "wei wang") shouldBe true
    MentionResolver.givenNamesCompatible("j smith", "john smith") shouldBe true
    MentionResolver.givenNamesCompatible("tsong yueh chen", "tsongyueh chen") shouldBe true
    MentionResolver.givenNamesCompatible("chen", "rui chen") shouldBe true
    MentionResolver.givenNamesCompatible("binbin he", "botao he") shouldBe false
    MentionResolver.givenNamesCompatible("rui chen", "rong chen") shouldBe false
  }

  "overlapCoefficient" should "divide the intersection by the smaller set" in {
    MentionResolver.overlapCoefficient(Set("a", "b"), Set("b", "c", "d")) shouldBe 0.5
    MentionResolver.overlapCoefficient(Set.empty, Set("a")) shouldBe 0.0
  }
}
