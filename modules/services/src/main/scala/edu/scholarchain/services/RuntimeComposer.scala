package edu.scholarchain.services

import edu.scholarchain.core.*
import edu.scholarchain.rag.*
import edu.scholarchain.storage.*

import java.time.Duration

/** How the service is wired: in-memory stubs (local runs, tests) or the real AWS-backed runtime. */
enum RuntimeMode(val wireName: String):
  case Stub extends RuntimeMode("stub")
  case Aws  extends RuntimeMode("aws")

object RuntimeMode:
  val EnvVar: String = "SCHOLARCHAIN_RUNTIME_MODE"

  /** Unset or blank is [[Stub]]; otherwise `stub` or `aws` (case-insensitive). */
  def parse(value: Option[String]): Either[String, RuntimeMode] =
    value.map(_.trim.toLowerCase).filter(_.nonEmpty) match
      case None => Right(Stub)
      case Some(v) =>
        RuntimeMode.values
          .find(_.wireName == v)
          .toRight(s"invalid $EnvVar '$v'; expected one of stub, aws")

/**
 * Configuration-presence readiness. Computed once at startup from configuration only: no downstream
 * call is ever made, so `/readyz` costs nothing and cannot cascade an outage.
 *
 * @param missing
 *   names of required environment variables that are unset or blank (never values)
 */
final case class Readiness(mode: RuntimeMode, missing: Vector[String]):
  def ready: Boolean = missing.isEmpty

object Readiness:

  /**
   * Environment variables the aws-mode API needs; the defaults in reference.conf are local only.
   */
  val AwsApiRequiredEnv: Vector[String] = Vector(
    "SCHOLARCHAIN_NEPTUNE_ENDPOINT",
    "SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX",
    "SCHOLARCHAIN_S3_VECTORS_BUCKET"
  )

  def check(mode: RuntimeMode, env: Map[String, String]): Readiness = mode match
    case RuntimeMode.Stub => Readiness(mode, Vector.empty)
    case RuntimeMode.Aws =>
      Readiness(mode, AwsApiRequiredEnv.filterNot(n => env.get(n).exists(_.trim.nonEmpty)))

/** Everything the HTTP routes need. */
final case class ServiceRuntime(
  mode: RuntimeMode,
  workflow: WorkflowPort,
  graph: ServiceGraphStore,
  audit: ServiceAuditStore,
  results: ResultStore,
  readiness: Readiness
)

/** The storage clients of the aws runtime. Tests pass clients over fake transports. */
final case class AwsStorageClients(
  neptune: NeptuneStorageClient,
  dynamo: DynamoDbStorageClient,
  s3: S3StorageClient,
  s3Vectors: S3VectorsStorageClient
)

object AwsStorageClients:

  /**
   * Builds the production clients from `scholarchain.storage`. Building a client makes no network
   * call; credentials come from the default chain (the ECS task role) when first needed.
   */
  def fromConfig(config: StorageModuleConfig, s3Region: String): AwsStorageClients =
    AwsStorageClients(
      neptune = NeptuneStorageClient.create(config.neptune),
      dynamo = DynamoDbStorageClient.create(config.dynamoDb),
      s3 = S3StorageClient.create(s3Region),
      s3Vectors = S3VectorsStorageClient.create(config.s3Vectors)
    )

  /** S3 region: `AWS_REGION` (set on ECS tasks), else the DynamoDB region. */
  def s3Region(config: StorageModuleConfig, env: Map[String, String]): String =
    env.get("AWS_REGION").map(_.trim).filter(_.nonEmpty).getOrElse(config.dynamoDb.region)

/**
 * [[ServiceGraphStore]] over Neptune for `/v1/graph/nodes/{id}/neighbors`. Edge types are validated
 * by the route (declared types only); an empty list means coauthorship only
 * ([[NeptuneStorageClient.DefaultPathEdgeTypes]]), never an unrestricted variable-length match.
 */
final class NeptuneServiceGraphStore(neptune: NeptuneStorageClient) extends ServiceGraphStore:

  def getNeighbors(
    nodeId: String,
    edgeTypes: Vector[String],
    depth: Int,
    limit: Int
  ): Vector[GraphNode] =
    val types = if edgeTypes.isEmpty then NeptuneStorageClient.DefaultPathEdgeTypes else edgeTypes
    neptune.getNeighbors(nodeId, types, depth, limit)

