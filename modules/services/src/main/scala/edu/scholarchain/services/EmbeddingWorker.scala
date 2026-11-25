package edu.scholarchain.services

import edu.scholarchain.core.*
import edu.scholarchain.rag.{QueryEmbedder, S3TextStore, SecretResolvers}
import edu.scholarchain.storage.*
import org.slf4j.LoggerFactory

import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.ByteBuffer
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/**
 * Bounds of one worker run.
 *
 * @param maxBatches
 *   stop after this many receive calls (empty receives count); unbounded when `None`
 * @param maxSeconds
 *   stop starting new batches after this many seconds; unbounded when `None`
 * @param batchSize
 *   messages per receive, 1..10 (SQS maximum)
 */
final case class WorkerOptions(
  maxBatches: Option[Int] = None,
  maxSeconds: Option[Long] = None,
  batchSize: Int = SqsQueueSettings.MaxMessagesPerReceive
)

object WorkerOptions:

  val Usage: String =
    "usage: EmbeddingWorker [--max-batches N] [--max-seconds S] [--batch-size 1..10]"

  def parse(args: Seq[String]): Either[String, WorkerOptions] =
    @annotation.tailrec
    def go(rest: List[String], acc: WorkerOptions): Either[String, WorkerOptions] = rest match
      case Nil => Right(acc)
      case "--max-batches" :: v :: tail =>
        v.toIntOption.filter(_ >= 1) match
          case Some(n) => go(tail, acc.copy(maxBatches = Some(n)))
          case None    => Left(s"--max-batches must be a positive integer; $Usage")
      case "--max-seconds" :: v :: tail =>
        v.toLongOption.filter(_ >= 1) match
          case Some(n) => go(tail, acc.copy(maxSeconds = Some(n)))
          case None    => Left(s"--max-seconds must be a positive integer; $Usage")
      case "--batch-size" :: v :: tail =>
        v.toIntOption.filter(n => n >= 1 && n <= SqsQueueSettings.MaxMessagesPerReceive) match
          case Some(n) => go(tail, acc.copy(batchSize = n))
          case None    => Left(s"--batch-size must be 1..10; $Usage")
      case other :: _ => Left(s"unknown or incomplete argument '${other.take(40)}'; $Usage")
    go(args.toList, WorkerOptions())

/** A job failure; the message is a reason code only, never text or a message body. */
final class EmbeddingJobException(val code: String) extends RuntimeException(code)

/** What happened to one valid job. */
enum JobOutcome:
  /** Embedded and written to the index. */
  case Written

  /** The stored vector already has this text hash and model; no fetch, no embedding call. */
  case Skipped

  /** Rejected by index validation; the rejection was stored at `location` and the job acked. */
  case Rejected(location: String)

/** Outcome and per-step latencies of one job. */
final case class JobResult(outcome: JobOutcome, fetchMs: Long, embedMs: Long, writeMs: Long)

/**
 * Embeds one [[EmbeddingJob]]:
 *   1. maps `documentType` to its S3 Vectors index and checks `embeddingModel` equals the model
 *      that populates the indexes (`s3-vectors.embedding-model-id`); 2. skips the job when the
 *      stored vector already has the same text hash and model; 3. reads the text from `textS3Uri`
 *      and checks its SHA-256 equals `textHash`; 4. embeds it with the configured embedder and
 *      writes it with `writeChanged`; 5. stores validation rejections with `storeRejections` (the
 *      job is then acknowledged, since a retry cannot change the outcome).
 *
 * Any other failure throws [[EmbeddingJobException]] or the underlying error, so the message is
 * left on the queue for redelivery and, after `maxReceiveCount`, the DLQ.
 */
