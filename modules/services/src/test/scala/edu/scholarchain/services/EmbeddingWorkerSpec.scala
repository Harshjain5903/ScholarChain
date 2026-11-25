package edu.scholarchain.services

import edu.scholarchain.core.*
import edu.scholarchain.services.ServiceTestFakes.*
import edu.scholarchain.storage.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable

class EmbeddingWorkerSpec extends AnyFlatSpec with Matchers:

  private val Titan    = "amazon.titan-embed-text-v2:0"
  private val index    = S3VectorsConfig(1024, DistanceMetric.Cosine, Titan)
  private val rag      = "rag-bucket"
  private val QueueUrl = "https://sqs.us-east-2.amazonaws.com/000000000000/embed"

  final private class World(dims: Int = 1024):
    val queue    = FakeQueue()
    val s3Fake   = FakeS3()
    val s3       = S3StorageClient(s3Fake)
    val vectors  = FakeS3Vectors()
    val embedder = FakeEmbedder(dims)
    val logs     = mutable.ArrayBuffer.empty[String]
    val writer   = EmbeddingBatchWriter(vectors, s3, "vec-bucket", rag, index, maxRetries = 0)
    val manager  = EmbeddingQueueManager(queue, QueueUrl, mutable.Set.empty, _ => ())
    val handler  = EmbeddingJobHandler(s3, embedder, Titan, writer, index)
    val loop     = EmbeddingWorkerLoop(manager, handler, line => logs += line, sleep = _ => ())

    def job(id: String, text: String, docType: String = "researcher-profile"): EmbeddingJob =
      val uri = s3Fake.put(rag, s"contexts/$docType/$id.txt", text)
      EmbeddingJob(
        jobId = s"job-$id",
        entityId = s"researcher:sc:$id",
        documentType = docType,
        textS3Uri = uri,
        textHash = S3StorageClient.sha256Hex(text.getBytes("UTF-8")),
        embeddingModel = Titan,
        windowStartYear = Some(1980),
        windowEndYear = Some(2026),
        runId = "run-1",
        retryCount = 0
      )

    def enqueue(j: EmbeddingJob): Unit = queue.sendMessage(QueueUrl, write(j)).get: Unit

  private val secretText = "SECRET-TEXT-BODY should never be logged"

  "WorkerOptions.parse" should "accept the bounds and reject bad values" in {
    WorkerOptions.parse(Nil) shouldBe Right(WorkerOptions())
    WorkerOptions.parse(
      Seq("--max-batches", "3", "--max-seconds", "60", "--batch-size", "5")
    ) shouldBe
      Right(WorkerOptions(Some(3), Some(60L), 5))
    WorkerOptions.parse(Seq("--max-batches", "0")).isLeft shouldBe true
    WorkerOptions.parse(Seq("--batch-size", "11")).isLeft shouldBe true
    WorkerOptions.parse(Seq("--max-seconds")).isLeft shouldBe true
    WorkerOptions.parse(Seq("--bogus")).isLeft shouldBe true
  }

  "EmbeddingWorker.configErrors" should "refuse a missing queue URL and a query model that differs from the index model" in {
    val app     = appConfig()
    val storage = StorageModuleConfig.loadWithEnv(Map.empty)
    EmbeddingWorker.configErrors(app, storage) should have size 1
    val voyage = appConfig(
      Map(
        "SCHOLARCHAIN_EMBEDDING_PROVIDER"     -> "voyage",
        "SCHOLARCHAIN_EMBEDDING_MODEL_ID"     -> "voyage-3",
        "SCHOLARCHAIN_VOYAGE_API_KEY_ENV_VAR" -> "VOYAGE_KEY_NAME"
      )
    )
    val withUrl =
      StorageModuleConfig.loadWithEnv(Map(StorageModuleConfig.EmbeddingQueueUrlEnv -> QueueUrl))
    EmbeddingWorker.configErrors(app, withUrl) shouldBe empty
    EmbeddingWorker.configErrors(voyage, withUrl).head should include(
      "does not match the index model"
    )
  }

  "EmbeddingWorkerLoop" should "embed, write and acknowledge valid jobs and log counts but never text" in {
    val w = World()
    val a = w.job("a", secretText)
    val b = w.job("b", "community text", "community-summary")
    w.enqueue(a)
    w.enqueue(b)
    val summary = w.loop.run(WorkerOptions(maxBatches = Some(1)), AtomicBoolean(false))

    summary.batches shouldBe 1
    val s = summary.stats.head
    (s.received, s.acknowledged, s.written, s.handlerFailed) shouldBe (2, 2, 2, 0)
    w.queue.deleted shouldBe Set("rh-m-1", "rh-m-2")
    w.embedder.calls.get() shouldBe 2
    val keyA = VectorKeys.stable("researcher-profile", a.entityId, Some(1980), Some(2026))
    val rec  = w.vectors.stored.get((S3VectorsIndexes.ResearcherProfile, keyA))
    rec.metadata.textHash shouldBe a.textHash
    rec.metadata.embeddingModel shouldBe Titan
    rec.metadata.runId shouldBe "run-1"
    w.vectors.stored
      .keySet()
      .stream()
      .anyMatch(_._1 == S3VectorsIndexes.CollaborationCommunity) shouldBe true

    w.logs should have size 1
    val line = ujson.read(w.logs.head)
    line("event").str shouldBe "embedding-batch"
    line("written").num shouldBe 2
    w.logs.head should not include "SECRET-TEXT-BODY"
    w.logs.head should not include "community text"
  }

  it should "skip unchanged text without fetching or embedding it" in {
    val w = World()
    val a = w.job("a", "same text")
    w.enqueue(a)
    w.loop.run(WorkerOptions(maxBatches = Some(1)), AtomicBoolean(false))
    val gets = w.s3Fake.gets.get()
    w.enqueue(a.copy(jobId = "job-a2"))
    val s = w.loop.run(WorkerOptions(maxBatches = Some(1)), AtomicBoolean(false)).stats.head
    (s.skipped, s.acknowledged, s.written) shouldBe (1, 1, 0)
    w.embedder.calls.get() shouldBe 1
    w.s3Fake.gets.get() shouldBe gets
  }

  it should "leave poison messages unacknowledged and still process the rest of the batch" in {
    val w = World()
    w.queue.sendMessage(QueueUrl, """{"not":"a job"}""")
    w.enqueue(w.job("ok", "fine"))
    val s = w.loop.run(WorkerOptions(maxBatches = Some(1)), AtomicBoolean(false)).stats.head
    (s.received, s.poison, s.acknowledged, s.written) shouldBe (2, 1, 1, 1)
    w.queue.deleted shouldBe Set("rh-m-2")
    s.failureCodes.keys.exists(_.startsWith("PARSE_ERROR")) shouldBe true
  }

  it should "fail only the broken jobs of a batch (missing text, hash mismatch, unknown type)" in {
    val w       = World()
    val missing = w.job("missing", "gone")
    w.s3Fake.objects.clear()
    val good    = w.job("good", "present")
    val changed = w.job("changed", "original").copy(textHash = "0" * 64)
    val unknown = w.job("unknown", "x", "mystery-type")
    Seq(missing, good, changed, unknown).foreach(w.enqueue)
    val s = w.loop.run(WorkerOptions(maxBatches = Some(1)), AtomicBoolean(false)).stats.head
    (s.received, s.acknowledged, s.handlerFailed, s.written) shouldBe (4, 1, 3, 1)
    w.queue.deleted shouldBe Set("rh-m-2")
    s.failureCodes.keySet shouldBe Set(
      "TEXT_FETCH_FAILED:NoSuchKeyException",
      "TEXT_HASH_MISMATCH",
      "UNKNOWN_DOCUMENT_TYPE"
    )
    w.embedder.calls.get() shouldBe 1
  }

  it should "leave a job for redelivery when PutVectors fails" in {
    val w = World()
    w.vectors.failPuts = true
    w.enqueue(w.job("a", "text"))
    val s = w.loop.run(WorkerOptions(maxBatches = Some(1)), AtomicBoolean(false)).stats.head
    (s.handlerFailed, s.acknowledged) shouldBe (1, 0)
    s.failureCodes shouldBe Map("PUT_VECTORS_FAILED" -> 1)
    w.queue.deleted shouldBe empty
  }

  it should "store index rejections in the rag bucket and acknowledge the job" in {
    val w = World(dims = 8) // embedder returns the wrong dimension
    val a = w.job("a", "text")
    w.enqueue(a)
    val s = w.loop.run(WorkerOptions(maxBatches = Some(1)), AtomicBoolean(false)).stats.head
    (s.rejected, s.acknowledged, s.written) shouldBe (1, 1, 0)
    val key = EmbeddingBatchWriter.rejectionKey(
      EmbeddingJobHandler.rejectionRunId(a),
      S3VectorsIndexes.ResearcherProfile
    )
    val stored = String(w.s3Fake.objects.get(s"$rag/$key"), "UTF-8")
    stored should include(VectorRejectionReason.EmbeddingDimensionMismatch)
    stored should not include "text\""
    w.vectors.stored.isEmpty shouldBe true
  }

  it should "reject a job whose model is not the index model before any call" in {
    val w = World()
    w.enqueue(w.job("a", "text").copy(embeddingModel = "voyage-3"))
    val s = w.loop.run(WorkerOptions(maxBatches = Some(1)), AtomicBoolean(false)).stats.head
    s.failureCodes shouldBe Map("JOB_MODEL_MISMATCH" -> 1)
    w.embedder.calls.get() shouldBe 0
    w.s3Fake.gets.get() shouldBe 0
  }

  it should "stop after --max-batches receives, counting empty ones" in {
    val w       = World()
    val summary = w.loop.run(WorkerOptions(maxBatches = Some(3)), AtomicBoolean(false))
    summary.batches shouldBe 3
    w.queue.receives.get() shouldBe 3
    w.logs should have size 3
  }

  it should "stop starting batches after --max-seconds" in {
    val w     = World()
    var clock = 0L
    val loop =
      EmbeddingWorkerLoop(w.manager, w.handler, _ => (), nowMs = () => clock, sleep = _ => ())
    w.queue.onReceive = () => clock += 400L
    val summary = loop.run(WorkerOptions(maxSeconds = Some(1L)), AtomicBoolean(false))
    summary.batches shouldBe 3 // starts at 0, 400 and 800 ms; not at 1200 ms
  }

  it should "finish the in-flight batch on shutdown and then stop" in {
    val w    = World()
    val stop = AtomicBoolean(false)
    w.enqueue(w.job("a", "one"))
    w.enqueue(w.job("b", "two"))
    w.queue.onReceive = () => stop.set(true) // SIGTERM arrives during the receive
    val summary = w.loop.run(WorkerOptions(batchSize = 10), stop)
    summary.batches shouldBe 1
    summary.stats.head.acknowledged shouldBe 2
    w.queue.deleted should have size 2
  }

  it should "back off after a failed receive and keep polling" in {
    val w      = World()
    val sleeps = mutable.ArrayBuffer.empty[Long]
    val loop   = EmbeddingWorkerLoop(w.manager, w.handler, _ => (), sleep = ms => sleeps += ms)
    w.queue.failReceive = true
    val summary = loop.run(WorkerOptions(maxBatches = Some(3)), AtomicBoolean(false))
    summary.stats.map(_.receiveFailed) shouldBe Vector(true, true, true)
    sleeps shouldBe Vector(1000L, 2000L)
  }

  "EmbeddingJobHandler.indexFor" should "map Spark and storage document types to the five indexes" in {
    EmbeddingJobHandler.indexFor("researcher-profile") shouldBe Some(
      S3VectorsIndexes.ResearcherProfile
    )
    EmbeddingJobHandler.indexFor("community-summary") shouldBe Some(
      S3VectorsIndexes.CollaborationCommunity
    )
    EmbeddingJobHandler.indexFor("collaboration-path") shouldBe Some(
      S3VectorsIndexes.CollaborationPath
    )
    EmbeddingJobHandler.indexFor("other") shouldBe None
  }

  "RuntimeComposer.embedder" should "build the configured provider lazily (no client until first call)" in {
    var built = 0
    val clients = edu.scholarchain.rag.ModelClients(
      bedrock = _ =>
        built += 1
        FakeBedrock()
      ,
      http = () => throw AssertionError("no HTTP"),
      sleep = _ => ()
    )
    val secrets = new edu.scholarchain.rag.SecretResolver:
      def resolve(ref: SecretRef): String = throw AssertionError("no secret")
    val app = appConfig()
    val e   = RuntimeComposer.embedder(app, secrets, Some(clients))
    built shouldBe 0
    e.embed("hello", app.embedding.modelId).vector should have size 1024
    built shouldBe 1
  }
