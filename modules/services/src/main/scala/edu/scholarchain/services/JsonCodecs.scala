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
  links: Option[LinksPayload] = None,
  jobId: Option[String] = None
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

/**
 * @param evidence
 *   `/v1/evidence/{queryId}`: every evidence item of the query; single items are at
 *   `/v1/evidence/{evidenceId}`
 * @param job
 *   `/v1/jobs/{jobId}`; the durable result is at `/v1/jobs/{jobId}/result`
 */
case class LinksPayload(
  evidence: String,
  explain: String,
  job: Option[String] = None,
  result: Option[String] = None
) derives ReadWriter

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

/**
 * Audit view of one answered query. Built only from the stored audit record and the stored result;
 * it carries identifiers, versions, statuses and reason codes, never prompts, model reasoning or
 * claim text.
 */
case class ExplainResponse(
  queryId: String,
  queryPlan: Option[QueryPlanPayload],
  stages: Vector[StagePayload],
  retrievalModes: Vector[String],
  promptVersions: Vector[String],
  modelVersions: Vector[String],
  promptHash: String,
  synthesisMode: String,
  resolvedIds: Vector[String],
  evidenceIds: Vector[String],
  claimVerifications: Vector[ClaimVerificationPayload],
  removedClaimIds: Vector[String],
  abstentionReason: Option[String],
  verificationSummary: String,
  latencyMs: Long,
  cacheHits: Int
) derives ReadWriter

/**
 * The query plan recorded with a result.
 *
 * @param source
 *   how the plan was obtained; see `StoredQuery.PlanSource`
 */
case class QueryPlanPayload(
  operations: Vector[String],
  filters: Map[String, String],
  schemaVersion: String,
  source: String
) derives ReadWriter

/** One workflow stage: `ok`, `unavailable`, `abstained` or `skipped`, with an optional reason. */
case class StagePayload(name: String, status: String, detail: Option[String] = None)
    derives ReadWriter

case class ClaimVerificationPayload(
  claimId: String,
  verificationStatus: String,
  evidenceIds: Vector[String]
) derives ReadWriter

/** All evidence of one query (`GET /v1/evidence/{queryId}`). */
case class EvidenceListResponse(queryId: String, items: Vector[EvidenceResponse]) derives ReadWriter

/** `/readyz` body. `missing` names unset configuration only, never values. */
case class ReadinessResponse(status: String, mode: String, missing: Vector[String])
    derives ReadWriter

case class ErrorResponse(
  error: String,
  message: String,
  traceId: String,
  retryable: Boolean
) derives ReadWriter
