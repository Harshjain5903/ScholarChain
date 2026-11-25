package edu.scholarchain.storage

import edu.scholarchain.core.S3VectorsConfig
import upickle.default.*

import java.security.MessageDigest
import scala.util.Try

/** An embedding job to be enqueued for vector generation. */
case class EmbeddingJob(
  jobId: String,
  entityId: String,
  documentType: String,
  textS3Uri: String,
  textHash: String,
  embeddingModel: String,
  windowStartYear: Option[Int],
  windowEndYear: Option[Int],
  runId: String,
  retryCount: Int
) derives ReadWriter

/**
 * A message received from a queue.
 *
 * @param messageId
 *   the queue-assigned message ID; stable across redeliveries, NOT valid for deletion
 * @param receiptHandle
 *   the handle for this particular receive; required to delete (acknowledge) the message
 * @param body
 *   the message body
 */
case class ReceivedMessage(messageId: String, receiptHandle: String, body: String)

/** An embedding job dequeued together with the identifiers needed to acknowledge it. */
case class DequeuedJob(job: EmbeddingJob, messageId: String, receiptHandle: String)

/** Per-message classification of a received message. */
enum ReceivedJob:
  /** A body that parsed and validated as an [[EmbeddingJob]]. */
  case Valid(dequeued: DequeuedJob)

  /**
   * A body that is not a valid [[EmbeddingJob]]. Deliberately carries no receipt handle and no
   * body: poison messages are never acknowledged, so SQS moves them to the DLQ.
   */
  case Poison(messageId: String, reason: String)

  /** The queue message ID of this message. */
  def id: String = this match
    case Valid(d)     => d.messageId
    case Poison(m, _) => m

/** Outcome for one message in [[EmbeddingQueueManager.processBatch]]. */
enum MessageStatus:
  /** Handler succeeded and the message was deleted. */
  case Acknowledged

  /** Body is not a valid job; left unacknowledged for redelivery and the DLQ. */
  case Poison

  /** Handler failed; left unacknowledged for redelivery (then DLQ). */
  case HandlerFailed

  /**
   * Handler succeeded but the delete failed; the job will be redelivered (writes are idempotent).
   */
  case AckFailed

/** One per received message; `reason` is a code plus exception class, never a message body. */
case class MessageResult(
  messageId: String,
  jobId: Option[String],
  status: MessageStatus,
  reason: Option[String]
)

/** Trait for queue operations -- allows SQS or in-memory implementations. */
trait QueueClient:
  def sendMessage(queueUrl: String, body: String): Try[String]

  /** Receive up to `maxMessages` messages, each with its message ID and receipt handle. */
  def receiveMessages(
    queueUrl: String,
    maxMessages: Int
  ): Try[List[ReceivedMessage]]

  /** Delete a received message. Takes the receipt handle, never the message ID (SQS semantics). */
  def deleteMessage(queueUrl: String, receiptHandle: String): Try[Unit]

/**
 * Manages embedding job enqueueing, dequeuing, and deduplication.
 *
 * In production build it with [[EmbeddingQueueManager.fromConfig]], which takes the queue URL from
 * `scholarchain.storage.embedding-queue-url` (env `SCHOLARCHAIN_EMBEDDING_QUEUE_URL`).
 */
