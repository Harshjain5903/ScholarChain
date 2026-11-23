package edu.scholarchain.storage

import edu.scholarchain.core.S3VectorsConfig

import scala.util.{Failure, Success, Try}

/** Reason codes attached to vectors rejected before they reach S3 Vectors. */
object VectorRejectionReason:
  val EmptyEmbedding: String             = "EMPTY_EMBEDDING"
  val EmbeddingDimensionMismatch: String = "EMBEDDING_DIMENSION_MISMATCH"
  val EmbeddingModelMismatch: String     = "EMBEDDING_MODEL_MISMATCH"

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
)

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
  rejectedByReason: Map[String, Int] = Map.empty
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
  retryBaseDelayMs: Long = 200
):

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
    val checked  = records.map(r => EmbeddingBatchWriter.validate(r, indexConfig))
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

  /**
   * Validate one record against the index contract. Checks run in order: empty vector, dimension,
   * model; the first failure determines the reason code.
   */
  def validate(
    record: VectorRecord,
    indexConfig: S3VectorsConfig
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
    else Right(record)
