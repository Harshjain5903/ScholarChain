package edu.scholarchain.rag

import edu.scholarchain.core.*
import upickle.default.*

trait ResearcherLookup:
  def findByName(name: String): Vector[CanonicalResearcher]
  def findById(id: String): Option[CanonicalResearcher]

trait MetricsStore:

  def getMetrics(
    researcherId: String,
    windowStart: Int,
    windowEnd: Int
  ): Option[ResearcherMetrics]

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
 * No production (Bedrock) implementation exists yet; tests use deterministic fakes.
 */
trait QueryEmbedder:
  def embed(text: String, modelId: String): QueryEmbedding

trait LlmClient:
  def generate(prompt: String, maxTokens: Int): String

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
  timestamp: String
) derives ReadWriter
