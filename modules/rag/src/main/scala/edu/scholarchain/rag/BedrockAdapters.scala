package edu.scholarchain.rag

import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.*

import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Error classification shared by the Bedrock adapters. */
object BedrockErrors:

  /** Throttling and transient service conditions; everything else fails immediately. */
  def isRetryable(e: Throwable): Boolean = e match
    case m: ModelCallException          => m.retryable
    case _: ThrottlingException         => true
    case _: ServiceUnavailableException => true
    case _: ModelNotReadyException      => true
    case _: InternalServerException     => true
    case _                              => false

  /** Wrap an SDK failure without its message (which may echo request content). */
  def wrap(operation: String, e: Throwable): ModelCallException = e match
    case m: ModelCallException => m
    case other =>
      ModelCallException(
        "bedrock",
        isRetryable(other),
        s"bedrock $operation failed: ${other.getClass.getSimpleName}",
        other
      )

/**
 * [[LlmClient]] over the Bedrock Runtime Converse API.
 *
 * Every call sends the configured model ID (default `us.anthropic.claude-haiku-5-5`, a US geo
 * inference profile), a system block, one user message and `maxTokens` bounded by
 * `maxOutputTokens`. Calls hold a [[ConcurrencyLimiter]] permit, carry an API-call timeout, and are
 * retried by [[BoundedRetry]] only on throttling and transient errors.
 *
 * @param client
 *   injected; tests pass a fake `BedrockRuntimeClient`
 */
final class BedrockConverseLlmClient(
  client: BedrockRuntimeClient,
  override val modelId: String,
  maxOutputTokens: Int,
  limiter: ConcurrencyLimiter,
  retry: BoundedRetry,
  callTimeout: Duration
) extends LlmClient:

  require(modelId.trim.nonEmpty, "modelId must not be empty")
  require(maxOutputTokens >= 1, s"maxOutputTokens must be positive, got $maxOutputTokens")

  def generate(prompt: String, maxTokens: Int): String =
    complete(LlmRequest("", prompt, maxTokens))

  override def complete(request: LlmRequest): String =
    val converse = buildRequest(request)
    val response =
      try
        retry.run(BedrockErrors.isRetryable)(
          limiter.withPermit("bedrock")(client.converse(converse))
        )
      catch case NonFatal(e) => throw BedrockErrors.wrap("Converse", e)
    BedrockConverseLlmClient.text(response)

  private[rag] def buildRequest(request: LlmRequest): ConverseRequest =
    val tokens = math.min(request.maxTokens, maxOutputTokens)
    val message = Message
      .builder()
      .role(ConversationRole.USER)
      .content(ContentBlock.fromText(request.user))
      .build()
    val builder = ConverseRequest
      .builder()
      .modelId(modelId)
      .messages(message)
      .inferenceConfig(InferenceConfiguration.builder().maxTokens(Int.box(tokens)).build())
      .overrideConfiguration(
        AwsRequestOverrideConfiguration.builder().apiCallTimeout(callTimeout).build()
      )
    if request.system.nonEmpty then builder.system(SystemContentBlock.fromText(request.system))
    builder.build()

  override def toString: String = s"BedrockConverseLlmClient(model=$modelId)"

object BedrockConverseLlmClient:

  /** Concatenated text blocks of the output message; an empty output is an error. */
  def text(response: ConverseResponse): String =
    val blocks =
      Option(response.output()).flatMap(o => Option(o.message())).map(_.content().asScala.toVector)
    val text = blocks.getOrElse(Vector.empty).flatMap(b => Option(b.text())).mkString
    if text.isEmpty then
      throw ModelCallException("bedrock", retryable = false, "bedrock Converse returned no text")
    text

/**
 * [[QueryEmbedder]] over Bedrock InvokeModel with Amazon Titan Text Embeddings v2.
 *
 * Request body: `{"inputText": ..., "dimensions": <dimensions>, "normalize": true}`. The returned
 * vector length must equal `dimensions`; otherwise [[EmbeddingDimensionException]] is thrown and no
 * vector is returned. The reported model is the configured model ID.
 */
final class TitanQueryEmbedder(
  client: BedrockRuntimeClient,
  modelId: String,
  dimensions: Int,
  limiter: ConcurrencyLimiter,
  retry: BoundedRetry,
  callTimeout: Duration
) extends QueryEmbedder:

  require(modelId.trim.nonEmpty, "modelId must not be empty")
  require(dimensions >= 1, s"dimensions must be positive, got $dimensions")

  def embed(text: String, requestedModel: String): QueryEmbedding =
    if requestedModel != modelId then
      throw ModelCallException(
        "bedrock",
        retryable = false,
        s"Titan embedder is configured for '$modelId', not '$requestedModel'"
      )
    val body = ujson.Obj(
      "inputText"  -> text,
      "dimensions" -> ujson.Num(dimensions.toDouble),
      "normalize"  -> ujson.Bool(true)
    )
    val request = InvokeModelRequest
      .builder()
      .modelId(modelId)
      .contentType("application/json")
      .accept("application/json")
      .body(SdkBytes.fromUtf8String(ujson.write(body)))
      .overrideConfiguration(
        AwsRequestOverrideConfiguration.builder().apiCallTimeout(callTimeout).build()
      )
      .build()
    val response =
      try
        retry.run(BedrockErrors.isRetryable)(
          limiter.withPermit("bedrock")(client.invokeModel(request))
        )
      catch case NonFatal(e) => throw BedrockErrors.wrap("InvokeModel", e)
    val vector = TitanQueryEmbedder.parse(response.body().asUtf8String())
    if vector.length != dimensions then
      throw EmbeddingDimensionException(dimensions, vector.length, "bedrock titan")
    QueryEmbedding(vector, modelId)

  override def toString: String = s"TitanQueryEmbedder(model=$modelId, dimensions=$dimensions)"

object TitanQueryEmbedder:

  /** The `embedding` array of a Titan v2 response. */
  def parse(json: String): Vector[Float] =
    val parsed =
      try ujson.read(json)
      catch
        case NonFatal(_) =>
          throw ModelCallException("bedrock", retryable = false, "Titan response is not JSON")
    parsed.objOpt.flatMap(_.get("embedding")).flatMap(_.arrOpt) match
      case Some(arr) => arr.map(_.num.toFloat).toVector
      case None =>
        throw ModelCallException("bedrock", retryable = false, "Titan response has no embedding")
