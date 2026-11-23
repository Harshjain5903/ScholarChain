package edu.scholarchain.storage

import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.{AwsCredentialsProvider, DefaultCredentialsProvider}
import upickle.default.*

import java.io.IOException
import java.net.URI
import java.time.{Clock, Duration}

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

/**
 * A vector search result from S3 Vectors.
 *
 * @param score
 *   similarity, higher is better: `1 - distance` for cosine, `1 / (1 + distance)` for euclidean
 * @param distance
 *   raw distance returned by QueryVectors (`returnDistance = true`)
 */
case class VectorResult(
  key: String,
  score: Double,
  metadata: VectorMetadata,
  distance: Option[Double] = None
) derives ReadWriter

/** Result of a batch put operation. */
case class PutVectorsResult(
  succeeded: Int,
  failed: Int,
  errors: Vector[String]
)

/** A non-retryable or retry-exhausted S3 Vectors failure. The message is credential-free. */
final class S3VectorsRequestException(
  val operation: String,
  val status: Int,
  message: String
) extends RuntimeException(message)

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
// Metadata encoding (S3 Vectors metadata values: string, number, boolean, list)
// ---------------------------------------------------------------------------

/** Encodes [[VectorMetadata]] as a flat S3 Vectors metadata object; `None` fields are omitted. */
object S3VectorsMetadataCodec:

  def toJson(m: VectorMetadata): ujson.Obj =
    val obj = ujson.Obj(
      "documentType"   -> m.documentType,
      "entityId"       -> m.entityId,
      "textS3Uri"      -> m.textS3Uri,
      "textHash"       -> m.textHash,
      "embeddingModel" -> m.embeddingModel,
      "runId"          -> m.runId
    )
    m.windowStartYear.foreach(y => obj("windowStartYear") = ujson.Num(y.toDouble))
    m.windowEndYear.foreach(y => obj("windowEndYear") = ujson.Num(y.toDouble))
    m.topicId.foreach(t => obj("topicId") = t)
    m.institutionId.foreach(i => obj("institutionId") = i)
    obj

  /** Decode metadata written by [[toJson]]; a missing required field raises an exception. */
  def fromJson(v: ujson.Value): VectorMetadata =
    val o = v.obj
    def req(k: String): String =
      o.get(k) match
        case Some(ujson.Str(s)) => s
        case _                  => throw IllegalArgumentException(s"vector metadata missing '$k'")
    def optStr(k: String): Option[String] = o.get(k).collect { case ujson.Str(s) => s }
    def optInt(k: String): Option[Int] =
      o.get(k).collect {
        case ujson.Num(n)                            => n.toInt
        case ujson.Str(s) if s.toIntOption.isDefined => s.toInt
      }
    VectorMetadata(
      documentType = req("documentType"),
      entityId = req("entityId"),
      windowStartYear = optInt("windowStartYear"),
      windowEndYear = optInt("windowEndYear"),
      topicId = optStr("topicId"),
      institutionId = optStr("institutionId"),
      textS3Uri = req("textS3Uri"),
      textHash = req("textHash"),
      embeddingModel = req("embeddingModel"),
      runId = req("runId")
    )

// ---------------------------------------------------------------------------
// S3 Vectors REST client
// ---------------------------------------------------------------------------

/**
 * Adapter for the Amazon S3 Vectors data-plane REST API.
 *
 * Every call is a SigV4-signed (signing name `s3vectors`) `POST
 * https://s3vectors.<region>.api.aws/<Operation>` with a JSON body (`PutVectors`, `QueryVectors`,
 * `GetVectors`). Service limits enforced here: at most 500 vectors per PutVectors (batched), at
 * most 100 keys per GetVectors (batched), and `topK` in 1..100 (validated before sending).
 * QueryVectors is a single request: the API has no `nextToken`, so a larger `topK` is rejected
 * rather than paged. Limits are taken from the AWS S3 Vectors documentation and were not verified
 * against the live service.
 *
 * @param transport
 *   sends the signed request; tests inject a fake
 * @param credentialsProvider
 *   resolved per request, never logged
 * @param endpointOverride
 *   replaces `https://s3vectors.<region>.api.aws` (local fake servers, VPC endpoints)
 * @param batchSize
 *   vectors per PutVectors call, clamped to 1..500
 */
