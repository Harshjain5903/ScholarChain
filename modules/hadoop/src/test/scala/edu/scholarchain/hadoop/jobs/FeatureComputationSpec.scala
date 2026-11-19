package edu.scholarchain.hadoop.jobs

import edu.scholarchain.core.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class FeatureComputationSpec extends AnyFunSuite with Matchers:

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
      sourceId: String,
      title: String,
      year: Int,
      venue: Option[String],
      authors: Vector[AuthorMention],
  ): WorkRecord =
    WorkRecord(
      workId = s"work:test:$sourceId",
      source = SourceType.DBLP,
      sourceRecordId = sourceId,
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

  // -- Jaccard similarity --

  test("jaccard of identical sets is 1.0") {
    FeatureConstructionJob.jaccard(
      Set("a", "b", "c"),
      Set("a", "b", "c"),
    ) shouldBe 1.0
  }

  test("jaccard of disjoint sets is 0.0") {
    FeatureConstructionJob.jaccard(
      Set("a", "b"),
      Set("c", "d"),
    ) shouldBe 0.0
  }

  test("jaccard of overlapping sets") {
    val result = FeatureConstructionJob.jaccard(
      Set("a", "b", "c"),
      Set("b", "c", "d"),
    )
    result shouldBe (2.0 / 4.0 +- 0.001)
  }

  test("jaccard of empty sets is 1.0") {
    FeatureConstructionJob.jaccard(
      Set.empty,
      Set.empty,
    ) shouldBe 1.0
  }

  // -- Author similarity --

  test("authorSimilarity of identical author lists") {
    val result = FeatureConstructionJob.authorSimilarity(
      Vector("liang chen", "rebecca williams"),
      Vector("liang chen", "rebecca williams"),
    )
    result shouldBe 1.0
  }

  test("authorSimilarity of completely different lists") {
    val result = FeatureConstructionJob.authorSimilarity(
      Vector("alpha beta"),
      Vector("gamma delta"),
    )
    result shouldBe 0.0
  }

  test("authorSimilarity with empty list is 0.0") {
    FeatureConstructionJob.authorSimilarity(
      Vector.empty,
      Vector("someone"),
    ) shouldBe 0.0
  }

  // -- Coauthor overlap --

  test("coauthorOverlap with shared names") {
    val result = FeatureConstructionJob.coauthorOverlap(
      Vector("alice", "bob", "carol"),
      Vector("bob", "carol", "dave"),
    )
    result shouldBe 2
  }

  test("coauthorOverlap with no overlap") {
    val result = FeatureConstructionJob.coauthorOverlap(
      Vector("alice"),
      Vector("bob"),
    )
    result shouldBe 0
  }

  // -- Full feature computation --

  test("computeFeatures for identical works") {
    val authors = Vector(
      mkMention("Liang Chen", 0),
      mkMention("Rebecca Williams", 1),
    )
    val work1 = mkWork(
      "s1", "Graph Partitioning", 2023, Some("ieee tse"), authors
    )
    val work2 = mkWork(
      "s2", "Graph Partitioning", 2023, Some("ieee tse"), authors
    )

    val features =
      FeatureConstructionJob.computeFeatures(work1, work2)
    features("title_jaccard") shouldBe 1.0
    features("year_distance") shouldBe 0.0
    features("venue_match") shouldBe 1.0
  }

  test("computeFeatures year distance") {
    val work1 =
      mkWork("s1", "Title A", 2020, None, Vector.empty)
    val work2 =
      mkWork("s2", "Title B", 2023, None, Vector.empty)
    val features =
      FeatureConstructionJob.computeFeatures(work1, work2)
    features("year_distance") shouldBe 3.0
  }

  test("computeFeatures venue mismatch") {
    val work1 =
      mkWork("s1", "Title", 2023, Some("venue a"), Vector.empty)
    val work2 =
      mkWork("s2", "Title", 2023, Some("venue b"), Vector.empty)
    val features =
      FeatureConstructionJob.computeFeatures(work1, work2)
    features("venue_match") shouldBe 0.0
  }
