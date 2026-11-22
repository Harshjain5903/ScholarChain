package edu.scholarchain.services

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.io.{ByteArrayOutputStream, InputStream}
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, Executors}
import upickle.default.*
import edu.scholarchain.core.*
import edu.scholarchain.rag.{AuditRecord, WorkflowResult}

/** HTTP route definitions for the ScholarChain REST API. */
class Routes(
    workflow: WorkflowPort,
    graphStore: ServiceGraphStore,
    auditStore: ServiceAuditStore,
    config: ServiceConfig
):

  private val idempotencyCache = new ConcurrentHashMap[String, String]()
  private val queryResults     = new ConcurrentHashMap[String, WorkflowResult]()
  private val jobStore         = new ConcurrentHashMap[String, JobResponse]()

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
    val is  = exchange.getRequestBody
    val buf = new ByteArrayOutputStream()
    val tmp = new Array[Byte](4096)
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

  private val queryHandler: HttpHandler = (exchange: HttpExchange) =>
    val tid = newTraceId()
    try
      exchange.getRequestMethod match
        case "POST" =>
          val body = readBody(exchange)
          if body.trim.isEmpty then
            throw BadRequestException("Request body must not be empty")

          val request =
            try read[QueryRequest](body)
            catch
              case _: upickle.core.AbortException =>
                throw BadRequestException("Invalid JSON in request body")
              case _: ujson.IncompleteParseException =>
                throw BadRequestException("Invalid JSON in request body")
              case _: ujson.ParseException =>
                throw BadRequestException("Invalid JSON in request body")
              case _: upickle.core.Abort =>
                throw BadRequestException("Invalid JSON in request body")

          if request.question.trim.isEmpty then
            throw BadRequestException("question must not be empty")

          val idempotencyKey = Option(
            exchange.getRequestHeaders.getFirst("Idempotency-Key")
          )
          val cached = idempotencyKey.flatMap(key =>
            Option(idempotencyCache.get(key))
          )

          cached match
            case Some(cachedJson) =>
              sendJson(exchange, 200, cachedJson, tid)
            case None =>
              val constraints = buildConstraints(request)
              val result =
                workflow.execute(request.question, constraints)
              val queryId = result.response.queryId
              queryResults.put(queryId, result)

              val claims = result.response.claims.map { c =>
                ClaimPayload(
                  claimId = c.claimId,
                  text = c.statement,
                  evidenceIds = c.supportingEvidence,
                  verificationStatus =
                    c.verificationStatus.toString,
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
                    explain = s"/v1/explain/$queryId"
                  )
                )
              )

              val json = write(response)
              idempotencyKey.foreach(key =>
                idempotencyCache.put(key, json)
              )
              sendJson(exchange, 200, json, tid)

        case _ =>
          throw BadRequestException("Only POST is supported on /v1/query")
    catch
      case e: ServiceException =>
        val (status, err) = ErrorHandler.toErrorResponse(e, tid)
        sendJson(exchange, status, write(err), tid)
      case e: Exception =>
        val (status, err) = ErrorHandler.toErrorResponse(e, tid)
        sendJson(exchange, status, write(err), tid)

  private def buildConstraints(
      request: QueryRequest
  ): Map[String, String] =
    val b = Map.newBuilder[String, String]
    request.constraints.foreach { c =>
      c.yearFrom.foreach(v => b += ("yearFrom" -> v.toString))
      c.yearTo.foreach(v => b += ("yearTo" -> v.toString))
      c.topics.foreach(v => b += ("topics" -> v.mkString(",")))
      c.institutions.foreach(v =>
        b += ("institutions" -> v.mkString(","))
      )
      c.venues.foreach(v => b += ("venues" -> v.mkString(",")))
    }
    b.result()

  private val jobHandler: HttpHandler = (exchange: HttpExchange) =>
    val tid = newTraceId()
    try
      exchange.getRequestMethod match
        case "GET" =>
          val path  = exchange.getRequestURI.getPath
          val jobId = path.stripPrefix("/v1/jobs/")
          if jobId.isEmpty then
            throw BadRequestException("Job ID must not be empty")
          val job = jobStore.get(jobId)
          if job == null then
            throw NotFoundException(s"Job $jobId not found")
          sendJson(exchange, 200, write(job), tid)
        case _ =>
          throw BadRequestException("Only GET is supported on /v1/jobs/")
    catch
      case e: ServiceException =>
        val (status, err) = ErrorHandler.toErrorResponse(e, tid)
        sendJson(exchange, status, write(err), tid)
      case e: Exception =>
        val (status, err) = ErrorHandler.toErrorResponse(e, tid)
        sendJson(exchange, status, write(err), tid)

  private val evidenceHandler: HttpHandler = (exchange: HttpExchange) =>
    val tid = newTraceId()
    try
      exchange.getRequestMethod match
        case "GET" =>
          val path       = exchange.getRequestURI.getPath
          val evidenceId = path.stripPrefix("/v1/evidence/")
          if evidenceId.isEmpty then
            throw BadRequestException("Evidence ID must not be empty")

          val found = queryResults.values().iterator()
          var result: Option[EvidenceItem] = None
          while found.hasNext && result.isEmpty do
            val wr = found.next()
            result = wr.response.evidenceBundle.items.find(
              _.evidenceId == evidenceId
            )

          result match
            case Some(item) =>
              val resp = EvidenceResponse(
                evidenceId = item.evidenceId,
                evidenceType = item.evidenceType.toString,
                sourceEntityId = item.sourceEntityId,
                sourceDataset = item.provenance.sourceDataset,
                text = item.content,
                textS3Uri = "",
                score = item.score,
                provenance = ProvenancePayload(
                  runId = item.provenance.pipelineRunId,
                  textHash = "",
                  schemaVersion = item.provenance.schemaVersion
                )
              )
              sendJson(exchange, 200, write(resp), tid)
            case None =>
              throw NotFoundException(
                s"Evidence $evidenceId not found"
              )
        case _ =>
          throw BadRequestException(
            "Only GET is supported on /v1/evidence/"
          )
    catch
      case e: ServiceException =>
        val (status, err) = ErrorHandler.toErrorResponse(e, tid)
        sendJson(exchange, status, write(err), tid)
      case e: Exception =>
        val (status, err) = ErrorHandler.toErrorResponse(e, tid)
        sendJson(exchange, status, write(err), tid)

  private val graphHandler: HttpHandler = (exchange: HttpExchange) =>
    val tid = newTraceId()
    try
      exchange.getRequestMethod match
        case "GET" =>
          val path = exchange.getRequestURI.getPath
          // /v1/graph/nodes/{nodeId}/neighbors
          val stripped = path.stripPrefix("/v1/graph/nodes/")
          val parts    = stripped.split("/")
          if parts.length < 2 || parts(1) != "neighbors" then
            throw BadRequestException(
              "Expected /v1/graph/nodes/{nodeId}/neighbors"
            )
          val nodeId = parts(0)

          val params =
            parseQueryParams(exchange.getRequestURI.getQuery)
          val depth =
            params.get("depth").map(_.toInt).getOrElse(1)
          val limit =
            params.get("limit").map(_.toInt).getOrElse(50)
          val edgeTypes = params
            .get("edgeTypes")
            .map(_.split(",").toVector)
            .getOrElse(Vector.empty)

          if depth < 1 || depth > config.maxGraphDepth then
            throw BadRequestException(
              s"depth must be between 1 and ${config.maxGraphDepth}"
            )
          if limit < 1 || limit > config.maxGraphLimit then
            throw BadRequestException(
              s"limit must be between 1 and ${config.maxGraphLimit}"
            )

          val neighbors =
            graphStore.getNeighbors(nodeId, edgeTypes, depth, limit)
          val response = neighbors.map(n =>
            NeighborResponse(
              nodeId = n.nodeId,
              nodeType = n.nodeType,
              properties = n.properties
            )
          )
          sendJson(exchange, 200, write(response), tid)
        case _ =>
          throw BadRequestException(
            "Only GET is supported on /v1/graph/nodes/"
          )
    catch
      case e: ServiceException =>
        val (status, err) = ErrorHandler.toErrorResponse(e, tid)
        sendJson(exchange, status, write(err), tid)
      case e: Exception =>
        val (status, err) = ErrorHandler.toErrorResponse(e, tid)
        sendJson(exchange, status, write(err), tid)

  private val explainHandler: HttpHandler = (exchange: HttpExchange) =>
    val tid = newTraceId()
    try
      exchange.getRequestMethod match
        case "GET" =>
          val path    = exchange.getRequestURI.getPath
          val queryId = path.stripPrefix("/v1/explain/")
          if queryId.isEmpty then
            throw BadRequestException("Query ID must not be empty")

          val audit = auditStore.getAudit(queryId)
          audit match
            case Some(a) =>
              val resp = ExplainResponse(
                queryId = a.queryId,
                queryPlan = "collaboration-analysis",
                retrievalModes = Vector("graph", "vector", "metrics"),
                promptVersions = Vector(a.promptVersion),
                modelVersions = Vector(a.modelVersion),
                evidenceIds = a.evidenceIds,
                verificationSummary = a.verificationSummary,
                latencyMs = a.latencyMs,
                cacheHits = 0
              )
              sendJson(exchange, 200, write(resp), tid)
            case None =>
              throw NotFoundException(
                s"Explain record for query $queryId not found"
              )
        case _ =>
          throw BadRequestException(
            "Only GET is supported on /v1/explain/"
          )
    catch
      case e: ServiceException =>
        val (status, err) = ErrorHandler.toErrorResponse(e, tid)
        sendJson(exchange, status, write(err), tid)
      case e: Exception =>
        val (status, err) = ErrorHandler.toErrorResponse(e, tid)
        sendJson(exchange, status, write(err), tid)

  private val healthHandler: HttpHandler = (exchange: HttpExchange) =>
    val tid = newTraceId()
    sendJson(exchange, 200, """{"status":"ok"}""", tid)

  private val readyHandler: HttpHandler = (exchange: HttpExchange) =>
    val tid = newTraceId()
    sendJson(exchange, 200, """{"status":"ready"}""", tid)
