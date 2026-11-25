package edu.scholarchain.storage

import org.slf4j.LoggerFactory
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{
  DeleteMessageRequest,
  KmsThrottledException,
  ReceiveMessageRequest,
  RequestThrottledException,
  SendMessageRequest
}

import java.net.URI
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/**
 * Settings for [[SqsQueueClient]].
 *
 * @param waitTimeSeconds
 *   ReceiveMessage long-poll wait, 0..20 (SQS maximum 20)
 * @param visibilityTimeoutSeconds
 *   per-receive visibility timeout, 0..43200; `None` uses the queue default (Terraform sets 300 s)
 * @param maxSendAttempts
 *   total SendMessage attempts when SQS throttles, 1..10 (only throttling is retried)
 * @param retryBaseDelayMs
 *   backoff before retry `n` (1-based) is `retryBaseDelayMs * 2^(n-1)`, capped at
 *   [[SqsQueueSettings.MaxBackoffMs]]
 */
final case class SqsQueueSettings(
  waitTimeSeconds: Int = SqsQueueSettings.MaxWaitTimeSeconds,
  visibilityTimeoutSeconds: Option[Int] = None,
  maxSendAttempts: Int = 3,
  retryBaseDelayMs: Long = 100L
):

  require(
    waitTimeSeconds >= 0 && waitTimeSeconds <= SqsQueueSettings.MaxWaitTimeSeconds,
    s"waitTimeSeconds must be 0..${SqsQueueSettings.MaxWaitTimeSeconds}, got $waitTimeSeconds"
  )

  require(
    visibilityTimeoutSeconds.forall(v =>
      v >= 0 && v <= SqsQueueSettings.MaxVisibilityTimeoutSeconds
    ),
    s"visibilityTimeoutSeconds must be 0..${SqsQueueSettings.MaxVisibilityTimeoutSeconds}"
  )

  require(
    maxSendAttempts >= 1 && maxSendAttempts <= SqsQueueSettings.MaxSendAttemptsCap,
    s"maxSendAttempts must be 1..${SqsQueueSettings.MaxSendAttemptsCap}, got $maxSendAttempts"
  )
  require(retryBaseDelayMs >= 0, "retryBaseDelayMs must be >= 0")

object SqsQueueSettings:
  /** SQS ReceiveMessage limits (AWS SQS API reference). */
  val MaxWaitTimeSeconds: Int          = 20
  val MaxMessagesPerReceive: Int       = 10
  val MaxVisibilityTimeoutSeconds: Int = 43200
  val MaxSendAttemptsCap: Int          = 10
  val MaxBackoffMs: Long               = 5000L

/**
 * [[QueueClient]] over Amazon SQS (AWS SDK v2).
 *
 *   - `receiveMessages` long-polls (`WaitTimeSeconds` from settings, at most 20) and asks for at
 *     most 10 messages, returning each message ID, receipt handle and body.
 *   - `deleteMessage` deletes by receipt handle only.
 *   - `sendMessage` retries throttling (`RequestThrottled`, `KmsThrottled`, HTTP 429 or any error
 *     code the SDK classifies as throttling) up to `maxSendAttempts` total attempts; any other
 *     error fails at once.
 *
 * Failure messages and logs carry the exception class, AWS error code and status only, never the
 * message body.
 *
 * @param sqs
 *   injected SDK client; tests pass a fake implementation of the `SqsClient` interface
 * @param sleep
 *   backoff sleep, injectable so tests do not wait
 */
