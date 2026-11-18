package edu.scholarchain.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

/** Round-trip serialization tests for all domain records. */
class SerializationSpec extends AnyFlatSpec with Matchers {
  import TestFixtures.*

  private def roundTrip[T: ReadWriter](value: T): Unit =
    val json = write(value)
    val back = read[T](json)
    back shouldBe value

  "Provenance" should "round-trip through JSON" in {
    roundTrip(sampleProvenance)
  }

  "WorkRecord" should "round-trip through JSON" in {
    roundTrip(sampleWorkRecord)
  }

  "Venue" should "round-trip through JSON" in {
    roundTrip(sampleVenue)
  }

  "Institution" should "round-trip through JSON" in {
    roundTrip(sampleInstitution)
  }

  "Topic" should "round-trip through JSON" in {
    roundTrip(sampleTopic)
  }

  "AuthorMention" should "round-trip through JSON" in {
    roundTrip(sampleAuthorMention)
  }

  "CanonicalResearcher" should "round-trip through JSON" in {
    roundTrip(sampleCanonicalResearcher)
  }

  "AuthorshipRecord" should "round-trip through JSON" in {
    roundTrip(sampleAuthorshipRecord)
  }

  "PublicationTeam" should "round-trip through JSON" in {
    roundTrip(samplePublicationTeam)
  }

  "CoauthorPairCandidate" should "round-trip through JSON" in {
    roundTrip(sampleCoauthorPairCandidate)
  }

  "CoauthorEdge" should "round-trip through JSON" in {
    roundTrip(sampleCoauthorEdge)
  }

  "ResearcherMetrics" should "round-trip through JSON" in {
    roundTrip(sampleResearcherMetrics)
  }

  "MatchDecision" should "round-trip through JSON" in {
    roundTrip(sampleMatchDecision)
  }

  "GraphNode" should "round-trip through JSON" in {
    roundTrip(sampleGraphNode)
  }

  "GraphEdge" should "round-trip through JSON" in {
    roundTrip(sampleGraphEdge)
  }

  "EvidenceItem" should "round-trip through JSON" in {
    roundTrip(sampleEvidenceItem)
  }

  "EvidenceBundle" should "round-trip through JSON" in {
    roundTrip(sampleEvidenceBundle)
  }

  "QueryPlan" should "round-trip through JSON" in {
    roundTrip(sampleQueryPlan)
  }

  "AnswerClaim" should "round-trip through JSON" in {
    roundTrip(sampleAnswerClaim)
  }

  "AnswerResponse" should "round-trip through JSON" in {
    roundTrip(sampleAnswerResponse)
  }

  // --- Enum string serialization ---

  "SourceType" should "serialize as a string name" in {
    write(SourceType.OpenAlex) shouldBe "\"OpenAlex\""
  }

  "MatchOutcome" should "serialize as a string name" in {
    write(MatchOutcome.Accepted) shouldBe "\"Accepted\""
  }

  "EvidenceType" should "serialize as a string name" in {
    write(EvidenceType.TextChunk) shouldBe "\"TextChunk\""
  }

  "CollaborationMetric" should "serialize as a string name" in {
    write(CollaborationMetric.WeightedDegree) shouldBe "\"WeightedDegree\""
  }

  "QueryOperation" should "serialize as a string name" in {
    write(QueryOperation.ShortestPath) shouldBe "\"ShortestPath\""
  }

  "VerificationStatus" should "serialize as a string name" in {
    write(VerificationStatus.Verified) shouldBe "\"Verified\""
  }

  // --- Schema version presence ---

  "Serialized Provenance" should "contain schemaVersion field" in {
    val json = write(sampleProvenance)
    json should include("\"schemaVersion\"")
  }

  "Serialized WorkRecord" should "contain schemaVersion in provenance" in {
    val json = write(sampleWorkRecord)
    json should include("\"schemaVersion\"")
    json should include("1.0.0")
  }
}