class S3VectorsStorageClient(
  region: String,
  maxRetries: Int,
  retryBaseDelayMs: Long,
  transport: HttpTransport,
  credentialsProvider: AwsCredentialsProvider,
  endpointOverride: Option[URI] = None,
  batchSize: Int = S3VectorsStorageClient.MaxVectorsPerPut,
  clock: Clock = Clock.systemUTC(),
  requestTimeout: Duration = Duration.ofSeconds(30)
):
  import S3VectorsStorageClient.*

  private val log    = LoggerFactory.getLogger(classOf[S3VectorsStorageClient])
  private val signer = SigV4Signer(credentialsProvider, region, SigningName, clock)

  /** Base endpoint, e.g. `https://s3vectors.us-east-2.api.aws`. */
  val endpoint: URI = endpointOverride.getOrElse(defaultEndpoint(region))

  /** Effective vectors per PutVectors request. */
  val putBatchSize: Int = math.max(1, math.min(batchSize, MaxVectorsPerPut))

  /**
   * Write vectors in batches of [[putBatchSize]]. Each batch is retried with exponential backoff on
   * throttling, 5xx, 408 and I/O errors; any other failure fails the whole batch without retry.
   */
  def putVectors(
    vectorBucket: String,
    indexName: String,
    vectors: Vector[VectorRecord]
  ): PutVectorsResult =
    vectors.grouped(putBatchSize).zipWithIndex.foldLeft(PutVectorsResult(0, 0, Vector.empty)) {
      case (acc, (batch, i)) =>
        val body = ujson.Obj(
          "vectorBucketName" -> vectorBucket,
          "indexName"        -> indexName,
          "vectors" -> ujson.Arr.from(batch.map { r =>
            ujson.Obj(
              "key"      -> r.key,
              "data"     -> float32(r.data),
              "metadata" -> S3VectorsMetadataCodec.toJson(r.metadata)
            )
          })
        )
        send("PutVectors", body) match
          case Right(_) => acc.copy(succeeded = acc.succeeded + batch.size)
          case Left(reason) =>
            val msg =
              s"PutVectors batch=$i size=${batch.size} keys=${batch.head.key}..${batch.last.key} $reason"
            log.warn(msg)
            acc.copy(failed = acc.failed + batch.size, errors = acc.errors :+ msg)
    }

  /**
   * Similarity query: one QueryVectors request returning up to `topK` nearest vectors with
   * distances and metadata; distance is converted to a higher-is-better `score` (see
   * [[VectorResult]]).
   *
   * QueryVectors has no pagination (`nextToken`), and `topK` must be 1..[[MaxTopK]]. A `topK`
   * outside that range raises IllegalArgumentException without calling the service; callers that
   * need more candidates must narrow the query with filters instead.
   *
   * @param metadataFilters
   *   sent as the S3 Vectors `filter` document
   */
  def queryVectors(
    vectorBucket: String,
    indexName: String,
    queryVector: Vector[Float],
    topK: Int,
    metadataFilters: Option[ujson.Value]
  ): Vector[VectorResult] =
    require(
      topK >= 1 && topK <= MaxTopK,
      s"topK must be between 1 and $MaxTopK (S3 Vectors QueryVectors limit; no paging), got $topK"
    )
    require(queryVector.nonEmpty, "queryVector must be non-empty")
    val body = ujson.Obj(
      "vectorBucketName" -> vectorBucket,
      "indexName"        -> indexName,
      "queryVector"      -> float32(queryVector),
      "topK"             -> ujson.Num(topK.toDouble),
      "returnMetadata"   -> true,
      "returnDistance"   -> true
    )
    metadataFilters.foreach(f => body("filter") = f)
    val json   = ujson.read(sendOrThrow("QueryVectors", body).body)
    val metric = json.obj.get("distanceMetric").collect { case ujson.Str(s) => s }
    json.obj
      .get("vectors")
      .map(_.arr.toVector.map(v => parseQueryResult(v, metric)))
      .getOrElse(Vector.empty)
      .take(topK)

  /** Fetch vectors by key in batches of 100, with metadata and optionally the float32 data. */
  def getVectors(
    vectorBucket: String,
    indexName: String,
    keys: Vector[String],
    returnData: Boolean = false
  ): Vector[VectorRecord] =
    keys.grouped(MaxKeysPerGet).toVector.flatMap { batch =>
      val body = ujson.Obj(
        "vectorBucketName" -> vectorBucket,
        "indexName"        -> indexName,
        "keys"             -> ujson.Arr.from(batch.map(ujson.Str(_))),
        "returnData"       -> returnData,
        "returnMetadata"   -> true
      )
      val json = ujson.read(sendOrThrow("GetVectors", body).body)
      json.obj
        .get("vectors")
        .map(_.arr.toVector.map { v =>
          val data = v.obj
            .get("data")
            .flatMap(_.obj.get("float32"))
            .map(_.arr.toVector.map(_.num.toFloat))
            .getOrElse(Vector.empty)
          VectorRecord(v("key").str, data, S3VectorsMetadataCodec.fromJson(v("metadata")))
        })
        .getOrElse(Vector.empty)
    }

  override def toString: String = s"S3VectorsStorageClient(endpoint=$endpoint, region=$region)"

  // ---- internals ----

  private def float32(data: Vector[Float]): ujson.Obj =
    ujson.Obj("float32" -> ujson.Arr.from(data.map(f => ujson.Num(f.toDouble))))

  private def parseQueryResult(v: ujson.Value, metric: Option[String]): VectorResult =
    val distance = v.obj.get("distance").collect { case ujson.Num(d) => d }
    VectorResult(
      key = v("key").str,
      score = distance.map(d => similarity(d, metric)).getOrElse(0.0),
      metadata = S3VectorsMetadataCodec.fromJson(v("metadata")),
      distance = distance
    )

  private def operationUri(operation: String): URI =
    URI(endpoint.getScheme, null, endpoint.getHost, endpoint.getPort, s"/$operation", null, null)

  /** Sign and send with retry. Left carries a credential-free failure description. */
  private def send(operation: String, body: ujson.Value): Either[String, HttpReply] =
    val call = HttpCall(
      "POST",
      operationUri(operation),
      Vector("Content-Type" -> "application/json"),
      ujson.write(body).getBytes("UTF-8")
    )

    @annotation.tailrec
    def attempt(n: Int): Either[String, HttpReply] =
      val outcome: Either[(Boolean, String), HttpReply] =
        try
          val reply = transport.execute(signer.sign(call), Some(requestTimeout))
          if reply.isSuccess then Right(reply)
          else Left((isRetryable(reply.status), describe(reply)))
        catch
          case e: IOException =>
            Left((true, s"error=${e.getClass.getSimpleName}: ${e.getMessage}"))
          case e: Exception =>
            Left((false, s"error=${e.getClass.getSimpleName}: ${e.getMessage}"))
      outcome match
        case Right(reply) => Right(reply)
        case Left((retryable, reason)) if retryable && n < maxRetries =>
          Thread.sleep(retryBaseDelayMs * (1L << n))
          attempt(n + 1)
        case Left((_, reason)) =>
          Left(signer.redact(s"attempts=${n + 1} $reason"))

    attempt(0)

  private def sendOrThrow(operation: String, body: ujson.Value): HttpReply =
    send(operation, body) match
      case Right(reply) => reply
      case Left(reason) =>
        val msg = s"$operation failed: $reason"
        log.warn(msg)
        val status = "status=(\\d+)".r.findFirstMatchIn(reason).map(_.group(1).toInt).getOrElse(-1)
        throw S3VectorsRequestException(operation, status, msg)

