package edu.scholarchain.storage

import edu.scholarchain.core.S3VectorsConfig

import org.slf4j.LoggerFactory
import upickle.default.*

import scala.util.{Failure, Success, Try}

/** Reason codes attached to vectors rejected before they reach S3 Vectors. */
object VectorRejectionReason:
  val EmptyEmbedding: String             = "EMPTY_EMBEDDING"
  val EmbeddingDimensionMismatch: String = "EMBEDDING_DIMENSION_MISMATCH"
  val EmbeddingModelMismatch: String     = "EMBEDDING_MODEL_MISMATCH"
  val InvalidVectorKey: String           = "INVALID_VECTOR_KEY"
  val TooManyMetadataKeys: String        = "TOO_MANY_METADATA_KEYS"
  val FilterableMetadataTooLarge: String = "FILTERABLE_METADATA_TOO_LARGE"
  val MetadataTooLarge: String           = "METADATA_TOO_LARGE"

/**
 * A vector record that failed pre-write validation and was not sent to S3 Vectors.
 *
 * @param key
 *   vector key of the rejected record
 * @param reasonCode
 *   one of [[VectorRejectionReason]]
 * @param detail
 *   human-readable explanation (expected vs actual)
 * @param metadata
 *   original metadata, kept for provenance (entityId, textHash, embeddingModel, runId)
 */
case class RejectedVector(
  key: String,
  reasonCode: String,
  detail: String,
  metadata: VectorMetadata
) derives ReadWriter

/**
 * Result of a batch write operation.
 *
 * `rejected` counts both pre-write validation rejections and records that `putVectors` reported as
 * failed. `rejections` and `rejectedByReason` cover the validation rejections only.
 */
case class BatchWriteResult(
  written: Int,
  rejected: Int,
  retried: Int,
  errors: List[String],
  rejections: Vector[RejectedVector] = Vector.empty,
  rejectedByReason: Map[String, Int] = Map.empty,
  skipped: Vector[String] = Vector.empty
)

/**
 * Writes embedding vectors to S3 Vectors with retry and rejection handling.
 *
 * Every record is validated against `indexConfig` before `putVectors`: the vector length must equal
 * `indexConfig.dimensions` and `metadata.embeddingModel` must exactly equal
 * `indexConfig.embeddingModelId`. Records that fail are never written; they are returned in
 * [[BatchWriteResult.rejections]] with a reason code and counted in `rejected`.
 */
