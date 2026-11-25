package edu.scholarchain.rag

import edu.scholarchain.core.*
import edu.scholarchain.storage.RankableResearcherMetric
import upickle.default.*

trait ResearcherLookup:
  def findByName(name: String): Vector[CanonicalResearcher]
  def findById(id: String): Option[CanonicalResearcher]

/**
 * Read port for Spark-computed [[ResearcherMetrics]]. Implementations return stored values only and
 * never fabricate a row; a researcher without stored metrics for the window is simply absent.
 */
trait MetricsStore:

  def getMetrics(
    researcherId: String,
    windowStart: Int,
    windowEnd: Int
  ): Option[ResearcherMetrics]

  /** Metrics for several researchers; the default makes one [[getMetrics]] call per distinct ID. */
  def getMetricsBatch(
    researcherIds: Vector[String],
    windowStart: Int,
    windowEnd: Int
  ): Map[String, ResearcherMetrics] =
    researcherIds.distinct.flatMap(id => getMetrics(id, windowStart, windowEnd).map(id -> _)).toMap

  /**
   * The `limit` researchers with the highest value of an allowlisted metric for exactly the window
   * `windowStart..windowEnd`, highest first. Only [[RankableResearcherMetric]] cases can be asked
   * for, so free text never selects the ranked property. The default (a store that cannot rank)
   * returns nothing, so a ranking answer abstains rather than inventing an order.
   */
  def topResearchers(
    metric: RankableResearcherMetric,
    windowStart: Int,
    windowEnd: Int,
    limit: Int
  ): Vector[ResearcherMetrics] =
    Vector.empty

trait GraphStore:

  def getDirectCoauthors(
    researcherId: String,
    limit: Int
  ): Vector[CoauthorEdge]

  def shortestPath(
    fromId: String,
    toId: String,
    maxDepth: Int
  ): Option[Vector[String]]

  def getNeighbors(
    nodeId: String,
    edgeTypes: Vector[String],
    depth: Int,
    limit: Int
  ): Vector[GraphNode]

  def getComponentMembers(
    componentId: String,
    limit: Int
  ): Vector[String]

trait VectorStore:

  def query(
    indexName: String,
    queryVector: Vector[Float],
    topK: Int,
    filters: Option[ujson.Value]
  ): Vector[VectorSearchResult]

case class VectorSearchResult(
  key: String,
  score: Double,
  documentType: String,
  entityId: String,
  textS3Uri: String
) derives ReadWriter

trait TextStore:
  def getText(s3Uri: String): Option[String]

/**
 * A query embedding together with the identity of the model that actually produced it.
 *
 * @param vector
 *   the embedding vector, passed unchanged to the vector store
 * @param modelId
 *   the model id reported by the embedding backend (not the requested id)
 */
case class QueryEmbedding(vector: Vector[Float], modelId: String)

/**
 * Embedding port for runtime query text.
 *
 * Implementations call the embedding backend for `modelId` and must report the model that produced
 * the vector in `QueryEmbedding.modelId`. They may throw on backend failure; callers treat that as
 * an abstention, never as a reason to substitute a placeholder vector.
 *
 * Production implementations: [[TitanQueryEmbedder]] (Bedrock) and [[VoyageQueryEmbedder]]; select
 * one with [[ModelAdapters.embedder]]. Tests use deterministic fakes.
 */
trait QueryEmbedder:
  def embed(text: String, modelId: String): QueryEmbedding

/**
 * One LLM call: a system instruction, a single user message and an output-token bound.
 *
 * @param system
 *   trusted instructions; may be empty
 * @param user
 *   the user turn; untrusted retrieved text must already be delimited and escaped
 */
case class LlmRequest(system: String, user: String, maxTokens: Int):
  require(maxTokens >= 1, s"maxTokens must be positive, got $maxTokens")

/**
 * Text-generation port. Production implementations: [[BedrockConverseLlmClient]] and
 * [[AnthropicMessagesLlmClient]]; select one with [[ModelAdapters.llm]].
 *
 * Implementations may throw [[ModelCallException]] after their bounded retries; callers abstain.
 */
trait LlmClient:
  def generate(prompt: String, maxTokens: Int): String

  /** Structured call. The default joins system and user text for simple single-prompt fakes. */
  def complete(request: LlmRequest): String =
    val prompt =
      if request.system.isEmpty then request.user else request.system + "\n\n" + request.user
    generate(prompt, request.maxTokens)

  /** Model identity recorded in audit records. */
  def modelId: String = "unknown"

trait AuditStore:
  def persistAudit(record: AuditRecord): Unit

case class AuditRecord(
  queryId: String,
  resolvedIds: Vector[String],
  evidenceIds: Vector[String],
  promptVersion: String,
  modelVersion: String,
  latencyMs: Long,
  verificationSummary: String,
  timestamp: String,
  promptHash: String = "",
  synthesisMode: String = "",
  removedClaimIds: Vector[String] = Vector.empty,
  abstentionReason: Option[String] = None
) derives ReadWriter