object S3VectorsStorageClient:

  /** SigV4 signing name for the S3 Vectors data plane. */
  val SigningName: String = "s3vectors"

  /** Vectors per PutVectors call (AWS S3 Vectors limit). */
  val MaxVectorsPerPut: Int = 500

  /** Keys per GetVectors call (AWS S3 Vectors limit). */
  val MaxKeysPerGet: Int = 100

  /**
   * Maximum `topK` for QueryVectors. Per the AWS S3 Vectors documentation the limit is 100 (30
   * during preview) and QueryVectors has no pagination. Taken from documentation only; not verified
   * against the live service.
   */
  val MaxTopK: Int = 100

  /** Regional data-plane endpoint, `https://s3vectors.<region>.api.aws`. */
  def defaultEndpoint(region: String): URI =
    require(SigV4Signer.isValidRegion(region), s"invalid AWS region '$region'")
    URI.create(s"https://s3vectors.$region.api.aws")

  /** Throttling, request timeout and server errors are retried. */
  def isRetryable(status: Int): Boolean =
    status == 408 || status == 429 || status >= 500

  /** Convert a distance to a higher-is-better similarity for the index distance metric. */
  def similarity(distance: Double, metric: Option[String]): Double =
    metric.map(_.toLowerCase) match
      case Some("euclidean") => 1.0 / (1.0 + distance)
      case _                 => 1.0 - distance

  /** `status=<code> type=<AWS error type> message=<truncated>` for a failed reply. */
  private[storage] def describe(reply: HttpReply): String =
    val parsed = scala.util.Try(ujson.read(reply.body)).toOption
    val errorType = reply.headers
      .get("x-amzn-errortype")
      .orElse(parsed.flatMap(_.obj.get("__type").collect { case ujson.Str(s) => s }))
      .map(_.takeWhile(_ != ':'))
      .getOrElse("unknown")
    val message = parsed
      .flatMap(p => p.obj.get("message").orElse(p.obj.get("Message")))
      .collect { case ujson.Str(s) => s }
      .getOrElse("")
      .take(300)
    s"status=${reply.status} type=$errorType message=$message"

  /** Production client: SigV4 with the default credential chain unless a provider is injected. */
  def create(
    config: S3VectorsStorageConfig,
    credentialsProvider: AwsCredentialsProvider = DefaultCredentialsProvider.create()
  ): S3VectorsStorageClient =
    S3VectorsStorageClient(
      region = config.region,
      maxRetries = config.maxRetries,
      retryBaseDelayMs = config.retryBaseDelayMs,
      transport = JavaHttpTransport.create(Duration.ofSeconds(10)),
      credentialsProvider = credentialsProvider,
      batchSize = config.batchSize
    )