class EmbeddingBatchWriter(
  s3Vectors: S3VectorsStorageClient,
  s3: S3StorageClient,
  vectorBucket: String,
  ragBucket: String,
  indexConfig: S3VectorsConfig,
  maxRetries: Int = 3,
  retryBaseDelayMs: Long = 200,
  nonFilterableMetadataKeys: Set[String] = S3VectorsLimits.DefaultNonFilterableKeys
):
  private val log = LoggerFactory.getLogger(classOf[EmbeddingBatchWriter])

  /** Mapping from document type to S3 Vectors index name. */
  val IndexNames: Map[String, String] = Map(
    "researcher-profile"        -> S3VectorsIndexes.ResearcherProfile,
    "collaboration-community"   -> S3VectorsIndexes.CollaborationCommunity,
    "paper-abstract"            -> S3VectorsIndexes.PaperAbstract,
    "collaboration-path"        -> S3VectorsIndexes.CollaborationPath,
    "topic-institution-summary" -> S3VectorsIndexes.TopicInstitutionSummary
  )

  /** Write a batch of vector records to the specified index. */
  def writeBatch(
    records: Vector[VectorRecord],
    indexName: String
  ): Try[BatchWriteResult] = Try {
    val checked =
      records.map(r => EmbeddingBatchWriter.validate(r, indexConfig, nonFilterableMetadataKeys))
    val valid    = checked.collect { case Right(r) => r }
    val rejected = checked.collect { case Left(r) => r }

    val result =
      if valid.nonEmpty then
        retryWithBackoff(maxRetries) {
          s3Vectors.putVectors(vectorBucket, indexName, valid)
        }.get
      else PutVectorsResult(0, 0, Vector.empty)

    BatchWriteResult(
      written = result.succeeded,
      rejected = rejected.size + result.failed,
      retried = 0,
      errors = result.errors.toList ++ rejected.map(_.detail),
      rejections = rejected,
      rejectedByReason = rejected.groupMapReduce(_.reasonCode)(_ => 1)(_ + _)
    )
  }

  /**
   * Keys whose stored vector already has the same `textHash` and `embeddingModel` (read with
   * GetVectors, metadata only). The worker calls this before embedding to skip unchanged text. A
   * GetVectors failure returns an empty set (fail open: everything is re-embedded and overwritten,
   * which is idempotent because keys are stable).
   *
   * @param candidates
   *   (vector key, textHash, embeddingModel)
   */
  def unchangedKeys(
    indexName: String,
    candidates: Vector[(String, String, String)]
  ): Set[String] =
    if candidates.isEmpty then Set.empty
    else
      Try(s3Vectors.getVectors(vectorBucket, indexName, candidates.map(_._1).distinct)) match
        case Success(existing) =>
          val stored = existing.map(r => r.key -> r.metadata).toMap
          candidates.collect {
            case (key, hash, model)
                if stored.get(key).exists(m => m.textHash == hash && m.embeddingModel == model) =>
              key
          }.toSet
        case Failure(e) =>
          log.warn(
            s"GetVectors for skip check failed index=$indexName; writing all " +
              s"error=${e.getClass.getSimpleName}"
          )
          Set.empty

  /**
   * Like [[writeBatch]], but first skips records whose stored vector has the same `textHash` and
   * `embeddingModel` (see [[unchangedKeys]]). Skipped keys are returned in
   * [[BatchWriteResult.skipped]] and are neither written nor rejected.
   */
  def writeChanged(
    records: Vector[VectorRecord],
    indexName: String
  ): Try[BatchWriteResult] =
    Try {
      unchangedKeys(
        indexName,
        records.map(r => (r.key, r.metadata.textHash, r.metadata.embeddingModel))
      )
    }.flatMap { unchanged =>
      val (skip, write) = records.partition(r => unchanged.contains(r.key))
      writeBatch(write, indexName).map(_.copy(skipped = skip.map(_.key)))
    }

  /**
   * Persist rejected vectors as JSON Lines (one [[RejectedVector]] per line: key, reason code,
   * detail and provenance metadata; never the embedding or source text) at
   * `s3://<ragBucket>/rejected-vectors/<runId>/<indexName>.jsonl`. Returns `None` when there is
   * nothing to record.
   */
  def storeRejections(
    runId: String,
    indexName: String,
    rejections: Vector[RejectedVector]
  ): Try[Option[String]] = Try {
    if rejections.isEmpty then None
    else
      val key  = EmbeddingBatchWriter.rejectionKey(runId, indexName)
      val body = EmbeddingBatchWriter.rejectionJsonl(rejections)
      s3.putObject(ragBucket, key, body.getBytes("UTF-8"), "application/x-ndjson")
      Some(s"s3://$ragBucket/$key")
  }

  /** Store context text in S3 and return the S3 URI. */
  def storeTextInS3(
    entityId: String,
    docType: String,
    text: String
  ): Try[String] = Try {
    val key = s"contexts/$docType/$entityId.json"
    val uri = s"s3://$ragBucket/$key"
    s3.putObject(ragBucket, key, text.getBytes("UTF-8"), "application/json")
    uri
  }

  private def retryWithBackoff[A](retries: Int)(f: => A): Try[A] =
    var attempt        = 0
    var result: Try[A] = Failure(new RuntimeException("not started"))
    while attempt <= retries do
      result = Try(f)
      result match
        case Success(_) => return result
        case Failure(_) if attempt < retries =>
          Thread.sleep(retryBaseDelayMs * (1L << attempt))
          attempt += 1
        case _ =>
          attempt = retries + 1
    result

