package edu.scholarchain.rag

import edu.scholarchain.core.*
import edu.scholarchain.storage.{HttpTransport, JavaHttpTransport}
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient

import java.time.Duration

/**
 * Clients the model adapters use. Every field is lazy so that, for example, an extractive
 * deployment never builds a Bedrock client and tests inject fakes without touching AWS.
 *
 * @param bedrock
 *   builds a Bedrock Runtime client for a region
 * @param http
 *   transport for the Anthropic and Voyage APIs
 * @param sleep
 *   retry backoff; tests pass a no-op
 */
final case class ModelClients(
  bedrock: String => BedrockRuntimeClient,
  http: () => HttpTransport,
  sleep: Long => Unit = ms => Thread.sleep(ms)
)

object ModelClients:

  /**
   * Production clients: a Bedrock Runtime client per region with SDK retries disabled (the adapters
   * retry, boundedly, themselves) and a `java.net.http` transport.
   */
  def default(timeout: Duration): ModelClients =
    ModelClients(
      bedrock = region =>
        BedrockRuntimeClient
          .builder()
          .region(Region.of(region))
          .overrideConfiguration(
            ClientOverrideConfiguration
              .builder()
              .retryStrategy(AwsRetryStrategy.doNotRetry())
              .apiCallTimeout(timeout)
              .build()
          )
          .build(),
      http = () => JavaHttpTransport.create(Duration.ofSeconds(10))
    )

/** Selects and builds the LLM and embedding adapters from core configuration by provider. */
object ModelAdapters:

  private def retry(ops: OperationsConfig, clients: ModelClients): BoundedRetry =
    BoundedRetry(ops.retryCount, sleep = clients.sleep)

  /**
   * LLM adapter for `llm.provider`: Bedrock Converse (default) or the Anthropic Messages API. The
   * Anthropic key is resolved through `secrets` on first call, never at construction.
   */
  def llm(
    cfg: LlmConfig,
    ops: OperationsConfig,
    clients: ModelClients,
    secrets: SecretResolver
  ): LlmClient =
    val timeout = ModelCallBounds.clampTimeout(ops.timeoutSeconds)
    val limiter = ConcurrencyLimiter(cfg.maxConcurrentCalls, timeout)
    cfg.provider match
      case LlmProvider.Bedrock =>
        BedrockConverseLlmClient(
          clients.bedrock(cfg.region),
          cfg.modelId,
          cfg.maxOutputTokens,
          limiter,
          retry(ops, clients),
          timeout
        )
      case LlmProvider.Anthropic =>
        val ref = cfg.anthropicApiKey.getOrElse(
          throw IllegalArgumentException("llm.provider=anthropic requires an API key reference")
        )
        AnthropicMessagesLlmClient(
          clients.http(),
          () => secrets.resolve(ref),
          cfg.modelId,
          cfg.maxOutputTokens,
          limiter,
          retry(ops, clients),
          timeout
        )

  /** Embedding adapter for `embedding.provider`: Titan v2 on Bedrock (default) or Voyage AI. */
  def embedder(
    cfg: EmbeddingConfig,
    ops: OperationsConfig,
    clients: ModelClients,
    secrets: SecretResolver
  ): QueryEmbedder =
    val timeout = ModelCallBounds.clampTimeout(ops.timeoutSeconds)
    val limiter = ConcurrencyLimiter(math.max(1, ops.maxConcurrency), timeout)
    cfg.provider match
      case EmbeddingProvider.Bedrock =>
        TitanQueryEmbedder(
          clients.bedrock(cfg.region),
          cfg.modelId,
          cfg.dimensions,
          limiter,
          retry(ops, clients),
          timeout
        )
      case EmbeddingProvider.Voyage =>
        val ref = cfg.voyageApiKey.getOrElse(
          throw IllegalArgumentException("embedding.provider=voyage requires an API key reference")
        )
        VoyageQueryEmbedder(
          clients.http(),
          () => secrets.resolve(ref),
          cfg.modelId,
          cfg.dimensions,
          limiter,
          retry(ops, clients),
          timeout
        )

/** Placeholder LLM for extractive mode: any call is a programming error. */
object NoLlmClient extends LlmClient:
  override def modelId: String = "none (extractive)"

  def generate(prompt: String, maxTokens: Int): String =
    throw ModelCallException("none", retryable = false, "no LLM is configured (extractive mode)")
