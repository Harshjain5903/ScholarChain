package edu.scholarchain.storage

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.*

import scala.collection.mutable
import scala.util.Success

/**
 * STO-P13-03: [[SqsQueueClient]] against a fake `SqsClient` (no network, no AWS calls).
 */
class SqsQueueClientSpec extends AnyFlatSpec with Matchers:

  private val QueueUrl = "https://sqs.us-east-2.amazonaws.com/123456789012/scholarchain-dev-embed"

  private def throttled(): RequestThrottledException =
    RequestThrottledException
      .builder()
      .statusCode(400)
      .awsErrorDetails(AwsErrorDetails.builder().errorCode("RequestThrottled").build())
      .message("Request is throttled.")
      .build()

  private def sqsError(code: String, status: Int): SqsException =
    SqsException
      .builder()
      .statusCode(status)
      .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
      .message(s"$code happened")
      .build()
      .asInstanceOf[SqsException]

  /** Records requests; behaviour per operation is supplied by the test. */
  private class FakeSqsClient(
    onSend: Int => SendMessageResponse = _ =>
      SendMessageResponse.builder().messageId("m-1").build(),
    onReceive: ReceiveMessageRequest => ReceiveMessageResponse = _ =>
      ReceiveMessageResponse.builder().build(),
    onDelete: DeleteMessageRequest => DeleteMessageResponse = _ =>
      DeleteMessageResponse.builder().build()
  ) extends SqsClient:
    val sends: mutable.ArrayBuffer[SendMessageRequest]       = mutable.ArrayBuffer.empty
    val receives: mutable.ArrayBuffer[ReceiveMessageRequest] = mutable.ArrayBuffer.empty
    val deletes: mutable.ArrayBuffer[DeleteMessageRequest]   = mutable.ArrayBuffer.empty
    var closed                                               = false

    override def serviceName(): String = "sqs"
    override def close(): Unit         = closed = true

    override def sendMessage(r: SendMessageRequest): SendMessageResponse =
      sends += r; onSend(sends.size)

    override def receiveMessage(r: ReceiveMessageRequest): ReceiveMessageResponse =
      receives += r; onReceive(r)

    override def deleteMessage(r: DeleteMessageRequest): DeleteMessageResponse =
      deletes += r; onDelete(r)

  private def msg(id: String, handle: String, body: String): Message =
    Message.builder().messageId(id).receiptHandle(handle).body(body).build()

  private val noSleep: Long => Unit = _ => ()

  // ---- receive ----

  "SqsQueueClient.receiveMessages" should "long-poll for at most 10 messages and map id, handle, body" in {
    val fake = FakeSqsClient(onReceive =
      _ =>
        ReceiveMessageResponse
          .builder()
          .messages(msg("id-1", "AQEB-h1", "b1"), msg("id-2", "AQEB-h2", "b2"))
          .build()
    )
    val client = SqsQueueClient(fake)
    client.receiveMessages(QueueUrl, 50) shouldBe Success(
      List(ReceivedMessage("id-1", "AQEB-h1", "b1"), ReceivedMessage("id-2", "AQEB-h2", "b2"))
    )
    val req = fake.receives.head
    req.queueUrl() shouldBe QueueUrl
    req.maxNumberOfMessages() shouldBe 10
    req.waitTimeSeconds() shouldBe 20
    req.visibilityTimeout() shouldBe null // queue default unless configured
  }

  it should "pass smaller batch sizes and the configured wait and visibility timeout through" in {
    val fake = FakeSqsClient()
    val client =
      SqsQueueClient(
        fake,
        SqsQueueSettings(waitTimeSeconds = 5, visibilityTimeoutSeconds = Some(600))
      )
    client.receiveMessages(QueueUrl, 3) shouldBe Success(Nil)
    val req = fake.receives.head
    req.maxNumberOfMessages() shouldBe 3
    req.waitTimeSeconds() shouldBe 5
    req.visibilityTimeout() shouldBe 600
  }

  it should "reject maxMessages < 1 without calling SQS" in {
    val fake = FakeSqsClient()
    SqsQueueClient(fake).receiveMessages(QueueUrl, 0).isFailure shouldBe true
    fake.receives shouldBe empty
  }

  it should "wrap a receive error without leaking anything but class, code and status" in {
    val fake = FakeSqsClient(onReceive = _ => throw sqsError("QueueDoesNotExist", 400))
    val err  = SqsQueueClient(fake).receiveMessages(QueueUrl, 1).failed.get
    err shouldBe a[SqsQueueException]
    err.getMessage should include("code=QueueDoesNotExist")
    err.getMessage should include("status=400")
  }

  // ---- delete ----

  "SqsQueueClient.deleteMessage" should "delete by receipt handle" in {
    val fake = FakeSqsClient()
    SqsQueueClient(fake).deleteMessage(QueueUrl, "AQEB-h1") shouldBe Success(())
    fake.deletes.map(d => (d.queueUrl(), d.receiptHandle())).toList shouldBe
      List(QueueUrl -> "AQEB-h1")
  }

  it should "refuse an empty receipt handle without calling SQS" in {
    val fake = FakeSqsClient()
    SqsQueueClient(fake).deleteMessage(QueueUrl, "").isFailure shouldBe true
    fake.deletes shouldBe empty
  }

  it should "surface ReceiptHandleIsInvalid as a failure" in {
    val fake = FakeSqsClient(onDelete = _ => throw sqsError("ReceiptHandleIsInvalid", 400))
    val err  = SqsQueueClient(fake).deleteMessage(QueueUrl, "stale").failed.get
    err.getMessage should include("ReceiptHandleIsInvalid")
  }

  // ---- send with bounded throttling retries ----

  "SqsQueueClient.sendMessage" should "retry throttling and succeed within the bound" in {
    val sleeps = mutable.ArrayBuffer.empty[Long]
    val fake = FakeSqsClient(onSend =
      n =>
        if n < 3 then throw throttled()
        else SendMessageResponse.builder().messageId("m-3").build()
    )
    val client = SqsQueueClient(
      fake,
      SqsQueueSettings(maxSendAttempts = 3, retryBaseDelayMs = 100),
      sleeps += _
    )
    client.sendMessage(QueueUrl, "{}") shouldBe Success("m-3")
    fake.sends should have size 3
    sleeps.toList shouldBe List(100L, 200L)
  }

  it should "stop after maxSendAttempts when throttling persists" in {
    val fake = FakeSqsClient(onSend = _ => throw throttled())
    val client =
      SqsQueueClient(fake, SqsQueueSettings(maxSendAttempts = 4, retryBaseDelayMs = 1), noSleep)
    val result = client.sendMessage(QueueUrl, "SECRET-BODY")
    result.isFailure shouldBe true
    fake.sends should have size 4
    result.failed.get.getMessage should include("attempts=4")
    result.failed.get.getMessage should not include "SECRET-BODY"
  }

  it should "treat HTTP 429 and ThrottlingException codes as throttling" in {
    SqsQueueClient.isThrottling(sqsError("ThrottlingException", 400)) shouldBe true
    SqsQueueClient.isThrottling(sqsError("Whatever", 429)) shouldBe true
    SqsQueueClient.isThrottling(sqsError("InvalidParameterValue", 400)) shouldBe false
    SqsQueueClient.isThrottling(RuntimeException("x")) shouldBe false
  }

  it should "not retry a non-throttling error" in {
    val fake   = FakeSqsClient(onSend = _ => throw sqsError("InvalidMessageContents", 400))
    val client = SqsQueueClient(fake, SqsQueueSettings(maxSendAttempts = 5), noSleep)
    client.sendMessage(QueueUrl, "{}").isFailure shouldBe true
    fake.sends should have size 1
  }

  it should "cap the backoff" in {
    SqsQueueClient.backoffMs(1000, 1) shouldBe 1000
    SqsQueueClient.backoffMs(1000, 2) shouldBe 2000
    SqsQueueClient.backoffMs(1000, 9) shouldBe SqsQueueSettings.MaxBackoffMs
  }

  // ---- settings and config ----

  "SqsQueueSettings" should "reject out-of-range values" in {
    an[IllegalArgumentException] should be thrownBy SqsQueueSettings(waitTimeSeconds = 21)
    an[IllegalArgumentException] should be thrownBy SqsQueueSettings(waitTimeSeconds = -1)
    an[IllegalArgumentException] should be thrownBy
      SqsQueueSettings(visibilityTimeoutSeconds = Some(43201))
    an[IllegalArgumentException] should be thrownBy SqsQueueSettings(maxSendAttempts = 0)
    an[IllegalArgumentException] should be thrownBy SqsQueueSettings(maxSendAttempts = 11)
  }

  "StorageModuleConfig.embeddingQueue" should "default to 20 s long poll and the queue visibility timeout" in {
    val q = StorageModuleConfig.loadWithEnv(Map.empty).embeddingQueue
    q.region shouldBe "us-east-2"
    q.settings shouldBe SqsQueueSettings(20, None, 3, 100)
  }

  it should "take the visibility timeout and send bound from the environment" in {
    val q = StorageModuleConfig
      .loadWithEnv(
        Map(
          "SCHOLARCHAIN_EMBEDDING_QUEUE_VISIBILITY_TIMEOUT_SECONDS" -> "900",
          "SCHOLARCHAIN_EMBEDDING_QUEUE_MAX_SEND_ATTEMPTS"          -> "5",
          "SCHOLARCHAIN_EMBEDDING_QUEUE_WAIT_TIME_SECONDS"          -> "10",
          "SCHOLARCHAIN_EMBEDDING_QUEUE_REGION"                     -> "eu-west-1"
        )
      )
      .embeddingQueue
    q.region shouldBe "eu-west-1"
    q.settings.visibilityTimeoutSeconds shouldBe Some(900)
    q.settings.maxSendAttempts shouldBe 5
    q.settings.waitTimeSeconds shouldBe 10
  }

  "SqsQueueClient.create" should "build a client without network calls and reject a bad region" in {
    val c = SqsQueueClient.create("us-east-2")
    c.settings shouldBe SqsQueueSettings()
    c.close()
    an[IllegalArgumentException] should be thrownBy SqsQueueClient.create("evil.com/x")
  }

  it should "close the injected SDK client" in {
    val fake = FakeSqsClient()
    SqsQueueClient(fake).close()
    fake.closed shouldBe true
  }

  // ---- end to end with the queue manager ----

  "EmbeddingQueueManager over SqsQueueClient" should "ack valid jobs by handle and leave poison" in {
    val good = upickle.default.write(
      EmbeddingJob("j1", "e1", "researcher-profile", "s3://b/k", "h", "m", None, None, "r", 0)
    )
    val fake = FakeSqsClient(onReceive =
      _ =>
        ReceiveMessageResponse
          .builder()
          .messages(msg("id-1", "h-1", good), msg("id-2", "h-2", "not json"))
          .build()
    )
    val logs = mutable.ArrayBuffer.empty[String]
    val manager =
      EmbeddingQueueManager(SqsQueueClient(fake), QueueUrl, mutable.Set.empty, logs += _)
    val results = manager.processBatch(10)(_ => Success(())).get
    results.map(r => r.messageId -> r.status) shouldBe
      List("id-1" -> MessageStatus.Acknowledged, "id-2" -> MessageStatus.Poison)
    fake.deletes.map(_.receiptHandle()).toList shouldBe List("h-1")
    logs.mkString should not include "not json"
  }
