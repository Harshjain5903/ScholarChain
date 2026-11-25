package edu.scholarchain.services

import edu.scholarchain.core.*
import edu.scholarchain.rag.{DynamoAuditStore, WorkflowResult}
import edu.scholarchain.storage.DynamoDbStorageClient
import upickle.default.*

import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * One answered query as persisted by the service.
 *
 * @param response
 *   the exact `POST /v1/query` response body, replayed by `GET /v1/jobs/{jobId}/result`
 * @param workflow
 *   the workflow answer (claims, evidence bundle with provenance) and its audit record
 * @param plan
 *   the query plan; see [[StoredQuery.PlanSource]]
 */
case class StoredQuery(
  queryId: String,
  jobId: String,
  createdAt: String,
  response: QueryResponse,
  workflow: WorkflowResult,
  plan: Option[QueryPlanPayload],
  evidenceContentTruncated: Boolean = false
) derives ReadWriter

object StoredQuery:

  /**
   * The rag `WorkflowResult` does not carry the executed `QueryPlan`. `QueryPlannerAgent.plan` is
   * pure apart from its random query ID and timestamp, so the service re-plans the same question
   * and constraints and records the operations and filters with this source tag.
   */
  val PlanSource: String = "service-replay:QueryPlannerAgent"

  /** Deterministic job ID of a synchronous query. */
  def jobIdFor(queryId: String): String =
    "job-" + IdempotencyStore.fingerprint(queryId).take(32)

  /**
   * Shrinks evidence item content until the JSON fits `maxBytes` (DynamoDB item limit). Each step
   * keeps a shorter prefix of every item's content and sets `evidenceContentTruncated`; IDs, scores
   * and provenance are always kept.
   */
  def fitting(stored: StoredQuery, maxBytes: Int): (StoredQuery, String) =
    def size(json: String) = json.getBytes(StandardCharsets.UTF_8).length
    val full               = write(stored)
    if size(full) <= maxBytes then (stored, full)
    else
      val steps = Vector(2000, 500, 100, 0)
      val shrunk = steps.iterator
        .map { n =>
          val bundle = stored.workflow.response.evidenceBundle
          val items  = bundle.items.map(i => i.copy(content = i.content.take(n)))
          val s = stored.copy(
            workflow = stored.workflow.copy(response =
              stored.workflow.response.copy(evidenceBundle = bundle.copy(items = items))
            ),
            evidenceContentTruncated = true
          )
          (s, write(s))
        }
        .find((_, json) => size(json) <= maxBytes)
      shrunk.getOrElse(
        throw DownstreamException(
          s"query result exceeds the ${maxBytes}-byte durable result limit even without evidence text"
        )
      )

/** Durable store for query results and job records. */
trait ResultStore:
  /** Idempotent: a second put for the same query ID keeps the first result. */
  def putQuery(stored: StoredQuery): Unit
  def getQuery(queryId: String): Option[StoredQuery]

  /** Idempotent: a second put for the same job ID keeps the first record. */
  def putJob(job: JobResponse): Unit
  def getJob(jobId: String): Option[JobResponse]

object ResultStore:
  /** Key prefixes reserved for non-result records in the query-session table. */
  val JobPrefix: String = "job:"

  val ReservedPrefixes: Vector[String] = Vector(JobPrefix, DynamoAuditStore.key(""))

  /** True when `id` could address a stored query result (not a job or audit record). */
  def isQueryId(id: String): Boolean =
    id.trim.nonEmpty && !ReservedPrefixes.exists(id.startsWith)

/**
 * [[ResultStore]] over `DynamoDbStorageClient.putQueryResult` / `getQueryResult` (conditional put,
 * TTL, strongly consistent reads). Results are stored under the query ID, job records under
 * `job:<jobId>`; audit records (written by the rag workflow) live under `audit:<queryId>`.
 */
final class DynamoResultStore(dynamo: DynamoDbStorageClient, ttl: Duration) extends ResultStore:

  def putQuery(stored: StoredQuery): Unit =
    val (_, json) = StoredQuery.fitting(stored, DynamoDbStorageClient.MaxQueryResultBytes)
    dynamo.putQueryResult(stored.queryId, json, ttl): Unit

  def getQuery(queryId: String): Option[StoredQuery] =
    if !ResultStore.isQueryId(queryId) then None
    else dynamo.getQueryResult(queryId).map(read[StoredQuery](_))

  def putJob(job: JobResponse): Unit =
    dynamo.putQueryResult(ResultStore.JobPrefix + job.jobId, write(job), ttl): Unit

  def getJob(jobId: String): Option[JobResponse] =
    if jobId.trim.isEmpty then None
    else dynamo.getQueryResult(ResultStore.JobPrefix + jobId).map(read[JobResponse](_))

/**
 * In-memory [[ResultStore]] for stub mode and tests only. Keeps the first write per key (like the
 * conditional put) and at most `maxEntries` results and jobs (oldest evicted first).
 */
final class InMemoryResultStore(maxEntries: Int = 1000) extends ResultStore:

  private def bounded[V]: java.util.Map[String, V] =
    java.util.Collections.synchronizedMap(
      new java.util.LinkedHashMap[String, V](16, 0.75f, false):
        override def removeEldestEntry(e: java.util.Map.Entry[String, V]): Boolean =
          size() > maxEntries
    )

  private val queries = bounded[StoredQuery]
  private val jobs    = bounded[JobResponse]

  def putQuery(stored: StoredQuery): Unit = queries.putIfAbsent(stored.queryId, stored): Unit
  def getQuery(queryId: String): Option[StoredQuery] = Option(queries.get(queryId))
  def putJob(job: JobResponse): Unit                 = jobs.putIfAbsent(job.jobId, job): Unit
  def getJob(jobId: String): Option[JobResponse]     = Option(jobs.get(jobId))
