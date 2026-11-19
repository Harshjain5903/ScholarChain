package edu.scholarchain.core

import upickle.default.*

/** Data source systems that contribute records to ScholarChain. */
enum SourceType:
  case DBLP, OpenAlex, ORCID, Crossref, SemanticScholar, ScholarChain

object SourceType:

  given ReadWriter[SourceType] =
    readwriter[String].bimap(_.toString, SourceType.valueOf(_))

/** Outcome of an entity-resolution match decision. */
enum MatchOutcome:
  case Accepted, Rejected, Unresolved, Conflict

object MatchOutcome:

  given ReadWriter[MatchOutcome] =
    readwriter[String].bimap(_.toString, MatchOutcome.valueOf(_))

/** Types of evidence that support an answer claim. */
enum EvidenceType:
  case TextChunk, GraphPath, MetricRecord, MatchRecord

object EvidenceType:

  given ReadWriter[EvidenceType] =
    readwriter[String].bimap(_.toString, EvidenceType.valueOf(_))

/** Collaboration-analysis metric kinds. */
enum CollaborationMetric:

  case DistinctCoauthorCount, WeightedDegree, PublicationCount,
    AverageCoauthorsPerPaper, BridgeScore, Coreness

object CollaborationMetric:

  given ReadWriter[CollaborationMetric] =
    readwriter[String].bimap(_.toString, CollaborationMetric.valueOf(_))

/** Operations that may appear in a query plan. */
enum QueryOperation:

  case ResearcherRanking, ShortestPath, ComponentLookup, CommunityComparison,
    BridgeRanking, TemporalComparison, TopicFilter, InstitutionFilter

object QueryOperation:

  given ReadWriter[QueryOperation] =
    readwriter[String].bimap(_.toString, QueryOperation.valueOf(_))

/** Verification status of an answer claim. */
enum VerificationStatus:
  case Verified, PartiallySupported, Unsupported, Conflicting

object VerificationStatus:

  given ReadWriter[VerificationStatus] =
    readwriter[String].bimap(_.toString, VerificationStatus.valueOf(_))
