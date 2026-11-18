package edu.scholarchain.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for domain validation rules. */
class ValidationSpec extends AnyFlatSpec with Matchers {
  import TestFixtures.sampleProvenance

  // --- Provenance required fields ---

  "Provenance" should "reject empty sourceDataset" in {
    an[IllegalArgumentException] should be thrownBy {
      Provenance("", "rec1", "run1", "1.0.0", "2024-01-01T00:00:00Z")
    }
  }

  it should "reject empty pipelineRunId" in {
    an[IllegalArgumentException] should be thrownBy {
      Provenance("dataset", "rec1", "", "1.0.0", "2024-01-01T00:00:00Z")
    }
  }

  it should "reject empty schemaVersion" in {
    an[IllegalArgumentException] should be thrownBy {
      Provenance("dataset", "rec1", "run1", "", "2024-01-01T00:00:00Z")
    }
  }

  it should "accept valid provenance" in {
    noException should be thrownBy {
      Provenance("dataset", "rec1", "run1", "1.0.0", "2024-01-01T00:00:00Z")
    }
  }

  // --- Confidence range ---

  "AuthorshipRecord" should "reject confidence below 0.0" in {
    an[IllegalArgumentException] should be thrownBy {
      AuthorshipRecord("r1", "w1", 0, -0.1, sampleProvenance)
    }
  }

  it should "reject confidence above 1.0" in {
    an[IllegalArgumentException] should be thrownBy {
      AuthorshipRecord("r1", "w1", 0, 1.01, sampleProvenance)
    }
  }

  it should "accept confidence at boundaries" in {
    noException should be thrownBy {
      AuthorshipRecord("r1", "w1", 0, 0.0, sampleProvenance)
    }
    noException should be thrownBy {
      AuthorshipRecord("r1", "w1", 0, 1.0, sampleProvenance)
    }
  }

  "MatchDecision" should "reject confidence below 0.0" in {
    an[IllegalArgumentException] should be thrownBy {
      MatchDecision(
        "s1",
        "t1",
        Map.empty,
        MatchOutcome.Accepted,
        -0.5,
        Vector.empty,
        "v1",
        "run1"
      )
    }
  }

  it should "reject confidence above 1.0" in {
    an[IllegalArgumentException] should be thrownBy {
      MatchDecision(
        "s1",
        "t1",
        Map.empty,
        MatchOutcome.Accepted,
        1.5,
        Vector.empty,
        "v1",
        "run1"
      )
    }
  }

  // --- AnswerClaim confidence ---

  "AnswerClaim" should "reject confidence below 0.0" in {
    an[IllegalArgumentException] should be thrownBy {
      AnswerClaim(
        "c1",
        "q1",
        "statement",
        -0.1,
        Vector.empty,
        VerificationStatus.Verified,
        sampleProvenance
      )
    }
  }

  it should "reject confidence above 1.0" in {
    an[IllegalArgumentException] should be thrownBy {
      AnswerClaim(
        "c1",
        "q1",
        "statement",
        1.5,
        Vector.empty,
        VerificationStatus.Verified,
        sampleProvenance
      )
    }
  }

  it should "accept confidence at boundaries" in {
    noException should be thrownBy {
      AnswerClaim(
        "c1",
        "q1",
        "statement",
        0.0,
        Vector.empty,
        VerificationStatus.Verified,
        sampleProvenance
      )
    }
    noException should be thrownBy {
      AnswerClaim(
        "c1",
        "q1",
        "statement",
        1.0,
        Vector.empty,
        VerificationStatus.Verified,
        sampleProvenance
      )
    }
  }

  // --- EvidenceItem score ---

  "EvidenceItem" should "reject score below 0.0" in {
    an[IllegalArgumentException] should be thrownBy {
      EvidenceItem(
        "ev1",
        EvidenceType.TextChunk,
        "entity1",
        "content",
        -0.1,
        sampleProvenance
      )
    }
  }

  it should "reject score above 1.0" in {
    an[IllegalArgumentException] should be thrownBy {
      EvidenceItem(
        "ev1",
        EvidenceType.TextChunk,
        "entity1",
        "content",
        1.5,
        sampleProvenance
      )
    }
  }

  it should "accept score at boundaries" in {
    noException should be thrownBy {
      EvidenceItem(
        "ev1",
        EvidenceType.TextChunk,
        "entity1",
        "content",
        0.0,
        sampleProvenance
      )
    }
    noException should be thrownBy {
      EvidenceItem(
        "ev1",
        EvidenceType.TextChunk,
        "entity1",
        "content",
        1.0,
        sampleProvenance
      )
    }
  }

  // --- Time window validation ---

