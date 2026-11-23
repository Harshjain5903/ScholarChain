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

/** Trait for queue operations -- allows SQS or in-memory implementations. */
trait QueueClient:
  def sendMessage(queueUrl: String, body: String): Try[String]

  def receiveMessages(
    queueUrl: String,
    maxMessages: Int
  ): Try[List[(String, String)]]
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
  deduplicationStore: scala.collection.mutable.Set[String]
):
  require(queueUrl != null && queueUrl.trim.nonEmpty, "embedding queue URL must be non-empty")

  /** Enqueue a single embedding job. Returns the message ID on success. */
  def enqueue(job: EmbeddingJob): Try[String] = Try {
    val body = write(job)
    queue.sendMessage(queueUrl, body).get
  }

  /** Dequeue a batch of embedding jobs. */
  def dequeueBatch(maxMessages: Int): Try[List[EmbeddingJob]] = Try {
    queue.receiveMessages(queueUrl, maxMessages).get.map { case (_, body) =>
      read[EmbeddingJob](body)
    }
  }

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