final class EmbeddingJobHandler(
  s3: S3StorageClient,
  embedder: QueryEmbedder,
  embeddingModelId: String,
  writer: EmbeddingBatchWriter,
  indexConfig: S3VectorsConfig,
  maxTextChars: Int = EmbeddingJobHandler.MaxTextChars,
  nowMs: () => Long = () => System.nanoTime() / 1000000L
):

  def handle(job: EmbeddingJob): JobResult =
    val index = EmbeddingJobHandler
      .indexFor(job.documentType)
      .getOrElse(throw EmbeddingJobException("UNKNOWN_DOCUMENT_TYPE"))
    if !indexConfig.isCompatibleModel(job.embeddingModel) then
      throw EmbeddingJobException("JOB_MODEL_MISMATCH")
    val key =
      VectorKeys.stable(job.documentType, job.entityId, job.windowStartYear, job.windowEndYear)

    if writer
        .unchangedKeys(index, Vector((key, job.textHash, indexConfig.embeddingModelId)))
        .contains(key)
    then JobResult(JobOutcome.Skipped, 0L, 0L, 0L)
    else
      val t0        = nowMs()
      val text      = fetch(job)
      val t1        = nowMs()
      val embedding = embedder.embed(text, embeddingModelId)
      val t2        = nowMs()
      val record = VectorRecord(
        key = key,
        data = embedding.vector,
        metadata = VectorMetadata(
          documentType = job.documentType,
          entityId = job.entityId,
          windowStartYear = job.windowStartYear,
          windowEndYear = job.windowEndYear,
          topicId = None,
          institutionId = None,
          textS3Uri = job.textS3Uri,
          textHash = job.textHash,
          embeddingModel = embedding.modelId,
          runId = job.runId
        )
      )
      val result = writer.writeChanged(Vector(record), index).get
      val outcome =
        if result.rejections.nonEmpty then
          val location = writer
            .storeRejections(EmbeddingJobHandler.rejectionRunId(job), index, result.rejections)
            .get
          JobOutcome.Rejected(location.getOrElse(""))
        else if result.rejected > 0 then throw EmbeddingJobException("PUT_VECTORS_FAILED")
        else if result.skipped.contains(key) then JobOutcome.Skipped
        else JobOutcome.Written
      JobResult(outcome, t1 - t0, t2 - t1, nowMs() - t2)

  private def fetch(job: EmbeddingJob): String =
    val (bucket, objectKey) =
      S3TextStore.parse(job.textS3Uri).getOrElse(throw EmbeddingJobException("INVALID_TEXT_URI"))
    val bytes =
      try s3.getObject(bucket, objectKey)
      catch
        case scala.util.control.NonFatal(e) =>
          throw EmbeddingJobException(s"TEXT_FETCH_FAILED:${e.getClass.getSimpleName}")
    if S3StorageClient.sha256Hex(bytes) != job.textHash then
      throw EmbeddingJobException("TEXT_HASH_MISMATCH")
    val text =
      try
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      catch case _: CharacterCodingException => throw EmbeddingJobException("TEXT_NOT_UTF8")
    if text.trim.isEmpty then throw EmbeddingJobException("EMPTY_TEXT")
    if text.length > maxTextChars then throw EmbeddingJobException("TEXT_TOO_LARGE")
    text

object EmbeddingJobHandler:

  /** Titan Text Embeddings v2 accepts at most 50,000 characters per input. */
  val MaxTextChars: Int = 50000

  /**
   * Index of a document type. Spark `summaries` partitions use `researcher-profile`,
   * `community-summary` and `collaboration-path`; `community-summary` is the Spark name of the
   * `collaboration-community` document type.
   */
  def indexFor(documentType: String): Option[String] =
    documentType match
      case "researcher-profile"        => Some(S3VectorsIndexes.ResearcherProfile)
      case "collaboration-community"   => Some(S3VectorsIndexes.CollaborationCommunity)
      case "community-summary"         => Some(S3VectorsIndexes.CollaborationCommunity)
      case "paper-abstract"            => Some(S3VectorsIndexes.PaperAbstract)
      case "collaboration-path"        => Some(S3VectorsIndexes.CollaborationPath)
      case "topic-institution-summary" => Some(S3VectorsIndexes.TopicInstitutionSummary)
      case _                           => None

  /**
   * Run segment of the rejection object. `storeRejections` writes one object per (runId, index), so
   * the job ID is appended to keep one job's rejections from overwriting another's; a redelivered
   * job overwrites its own object with the same content.
   */
  def rejectionRunId(job: EmbeddingJob): String = s"${job.runId}_job-${job.jobId}"

/** Counts and latencies of one receive-and-process cycle. Never holds text or message bodies. */
final case class BatchStats(
  batch: Int,
  receiveFailed: Boolean,
  received: Int,
  acknowledged: Int,
  poison: Int,
  handlerFailed: Int,
  ackFailed: Int,
  written: Int,
  skipped: Int,
  rejected: Int,
  totalMs: Long,
  fetchMs: Long,
  embedMs: Long,
  writeMs: Long,
  failureCodes: Map[String, Int]
):

  def toJson: String =
    ujson.write(
      ujson.Obj(
        "event"         -> "embedding-batch",
        "batch"         -> batch,
        "receiveFailed" -> receiveFailed,
        "received"      -> received,
        "acknowledged"  -> acknowledged,
        "poison"        -> poison,
        "handlerFailed" -> handlerFailed,
        "ackFailed"     -> ackFailed,
        "written"       -> written,
        "skipped"       -> skipped,
        "rejected"      -> rejected,
        "totalMs"       -> totalMs.toDouble,
        "fetchMs"       -> fetchMs.toDouble,
        "embedMs"       -> embedMs.toDouble,
        "writeMs"       -> writeMs.toDouble,
        "failureCodes" -> ujson.Obj.from(failureCodes.toSeq.sorted.map((k, v) => k -> ujson.Num(v)))
      )
    )

