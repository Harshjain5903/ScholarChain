package edu.scholarchain.rag

import edu.scholarchain.core.*
import edu.scholarchain.storage.*
import software.amazon.awssdk.auth.credentials.{AwsCredentialsProvider, DefaultCredentialsProvider}

import java.time.Duration

/**
 * Storage clients the runtime reads from and writes to. Built by the service layer from the storage
 * configuration.
 *
 * @param metrics
 *   researcher-metrics source override; defaults to [[NeptuneMetricsStore]] over `neptune`
 * @param metricWindow
 *   the metric window used when a question names no years (and for the missing bound of a "since
 *   2015" question). Neptune metrics are matched to the window exactly, so this must equal the
 *   window the Spark metrics run computed (`windowStartYear..windowEndYear` on the vertices).
 * @param auditTtl
 *   retention of audit records written through `DynamoDbStorageClient.putQueryResult`
 */
final case class RuntimeStorage(
  neptune: NeptuneStorageClient,
  dynamo: DynamoDbStorageClient,
  s3: S3StorageClient,
  s3Vectors: S3VectorsStorageClient,
  metrics: Option[MetricsStore] = None,
  auditTtl: Duration = Duration.ofDays(30),
  metricWindow: MetricWindow = RuntimeWorkflow.DefaultWindow
)

/** The data ports one [[RuntimeWorkflow]] uses. */
final case class RuntimePorts(
  lookup: ResearcherLookup,
  metrics: MetricsStore,
  graph: GraphStore,
  vectors: VectorStore,
  text: TextStore,
  audit: AuditStore
)

/** Production secret resolution: env-var names from the process env, ARNs from Secrets Manager. */
object SecretResolvers:

  def default(
    credentials: AwsCredentialsProvider = DefaultCredentialsProvider.create()
  ): SecretResolver =
    CompositeSecretResolver(
      EnvSecretResolver(),
      SecretsManagerSecretResolver(JavaHttpTransport.create(Duration.ofSeconds(10)), credentials)
    )

/**
 * Builds the real runtime from configuration. Entry point for the service layer:
 * {{{
 * val workflow: RuntimeWorkflow =
 *   RuntimeComposition.fromConfig(appConfig, RuntimeStorage(neptune, dynamo, s3, s3Vectors),
 *     SecretResolvers.default())
 * }}}
 *
 * Wiring:
 *   - researcher lookup: Neptune (`displayName` exact match, `researcherId`);
 *   - metrics: `RuntimeStorage.metrics`, else [[NeptuneMetricsStore]] (exact stored values for
 *     exactly the requested window; rankings only by allowlisted metrics);
 *   - paths and coauthors: Neptune `shortestPath`/`getDirectCoauthors`/`getNeighbors`,
 *     COAUTHORED_WITH only, depth <= `retrieval.max-hops`, limits <= 100;
 *   - semantic context: S3 Vectors in `storage.s3-vectors-bucket`, text from S3, query embedded
 *     with the `embedding` provider (Titan v2 by default);
 *   - synthesis: `synthesis.mode` (`llm` uses the `llm` provider; `extractive` builds no LLM);
 *   - audit: DynamoDB `putQueryResult` under `audit:<queryId>` ([[DynamoAuditStore]]).
 *
 * No model client is created and no secret is resolved until the first call that needs it.
 */
object RuntimeComposition:

  def fromConfig(
    appConfig: AppConfig,
    storage: RuntimeStorage,
    secrets: SecretResolver
  ): RuntimeWorkflow =
    fromConfig(
      appConfig,
      storage,
      secrets,
      ModelClients.default(ModelCallBounds.clampTimeout(appConfig.operations.timeoutSeconds))
    )

  /** As above with injected model clients (tests, custom HTTP or SDK clients). */
  def fromConfig(
    appConfig: AppConfig,
    storage: RuntimeStorage,
    secrets: SecretResolver,
    clients: ModelClients
  ): RuntimeWorkflow =
    val llm =
      if appConfig.synthesis.mode == SynthesisMode.Extractive then NoLlmClient
      else
        LazyLlmClient(
          appConfig.llm.modelId,
          () => ModelAdapters.llm(appConfig.llm, appConfig.operations, clients, secrets)
        )
    val embedder =
      LazyEmbedder(() =>
        ModelAdapters.embedder(appConfig.embedding, appConfig.operations, clients, secrets)
      )
    fromPorts(appConfig, ports(appConfig, storage), llm, embedder, storage.metricWindow)

  /** Storage-backed ports. */
  def ports(appConfig: AppConfig, storage: RuntimeStorage): RuntimePorts =
    RuntimePorts(
      lookup = NeptuneResearcherLookup(storage.neptune),
      metrics = storage.metrics.getOrElse(NeptuneMetricsStore(storage.neptune)),
      graph = NeptuneGraphStore(storage.neptune, appConfig.retrieval.maxHops),
      vectors = S3VectorsVectorStore(storage.s3Vectors, appConfig.storage.s3VectorsBucket),
      text = S3TextStore(storage.s3),
      audit = DynamoAuditStore(storage.dynamo, storage.auditTtl)
    )

  /** Assemble the agents over arbitrary ports (in-memory fixtures in tests). */
  def fromPorts(
    appConfig: AppConfig,
    ports: RuntimePorts,
    llm: LlmClient,
    embedder: QueryEmbedder,
    defaultWindow: MetricWindow = RuntimeWorkflow.DefaultWindow
  ): RuntimeWorkflow =
    val embeddingConfig = EmbeddingModelConfig(appConfig.embedding.modelId, appConfig.s3Vectors)
    RuntimeWorkflow(
      planner = QueryPlannerAgent(),
      resolver = ResearcherResolverAgent(ports.lookup),
      metricsAgent = CollaborationMetricsAgent(ports.metrics),
      pathFinder = PathFinderAgent(ports.graph, appConfig.retrieval.maxHops),
      community = CommunityAnalystAgent(ports.metrics, ports.graph),
      semantic = SemanticContextAgent(ports.vectors, ports.text, embedder, embeddingConfig),
      evidenceBuilder = EvidenceBuilderAgent(),
      synthesis =
        AnswerSynthesisAgent(llm, appConfig.synthesis.mode, appConfig.llm.maxOutputTokens),
      verification = ClaimVerificationAgent(),
      audit = ports.audit,
      generationModelId = appConfig.llm.modelId,
      defaultWindow = defaultWindow
    )

/** Builds the underlying embedder on first use, so composition never touches AWS. */
final class LazyEmbedder(build: () => QueryEmbedder) extends QueryEmbedder:
  private lazy val underlying = build()

  def embed(text: String, modelId: String): QueryEmbedding = underlying.embed(text, modelId)

/** Builds the underlying LLM client on first use. */
final class LazyLlmClient(override val modelId: String, build: () => LlmClient) extends LlmClient:
  private lazy val underlying = build()

  def generate(prompt: String, maxTokens: Int): String = underlying.generate(prompt, maxTokens)

  override def complete(request: LlmRequest): String = underlying.complete(request)
