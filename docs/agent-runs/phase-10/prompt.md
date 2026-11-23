Use the service-engineer agent to implement ScholarChain RESTful microservices under modules/services/.

Create 8 files: ServiceConfig.scala, JsonCodecs.scala, ServiceExceptions.scala, ErrorHandler.scala, WorkflowPort.scala, Routes.scala, RuntimeComposer.scala, ScholarChainServer.scala plus ServiceRoutesSpec.scala with 18 tests.

7 endpoints on JDK HttpServer: POST /v1/query, GET /v1/jobs/{jobId}, GET /v1/evidence/{evidenceId}, GET /v1/graph/nodes/{nodeId}/neighbors, GET /v1/explain/{queryId}, GET /healthz, GET /readyz.

All JSON via uPickle. Idempotency-Key support. Trace IDs on every response. Bounded request body, graph depth 1-3, limit 1-200.


## Exact human work orders (RECONSTRUCTED 2026-11-23)
Copied word for word from the saved Claude Code session transcripts (`~/.claude/projects/-Users-harshjain-Documents-scholarchain/<session>.jsonl`). Text above this section is the original file and is unchanged.

### Work order (2026-11-22T03:45Z)

Session `7aafb09f-5927-4b74-9c74-70dface1b0d2`, message `7b393354-4aaa-4a2c-ba8e-13e54aee92dc`, sent 2026-11-22T03:45:09.900Z.

````text
Use the service-engineer agent to implement ScholarChain RESTful microservices under modules/services/.

Read CLAUDE.md and docs/agents.md first. Then inspect:
- modules/core/src/main/scala/edu/scholarchain/core/ (domain records, enums, config)
- modules/rag/src/main/scala/edu/scholarchain/rag/ (RuntimeWorkflow, Ports, all agents)
- modules/storage/src/main/scala/edu/scholarchain/storage/ (all storage clients)
- build.sbt (services project dependencies)

Create these files under modules/services/src/main/scala/edu/scholarchain/services/:

1. ServiceConfig.scala
- case class ServiceConfig(port, host, syncDeadlineMs, maxRequestBodyBytes, maxGraphDepth, maxGraphLimit, neptuneEndpoint, dynamoTablePrefix, vectorBucket, ragBucket, embeddingModel, synthesisModel, verificationModel)
- Load from environment variables with sensible defaults
- port default 8080, syncDeadlineMs default 15000, maxRequestBodyBytes default 1048576
- maxGraphDepth default 3 (bounded 1-3), maxGraphLimit default 200 (bounded 1-200)

2. JsonCodecs.scala
- uPickle ReadWriter codecs for all request/response types
- case class QueryRequest(question, constraints: Option[QueryConstraints], analysis: Option[AnalysisOptions], retrieval: Option[RetrievalOptions], output: Option[OutputOptions])
- case class QueryConstraints(yearFrom: Option[Int], yearTo: Option[Int], topics: Option[Vector[String]], institutions: Option[Vector[String]], venues: Option[Vector[String]])
- case class AnalysisOptions(rankingMetric: Option[String], includeTeamSize: Option[Boolean], includeBridgeMetrics: Option[Boolean], includeCommunities: Option[Boolean], includePaths: Option[Boolean])
- case class RetrievalOptions(graphLimit: Option[Int], vectorLimit: Option[Int], maxPathDepth: Option[Int])
- case class OutputOptions(includeEvidence: Option[Boolean], includeScores: Option[Boolean], includeUncertainty: Option[Boolean])
- case class QueryResponse(queryId, mode, answer: Option[AnswerPayload], claims: Option[Vector[ClaimPayload]], links: Option[LinksPayload])
- case class AsyncResponse(queryId, mode, jobId, status, statusUrl)
- case class AnswerPayload(summary, researchers: Vector[ResearcherPayload])
- case class ResearcherPayload(researcherId, displayName, publicationCount, distinctCoauthorCount, weightedDegree, averageCoauthorsPerPaper, maximumCoauthorsOnOnePaper, maximumTeamWorkIds, componentId, communityId, bridgeMetric: Option[BridgeMetricPayload], claimIds)
- case class BridgeMetricPayload(name, value: Double)
- case class ClaimPayload(claimId, text, evidenceIds, verificationStatus, uncertainty: Option[String])
- case class LinksPayload(evidence, explain)
- case class JobResponse(jobId, queryId, state, stage, createdAt, updatedAt, resultUrl: Option[String], error: Option[String])
- case class EvidenceResponse(evidenceId, evidenceType, workId, sourceDataset, sourceRecordId, snapshot, text, textS3Uri, score, provenance: ProvenancePayload)
- case class ProvenancePayload(runId, textHash, schemaVersion)
- case class NeighborResponse(nodeId, nodeType, properties: Map[String,String], edges: Vector[EdgePayload])
- case class EdgePayload(edgeId, edgeType, targetNodeId, properties: Map[String,String])
- case class ExplainResponse(queryId, queryPlan, retrievalModes, promptVersions, modelVersions, evidenceIds, verificationSummary, latencyMs, cacheHits: Int)
- case class ErrorResponse(error, message, traceId, retryable: Boolean)
- All derive ReadWriter

