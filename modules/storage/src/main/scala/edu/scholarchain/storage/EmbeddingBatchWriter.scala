package edu.scholarchain.storage

import scala.util.{Failure, Success, Try}

/** Result of a batch write operation. */
case class BatchWriteResult(
  written: Int,
  rejected: Int,
  retried: Int,
  errors: List[String]
)

/** Writes embedding vectors to S3 Vectors with retry and rejection handling. */
class EmbeddingBatchWriter(
  s3Vectors: S3VectorsStorageClient,
  s3: S3StorageClient,
  vectorBucket: String,
  ragBucket: String,
  maxRetries: Int = 3,
  retryBaseDelayMs: Long = 200
):

  /** Mapping from document type to S3 Vectors index name. */
  val IndexNames: Map[String, String] = Map(
    "researcher-profile" -> S3VectorsIndexes.ResearcherProfile,
    "collaboration-community" -> S3VectorsIndexes.CollaborationCommunity,
    "paper-abstract" -> S3VectorsIndexes.PaperAbstract,
    "collaboration-path" -> S3VectorsIndexes.CollaborationPath,
    "topic-institution-summary" -> S3VectorsIndexes.TopicInstitutionSummary
  )

  /** Write a batch of vector records to the specified index. */
  def writeBatch(
    records: Vector[VectorRecord],
    indexName: String
  ): Try[BatchWriteResult] = Try {
    val (valid, rejected) = records.partition(_.data.nonEmpty)
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
      errors = result.errors.toList ++ rejected.map(r =>
        s"Empty embedding: key=${r.key}"
      )
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
    var attempt = 0
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