class EmbeddingQueueManager(
  queue: QueueClient,
  val queueUrl: String,
  deduplicationStore: scala.collection.mutable.Set[String],
  logSink: String => Unit = EmbeddingQueueManager.defaultLog
):
  require(queueUrl != null && queueUrl.trim.nonEmpty, "embedding queue URL must be non-empty")

  /** Enqueue a single embedding job. Returns the message ID on success. */
  def enqueue(job: EmbeddingJob): Try[String] = Try {
    val body = write(job)
    queue.sendMessage(queueUrl, body).get
  }

  /**
   * Receive up to `maxMessages` messages and classify each one independently: a body that parses as
   * a valid [[EmbeddingJob]] becomes [[ReceivedJob.Valid]]; anything else becomes
   * [[ReceivedJob.Poison]]. A poison message is NOT acknowledged, so SQS redelivers it after the
   * visibility timeout and moves it to the DLQ once `maxReceiveCount` (Terraform redrive policy) is
   * reached. Only the message ID and a reason code are logged, never the body.
   *
   * Fails only when the receive call itself fails.
   */
  def receiveBatch(maxMessages: Int): Try[List[ReceivedJob]] =
    queue.receiveMessages(queueUrl, maxMessages).map(_.map(classify))

  /**
   * Dequeue a batch of valid embedding jobs, each paired with the receipt handle of this receive.
   * Pass the [[DequeuedJob]] (or its receipt handle) to [[acknowledge]] once the job is done.
   *
   * Poison messages are dropped from the result (and left unacknowledged for the DLQ), so one bad
   * message never fails the batch. Use [[receiveBatch]] to see them.
   */
  def dequeueBatch(maxMessages: Int): Try[List[DequeuedJob]] =
    receiveBatch(maxMessages).map(_.collect { case ReceivedJob.Valid(d) => d })

  /**
   * Receive a batch and run `handler` on each valid job, returning one [[MessageResult]] per
   * received message. A job is acknowledged only when its handler succeeds; a handler failure or a
   * poison body leaves that message on the queue (redelivery, then DLQ) without affecting the other
   * messages. Fails only when the receive call itself fails.
   */
  def processBatch(maxMessages: Int)(handler: DequeuedJob => Try[Unit]): Try[List[MessageResult]] =
    receiveBatch(maxMessages).map(_.map {
      case ReceivedJob.Poison(messageId, reason) =>
        MessageResult(messageId, None, MessageStatus.Poison, Some(reason))
      case ReceivedJob.Valid(d) =>
        val outcome =
          try handler(d)
          catch case scala.util.control.NonFatal(e) => scala.util.Failure(e)
        outcome match
          case scala.util.Failure(e) =>
            val reason = s"HANDLER_FAILED:${e.getClass.getSimpleName}"
            logSink(s"embedding job failed messageId=${d.messageId} jobId=${d.job.jobId} $reason")
            val _ = markFailed(d.job.jobId, reason)
            MessageResult(d.messageId, Some(d.job.jobId), MessageStatus.HandlerFailed, Some(reason))
          case scala.util.Success(_) =>
            acknowledge(d) match
              case scala.util.Success(_) =>
                MessageResult(d.messageId, Some(d.job.jobId), MessageStatus.Acknowledged, None)
              case scala.util.Failure(e) =>
                val reason = s"ACK_FAILED:${e.getClass.getSimpleName}"
                logSink(s"embedding job ack failed messageId=${d.messageId} $reason")
                MessageResult(d.messageId, Some(d.job.jobId), MessageStatus.AckFailed, Some(reason))
    })

  private def classify(msg: ReceivedMessage): ReceivedJob =
    EmbeddingQueueManager.parseJob(msg.body) match
      case Right(job) => ReceivedJob.Valid(DequeuedJob(job, msg.messageId, msg.receiptHandle))
      case Left(reason) =>
        logSink(s"poison embedding message left for DLQ messageId=${msg.messageId} reason=$reason")
        ReceivedJob.Poison(msg.messageId, reason)

  /** Acknowledge (delete) a dequeued job using its receipt handle. */
  def acknowledge(dequeued: DequeuedJob): Try[Unit] = acknowledge(dequeued.receiptHandle)

  /** Acknowledge (delete) a received message by receipt handle. */
  def acknowledge(receiptHandle: String): Try[Unit] =
    if receiptHandle == null || receiptHandle.isEmpty then
      scala.util.Failure(IllegalArgumentException("receipt handle must be non-empty"))
    else queue.deleteMessage(queueUrl, receiptHandle)

  /** Mark a job as complete by adding its ID to the deduplication store. */
  def markComplete(jobId: String): Try[Unit] = Try {
    deduplicationStore.add(jobId)
    ()
  }

  /** Mark a job as failed. Does not add to dedup store so it can be retried. */
  def markFailed(jobId: String, reason: String): Try[Unit] = Try {
    val _ = reason
    ()
  }

  /** Check whether an entity/hash/model combination has already been processed. */
  def isDuplicate(
    entityId: String,
    textHash: String,
    modelVersion: String
  ): Try[Boolean] = Try {
    deduplicationStore.contains(EmbeddingQueueManager.dedupKey(entityId, textHash, modelVersion))
  }

  /**
   * Content-hash skip rule: skip re-embedding only when the same text (entityId + textHash) was
   * already embedded by the model that populates the configured index set. A record embedded by any
   * other model (e.g. a Voyage fallback) does not cause a skip, so the text is re-embedded for the
   * configured index.
   */
  def shouldSkipEmbedding(
    entityId: String,
    textHash: String,
    indexConfig: S3VectorsConfig
  ): Try[Boolean] =
    isDuplicate(entityId, textHash, indexConfig.embeddingModelId)

  /**
   * Record that `entityId`/`textHash` was embedded by `embeddingModel` and written. Call only after
   * the vector was accepted by the writer; this makes the key visible to [[isDuplicate]] and
   * [[shouldSkipEmbedding]]. (`markComplete` records the jobId, which the skip rule does not read.)
   */
  def recordEmbedded(
    entityId: String,
    textHash: String,
    embeddingModel: String
  ): Try[Unit] = Try {
    deduplicationStore.add(EmbeddingQueueManager.dedupKey(entityId, textHash, embeddingModel))
    ()
  }