class SqsQueueClient(
  sqs: SqsClient,
  val settings: SqsQueueSettings = SqsQueueSettings(),
  sleep: Long => Unit = (ms: Long) => Thread.sleep(ms)
) extends QueueClient
    with AutoCloseable:
  import SqsQueueClient.*

  private val log = LoggerFactory.getLogger(classOf[SqsQueueClient])

  def sendMessage(queueUrl: String, body: String): Try[String] =
    val request = SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body).build()

    @annotation.tailrec
    def attempt(n: Int): Try[String] =
      Try(sqs.sendMessage(request).messageId()) match
        case s @ Success(_) => s
        case Failure(e) if isThrottling(e) && n < settings.maxSendAttempts =>
          val delay = backoffMs(settings.retryBaseDelayMs, n)
          log.warn(
            s"SQS SendMessage throttled attempt=$n/${settings.maxSendAttempts} ${describe(e)}"
          )
          sleep(delay)
          attempt(n + 1)
        case Failure(e) =>
          Failure(
            SqsQueueException("SendMessage", s"SendMessage failed attempts=$n ${describe(e)}", e)
          )

    attempt(1)

  def receiveMessages(queueUrl: String, maxMessages: Int): Try[List[ReceivedMessage]] =
    if maxMessages < 1 then
      Failure(IllegalArgumentException(s"maxMessages must be >= 1, got $maxMessages"))
    else
      val builder = ReceiveMessageRequest
        .builder()
        .queueUrl(queueUrl)
        .maxNumberOfMessages(math.min(maxMessages, SqsQueueSettings.MaxMessagesPerReceive))
        .waitTimeSeconds(settings.waitTimeSeconds)
      settings.visibilityTimeoutSeconds.foreach(v => builder.visibilityTimeout(v))
      Try(sqs.receiveMessage(builder.build())) match
        case Success(resp) =>
          Success(
            resp
              .messages()
              .asScala
              .toList
              .map(m => ReceivedMessage(m.messageId(), m.receiptHandle(), m.body()))
          )
        case Failure(e) =>
          Failure(SqsQueueException("ReceiveMessage", s"ReceiveMessage failed ${describe(e)}", e))

  def deleteMessage(queueUrl: String, receiptHandle: String): Try[Unit] =
    if receiptHandle == null || receiptHandle.isEmpty then
      Failure(IllegalArgumentException("receipt handle must be non-empty"))
    else
      val request =
        DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(receiptHandle).build()
      Try(sqs.deleteMessage(request)) match
        case Success(_) => Success(())
        case Failure(e) =>
          Failure(SqsQueueException("DeleteMessage", s"DeleteMessage failed ${describe(e)}", e))

  def close(): Unit = sqs.close()

  override def toString: String = s"SqsQueueClient(settings=$settings)"

/** An SQS call failure; the message names the operation, error class, code and status only. */
final class SqsQueueException(val operation: String, message: String, cause: Throwable)
    extends RuntimeException(message, cause)

object SqsQueueClient:

  /** SQS error codes treated as throttling in addition to the SDK's own classification. */
  val ThrottlingErrorCodes: Set[String] =
    Set("RequestThrottled", "ThrottlingException", "Throttling", "KmsThrottled")

  def isThrottling(e: Throwable): Boolean = e match
    case _: RequestThrottledException | _: KmsThrottledException => true
    case a: AwsServiceException =>
      a.isThrottlingException ||
      a.statusCode() == 429 ||
      Option(a.awsErrorDetails()).flatMap(d => Option(d.errorCode())).exists(ThrottlingErrorCodes)
    case _ => false

  /** Backoff before attempt `attempt + 1`, where `attempt` is the 1-based failed attempt. */
  def backoffMs(baseMs: Long, attempt: Int): Long =
    math.min(SqsQueueSettings.MaxBackoffMs, baseMs * (1L << math.min(attempt - 1, 20)))

  /** `error=<class> code=<AWS code> status=<http>`; never includes request bodies. */
  def describe(e: Throwable): String = e match
    case a: AwsServiceException =>
      val code =
        Option(a.awsErrorDetails()).flatMap(d => Option(d.errorCode())).getOrElse("unknown")
      s"error=${e.getClass.getSimpleName} code=$code status=${a.statusCode()}"
    case _ => s"error=${e.getClass.getSimpleName}"

  /**
   * Production client in `region`. The SDK's own retries are disabled so the send attempt bound in
   * `settings` is the real bound; a failed receive or delete is retried by the worker's next poll
   * (an unacknowledged message is redelivered after the visibility timeout).
   */
  def create(
    region: String,
    settings: SqsQueueSettings = SqsQueueSettings(),
    endpointOverride: Option[URI] = None
  ): SqsQueueClient =
    require(SigV4Signer.isValidRegion(region), s"invalid AWS region '$region'")
    val builder = SqsClient
      .builder()
      .region(Region.of(region))
      .overrideConfiguration(
        ClientOverrideConfiguration.builder().retryStrategy(AwsRetryStrategy.doNotRetry()).build()
      )
    endpointOverride.foreach(u => builder.endpointOverride(u))
    SqsQueueClient(builder.build(), settings)
