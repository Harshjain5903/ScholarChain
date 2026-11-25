package edu.scholarchain.services

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import upickle.default.*
import edu.scholarchain.core.*
import edu.scholarchain.rag.QueryPlannerAgent

/**
 * HTTP route definitions for the ScholarChain REST API.
 *
 * Results are durable: `POST /v1/query` persists the response and a job record through `results`
 * before answering, and the jobs, evidence and explain routes read only from `results` and
 * `auditStore`, so any task behind the load balancer can serve them.
 *
 * @param results
 *   durable result store; the default in-memory store is for stub mode and tests only
 * @param readiness
 *   configuration-presence readiness reported by `/readyz`
 */
class Routes(
  workflow: WorkflowPort,
  graphStore: ServiceGraphStore,
  auditStore: ServiceAuditStore,
  config: ServiceConfig,
  idempotencyStore: Option[IdempotencyStore] = None,
  results: ResultStore = InMemoryResultStore(),
  readiness: Readiness = Readiness(RuntimeMode.Stub, Vector.empty)
):

  private val runner = DeadlineRunner(config.maxConcurrentQueries)

  private val idempotency =
    idempotencyStore.getOrElse(
      IdempotencyStore(
        awaitTimeoutMs = config.syncDeadlineMs,
        leaderDeadlineMs = config.syncDeadlineMs,
        ttlMs = config.idempotencyTtlMs,
        maxEntries = config.idempotencyMaxEntries,
        maxKeyLength = config.idempotencyMaxKeyLength,
        runner = runner
      )
    )

  private val planner = QueryPlannerAgent()

  def start(): HttpServer =
    val server = HttpServer.create(
      new InetSocketAddress(config.host, config.port),
      0
    )
    server.setExecutor(Executors.newFixedThreadPool(10))
    server.createContext("/v1/query", queryHandler)
    server.createContext("/v1/jobs/", jobHandler)
    server.createContext("/v1/evidence/", evidenceHandler)
    server.createContext("/v1/graph/nodes/", graphHandler)
    server.createContext("/v1/explain/", explainHandler)
    server.createContext("/healthz", healthHandler)
    server.createContext("/readyz", readyHandler)
    server.start()
    server

  private def readBody(exchange: HttpExchange): String =
    val is    = exchange.getRequestBody
    val buf   = new ByteArrayOutputStream()
    val tmp   = new Array[Byte](4096)
    var total = 0
    var n     = is.read(tmp)
    while n >= 0 do
      total += n
      if total > config.maxRequestBodyBytes then
        is.close()
        throw BadRequestException(
          s"Request body exceeds ${config.maxRequestBodyBytes} bytes"
        )
      buf.write(tmp, 0, n)
      n = is.read(tmp)
    is.close()
    buf.toString("UTF-8")

  private def sendJson(
    exchange: HttpExchange,
    status: Int,
    body: String,
    tid: String
  ): Unit =
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.getResponseHeaders.add("X-Trace-Id", tid)
    val bytes = body.getBytes("UTF-8")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()

  private def newTraceId(): String = s"trace-${UUID.randomUUID()}"

  private def parseQueryParams(
    query: String
  ): Map[String, String] =
    if query == null || query.isEmpty then Map.empty
    else
      query
        .split("&")
        .flatMap { p =>
          val parts = p.split("=", 2)
          if parts.length == 2 then
            Some(
              java.net.URLDecoder.decode(parts(0), "UTF-8") ->
                java.net.URLDecoder.decode(parts(1), "UTF-8")
            )
          else None
        }
        .toMap

  /** Runs `body` and maps any failure to the typed error response. */
  private def handle(exchange: HttpExchange)(body: String => Unit): Unit =
    val tid = newTraceId()
    try body(tid)
    catch
      case e: Exception =>
        val (status, err) = ErrorHandler.toErrorResponse(e, tid)
        sendJson(exchange, status, write(err), tid)

  /** Path segment after `prefix`, URL-decoded. */
  private def pathTail(exchange: HttpExchange, prefix: String): String =
    exchange.getRequestURI.getPath.stripPrefix(prefix)

  private def requireGet(exchange: HttpExchange, route: String): Unit =
    if exchange.getRequestMethod != "GET" then
      throw BadRequestException(s"Only GET is supported on $route")

  private def parseRequest(body: String): QueryRequest =
    try read[QueryRequest](body)
    catch
      case _: upickle.core.AbortException | _: ujson.IncompleteParseException |
          _: ujson.ParseException | _: upickle.core.Abort =>
        throw BadRequestException("Invalid JSON in request body")

  private val queryHandler: HttpHandler = (exchange: HttpExchange) =>
    handle(exchange) { tid =>
      if exchange.getRequestMethod != "POST" then
        throw BadRequestException("Only POST is supported on /v1/query")
      val body = readBody(exchange)
      if body.trim.isEmpty then throw BadRequestException("Request body must not be empty")
      val request = parseRequest(body)
      if request.question.trim.isEmpty then throw BadRequestException("question must not be empty")

      val idempotencyKey = Option(exchange.getRequestHeaders.getFirst("Idempotency-Key"))

      // Atomic single-flight bound to the canonical request (same key + other body = 409).
      val json = idempotencyKey match
        case Some(key) =>
          val fingerprint = IdempotencyStore.fingerprint(write(request))
          idempotency.getOrCompute(key, fingerprint)(executeQuery(request))
        case None =>
          runner.run(config.syncDeadlineMs, "Query")(executeQuery(request))
      sendJson(exchange, 200, json, tid)
    }

  /** Executes the workflow, persists the result and job record, and returns the response JSON. */
  private def executeQuery(request: QueryRequest): String =
    val constraints = buildConstraints(request)
    val result =
      try workflow.execute(request.question, constraints)
      catch
        case e: Exception if Routes.isDownstreamFailure(e) =>
          throw DownstreamException(s"Downstream call failed: ${e.getClass.getSimpleName}")
    val queryId = result.response.queryId
    val jobId   = StoredQuery.jobIdFor(queryId)

    val claims = result.response.claims.map { c =>
      ClaimPayload(
        claimId = c.claimId,
        text = c.statement,
        evidenceIds = c.supportingEvidence,
        verificationStatus = c.verificationStatus.toString,
        uncertainty = None
      )
    }

    val response = QueryResponse(
      queryId = queryId,
      mode = "synchronous",
      answer = Some(
        AnswerPayload(
          summary = result.response.generatedText,
          researchers = Vector.empty
        )
      ),
      claims = Some(claims),
      links = Some(
        LinksPayload(
          evidence = s"/v1/evidence/$queryId",
          explain = s"/v1/explain/$queryId",
          job = Some(s"/v1/jobs/$jobId"),
          result = Some(s"/v1/jobs/$jobId/result")
        )
      ),
      jobId = Some(jobId)
    )

    val plan = planner.plan(request.question, constraints)
    val now  = java.time.Instant.now().toString
    val stored = StoredQuery(
      queryId = queryId,
      jobId = jobId,
      createdAt = now,
      response = response,
      workflow = result,
      plan = Some(
        QueryPlanPayload(
          operations = plan.operations.map(_.toString),
          filters = plan.filters,
          schemaVersion = plan.provenance.schemaVersion,
          source = StoredQuery.PlanSource
        )
      )
    )
    val job = JobResponse(
      jobId = jobId,
      queryId = queryId,
      state = "SUCCEEDED",
      stage = "COMPLETE",
      createdAt = now,
      updatedAt = now,
      resultUrl = Some(s"/v1/jobs/$jobId/result"),
      error = None
    )
    persist("query result")(results.putQuery(stored))
    persist("job record")(results.putJob(job))
    write(response)

  /** No answer without a durable record: a persistence failure is a retryable 502. */
  private def persist(what: String)(f: => Unit): Unit =
    try f
    catch
      case e: ServiceException => throw e
      case e: Exception =>
        throw DownstreamException(s"Failed to persist $what: ${e.getClass.getSimpleName}")

  private def buildConstraints(
    request: QueryRequest
  ): Map[String, String] =
    val b = Map.newBuilder[String, String]
    request.constraints.foreach { c =>
      c.yearFrom.foreach(v => b += ("yearFrom" -> v.toString))
      c.yearTo.foreach(v => b += ("yearTo" -> v.toString))
      c.topics.foreach(v => b += ("topics" -> v.mkString(",")))
      c.institutions.foreach(v => b += ("institutions" -> v.mkString(",")))
      c.venues.foreach(v => b += ("venues" -> v.mkString(",")))
    }
    b.result()

  /** Reads a downstream store, mapping unexpected failures to a retryable 502. */
  private def fromStore[A](what: String)(f: => A): A =
    try f
    catch
      case e: ServiceException => throw e
      case e: Exception =>
        throw DownstreamException(s"Failed to read $what: ${e.getClass.getSimpleName}")

  // GET /v1/jobs/{jobId} and /v1/jobs/{jobId}/result
  private val jobHandler: HttpHandler = (exchange: HttpExchange) =>
    handle(exchange) { tid =>
      requireGet(exchange, "/v1/jobs/")
      val tail = pathTail(exchange, "/v1/jobs/")
      val (jobId, wantResult) =
        if tail.endsWith("/result") then (tail.stripSuffix("/result"), true) else (tail, false)
      if jobId.isEmpty || jobId.contains("/") then
        throw BadRequestException("Expected /v1/jobs/{jobId} or /v1/jobs/{jobId}/result")
      val job = fromStore("job record")(results.getJob(jobId))
        .getOrElse(throw NotFoundException(s"Job $jobId not found"))
      if !wantResult then sendJson(exchange, 200, write(job), tid)
      else
        val stored = fromStore("query result")(results.getQuery(job.queryId))
          .getOrElse(throw NotFoundException(s"Result for job $jobId not found"))
        sendJson(exchange, 200, write(stored.response), tid)
    }

  private def evidencePayload(item: EvidenceItem): EvidenceResponse =
    EvidenceResponse(
      evidenceId = item.evidenceId,
      evidenceType = item.evidenceType.toString,
      sourceEntityId = item.sourceEntityId,
      sourceDataset = item.provenance.sourceDataset,
      text = item.content,
      textS3Uri = "",
      score = item.score,
      provenance = ProvenancePayload(
        runId = item.provenance.pipelineRunId,
        textHash = IdempotencyStore.fingerprint(item.content),
        schemaVersion = item.provenance.schemaVersion
      )
    )

  // GET /v1/evidence/{evidenceId} (one item) or /v1/evidence/{queryId} (all items of a query)
  private val evidenceHandler: HttpHandler = (exchange: HttpExchange) =>
    handle(exchange) { tid =>
      requireGet(exchange, "/v1/evidence/")
      val id = pathTail(exchange, "/v1/evidence/")
      if id.isEmpty then throw BadRequestException("Evidence ID must not be empty")
      IdGen.parseEvidenceId(id) match
        case Some((queryId, _)) =>
          val item = fromStore("query result")(results.getQuery(queryId))
            .flatMap(_.workflow.response.evidenceBundle.items.find(_.evidenceId == id))
            .getOrElse(throw NotFoundException(s"Evidence $id not found"))
          sendJson(exchange, 200, write(evidencePayload(item)), tid)
        case None if ResultStore.isQueryId(id) =>
          val stored = fromStore("query result")(results.getQuery(id))
            .getOrElse(throw NotFoundException(s"Evidence $id not found"))
          val items = stored.workflow.response.evidenceBundle.items.map(evidencePayload)
          sendJson(exchange, 200, write(EvidenceListResponse(id, items)), tid)
        case None =>
          throw NotFoundException(s"Evidence $id not found")
    }

  /** Declared, well-formed edge types; anything else is a 400 (review finding N43). */
  private def parseEdgeTypes(raw: Option[String]): Vector[String] =
    val types = raw.toVector.flatMap(_.split(",").map(_.trim).filter(_.nonEmpty))
    types.foreach { t =>
      if !GraphSchema.isValidEdgeTypeIdentifier(t) || !GraphSchema.isDeclaredEdgeType(t) then
        throw BadRequestException(
          s"Undeclared edge type '${t.take(64)}'; expected one of " +
            GraphSchema.EdgeType.All.mkString(", ")
        )
    }
    types.distinct

  private def intParam(params: Map[String, String], name: String, default: Int): Int =
    params.get(name) match
      case None => default
      case Some(v) =>
        v.trim.toIntOption.getOrElse(throw BadRequestException(s"$name must be an integer"))

  private val graphHandler: HttpHandler = (exchange: HttpExchange) =>
    handle(exchange) { tid =>
      requireGet(exchange, "/v1/graph/nodes/")
      // /v1/graph/nodes/{nodeId}/neighbors
      val parts = pathTail(exchange, "/v1/graph/nodes/").split("/")
      if parts.length != 2 || parts(0).isEmpty || parts(1) != "neighbors" then
        throw BadRequestException("Expected /v1/graph/nodes/{nodeId}/neighbors")
      val nodeId = parts(0)

      val params    = parseQueryParams(exchange.getRequestURI.getRawQuery)
      val depth     = intParam(params, "depth", 1)
      val limit     = intParam(params, "limit", 50)
      val edgeTypes = parseEdgeTypes(params.get("edgeTypes"))

      if depth < 1 || depth > config.maxGraphDepth then
        throw BadRequestException(s"depth must be between 1 and ${config.maxGraphDepth}")
      if limit < 1 || limit > config.maxGraphLimit then
        throw BadRequestException(s"limit must be between 1 and ${config.maxGraphLimit}")

      val neighbors = fromStore("graph")(graphStore.getNeighbors(nodeId, edgeTypes, depth, limit))
      val response = neighbors.map(n =>
        NeighborResponse(nodeId = n.nodeId, nodeType = n.nodeType, properties = n.properties)
      )
      sendJson(exchange, 200, write(response), tid)
    }

  private val explainHandler: HttpHandler = (exchange: HttpExchange) =>
    handle(exchange) { tid =>
      requireGet(exchange, "/v1/explain/")
      val queryId = pathTail(exchange, "/v1/explain/")
      if queryId.isEmpty then throw BadRequestException("Query ID must not be empty")
      if !ResultStore.isQueryId(queryId) then
        throw NotFoundException(s"Explain record for query $queryId not found")
      val audit = fromStore("audit record")(auditStore.getAudit(queryId))
        .getOrElse(throw NotFoundException(s"Explain record for query $queryId not found"))
      val stored = fromStore("query result")(results.getQuery(queryId))
      sendJson(exchange, 200, write(Explain.build(audit, stored)), tid)
    }

  private val healthHandler: HttpHandler = (exchange: HttpExchange) =>
    val tid = newTraceId()
    sendJson(exchange, 200, """{"status":"ok"}""", tid)

  private val readyHandler: HttpHandler = (exchange: HttpExchange) =>
    val tid = newTraceId()
    val body = ReadinessResponse(
      status = if readiness.ready then "ready" else "not-ready",
      mode = readiness.mode.wireName,
      missing = readiness.missing
    )
    sendJson(exchange, if readiness.ready then 200 else 503, write(body), tid)

object Routes:

  /**
   * AWS, Neptune, S3 Vectors and model-call failures that escape the workflow (for example the
   * audit write, which must succeed before an answer is returned) are retryable 502s, not 500s.
   */
  def isDownstreamFailure(e: Throwable): Boolean = e match
    case _: software.amazon.awssdk.core.exception.SdkException => true
    case _: edu.scholarchain.storage.NeptuneRequestException   => true
    case _: edu.scholarchain.storage.S3VectorsRequestException => true
    case _: edu.scholarchain.rag.ModelCallException            => true
    case _: java.io.IOException                                => true
    case _                                                     => false
