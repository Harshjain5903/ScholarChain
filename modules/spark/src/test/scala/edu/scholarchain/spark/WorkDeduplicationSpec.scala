package edu.scholarchain.spark

import edu.scholarchain.core.*
import edu.scholarchain.spark.TestFixtures.{lineage, mkProv}

/**
 * Cross-dataset duplicate works (enriched 10K run: one DBLP paper and its Crossref and OpenAlex
 * copies gave jointPublicationCount = 3 for a single shared paper) and DBLP-preferred canonical IDs.
 */
class WorkDeduplicationSpec extends SparkTestBase:

  private def work(id: String, source: SourceType, doi: Option[String]) =
    WorkRecord(
      workId = id,
      source = source,
      sourceRecordId = id,
      title = "T",
      normalizedTitle = "t",
      year = 2020,
      venueId = None,
      doi = doi,
      arxivId = None,
      authors = Vector.empty,
      abstractTextRef = None,
      provenance = mkProv(id)
    )

  private def mention(id: String) =
    AuthorMention(id, "N", "n", 0, None, None, None, id, mkProv(id))

  private def team(workId: String, mentions: String*) =
    PublicationTeam(workId, 2020, mentions.toVector.map(mention), mentions.size, mkProv(workId))

  private def pair(workId: String, a: String, b: String) =
    CoauthorPairCandidate(a, b, workId, 2020, mkProv(workId))

  private def accept(a: String, b: String) =
    MatchDecision(a, b, Map.empty, MatchOutcome.Accepted, 0.99, Vector.empty, "p", "r")

  private val dblpW = "work:dblp:conf/x/A20"
  private val crW   = "work:crossref:10.1/abc"
  private val oaW   = "work:openalex:W1"

  private val works = Seq(
    work(dblpW, SourceType.DBLP, Some("10.1/ABC")),
    work(crW, SourceType.Crossref, Some("https://doi.org/10.1/abc")),
    work(oaW, SourceType.OpenAlex, Some("doi:10.1/abc")),
    work("work:dblp:conf/x/B21", SourceType.DBLP, None),
    work("work:crossref:10.2/zz", SourceType.Crossref, Some("10.2/zz")),
    work("work:openalex:W9", SourceType.OpenAlex, Some("10.2/ZZ"))
  )

  test("normalizeDoi strips resolver and doi: prefixes and lowercases") {
    WorkDeduplication.normalizeDoi(" https://doi.org/10.1/ABC ") shouldBe "10.1/abc"
    WorkDeduplication.normalizeDoi("doi:10.1/Abc") shouldBe "10.1/abc"
    WorkDeduplication.normalizeDoi("10.1/abc") shouldBe "10.1/abc"
  }

  test("copies of one DOI map to the DBLP work; without DBLP the smallest ID is kept") {
    WorkDeduplication.duplicateWorks(sc.parallelize(works)).collect().toMap shouldBe Map(
      crW                     -> dblpW,
      oaW                     -> dblpW,
      "work:openalex:W9"      -> "work:crossref:10.2/zz"
    )
  }

  test("works without a DOI are never merged") {
    val dups = WorkDeduplication.duplicateWorks(sc.parallelize(works)).keys.collect().toSet
    dups should not contain "work:dblp:conf/x/B21"
  }

  test("one paper in three datasets yields one shared publication, not three") {
    val dA = "mention:dblp:conf/x/A20:0"
    val dB = "mention:dblp:conf/x/A20:1"
    val cA = "mention:crossref:10.1/abc:0"
    val cB = "mention:crossref:10.1/abc:1"
    val oA = "mention:openalex:W1:0"
    val oB = "mention:openalex:W1:1"
    val pairs = sc.parallelize(Seq(pair(dblpW, dA, dB), pair(crW, cA, cB), pair(oaW, oA, oB)))
    val teams = sc.parallelize(Seq(team(dblpW, dA, dB), team(crW, cA, cB), team(oaW, oA, oB)))
    val accepted =
      sc.parallelize(Seq(accept(cA, dA), accept(oA, dA), accept(cB, dB), accept(oB, dB)))
    val dups = WorkDeduplication.duplicateWorks(sc.parallelize(works))

    val mapping = CanonicalGraphBuilder.matchedMentionMapping(accepted)
    val keptPairs = WorkDeduplication.keepPairs(pairs, dups)
    val edges = CanonicalGraphBuilder
      .aggregatePairs(
        CanonicalGraphBuilder.canonicalPairs(keptPairs, mapping, 2000, 2026),
        2000,
        2026,
        "p",
        lineage()
      )
      .collect()
    edges.length shouldBe 1
    edges.head.jointPublicationCount shouldBe 1L
    edges.head.representativeWorkIds shouldBe Vector(dblpW)
    Set(edges.head.researcherA, edges.head.researcherB) shouldBe Set(dA, dB)

    val authorships = CanonicalGraphBuilder
      .canonicalAuthorships(WorkDeduplication.keepTeams(teams, dups), mapping, 2000, 2026)
      .collect()
    authorships.map(a => (a.researcherId, a.workId, a.coauthorsOnPaper)).toSet shouldBe Set(
      (dA, dblpW, 1),
      (dB, dblpW, 1)
    )
  }

  test("the canonical ID of a merged group prefers the DBLP mention over smaller IDs") {
    val accepted = sc.parallelize(
      Seq(
        accept("mention:crossref:10.1/abc:0", "mention:dblp:conf/x/A20:0"),
        accept("mention:dblp:conf/x/A20:0", "mention:openalex:W1:0")
      )
    )
    CanonicalGraphBuilder.matchedMentionMapping(accepted).values.distinct().collect() shouldBe
      Array("mention:dblp:conf/x/A20:0")
    CanonicalGraphBuilder.preferCanonical("mention:dblp:z", "mention:crossref:a") shouldBe true
    CanonicalGraphBuilder.preferCanonical("m:1", "m:2") shouldBe true
  }
