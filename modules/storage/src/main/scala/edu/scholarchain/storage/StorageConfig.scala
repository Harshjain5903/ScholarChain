package edu.scholarchain.storage

import com.typesafe.config.{Config, ConfigFactory}

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

/** Top-level storage configuration container. */
case class StorageModuleConfig(
    s3: S3BucketConfig,
    neptune: NeptuneStorageConfig,
    dynamoDb: DynamoDbStorageConfig,
    s3Vectors: S3VectorsStorageConfig
)

/** Loads storage configuration from HOCON with environment variable overrides. */
object StorageModuleConfig:

  /** Load from the default ConfigFactory resolution chain. */
  def load(): StorageModuleConfig = load(ConfigFactory.load())

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
      )
    )