  "ResearcherMetrics" should "reject windowStartYear > windowEndYear" in {
    an[IllegalArgumentException] should be thrownBy {
      ResearcherMetrics(
        researcherId = "r1",
        publicationCount = 10,
        distinctCoauthorCount = 5,
        totalCoauthorOccurrences = 30,
        averageCoauthorsPerPaper = 3.0,
        maximumCoauthorsOnOnePaper = 5,
        weightedDegree = 10.0,
        componentId = None,
        communityId = None,
        bridgeScore = 0.5,
        coreness = 2,
        windowStartYear = 2024,
        windowEndYear = 2020,
        provenance = sampleProvenance
      )
    }
  }

  it should "accept windowStartYear == windowEndYear" in {
    noException should be thrownBy {
      ResearcherMetrics(
        researcherId = "r1",
        publicationCount = 10,
        distinctCoauthorCount = 5,
        totalCoauthorOccurrences = 30,
        averageCoauthorsPerPaper = 3.0,
        maximumCoauthorsOnOnePaper = 5,
        weightedDegree = 10.0,
        componentId = None,
        communityId = None,
        bridgeScore = 0.5,
        coreness = 2,
        windowStartYear = 2023,
        windowEndYear = 2023,
        provenance = sampleProvenance
      )
    }
  }

  "CoauthorEdge" should "reject firstCollaborationYear > lastCollaborationYear" in {
    an[IllegalArgumentException] should be thrownBy {
      CoauthorEdge(
        "researcher:A",
        "researcher:B",
        3,
        2024,
        2020,
        Vector("w1"),
        sampleProvenance
      )
    }
  }

  it should "accept equal collaboration years" in {
    noException should be thrownBy {
      CoauthorEdge(
        "researcher:A",
        "researcher:B",
        1,
        2023,
        2023,
        Vector("w1"),
        sampleProvenance
      )
    }
  }

  // --- Self-pair rejection ---

  "CoauthorPairCandidate" should "reject equal IDs (self-pairs)" in {
    an[IllegalArgumentException] should be thrownBy {
      CoauthorPairCandidate("X", "X", "w1", 2023, sampleProvenance)
    }
  }

  "CoauthorEdge" should "reject equal researcher IDs (self-edges)" in {
    an[IllegalArgumentException] should be thrownBy {
      CoauthorEdge(
        "researcher:A",
        "researcher:A",
        1,
        2023,
        2023,
        Vector("w1"),
        sampleProvenance
      )
    }
  }

  // --- WorkRecord year validation ---

  "WorkRecord" should "reject negative year" in {
    an[IllegalArgumentException] should be thrownBy {
      WorkRecord(
        workId = "w1",
        source = SourceType.OpenAlex,
        sourceRecordId = "rec1",
        title = "Title",
        normalizedTitle = "title",
        year = -1,
        venueId = None,
        doi = None,
        arxivId = None,
        authors = Vector.empty,
        abstractTextRef = None,
        provenance = sampleProvenance
      )
    }
  }

  it should "accept year zero" in {
    noException should be thrownBy {
      WorkRecord(
        workId = "w1",
        source = SourceType.OpenAlex,
        sourceRecordId = "rec1",
        title = "Title",
        normalizedTitle = "title",
        year = 0,
        venueId = None,
        doi = None,
        arxivId = None,
        authors = Vector.empty,
        abstractTextRef = None,
        provenance = sampleProvenance
      )
    }
  }

  // --- AuthorMention position validation ---

  "AuthorMention" should "reject negative position" in {
    an[IllegalArgumentException] should be thrownBy {
      AuthorMention(
        mentionId = "m1",
        rawName = "Test",
        normalizedName = "test",
        position = -1,
        orcid = None,
        openAlexId = None,
        affiliationRaw = None,
        sourceRecordId = "rec1",
        provenance = sampleProvenance
      )
    }
  }

  it should "accept position zero" in {
    noException should be thrownBy {
      AuthorMention(
        mentionId = "m1",
        rawName = "Test",
        normalizedName = "test",
        position = 0,
        orcid = None,
        openAlexId = None,
        affiliationRaw = None,
        sourceRecordId = "rec1",
        provenance = sampleProvenance
      )
    }
  }

  // --- PublicationTeam teamSize consistency ---

  "PublicationTeam" should "reject teamSize not matching authorMentions.size" in {
    an[IllegalArgumentException] should be thrownBy {
      PublicationTeam(
        workId = "w1",
        year = 2023,
        authorMentions = Vector.empty,
        teamSize = 5,
        provenance = sampleProvenance
      )
    }
  }

  it should "accept matching teamSize and authorMentions.size" in {
    noException should be thrownBy {
      PublicationTeam(
        workId = "w1",
        year = 2023,
        authorMentions = Vector.empty,
        teamSize = 0,
        provenance = sampleProvenance
      )
    }
  }
}
