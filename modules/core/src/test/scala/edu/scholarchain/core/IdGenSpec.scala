package edu.scholarchain.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for deterministic and stable identifier generation. */
class IdGenSpec extends AnyFlatSpec with Matchers {

  "workId" should "produce deterministic IDs" in {
    val id1 = IdGen.workId(SourceType.OpenAlex, "W123")
    val id2 = IdGen.workId(SourceType.OpenAlex, "W123")
    id1 shouldBe id2
  }

  it should "include lowercase source name" in {
    IdGen.workId(SourceType.OpenAlex, "W123") shouldBe "work:openalex:W123"
    IdGen.workId(SourceType.DBLP, "conf/icml/2023") shouldBe
      "work:dblp:conf/icml/2023"
  }

  it should "produce different IDs for different sources" in {
    val a = IdGen.workId(SourceType.OpenAlex, "W123")
    val b = IdGen.workId(SourceType.DBLP, "W123")
    a should not be b
  }

  it should "produce different IDs for different record IDs" in {
    val a = IdGen.workId(SourceType.OpenAlex, "W123")
    val b = IdGen.workId(SourceType.OpenAlex, "W456")
    a should not be b
  }

  "researcherId" should "prefer ORCID when present" in {
    val id = IdGen.researcherId(
      Some("0000-0001-2345-6789"),
      Some("A100"),
      "Jane Smith"
    )
    id shouldBe "researcher:orcid:0000-0001-2345-6789"
  }

  it should "fall back to OpenAlex when no ORCID" in {
    val id = IdGen.researcherId(None, Some("A100"), "Jane Smith")
    id shouldBe "researcher:openalex:A100"
  }

  it should "use SHA-256 of fallback name when no IDs available" in {
    val id = IdGen.researcherId(None, None, "Jane Smith")
    id should startWith("researcher:sc:")
    id.length should be > "researcher:sc:".length
  }

  it should "produce deterministic SHA-256 fallback" in {
    val id1 = IdGen.researcherId(None, None, "Jane Smith")
    val id2 = IdGen.researcherId(None, None, "Jane Smith")
    id1 shouldBe id2
  }

  it should "produce different fallbacks for different names" in {
    val a = IdGen.researcherId(None, None, "Jane Smith")
    val b = IdGen.researcherId(None, None, "Bob Chen")
    a should not be b
  }

  it should "reject empty fallbackName when no ORCID or OpenAlex ID" in {
    an[IllegalArgumentException] should be thrownBy {
      IdGen.researcherId(None, None, "")
    }
  }

  it should "allow empty fallbackName when ORCID is present" in {
    noException should be thrownBy {
      IdGen.researcherId(Some("0000-0001-2345-6789"), None, "")
    }
  }

  it should "allow empty fallbackName when OpenAlex ID is present" in {
    noException should be thrownBy {
      IdGen.researcherId(None, Some("A100"), "")
    }
  }

  "coauthorEdgeId" should "be deterministic" in {
    val id1 = IdGen.coauthorEdgeId("A", "B", 2019, 2023, "v1")
    val id2 = IdGen.coauthorEdgeId("A", "B", 2019, 2023, "v1")
    id1 shouldBe id2
  }

  it should "be order-independent for the researcher pair" in {
    val id1 = IdGen.coauthorEdgeId("A", "B", 2019, 2023, "v1")
    val id2 = IdGen.coauthorEdgeId("B", "A", 2019, 2023, "v1")
    id1 shouldBe id2
  }

  it should "differ by time window" in {
    val a = IdGen.coauthorEdgeId("A", "B", 2019, 2023, "v1")
    val b = IdGen.coauthorEdgeId("A", "B", 2020, 2024, "v1")
    a should not be b
  }

  it should "differ by policy version" in {
    val a = IdGen.coauthorEdgeId("A", "B", 2019, 2023, "v1")
    val b = IdGen.coauthorEdgeId("A", "B", 2019, 2023, "v2")
    a should not be b
  }

  "chunkId" should "be deterministic" in {
    val id1 = IdGen.chunkId("e1", "v1", "abstract", "abc123")
    val id2 = IdGen.chunkId("e1", "v1", "abstract", "abc123")
    id1 shouldBe id2
  }

  it should "differ for different inputs" in {
    val a = IdGen.chunkId("e1", "v1", "abstract", "abc123")
    val b = IdGen.chunkId("e1", "v1", "abstract", "def456")
    a should not be b
  }

  "evidenceId" should "follow the documented format" in {
    IdGen.evidenceId("q-42", 7) shouldBe "evidence:q-42:0007"
    IdGen.evidenceId("q-42", 0) shouldBe "evidence:q-42:0000"
    IdGen.evidenceId("q-42", 9999) shouldBe "evidence:q-42:9999"
  }

  it should "be deterministic" in {
    IdGen.evidenceId("query:abc", 3) shouldBe IdGen.evidenceId("query:abc", 3)
  }

  it should "differ by query and by ordinal" in {
    IdGen.evidenceId("q1", 1) should not be IdGen.evidenceId("q2", 1)
    IdGen.evidenceId("q1", 1) should not be IdGen.evidenceId("q1", 2)
  }

  it should "round-trip through parseEvidenceId" in {
    val cases = Seq(
      ("q-42", 0),
      ("q-42", 7),
      ("q-42", 9999),
      ("query:with:colons", 12),
      ("query:2026-11-24T00:00:00Z", 5),
      ("unicode-\u00e9", 100)
    )
    for (q, n) <- cases do IdGen.parseEvidenceId(IdGen.evidenceId(q, n)) shouldBe Some((q, n))
  }

  it should "sort lexicographically in ordinal order within a query" in {
    val ids = (0 to 120).map(IdGen.evidenceId("q", _))
    ids.sorted shouldBe ids
  }

  it should "reject an empty or blank queryId" in {
    an[IllegalArgumentException] should be thrownBy IdGen.evidenceId("", 0)
    an[IllegalArgumentException] should be thrownBy IdGen.evidenceId("   ", 0)
  }

  it should "reject out-of-range ordinals" in {
    an[IllegalArgumentException] should be thrownBy IdGen.evidenceId("q", -1)
    an[IllegalArgumentException] should be thrownBy
      IdGen.evidenceId("q", IdGen.MaxEvidenceOrdinal + 1)
  }

  "parseEvidenceId" should "reject malformed IDs" in {
    IdGen.parseEvidenceId("ev:q:0001") shouldBe None
    IdGen.parseEvidenceId("evidence:q") shouldBe None
    IdGen.parseEvidenceId("evidence::0001") shouldBe None
    IdGen.parseEvidenceId("evidence:q:1") shouldBe None
    IdGen.parseEvidenceId("evidence:q:00001") shouldBe None
    IdGen.parseEvidenceId("evidence:q:00a1") shouldBe None
    IdGen.parseEvidenceId("evidence:q:-001") shouldBe None
    IdGen.parseEvidenceId("") shouldBe None
  }

  "runId" should "start with run: prefix" in {
    IdGen.runId() should startWith("run:")
  }

  it should "produce unique values" in {
    val ids = (1 to 100).map(_ => IdGen.runId()).toSet
    ids.size shouldBe 100
  }
}
