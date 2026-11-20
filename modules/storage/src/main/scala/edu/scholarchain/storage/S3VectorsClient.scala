package edu.scholarchain.storage

import upickle.default.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

// ---------------------------------------------------------------------------
// S3 Vectors data types
// ---------------------------------------------------------------------------

/** Metadata attached to each vector in S3 Vectors. */
case class VectorMetadata(
  documentType: String,
  entityId: String,
  windowStartYear: Option[Int],
  windowEndYear: Option[Int],
  topicId: Option[String],
  institutionId: Option[String],
  textS3Uri: String,
  textHash: String,
  embeddingModel: String,
  runId: String
) derives ReadWriter

/** A vector record for ingestion into S3 Vectors. */
case class VectorRecord(
  key: String,
  data: Vector[Float],
  metadata: VectorMetadata
) derives ReadWriter

/** A vector search result from S3 Vectors. */
case class VectorResult(
  key: String,
  score: Double,
  metadata: VectorMetadata
) derives ReadWriter

/** Result of a batch put operation. */
case class PutVectorsResult(
  succeeded: Int,
  failed: Int,
  errors: Vector[String]
)

// ---------------------------------------------------------------------------
// S3 Vectors index definitions
// ---------------------------------------------------------------------------

/** Known S3 Vectors index names for ScholarChain. */
object S3VectorsIndexes:

  val ResearcherProfile: String       = "researcher-profile-v1"
  val CollaborationCommunity: String  = "collaboration-community-v1"
  val PaperAbstract: String           = "paper-abstract-v1"
  val CollaborationPath: String       = "collaboration-path-v1"
  val TopicInstitutionSummary: String = "topic-institution-summary-v1"

  val all: Vector[String] = Vector(
    ResearcherProfile,
    CollaborationCommunity,
    PaperAbstract,
    CollaborationPath,
    TopicInstitutionSummary
  )

// ---------------------------------------------------------------------------
// S3 Vectors REST client
// ---------------------------------------------------------------------------

/** Adapter for S3 Vectors REST API using java.net.http.HttpClient. */
class S3VectorsStorageClient(
  region: String,
  maxRetries: Int,
  retryBaseDelayMs: Long,
  httpClient: HttpClient
):

  private def vectorsEndpoint(vectorBucket: String): String =
    s"https://$vectorBucket.s3vectors.$region.amazonaws.com"

  /** Batch write vectors with retry and exponential backoff. */
  def putVectors(
    vectorBucket: String,
    indexName: String,
    vectors: Vector[VectorRecord]
  ): PutVectorsResult =
    var succeeded = 0
    var failed    = 0
    var errors    = Vector.empty[String]

    vectors.foreach { record =>
      var attempt = 0
      var done    = false
      while attempt <= maxRetries && !done do
        try
          val body = ujson.Obj(
            "key"      -> ujson.Str(record.key),
            "data"     -> ujson.Arr(record.data.map(f => ujson.Num(f.toDouble))*),
            "metadata" -> writeJs(record.metadata)
          )
          val request = HttpRequest
            .newBuilder()
            .uri(
              URI.create(
                s"${vectorsEndpoint(vectorBucket)}/vectors/$indexName"
              )
            )
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
            .build()
          val response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
          if response.statusCode() >= 200 && response.statusCode() < 300 then
            succeeded += 1
            done = true
          else if attempt >= maxRetries then
            failed += 1
            errors = errors :+ s"key=${record.key} status=${response.statusCode()}"
            done = true
          else
            Thread.sleep(retryBaseDelayMs * (1L << attempt))
            attempt += 1
        catch
          case e: Exception =>
            if attempt >= maxRetries then
              failed += 1
              errors = errors :+ s"key=${record.key} error=${e.getMessage}"
              done = true
            else
              Thread.sleep(retryBaseDelayMs * (1L << attempt))
              attempt += 1
    }

    PutVectorsResult(succeeded, failed, errors)

  /** Query vectors by similarity. */
  def queryVectors(
    vectorBucket: String,
    indexName: String,
    queryVector: Vector[Float],
    topK: Int,
    metadataFilters: Option[ujson.Value]
  ): Vector[VectorResult] =
    val body = ujson.Obj(
      "queryVector" -> ujson.Arr(queryVector.map(f => ujson.Num(f.toDouble))*),
      "topK"        -> ujson.Num(topK.toDouble)
    )
    metadataFilters.foreach(f => body("metadataFilters") = f)

    val request = HttpRequest
      .newBuilder()
      .uri(
        URI.create(
          s"${vectorsEndpoint(vectorBucket)}/vectors/$indexName/query"
        )
      )
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
      .build()
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    val json     = ujson.read(response.body())
    json.obj
      .get("results")
      .map(
        _.arr
          .map { r =>
            VectorResult(
              key = r("key").str,
              score = r("score").num,
              metadata = read[VectorMetadata](r("metadata"))
            )
          }
          .toVector
      )
      .getOrElse(Vector.empty)

  /** Delete vectors by keys. */
  def deleteVectors(
    vectorBucket: String,
    indexName: String,
    keys: Vector[String]
  ): Unit =
    val body = ujson.Obj(
      "keys" -> ujson.Arr(keys.map(ujson.Str(_))*)
    )
    val request = HttpRequest
      .newBuilder()
      .uri(
        URI.create(
          s"${vectorsEndpoint(vectorBucket)}/vectors/$indexName/delete"
        )
      )
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
      .build()
    httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    ()

object S3VectorsStorageClient:

  /** Create a client with standard configuration. */
  def create(config: S3VectorsStorageConfig): S3VectorsStorageClient =
    val httpClient = HttpClient.newBuilder().build()
    S3VectorsStorageClient(
      config.region,
      config.maxRetries,
      config.retryBaseDelayMs,
      httpClient
    )