/** Totals of a worker run. */
final case class RunSummary(batches: Int, stats: Vector[BatchStats]):
  def total(f: BatchStats => Int): Int = stats.map(f).sum

/**
 * The polling loop: `processBatch` until stopped, `maxBatches` receives were made or `maxSeconds`
 * passed. The stop flag is checked only between batches, so an in-flight batch always finishes
 * (graceful shutdown). A failed receive backs off exponentially (1 s doubling to 30 s).
 */
final class EmbeddingWorkerLoop(
  manager: EmbeddingQueueManager,
  handler: EmbeddingJobHandler,
  log: String => Unit,
  nowMs: () => Long = () => System.nanoTime() / 1000000L,
  sleep: Long => Unit = ms => Thread.sleep(ms)
):

  def runBatch(batch: Int, batchSize: Int): BatchStats =
    val start   = nowMs()
    val results = mutable.ArrayBuffer.empty[JobResult]
    val codes   = mutable.Map.empty[String, Int].withDefaultValue(0)
    val outcome = manager.processBatch(batchSize) { d =>
      Try(handler.handle(d.job)) match
        case Success(r) =>
          results += r
          Success(())
        case Failure(e) =>
          val code = e match
            case j: EmbeddingJobException => j.code
            case other                    => s"ERROR:${other.getClass.getSimpleName}"
          codes(code) += 1
          Failure(e)
    }
    val elapsed                            = nowMs() - start
    def outcomes(p: JobOutcome => Boolean) = results.count(r => p(r.outcome))
    outcome match
      case Failure(_) =>
        BatchStats(batch, true, 0, 0, 0, 0, 0, 0, 0, 0, elapsed, 0, 0, 0, Map.empty)
      case Success(msgs) =>
        def status(s: MessageStatus) = msgs.count(_.status == s)
        BatchStats(
          batch = batch,
          receiveFailed = false,
          received = msgs.size,
          acknowledged = status(MessageStatus.Acknowledged),
          poison = status(MessageStatus.Poison),
          handlerFailed = status(MessageStatus.HandlerFailed),
          ackFailed = status(MessageStatus.AckFailed),
          written = outcomes(_ == JobOutcome.Written),
          skipped = outcomes(_ == JobOutcome.Skipped),
          rejected = outcomes(_.isInstanceOf[JobOutcome.Rejected]),
          totalMs = elapsed,
          fetchMs = results.map(_.fetchMs).sum,
          embedMs = results.map(_.embedMs).sum,
          writeMs = results.map(_.writeMs).sum,
          failureCodes = codes.toMap ++ msgs
            .filter(m => m.status == MessageStatus.Poison || m.status == MessageStatus.AckFailed)
            .flatMap(_.reason)
            .groupMapReduce(identity)(_ => 1)(_ + _)
        )

  def run(options: WorkerOptions, stop: AtomicBoolean): RunSummary =
    val start   = nowMs()
    val stats   = Vector.newBuilder[BatchStats]
    var batches = 0
    var backoff = 0L
    def withinBounds =
      !stop.get() &&
        options.maxBatches.forall(batches < _) &&
        options.maxSeconds.forall(s => nowMs() - start < s * 1000L)
    while withinBounds do
      batches += 1
      val s = runBatch(batches, options.batchSize)
      stats += s
      log(s.toJson)
      if s.receiveFailed then
        backoff = if backoff == 0L then 1000L else math.min(backoff * 2, 30000L)
        if withinBounds then sleep(backoff)
      else backoff = 0L
    RunSummary(batches, stats.result())

/**
 * Embedding worker: polls `SCHOLARCHAIN_EMBEDDING_QUEUE_URL`, embeds each job with the configured
 * embedder and writes it to S3 Vectors. Runs until SIGTERM unless `--max-batches` or
 * `--max-seconds` bound it; on SIGTERM the in-flight batch finishes before exit.
 *
 * Exit codes: 0 done, 2 bad arguments or configuration.
 */