3. ServiceExceptions.scala
- sealed trait ServiceException extends Exception
- case class BadRequestException(msg) extends ServiceException
- case class NotFoundException(msg) extends ServiceException
- case class ConflictException(msg) extends ServiceException
- case class UnprocessableException(msg) extends ServiceException
- case class TimeoutException(msg) extends ServiceException
- case class DownstreamException(msg) extends ServiceException

4. ErrorHandler.scala
- object ErrorHandler with method toErrorResponse(e: Throwable, traceId: String): (Int, ErrorResponse)
- Map ServiceException subtypes to HTTP status codes: BadRequest→400, NotFound→404, Conflict→409, Unprocessable→422, Timeout→504, Downstream→502
- All others → 500 with generic message (don't leak internals)
- Always include traceId

5. Routes.scala
- class Routes(workflow: RuntimeWorkflow, config: ServiceConfig) — but RuntimeWorkflow is from rag module, so use a trait WorkflowPort instead:
  trait WorkflowPort:
    def execute(question: String, constraints: Map[String,String]): WorkflowResult
- class Routes(workflow: WorkflowPort, graphStore: GraphStore, evidenceStore: AuditStore, config: ServiceConfig)
- Use com.sun.net.httpserver.HttpServer (JDK built-in, no extra dependency)
- Each route is an HttpHandler registered on the HttpServer
- Generate traceId (UUID) for every request, add to response headers as X-Trace-Id
- Support Idempotency-Key header for POST — store in ConcurrentHashMap, return cached response on duplicate
- Bound request body to config.maxRequestBodyBytes

  POST /v1/query
  - Parse QueryRequest JSON body
  - Convert to workflow constraints map
  - Execute workflow synchronously
  - Return QueryResponse with mode="synchronous"
  - On timeout, return AsyncResponse with mode="asynchronous", jobId, status="QUEUED"

  GET /v1/jobs/{jobId}
  - Return JobResponse (use in-memory ConcurrentHashMap for job tracking)
  - 404 if jobId unknown

  GET /v1/evidence/{evidenceId}
  - Look up evidence from last query result (in-memory cache)
  - Return EvidenceResponse
  - 404 if not found

  GET /v1/graph/nodes/{nodeId}/neighbors
  - Parse query params: direction (default "both"), depth (default 1, max config.maxGraphDepth), limit (default 50, max config.maxGraphLimit), edgeTypes (comma-separated, default all)
  - Validate bounds, return 400 on violation
  - Query graphStore.getNeighbors
  - Return Vector[NeighborResponse]

  GET /v1/explain/{queryId}
  - Look up audit record for queryId
  - Return ExplainResponse
  - Do NOT return hidden chain-of-thought
  - 404 if not found

  GET /healthz — always return 200 {"status":"ok"}
  GET /readyz — return 200 if workflow port is available, 503 otherwise

6. RuntimeComposer.scala
- object RuntimeComposer
- def compose(config: ServiceConfig): (WorkflowPort, GraphStore, AuditStore)
- Create fake/stub implementations of all ports for local testing
- In production, would wire real Neptune, DynamoDB, S3Vectors, Bedrock clients
- For now, return stub implementations that return empty/default values
- This keeps services module compilable and testable without AWS

7. ScholarChainServer.scala
- object ScholarChainServer extends App (or @main def)
- Load ServiceConfig from environment
- Compose runtime via RuntimeComposer
- Create Routes and start HttpServer
- Register shutdown hook to stop server
- Log startup message with port and host

Create tests under modules/services/src/test/scala/edu/scholarchain/services/ServiceRoutesSpec.scala:

Use a single test file. Start a real HttpServer on a random port in beforeAll, stop in afterAll. Use java.net.http.HttpClient for requests.

Required tests (at least 15):

1. POST /v1/query with valid request returns 200 with queryId and mode=synchronous
2. POST /v1/query with empty body returns 400
3. POST /v1/query with invalid JSON returns 400
4. POST /v1/query with oversized body returns 400
5. POST /v1/query with Idempotency-Key returns same response on replay
6. GET /v1/jobs/{jobId} with unknown ID returns 404
7. GET /v1/evidence/{evidenceId} with unknown ID returns 404
8. GET /v1/graph/nodes/{nodeId}/neighbors with default params returns 200
9. GET /v1/graph/nodes/{nodeId}/neighbors with depth=0 returns 400
10. GET /v1/graph/nodes/{nodeId}/neighbors with depth=4 returns 400 (exceeds max 3)
11. GET /v1/graph/nodes/{nodeId}/neighbors with limit=201 returns 400
12. GET /v1/explain/{queryId} with unknown ID returns 404
13. GET /healthz returns 200 with status ok
14. GET /readyz returns 200 when ready
15. All responses include X-Trace-Id header

Requirements:
- Use ONLY com.sun.net.httpserver (JDK built-in) — no Akka, http4s, or external HTTP libs
- All JSON via uPickle (already in commonSettings)
- No hardcoded endpoints, bucket names, or model IDs
- Thin routes — business logic stays in rag module
- Trace ID on every response
- Request body size bounded
- Graph depth bounded 1-3, limit bounded 1-200
- Error responses always include traceId and are typed
- Do NOT modify files outside modules/services/

Run sbt check after implementation.
Report: route table, status-code mapping, timeout policy, authentication method, and test counts.
````