/** Composes runtime dependencies for the service layer. */
object RuntimeComposer:

  /** Stub ports for local runs and tests; the stub workflow records its audit in memory. */
  def composeStubs(): (WorkflowPort, ServiceGraphStore, ServiceAuditStore) =
    val audits = java.util.concurrent.ConcurrentHashMap[String, AuditRecord]()

    val stubWorkflow = new WorkflowPort:

      def execute(
        question: String,
        constraints: Map[String, String]
      ): WorkflowResult =
        val now     = java.time.Instant.now().toString
        val queryId = s"q-${java.util.UUID.randomUUID()}"
        val runId   = s"run-${java.util.UUID.randomUUID()}"
        val prov = Provenance(
          sourceDataset = "stub",
          sourceRecordId = queryId,
          pipelineRunId = runId,
          schemaVersion = "v1",
          timestamp = now
        )
        val bundle = EvidenceBundle(
          bundleId = s"bundle-$queryId",
          queryId = queryId,
          items = Vector.empty,
          totalScore = 0.0,
          provenance = prov
        )
        val response = AnswerResponse(
          responseId = s"resp-$queryId",
          queryId = queryId,
          claims = Vector.empty,
          evidenceBundle = bundle,
          generatedText = s"Stub answer for: $question",
          provenance = prov
        )
        val audit = AuditRecord(
          queryId = queryId,
          resolvedIds = Vector.empty,
          evidenceIds = Vector.empty,
          promptVersion = "stub-v1",
          modelVersion = "stub-v1",
          latencyMs = 0L,
          verificationSummary = "stub",
          timestamp = now
        )
        audits.put(queryId, audit)
        WorkflowResult(response = response, auditRecord = audit)

    val stubGraph = new ServiceGraphStore:

      def getNeighbors(
        nodeId: String,
        edgeTypes: Vector[String],
        depth: Int,
        limit: Int
      ): Vector[GraphNode] = Vector.empty

    val stubAudit = new ServiceAuditStore:
      def getAudit(queryId: String): Option[AuditRecord] = Option(audits.get(queryId))

    (stubWorkflow, stubGraph, stubAudit)

  /** Stub runtime with an in-memory result store (stub mode only). */
  def stubRuntime(): ServiceRuntime =
    val (workflow, graph, audit) = composeStubs()
    ServiceRuntime(
      RuntimeMode.Stub,
      workflow,
      graph,
      audit,
      InMemoryResultStore(),
      Readiness(RuntimeMode.Stub, Vector.empty)
    )

  /** The metric window the runtime uses when a question names no years. */
  def metricWindow(appConfig: AppConfig): MetricWindow =
    MetricWindow(appConfig.retrieval.metricWindowStart, appConfig.retrieval.metricWindowEnd)

  /**
   * The aws runtime: `RuntimeComposition.fromConfig` over the given storage clients, with durable
   * results and audit in DynamoDB. No network call is made here.
   *
   * @param modelClients
   *   injected model clients (tests); `None` uses the production Bedrock and HTTP clients
   */
  def awsRuntime(
    appConfig: AppConfig,
    clients: AwsStorageClients,
    secrets: SecretResolver,
    resultTtl: Duration,
    env: Map[String, String],
    modelClients: Option[ModelClients] = None
  ): ServiceRuntime =
    val storage = RuntimeStorage(
      neptune = clients.neptune,
      dynamo = clients.dynamo,
      s3 = clients.s3,
      s3Vectors = clients.s3Vectors,
      auditTtl = resultTtl,
      metricWindow = metricWindow(appConfig)
    )
    val runtime = modelClients match
      case Some(mc) => RuntimeComposition.fromConfig(appConfig, storage, secrets, mc)
      case None     => RuntimeComposition.fromConfig(appConfig, storage, secrets)
    val auditStore = DynamoAuditStore(clients.dynamo, resultTtl)
    ServiceRuntime(
      mode = RuntimeMode.Aws,
      workflow = new WorkflowPort:
        def execute(question: String, constraints: Map[String, String]): WorkflowResult =
          runtime.execute(question, constraints)
      ,
      graph = NeptuneServiceGraphStore(clients.neptune),
      audit = new ServiceAuditStore:
        def getAudit(queryId: String): Option[AuditRecord] =
          if !ResultStore.isQueryId(queryId) then None else auditStore.getAudit(queryId)
      ,
      results = DynamoResultStore(clients.dynamo, resultTtl),
      readiness = Readiness.check(RuntimeMode.Aws, env)
    )

  /**
   * The configured embedder (`embedding.provider`: Titan v2 on Bedrock by default, Voyage as the
   * approved fallback), built lazily so composing it makes no call and resolves no secret.
   */
  def embedder(
    appConfig: AppConfig,
    secrets: SecretResolver,
    modelClients: Option[ModelClients] = None
  ): QueryEmbedder =
    val clients = modelClients.getOrElse(
      ModelClients.default(ModelCallBounds.clampTimeout(appConfig.operations.timeoutSeconds))
    )
    LazyEmbedder(() =>
      ModelAdapters.embedder(appConfig.embedding, appConfig.operations, clients, secrets)
    )

  /** Selects the runtime from `SCHOLARCHAIN_RUNTIME_MODE` (default stub). */
  def fromEnv(env: Map[String, String], config: ServiceConfig): Either[String, ServiceRuntime] =
    RuntimeMode.parse(env.get(RuntimeMode.EnvVar)).map {
      case RuntimeMode.Stub => stubRuntime()
      case RuntimeMode.Aws =>
        val app     = AppConfig.load()
        val storage = StorageModuleConfig.load()
        awsRuntime(
          app,
          AwsStorageClients.fromConfig(storage, AwsStorageClients.s3Region(storage, env)),
          SecretResolvers.default(),
          Duration.ofDays(config.resultTtlDays.toLong),
          env
        )
    }