object EmbeddingWorker:

  private val log = LoggerFactory.getLogger("edu.scholarchain.services.EmbeddingWorker")

  /** How long the shutdown hook waits for the in-flight batch. */
  val ShutdownGraceSeconds: Long = 90L

  /** Most dedup keys kept in memory (the durable skip check is the S3 Vectors metadata). */
  val MaxDedupKeys: Int = 100000

  def boundedDedupSet(max: Int = MaxDedupKeys): mutable.Set[String] =
    java.util.Collections
      .synchronizedSet(
        java.util.Collections.newSetFromMap(
          new java.util.LinkedHashMap[String, java.lang.Boolean](16, 0.75f, false):
            override def removeEldestEntry(
              e: java.util.Map.Entry[String, java.lang.Boolean]
            ): Boolean = size() > max
        )
      )
      .asScala

  /** Configuration problems that must stop the worker before it polls. */
  def configErrors(app: AppConfig, storage: StorageModuleConfig): Vector[String] =
    Vector(
      Option.when(storage.embeddingQueueUrl.isEmpty)(
        s"${StorageModuleConfig.EmbeddingQueueUrlEnv} is not set"
      ),
      Option.when(!app.s3Vectors.isCompatibleModel(app.embedding.modelId))(
        s"embedding.model-id '${app.embedding.modelId}' does not match the index model " +
          s"'${app.s3Vectors.embeddingModelId}'; a fallback model needs its own re-embedded index set"
      )
    ).flatten

  def main(args: Array[String]): Unit =
    val options = WorkerOptions.parse(args.toSeq) match
      case Right(o) => o
      case Left(err) =>
        System.err.println(err)
        sys.exit(2)
    val app     = AppConfig.load()
    val storage = StorageModuleConfig.load()
    val errors  = configErrors(app, storage)
    if errors.nonEmpty then
      errors.foreach(e => System.err.println(s"EmbeddingWorker not started: $e"))
      sys.exit(2)

    val s3        = S3StorageClient.create(AwsStorageClients.s3Region(storage, sys.env))
    val s3Vectors = S3VectorsStorageClient.create(storage.s3Vectors)
    val writer = EmbeddingBatchWriter(
      s3Vectors,
      s3,
      storage.s3Vectors.vectorBucket,
      storage.s3.ragBucket,
      app.s3Vectors,
      storage.s3Vectors.maxRetries,
      storage.s3Vectors.retryBaseDelayMs
    )
    val sqs = SqsQueueClient.create(storage.embeddingQueue.region, storage.embeddingQueue.settings)
    val manager = EmbeddingQueueManager(sqs, storage.embeddingQueueUrl.get, boundedDedupSet())
    val handler = EmbeddingJobHandler(
      s3,
      RuntimeComposer.embedder(app, SecretResolvers.default()),
      app.embedding.modelId,
      writer,
      app.s3Vectors
    )
    val loop = EmbeddingWorkerLoop(manager, handler, line => log.info(line))

    val stop     = AtomicBoolean(false)
    val finished = CountDownLatch(1)
    Runtime.getRuntime.addShutdownHook(Thread { () =>
      stop.set(true)
      finished.await(ShutdownGraceSeconds, TimeUnit.SECONDS): Unit
    })
    log.info(
      ujson.write(
        ujson.Obj(
          "event"      -> "embedding-worker-start",
          "maxBatches" -> options.maxBatches.fold[ujson.Value](ujson.Null)(ujson.Num(_)),
          "maxSeconds" -> options.maxSeconds.fold[ujson.Value](ujson.Null)(n =>
            ujson.Num(n.toDouble)
          ),
          "batchSize" -> options.batchSize,
          "model"     -> app.embedding.modelId
        )
      )
    )
    try
      val summary = loop.run(options, stop)
      log.info(
        ujson.write(
          ujson.Obj(
            "event"           -> "embedding-worker-stop",
            "batches"         -> summary.batches,
            "acknowledged"    -> summary.total(_.acknowledged),
            "written"         -> summary.total(_.written),
            "skipped"         -> summary.total(_.skipped),
            "rejected"        -> summary.total(_.rejected),
            "failed"          -> summary.total(s => s.handlerFailed + s.poison + s.ackFailed),
            "stoppedBySignal" -> stop.get()
          )
        )
      )
    finally
      finished.countDown()
      sqs.close()