object EmbeddingQueueManager:

  private val log = org.slf4j.LoggerFactory.getLogger(classOf[EmbeddingQueueManager])

  /** Default log sink (WARN). Callers never pass message bodies to it. */
  val defaultLog: String => Unit = (msg: String) => log.warn(msg)

  /**
   * Parse and validate a queue body. `Left` is a reason code: `PARSE_ERROR:<exception class>` or
   * `INVALID_FIELD:<name>`; it never contains any part of the body.
   */
  def parseJob(body: String): Either[String, EmbeddingJob] =
    Try(read[EmbeddingJob](body)) match
      case scala.util.Failure(e) => Left(s"PARSE_ERROR:${e.getClass.getSimpleName}")
      case scala.util.Success(job) =>
        def blank(v: String) = v == null || v.trim.isEmpty
        val invalid = Seq(
          "jobId"          -> blank(job.jobId),
          "entityId"       -> blank(job.entityId),
          "documentType"   -> blank(job.documentType),
          "textS3Uri"      -> blank(job.textS3Uri),
          "textHash"       -> blank(job.textHash),
          "embeddingModel" -> blank(job.embeddingModel),
          "runId"          -> blank(job.runId),
          "retryCount"     -> (job.retryCount < 0)
        ).collectFirst { case (name, true) => name }
        invalid match
          case Some(name) => Left(s"INVALID_FIELD:$name")
          case None       => Right(job)

  /**
   * Build a manager whose queue URL comes from storage config. Fails when the URL is not
   * configured, rather than falling back to a hardcoded queue.
   */
  def fromConfig(
    queue: QueueClient,
    config: StorageModuleConfig,
    deduplicationStore: scala.collection.mutable.Set[String]
  ): Try[EmbeddingQueueManager] =
    config.embeddingQueueUrl match
      case Some(url) => Try(EmbeddingQueueManager(queue, url, deduplicationStore))
      case None =>
        scala.util.Failure(
          IllegalStateException(
            "embedding queue URL is not configured: set scholarchain.storage.embedding-queue-url " +
              s"or ${StorageModuleConfig.EmbeddingQueueUrlEnv}"
          )
        )

  /** Dedup key over the (entityId, textHash, embedding model) triple. */
  def dedupKey(entityId: String, textHash: String, embeddingModel: String): String =
    sha256(s"$entityId|$textHash|$embeddingModel")

  /** Compute lowercase hex SHA-256 digest of a string. */
  def sha256(text: String): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(text.getBytes("UTF-8")).map("%02x".format(_)).mkString
