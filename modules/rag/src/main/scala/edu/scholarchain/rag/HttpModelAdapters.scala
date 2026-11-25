package edu.scholarchain.rag

import edu.scholarchain.storage.{HttpCall, HttpReply, HttpTransport, SigV4Signer}

import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.util.control.NonFatal

/** Shared helpers for the API-key HTTP adapters. The key is never logged or put in a message. */
private[rag] object HttpModelCalls:

  /** Throttling, overload and server errors. */
  def isRetryableStatus(status: Int): Boolean =
    status == 408 || status == 429 || status == 529 || status >= 500

  def isRetryable(e: Throwable): Boolean = e match
    case m: ModelCallException  => m.retryable
    case _: java.io.IOException => true
    case _                      => false

  /** Status and the provider's short `error.type`; never the response message or body. */
  def describe(reply: HttpReply): String =
    val errorType = scala.util
      .Try(ujson.read(reply.body))
      .toOption
      .flatMap(_.objOpt)
      .flatMap(o => o.get("error").flatMap(_.objOpt).flatMap(_.get("type")).flatMap(_.strOpt))
      .getOrElse("unknown")
    s"status=${reply.status} type=${errorType.take(64)}"

  /** Send once; non-2xx becomes a [[ModelCallException]] whose message is redacted. */
  def send(
    provider: String,
    transport: HttpTransport,
    call: HttpCall,
    timeout: Duration,
    secret: String
  ): ujson.Value =
    val reply =
      try transport.execute(call, Some(timeout))
      catch
        case NonFatal(e) =>
          throw ModelCallException(
            provider,
            retryable = e.isInstanceOf[java.io.IOException],
            SigV4Signer.redact(
              s"$provider request failed: ${e.getClass.getSimpleName}",
              Seq(secret)
            )
          )
    if !reply.isSuccess then
      throw ModelCallException(
        provider,
        isRetryableStatus(reply.status),
        SigV4Signer.redact(s"$provider request failed: ${describe(reply)}", Seq(secret))
      )
    try ujson.read(reply.body)
    catch
      case NonFatal(_) =>
        throw ModelCallException(provider, retryable = false, s"$provider response is not JSON")

/**
 * [[LlmClient]] over the Anthropic Messages API (approved fallback provider).
 *
 * The API key is obtained from `apiKey` on first use (a [[SecretResolver]] lookup supplied by the
 * caller) and sent only in the `x-api-key` header. It never appears in logs, exception messages or
 * `toString`.
 */
final class AnthropicMessagesLlmClient(
  transport: HttpTransport,
  apiKey: () => String,
  override val modelId: String,
  maxOutputTokens: Int,
  limiter: ConcurrencyLimiter,
  retry: BoundedRetry,
  callTimeout: Duration,
  endpoint: URI = AnthropicMessagesLlmClient.DefaultEndpoint
) extends LlmClient:

  require(modelId.trim.nonEmpty, "modelId must not be empty")

  private lazy val key: String = apiKey()

  def generate(prompt: String, maxTokens: Int): String =
    complete(LlmRequest("", prompt, maxTokens))

  override def complete(request: LlmRequest): String =
    val body = ujson.Obj(
      "model"      -> modelId,
      "max_tokens" -> ujson.Num(math.min(request.maxTokens, maxOutputTokens).toDouble),
      "messages"   -> ujson.Arr(ujson.Obj("role" -> "user", "content" -> request.user))
    )
    if request.system.nonEmpty then body("system") = request.system
    val call = HttpCall(
      "POST",
      endpoint,
      Vector(
        "content-type"      -> "application/json",
        "anthropic-version" -> AnthropicMessagesLlmClient.ApiVersion,
        "x-api-key"         -> key
      ),
      ujson.write(body).getBytes(StandardCharsets.UTF_8)
    )
    val json = retry.run(HttpModelCalls.isRetryable)(
      limiter.withPermit("anthropic")(
        HttpModelCalls.send("anthropic", transport, call, callTimeout, key)
      )
    )
    val text = json.objOpt
      .flatMap(_.get("content"))
      .flatMap(_.arrOpt)
      .map(_.flatMap(b => b.objOpt.flatMap(_.get("text")).flatMap(_.strOpt)).mkString)
      .getOrElse("")
    if text.isEmpty then
      throw ModelCallException("anthropic", retryable = false, "anthropic returned no text")
    text

  override def toString: String = s"AnthropicMessagesLlmClient(model=$modelId)"

object AnthropicMessagesLlmClient:
  val DefaultEndpoint: URI = URI.create("https://api.anthropic.com/v1/messages")
  val ApiVersion: String   = "2023-06-01"

/**
 * [[QueryEmbedder]] over the Voyage AI embeddings API (approved fallback provider).
 *
 * Sends `input_type = "query"` and `output_dimension = dimensions`; the returned vector length must
 * equal `dimensions` or [[EmbeddingDimensionException]] is thrown. The key is sent only as a bearer
 * token and never logged.
 */
final class VoyageQueryEmbedder(
  transport: HttpTransport,
  apiKey: () => String,
  modelId: String,
  dimensions: Int,
  limiter: ConcurrencyLimiter,
  retry: BoundedRetry,
  callTimeout: Duration,
  endpoint: URI = VoyageQueryEmbedder.DefaultEndpoint
) extends QueryEmbedder:

  private lazy val key: String = apiKey()

  def embed(text: String, requestedModel: String): QueryEmbedding =
    if requestedModel != modelId then
      throw ModelCallException(
        "voyage",
        retryable = false,
        s"Voyage embedder is configured for '$modelId', not '$requestedModel'"
      )
    val body = ujson.Obj(
      "input"            -> ujson.Arr(text),
      "model"            -> modelId,
      "input_type"       -> "query",
      "output_dimension" -> ujson.Num(dimensions.toDouble)
    )
    val call = HttpCall(
      "POST",
      endpoint,
      Vector("content-type" -> "application/json", "authorization" -> s"Bearer $key"),
      ujson.write(body).getBytes(StandardCharsets.UTF_8)
    )
    val json = retry.run(HttpModelCalls.isRetryable)(
      limiter.withPermit("voyage")(HttpModelCalls.send("voyage", transport, call, callTimeout, key))
    )
    val vector = json.objOpt
      .flatMap(_.get("data"))
      .flatMap(_.arrOpt)
      .flatMap(_.headOption)
      .flatMap(_.objOpt)
      .flatMap(_.get("embedding"))
      .flatMap(_.arrOpt)
      .map(_.map(_.num.toFloat).toVector)
      .getOrElse(
        throw ModelCallException("voyage", retryable = false, "voyage response has no embedding")
      )
    if vector.length != dimensions then
      throw EmbeddingDimensionException(dimensions, vector.length, "voyage")
    QueryEmbedding(vector, modelId)

  override def toString: String = s"VoyageQueryEmbedder(model=$modelId, dimensions=$dimensions)"

object VoyageQueryEmbedder:
  val DefaultEndpoint: URI = URI.create("https://api.voyageai.com/v1/embeddings")