object EmbeddingBatchWriter:

  /** `rejected-vectors/<runId>/<indexName>.jsonl`; path segments are sanitised. */
  def rejectionKey(runId: String, indexName: String): String =
    def seg(v: String) = v.replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "_")
    s"rejected-vectors/${seg(runId)}/${seg(indexName)}.jsonl"

  /** One compact JSON object per line. */
  def rejectionJsonl(rejections: Vector[RejectedVector]): String =
    rejections.map(r => write(r)).mkString("", "\n", "\n")

  /** Metadata-size and key checks against [[S3VectorsLimits]]; `Left` is (reason, detail). */
  def checkLimits(
    record: VectorRecord,
    nonFilterable: Set[String]
  ): Either[(String, String), Unit] =
    val meta = S3VectorsMetadataCodec.toJson(record.metadata)
    val key  = record.key
    if key == null || key.isEmpty || key.length > S3VectorsLimits.MaxKeyLength then
      Left(
        VectorRejectionReason.InvalidVectorKey ->
          (s"Invalid vector key: length=${Option(key).fold(0)(_.length)} " +
            s"max=${S3VectorsLimits.MaxKeyLength}")
      )
    else if meta.value.size > S3VectorsLimits.MaxMetadataKeys then
      Left(
        VectorRejectionReason.TooManyMetadataKeys ->
          (s"Too many metadata keys: key=$key count=${meta.value.size} " +
            s"max=${S3VectorsLimits.MaxMetadataKeys}")
      )
    else
      val filterable = S3VectorsLimits.filterableBytes(meta, nonFilterable)
      val total      = S3VectorsLimits.totalBytes(meta)
      if filterable > S3VectorsLimits.MaxFilterableMetadataBytes then
        Left(
          VectorRejectionReason.FilterableMetadataTooLarge ->
            (s"Filterable metadata too large: key=$key bytes=$filterable " +
              s"max=${S3VectorsLimits.MaxFilterableMetadataBytes}")
        )
      else if total > S3VectorsLimits.MaxTotalMetadataBytes then
        Left(
          VectorRejectionReason.MetadataTooLarge ->
            s"Metadata too large: key=$key bytes=$total max=${S3VectorsLimits.MaxTotalMetadataBytes}"
        )
      else Right(())

  /**
   * Validate one record against the index contract. Checks run in order: empty vector, dimension,
   * model, then key and metadata limits ([[S3VectorsLimits]]); the first failure sets the reason
   * code.
   */
  def validate(
    record: VectorRecord,
    indexConfig: S3VectorsConfig,
    nonFilterable: Set[String] = S3VectorsLimits.DefaultNonFilterableKeys
  ): Either[RejectedVector, VectorRecord] =
    val model = record.metadata.embeddingModel
    if record.data.isEmpty then
      Left(
        RejectedVector(
          record.key,
          VectorRejectionReason.EmptyEmbedding,
          s"Empty embedding: key=${record.key} reason=${VectorRejectionReason.EmptyEmbedding}",
          record.metadata
        )
      )
    else if !indexConfig.isCompatibleVector(record.data.size) then
      Left(
        RejectedVector(
          record.key,
          VectorRejectionReason.EmbeddingDimensionMismatch,
          s"Embedding dimension mismatch: key=${record.key} " +
            s"expected=${indexConfig.dimensions} actual=${record.data.size} " +
            s"reason=${VectorRejectionReason.EmbeddingDimensionMismatch}",
          record.metadata
        )
      )
    else if !indexConfig.isCompatibleModel(model) then
      Left(
        RejectedVector(
          record.key,
          VectorRejectionReason.EmbeddingModelMismatch,
          s"Embedding model mismatch: key=${record.key} " +
            s"expected='${indexConfig.embeddingModelId}' actual='$model' " +
            s"reason=${VectorRejectionReason.EmbeddingModelMismatch}",
          record.metadata
        )
      )
    else
      checkLimits(record, nonFilterable) match
        case Left((reason, detail)) =>
          Left(RejectedVector(record.key, reason, s"$detail reason=$reason", record.metadata))
        case Right(_) => Right(record)
