package edu.scholarchain.storage

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

/** Manages embedding job enqueueing, dequeuing, and deduplication. */
class EmbeddingQueueManager(
  queue: QueueClient,
  queueUrl: String,
  deduplicationStore: scala.collection.mutable.Set[String]
):

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
    val key = EmbeddingQueueManager.sha256(s"$entityId|$textHash|$modelVersion")
    deduplicationStore.contains(key)
  }

object EmbeddingQueueManager:

  /** Compute lowercase hex SHA-256 digest of a string. */
  def sha256(text: String): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(text.getBytes("UTF-8")).map("%02x".format(_)).mkString
