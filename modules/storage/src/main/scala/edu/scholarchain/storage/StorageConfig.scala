package edu.scholarchain.storage

import com.typesafe.config.{Config, ConfigFactory, ConfigResolveOptions}

/** S3 bucket configuration for all storage tiers. */
case class S3BucketConfig(
  rawBucket: String,
  curatedBucket: String,
  graphBucket: String,
  ragBucket: String
)

/** Neptune graph-database connection configuration. */
case class NeptuneStorageConfig(
  endpoint: String,
  port: Int,
  region: String,
  queryTimeoutMs: Long
)

/** DynamoDB table configuration. */
case class DynamoDbStorageConfig(
  region: String,
  tablePrefix: String
)

/** S3 Vectors service configuration. */
case class S3VectorsStorageConfig(
  vectorBucket: String,
  region: String,
  batchSize: Int,
  maxRetries: Int,
  retryBaseDelayMs: Long
)

/**
 * Top-level storage configuration container.
 *
 * @param embeddingQueueUrl
 *   `scholarchain.storage.embedding-queue-url` (env `SCHOLARCHAIN_EMBEDDING_QUEUE_URL`); `None`
 *   when blank
 */
case class StorageModuleConfig(
  s3: S3BucketConfig,
  neptune: NeptuneStorageConfig,
  dynamoDb: DynamoDbStorageConfig,
  s3Vectors: S3VectorsStorageConfig,
  embeddingQueueUrl: Option[String] = None,
  embeddingQueue: EmbeddingQueueConfig = EmbeddingQueueConfig()
)

/**
 * SQS settings for the embedding work queue (`scholarchain.storage.embedding-queue`).
 *
 * @param region
 *   SQS region for [[SqsQueueClient.create]]
 * @param settings
 *   long-poll wait, optional visibility timeout, send attempt bound and backoff
 */
case class EmbeddingQueueConfig(
  region: String = "us-east-2",
  settings: SqsQueueSettings = SqsQueueSettings()
)

/** Loads storage configuration from HOCON with environment variable overrides. */
object StorageModuleConfig:

  /** Environment variable that overrides `scholarchain.storage.embedding-queue-url`. */
  val EmbeddingQueueUrlEnv: String = "SCHOLARCHAIN_EMBEDDING_QUEUE_URL"

  /** Load from the default ConfigFactory resolution chain. */
  def load(): StorageModuleConfig = load(ConfigFactory.load())

  /**
   * Load `reference.conf` resolving `${?VAR}` overrides from `env` only, not from the process
   * environment. Used to test env overrides deterministically.
   */
  def loadWithEnv(env: Map[String, String]): StorageModuleConfig =
    val envConfig = ConfigFactory.parseMap(
      scala.jdk.CollectionConverters.MapHasAsJava(env).asJava,
      "injected environment"
    )
    val resolved = envConfig
      .withFallback(ConfigFactory.parseResources("reference.conf"))
      .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))
    load(resolved)

  /** Load from an explicit Config instance (useful for testing). */
  def load(config: Config): StorageModuleConfig =
    val sc = config.getConfig("scholarchain.storage")
    StorageModuleConfig(
      s3 = S3BucketConfig(
        rawBucket = sc.getString("s3.raw-bucket"),
        curatedBucket = sc.getString("s3.curated-bucket"),
        graphBucket = sc.getString("s3.graph-bucket"),
        ragBucket = sc.getString("s3.rag-bucket")
      ),
      neptune = NeptuneStorageConfig(
        endpoint = sc.getString("neptune.endpoint"),
        port = sc.getInt("neptune.port"),
        region = sc.getString("neptune.region"),
        queryTimeoutMs = sc.getLong("neptune.query-timeout-ms")
      ),
      dynamoDb = DynamoDbStorageConfig(
        region = sc.getString("dynamodb.region"),
        tablePrefix = sc.getString("dynamodb.table-prefix")
      ),
      s3Vectors = S3VectorsStorageConfig(
        vectorBucket = sc.getString("s3-vectors.vector-bucket"),
        region = sc.getString("s3-vectors.region"),
        batchSize = sc.getInt("s3-vectors.batch-size"),
        maxRetries = sc.getInt("s3-vectors.max-retries"),
        retryBaseDelayMs = sc.getLong("s3-vectors.retry-base-delay-ms")
      ),
      embeddingQueueUrl =
        if sc.hasPath("embedding-queue-url") then
          Some(sc.getString("embedding-queue-url").trim).filter(_.nonEmpty)
        else None,
      embeddingQueue =
        if sc.hasPath("embedding-queue") then loadQueue(sc.getConfig("embedding-queue"))
        else EmbeddingQueueConfig()
    )

  private def loadQueue(q: Config): EmbeddingQueueConfig =
    val d = SqsQueueSettings()
    EmbeddingQueueConfig(
      region = if q.hasPath("region") then q.getString("region") else "us-east-2",
      settings = SqsQueueSettings(
        waitTimeSeconds =
          if q.hasPath("wait-time-seconds") then q.getInt("wait-time-seconds")
          else d.waitTimeSeconds,
        visibilityTimeoutSeconds =
          if q.hasPath("visibility-timeout-seconds") then
            Some(q.getInt("visibility-timeout-seconds"))
          else None,
        maxSendAttempts =
          if q.hasPath("max-send-attempts") then q.getInt("max-send-attempts")
          else d.maxSendAttempts,
        retryBaseDelayMs =
          if q.hasPath("retry-base-delay-ms") then q.getLong("retry-base-delay-ms")
          else d.retryBaseDelayMs
      )
    )
