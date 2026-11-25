package edu.scholarchain.services

import com.typesafe.config.{Config, ConfigFactory, ConfigResolveOptions}
import edu.scholarchain.core.*
import edu.scholarchain.rag.{QueryEmbedder, QueryEmbedding}
import edu.scholarchain.storage.*
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.{ResponseBytes, SdkBytes}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.{
  InvokeModelRequest,
  InvokeModelResponse
}
import software.amazon.awssdk.services.dynamodb.{DynamoDbClient => AwsDynamoDbClient}
import software.amazon.awssdk.services.dynamodb.model.*
import software.amazon.awssdk.services.s3.{S3Client => AwsS3Client}
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  GetObjectResponse,
  NoSuchKeyException,
  PutObjectRequest,
  PutObjectResponse
}

import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/** In-memory fakes of the AWS clients the service uses. None of them opens a socket. */
object ServiceTestFakes:

  /** Placeholder test credentials (the AWS documentation example values), never real. */
  val creds: StaticCredentialsProvider =
    StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secretexample"))

  /** reference.conf plus `env`, ignoring the process environment. */
  def referenceConfig(env: Map[String, String] = Map.empty): Config =
    ConfigFactory
      .parseMap(env.asJava)
      .withFallback(ConfigFactory.parseResources("reference.conf"))
      .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))

  def appConfig(env: Map[String, String] = Map.empty): AppConfig =
    AppConfig.load(referenceConfig(env))

  val prov: Provenance = Provenance("fixture", "rec-1", "run-1", "v1", "2026-01-01T00:00:00Z")

  // ---- HTTP (Neptune, S3 Vectors) ----

  final class FakeTransport(handler: HttpCall => HttpReply) extends HttpTransport:
    val calls = AtomicInteger(0)

    def execute(call: HttpCall, timeout: Option[Duration]): HttpReply =
      calls.incrementAndGet()
      handler(call)

  def json(status: Int, body: ujson.Value): HttpReply =
    HttpReply(status, Map("content-type" -> "application/json"), ujson.write(body))

  object NoNetwork extends HttpTransport:

    def execute(call: HttpCall, timeout: Option[Duration]): HttpReply =
      throw AssertionError("no network call expected")

  def neptune(transport: HttpTransport): NeptuneStorageClient =
    NeptuneStorageClient(
      "neptune.test",
      8182,
      "us-east-2",
      1000,
      transport,
      creds,
      baseUriOverride = Some(URI.create("http://neptune.test:8182"))
    )

  // ---- DynamoDB ----

  /** Honours the conditional put (`attribute_not_exists`) like DynamoDB. */
  final class FakeDynamo extends AwsDynamoDbClient:
    val items = ConcurrentHashMap[String, java.util.Map[String, AttributeValue]]()
    @volatile var failPuts: Boolean          = false
    @volatile var failKey: String => Boolean = _ => false
    override def serviceName(): String       = "dynamodb"
    override def close(): Unit               = ()

    private def id(table: String, key: String) = s"$table/$key"

    override def putItem(r: PutItemRequest): PutItemResponse =
      val k = id(r.tableName(), r.item().get("queryId").s())
      if failPuts || failKey(r.item().get("queryId").s()) then
        throw DynamoDbException.builder().message("injected").build()
      if r.conditionExpression() != null then
        if items.putIfAbsent(k, r.item()) != null then
          throw ConditionalCheckFailedException.builder().message("exists").build()
      else items.put(k, r.item())
      PutItemResponse.builder().build()

    override def getItem(r: GetItemRequest): GetItemResponse =
      Option(items.get(id(r.tableName(), r.key().get("queryId").s()))) match
        case Some(item) => GetItemResponse.builder().item(item).build()
        case None       => GetItemResponse.builder().build()

    def keys: Set[String] = items.keySet().asScala.toSet

  // ---- S3 ----

  final class FakeS3 extends AwsS3Client:
    val objects                        = ConcurrentHashMap[String, Array[Byte]]()
    val gets                           = AtomicInteger(0)
    override def serviceName(): String = "s3"
    override def close(): Unit         = ()

    override def putObject(r: PutObjectRequest, body: RequestBody): PutObjectResponse =
      objects.put(
        s"${r.bucket()}/${r.key()}",
        body.contentStreamProvider().newStream().readAllBytes()
      )
      PutObjectResponse.builder().build()

    override def getObjectAsBytes(r: GetObjectRequest): ResponseBytes[GetObjectResponse] =
      gets.incrementAndGet()
      Option(objects.get(s"${r.bucket()}/${r.key()}")) match
        case Some(b) => ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), b)
        case None    => throw NoSuchKeyException.builder().message("no such key").build()

    def put(bucket: String, key: String, text: String): String =
      objects.put(s"$bucket/$key", text.getBytes("UTF-8"))
      s"s3://$bucket/$key"

  // ---- S3 Vectors ----

  /** In-memory S3 Vectors keyed by (index, key). `failPuts` reports every record as failed. */
  final class FakeS3Vectors
      extends S3VectorsStorageClient("us-east-2", 0, 0L, NoNetwork, ServiceTestFakes.creds):
    val stored                      = ConcurrentHashMap[(String, String), VectorRecord]()
    val puts                        = AtomicInteger(0)
    @volatile var failPuts: Boolean = false

    override def putVectors(
      vectorBucket: String,
      indexName: String,
      vectors: Vector[VectorRecord]
    ): PutVectorsResult =
      puts.incrementAndGet()
      if failPuts then PutVectorsResult(0, vectors.size, Vector("injected failure"))
      else
        vectors.foreach(v => stored.put((indexName, v.key), v))
        PutVectorsResult(vectors.size, 0, Vector.empty)

    override def getVectors(
      vectorBucket: String,
      indexName: String,
      keys: Vector[String],
      returnData: Boolean
    ): Vector[VectorRecord] =
      keys.flatMap(k => Option(stored.get((indexName, k))))

    override def queryVectors(
      vectorBucket: String,
      indexName: String,
      queryVector: Vector[Float],
      topK: Int,
      metadataFilters: Option[ujson.Value]
    ): Vector[VectorResult] = Vector.empty

  // ---- SQS ----

  /** Messages are delivered once; deletes are recorded by receipt handle. */
  final class FakeQueue extends QueueClient:
    val pending                         = mutable.Queue.empty[ReceivedMessage]
    val deleted                         = mutable.Set.empty[String]
    val receives                        = AtomicInteger(0)
    @volatile var failReceive: Boolean  = false
    @volatile var onReceive: () => Unit = () => ()
    private var seq                     = 0

    def sendMessage(queueUrl: String, body: String): Try[String] = synchronized {
      seq += 1
      val id = s"m-$seq"
      pending.enqueue(ReceivedMessage(id, s"rh-$id", body))
      Success(id)
    }

    def receiveMessages(queueUrl: String, maxMessages: Int): Try[List[ReceivedMessage]] =
      receives.incrementAndGet()
      onReceive()
      if failReceive then Failure(RuntimeException("injected receive failure"))
      else synchronized(Success(List.fill(math.min(maxMessages, pending.size))(pending.dequeue())))

    def deleteMessage(queueUrl: String, receiptHandle: String): Try[Unit] = synchronized {
      deleted += receiptHandle
      Success(())
    }

  // ---- models ----

  /** Deterministic embedder; counts calls and never records text. */
  final class FakeEmbedder(dims: Int = 1024, reportedModel: String = "amazon.titan-embed-text-v2:0")
      extends QueryEmbedder:
    val calls = AtomicInteger(0)

    def embed(text: String, modelId: String): QueryEmbedding =
      calls.incrementAndGet()
      QueryEmbedding(Vector.fill(dims)(0.01f), reportedModel)

  /** Bedrock fake answering Titan InvokeModel calls. */
  final class FakeBedrock extends BedrockRuntimeClient:
    val invokeCalls                    = AtomicInteger(0)
    override def serviceName(): String = "bedrock-runtime"
    override def close(): Unit         = ()

    override def invokeModel(r: InvokeModelRequest): InvokeModelResponse =
      invokeCalls.incrementAndGet()
      val body = ujson.Obj(
        "embedding"           -> ujson.Arr.from(Vector.fill(1024)(ujson.Num(0.01))),
        "inputTextTokenCount" -> 3
      )
      InvokeModelResponse.builder().body(SdkBytes.fromUtf8String(ujson.write(body))).build()

  // ---- HTTP client helpers ----

  private val client = java.net.http.HttpClient.newHttpClient()

  def get(base: String, path: String): java.net.http.HttpResponse[String] =
    client.send(
      java.net.http.HttpRequest.newBuilder().uri(URI.create(s"$base$path")).GET().build(),
      java.net.http.HttpResponse.BodyHandlers.ofString()
    )

  def post(
    base: String,
    path: String,
    body: String,
    headers: Map[String, String] = Map.empty
  ): java.net.http.HttpResponse[String] =
    val b = java.net.http.HttpRequest
      .newBuilder()
      .uri(URI.create(s"$base$path"))
      .header("Content-Type", "application/json")
      .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
    headers.foreach((k, v) => b.header(k, v))
    client.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString())

  def testConfig: ServiceConfig = ServiceConfig(
    port = 0,
    host = "127.0.0.1",
    syncDeadlineMs = 15000L,
    maxRequestBodyBytes = 1048576,
    maxGraphDepth = 3,
    maxGraphLimit = 200
  )

  def withRoutes[A](routes: Routes)(f: String => A): A =
    val server = routes.start()
    try f(s"http://127.0.0.1:${server.getAddress.getPort}")
    finally server.stop(0)
