package edu.scholarchain.services

import upickle.default.*

// --- Request types ---

case class QueryRequest(
  question: String,
  constraints: Option[QueryConstraints] = None,
  analysis: Option[AnalysisOptions] = None,
  retrieval: Option[RetrievalOptions] = None,
  output: Option[OutputOptions] = None
) derives ReadWriter

case class QueryConstraints(
  yearFrom: Option[Int] = None,
  yearTo: Option[Int] = None,
  topics: Option[Vector[String]] = None,
  institutions: Option[Vector[String]] = None,
  venues: Option[Vector[String]] = None
) derives ReadWriter

case class AnalysisOptions(
  rankingMetric: Option[String] = None,
  includeTeamSize: Option[Boolean] = None,
  includeBridgeMetrics: Option[Boolean] = None,
  includeCommunities: Option[Boolean] = None,
  includePaths: Option[Boolean] = None
) derives ReadWriter

case class RetrievalOptions(
  graphLimit: Option[Int] = None,
  vectorLimit: Option[Int] = None,
  maxPathDepth: Option[Int] = None
) derives ReadWriter

case class OutputOptions(
  includeEvidence: Option[Boolean] = None,
  includeScores: Option[Boolean] = None,
  includeUncertainty: Option[Boolean] = None
) derives ReadWriter

// --- Response types ---

case class QueryResponse(
  queryId: String,
  mode: String,
  answer: Option[AnswerPayload] = None,
  claims: Option[Vector[ClaimPayload]] = None,
  links: Option[LinksPayload] = None
) derives ReadWriter

case class AsyncResponse(
  queryId: String,
  mode: String,
  jobId: String,
  status: String,
  statusUrl: String
) derives ReadWriter

case class AnswerPayload(
  summary: String,
  researchers: Vector[ResearcherPayload]
) derives ReadWriter

case class ResearcherPayload(
  researcherId: String,
  displayName: String,
  publicationCount: Int,
  distinctCoauthorCount: Int,
  weightedDegree: Double,
  averageCoauthorsPerPaper: Double,
  maximumCoauthorsOnOnePaper: Int,
  maximumTeamWorkIds: Vector[String],
  componentId: Option[String],
  communityId: Option[String],
  bridgeMetric: Option[BridgeMetricPayload],
  claimIds: Vector[String]
) derives ReadWriter

case class BridgeMetricPayload(name: String, value: Double) derives ReadWriter

case class ClaimPayload(
  claimId: String,
  text: String,
  evidenceIds: Vector[String],
  verificationStatus: String,
  uncertainty: Option[String]
) derives ReadWriter

case class LinksPayload(evidence: String, explain: String) derives ReadWriter

case class JobResponse(
  jobId: String,
  queryId: String,
  state: String,
  stage: String,
  createdAt: String,
  updatedAt: String,
  resultUrl: Option[String],
  error: Option[String]
) derives ReadWriter

case class EvidenceResponse(
  evidenceId: String,
  evidenceType: String,
  sourceEntityId: String,
  sourceDataset: String,
  text: String,
  textS3Uri: String,
  score: Double,
  provenance: ProvenancePayload
) derives ReadWriter

case class ProvenancePayload(
  runId: String,
  textHash: String,
  schemaVersion: String
) derives ReadWriter

case class NeighborResponse(
  nodeId: String,
  nodeType: String,
  properties: Map[String, String]
) derives ReadWriter

case class ExplainResponse(
  queryId: String,
  queryPlan: String,
  retrievalModes: Vector[String],
  promptVersions: Vector[String],
  modelVersions: Vector[String],
  evidenceIds: Vector[String],
  verificationSummary: String,
  latencyMs: Long,
  cacheHits: Int
) derives ReadWriter

case class ErrorResponse(
  error: String,
  message: String,
  traceId: String,
  retryable: Boolean
) derives ReadWriter
