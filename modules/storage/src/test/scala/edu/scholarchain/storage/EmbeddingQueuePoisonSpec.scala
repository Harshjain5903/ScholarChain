package edu.scholarchain.storage

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/**
 * STO-P13-03: poison messages are never acknowledged (SQS redelivers them and moves them to the DLQ
 * after maxReceiveCount), are logged by message ID only, and do not fail the rest of the batch.
 */
class EmbeddingQueuePoisonSpec extends AnyFlatSpec with Matchers:

  private val QueueUrl = "https://sqs.us-east-2.amazonaws.com/123456789012/scholarchain-dev-embed"
  private val Secret   = "PRIVATE-BODY-CONTENT"

  private def job(id: String) =
    EmbeddingJob(id, s"e-$id", "researcher-profile", s"s3://b/$id", "h", "m", None, None, "r", 0)

  /**
   * SQS-like queue with a redrive policy: every receive increments a message's receive count; a
   * message received more than `maxReceiveCount` times is moved to the DLQ instead of delivered.
   */
  private class RedriveQueue(maxReceiveCount: Int, failDelete: Set[String] = Set.empty)
      extends QueueClient:
    private var n                                    = 0
    val live: mutable.LinkedHashMap[String, String]  = mutable.LinkedHashMap.empty
    val receiveCount: mutable.Map[String, Int]       = mutable.Map.empty
    val dlq: mutable.ArrayBuffer[String]             = mutable.ArrayBuffer.empty
    private val handles: mutable.Map[String, String] = mutable.Map.empty
    val deleted: mutable.ArrayBuffer[String]         = mutable.ArrayBuffer.empty

    def sendMessage(queueUrl: String, body: String): Try[String] =
      n += 1; val id = s"msg-$n"; live(id) = body; Success(id)

    def receiveMessages(queueUrl: String, maxMessages: Int): Try[List[ReceivedMessage]] =
      live.keys.toList.foreach { id =>
        if receiveCount.getOrElse(id, 0) >= maxReceiveCount then
          dlq += id; live.remove(id)
      }
      Success(live.toList.take(maxMessages).map { (id, body) =>
        receiveCount(id) = receiveCount.getOrElse(id, 0) + 1
        n += 1
        val h = s"h-$id-$n"
        handles(h) = id
        ReceivedMessage(id, h, body)
      })

    def deleteMessage(queueUrl: String, receiptHandle: String): Try[Unit] =
      handles.get(receiptHandle) match
        case Some(id) if failDelete(id) => Failure(RuntimeException("ServiceUnavailable"))
        case Some(id) =>
          deleted += id; live.remove(id); handles.remove(receiptHandle); Success(())
        case None => Failure(IllegalArgumentException("ReceiptHandleIsInvalid"))

  "EmbeddingQueueManager.receiveBatch" should "classify each message independently" in {
    val q = RedriveQueue(3)
    q.sendMessage(QueueUrl, write(job("j1")))
    q.sendMessage(QueueUrl, s"""{"garbage":"$Secret"}""")
    q.sendMessage(QueueUrl, write(job("j3")))
    val logs = mutable.ArrayBuffer.empty[String]
    val m    = EmbeddingQueueManager(q, QueueUrl, mutable.Set.empty, logs += _)

    val batch = m.receiveBatch(10).get
    batch.map(_.id) shouldBe List("msg-1", "msg-2", "msg-3")
    batch(0) shouldBe a[ReceivedJob.Valid]
    batch(1) shouldBe a[ReceivedJob.Poison]
    batch(1).asInstanceOf[ReceivedJob.Poison].reason should startWith("PARSE_ERROR:")
    batch(2) shouldBe a[ReceivedJob.Valid]
    logs should have size 1
    logs.head should include("msg-2")
    logs.head should not include Secret
  }

  it should "treat a parseable job with a blank required field as poison" in {
    EmbeddingQueueManager.parseJob(write(job("j1").copy(textHash = " "))) shouldBe
      Left("INVALID_FIELD:textHash")
    EmbeddingQueueManager.parseJob(write(job("j1").copy(retryCount = -1))) shouldBe
      Left("INVALID_FIELD:retryCount")
    EmbeddingQueueManager.parseJob(write(job("j1"))) shouldBe Right(job("j1"))
    EmbeddingQueueManager.parseJob(Secret).left.toOption.get should not include Secret
  }

  it should "fail only when the receive call itself fails" in {
    val broken = new QueueClient:
      def sendMessage(u: String, b: String)    = Success("x")
      def receiveMessages(u: String, max: Int) = Failure(RuntimeException("down"))
      def deleteMessage(u: String, h: String)  = Success(())
    EmbeddingQueueManager(broken, QueueUrl, mutable.Set.empty).receiveBatch(10).isFailure shouldBe
      true
  }

  "EmbeddingQueueManager.processBatch" should "ack successes and keep poison and failures (partial batch)" in {
    val q = RedriveQueue(3)
    q.sendMessage(QueueUrl, write(job("ok")))
    q.sendMessage(QueueUrl, Secret)
    q.sendMessage(QueueUrl, write(job("boom")))
    q.sendMessage(QueueUrl, write(job("ok2")))
    val logs = mutable.ArrayBuffer.empty[String]
    val m    = EmbeddingQueueManager(q, QueueUrl, mutable.Set.empty, logs += _)

    val results = m
      .processBatch(10) { d =>
        if d.job.jobId == "boom" then Failure(IllegalStateException("bedrock 500"))
        else Success(())
      }
      .get

    results.map(r => (r.messageId, r.jobId, r.status)) shouldBe List(
      ("msg-1", Some("ok"), MessageStatus.Acknowledged),
      ("msg-2", None, MessageStatus.Poison),
      ("msg-3", Some("boom"), MessageStatus.HandlerFailed),
      ("msg-4", Some("ok2"), MessageStatus.Acknowledged)
    )
    results(2).reason shouldBe Some("HANDLER_FAILED:IllegalStateException")
    q.deleted.toList shouldBe List("msg-1", "msg-4")
    q.live.keySet shouldBe Set("msg-2", "msg-3")
    logs.mkString should not include Secret
  }

  it should "contain a handler that throws instead of returning Failure" in {
    val q = RedriveQueue(3)
    q.sendMessage(QueueUrl, write(job("t")))
    val r = EmbeddingQueueManager(q, QueueUrl, mutable.Set.empty, _ => ())
      .processBatch(1)(_ => throw RuntimeException("x"))
      .get
    r.map(_.status) shouldBe List(MessageStatus.HandlerFailed)
    q.deleted shouldBe empty
  }

  it should "report AckFailed when the delete fails after a successful handler" in {
    val q = RedriveQueue(3, failDelete = Set("msg-1"))
    q.sendMessage(QueueUrl, write(job("a")))
    val r = EmbeddingQueueManager(q, QueueUrl, mutable.Set.empty, _ => ())
      .processBatch(1)(_ => Success(()))
      .get
    r.map(_.status) shouldBe List(MessageStatus.AckFailed)
    r.head.reason.get should startWith("ACK_FAILED:")
  }

  it should "let a poison message reach the DLQ after maxReceiveCount receives" in {
    val q = RedriveQueue(maxReceiveCount = 3)
    q.sendMessage(QueueUrl, Secret)
    q.sendMessage(QueueUrl, write(job("good")))
    val m = EmbeddingQueueManager(q, QueueUrl, mutable.Set.empty, _ => ())

    val first = m.processBatch(10)(_ => Success(())).get
    first.map(_.status) shouldBe List(MessageStatus.Poison, MessageStatus.Acknowledged)
    // the poison message is redelivered (never deleted) until the redrive moves it
    m.processBatch(10)(_ => Success(())).get.map(_.status) shouldBe List(MessageStatus.Poison)
    m.processBatch(10)(_ => Success(())).get.map(_.status) shouldBe List(MessageStatus.Poison)
    m.processBatch(10)(_ => Success(())).get shouldBe Nil
    q.dlq.toList shouldBe List("msg-1")
    q.deleted.toList shouldBe List("msg-2")
  }
