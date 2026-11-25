package edu.scholarchain.storage

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/**
 * STO-P13-01: `EmbeddingQueueManager` dequeues jobs with their receipt handles and acknowledges by
 * receipt handle, never by message ID (SQS `DeleteMessage` only accepts a receipt handle).
 */
class EmbeddingQueueAckSpec extends AnyFlatSpec with Matchers:

  private val QueueUrl = "https://sqs.us-east-2.amazonaws.com/123456789012/scholarchain-dev-embed"

  private def job(id: String) = EmbeddingJob(
    jobId = id,
    entityId = s"researcher:orcid:$id",
    documentType = "researcher-profile",
    textS3Uri = s"s3://rag/text/$id.txt",
    textHash = s"hash-$id",
    embeddingModel = "amazon.titan-embed-text-v2:0",
    windowStartYear = None,
    windowEndYear = None,
    runId = "run-001",
    retryCount = 0
  )

  /**
   * In-memory queue with SQS semantics: message IDs are stable, each receive issues a fresh receipt
   * handle, and only a currently valid receipt handle deletes a message.
   */
  private class FakeSqs extends QueueClient:
    private var counter                                 = 0
    val messages: mutable.LinkedHashMap[String, String] = mutable.LinkedHashMap.empty
    val handles: mutable.Map[String, String]            = mutable.Map.empty // handle -> messageId
    val deleteCalls: mutable.ArrayBuffer[String]        = mutable.ArrayBuffer.empty

    def sendMessage(queueUrl: String, body: String): Try[String] =
      counter += 1
      val id = s"msg-$counter"
      messages(id) = body
      Success(id)

    def receiveMessages(queueUrl: String, maxMessages: Int): Try[List[ReceivedMessage]] =
      Success(messages.toList.take(maxMessages).map { (id, body) =>
        counter += 1
        val handle = s"AQEB-receipt-$id-$counter"
        handles(handle) = id
        ReceivedMessage(id, handle, body)
      })

    def deleteMessage(queueUrl: String, receiptHandle: String): Try[Unit] =
      deleteCalls += receiptHandle
      handles.remove(receiptHandle) match
        case Some(id) => messages.remove(id); Success(())
        case None =>
          Failure(IllegalArgumentException(s"ReceiptHandleIsInvalid: $receiptHandle"))

  "EmbeddingQueueManager.dequeueBatch" should "return each job with its receipt handle and message ID" in {
    val sqs     = FakeSqs()
    val manager = EmbeddingQueueManager(sqs, QueueUrl, mutable.Set.empty)
    val id1     = manager.enqueue(job("j1")).get
    val id2     = manager.enqueue(job("j2")).get

    val batch = manager.dequeueBatch(10).get
    batch.map(_.job.jobId) shouldBe List("j1", "j2")
    batch.map(_.messageId) shouldBe List(id1, id2)
    batch.foreach { d =>
      d.receiptHandle should startWith("AQEB-receipt-")
      d.receiptHandle should not be d.messageId
    }
  }

  it should "acknowledge by receipt handle, deleting the message" in {
    val sqs     = FakeSqs()
    val manager = EmbeddingQueueManager(sqs, QueueUrl, mutable.Set.empty)
    manager.enqueue(job("j1")).get
    val dequeued = manager.dequeueBatch(1).get.head

    manager.acknowledge(dequeued) shouldBe Success(())
    sqs.deleteCalls.toList shouldBe List(dequeued.receiptHandle)
    sqs.deleteCalls should not contain dequeued.messageId
    sqs.messages shouldBe empty
  }

  it should "not be able to delete with a message ID (the old bug)" in {
    val sqs     = FakeSqs()
    val manager = EmbeddingQueueManager(sqs, QueueUrl, mutable.Set.empty)
    manager.enqueue(job("j1")).get
    val dequeued = manager.dequeueBatch(1).get.head

    manager.acknowledge(dequeued.messageId).isFailure shouldBe true
    sqs.messages should have size 1
    manager.acknowledge(dequeued.receiptHandle) shouldBe Success(())
    sqs.messages shouldBe empty
  }

  it should "acknowledge only the jobs passed, leaving others on the queue" in {
    val sqs     = FakeSqs()
    val manager = EmbeddingQueueManager(sqs, QueueUrl, mutable.Set.empty)
    Seq("j1", "j2", "j3").foreach(j => manager.enqueue(job(j)).get)
    val batch = manager.dequeueBatch(3).get
    manager.acknowledge(batch(1)).get
    sqs.messages.values.map(read[EmbeddingJob](_).jobId).toList shouldBe List("j1", "j3")
  }

  it should "reject an empty receipt handle without calling the queue" in {
    val sqs     = FakeSqs()
    val manager = EmbeddingQueueManager(sqs, QueueUrl, mutable.Set.empty)
    manager.acknowledge("").isFailure shouldBe true
    sqs.deleteCalls shouldBe empty
  }

  it should "skip (not fail on, not acknowledge) an invalid job body" in {
    val sqs = FakeSqs()
    sqs.sendMessage(QueueUrl, """{"not":"a job","secretish":"BODY-VALUE"}""").get
    val logs    = mutable.ArrayBuffer.empty[String]
    val manager = EmbeddingQueueManager(sqs, QueueUrl, mutable.Set.empty, logs += _)
    manager.dequeueBatch(1) shouldBe Success(Nil)
    sqs.deleteCalls shouldBe empty
    sqs.messages should have size 1
    logs.mkString should include("msg-1")
    logs.mkString should not include "BODY-VALUE"
  }

  it should "return an empty batch for an empty queue" in {
    val manager = EmbeddingQueueManager(FakeSqs(), QueueUrl, mutable.Set.empty)
    manager.dequeueBatch(10) shouldBe Success(Nil)
  }
