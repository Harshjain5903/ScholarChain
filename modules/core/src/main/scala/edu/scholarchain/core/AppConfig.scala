package edu.scholarchain.core

import com.typesafe.config.{Config, ConfigFactory}
import scala.jdk.CollectionConverters.*

case class StorageConfig(
    s3RawBucket: String,
    s3ProcessedBucket: String,
    s3VectorsBucket: String
)

case class NeptuneConfig(
    endpoint: String,
    port: Int,
    region: String
)

case class S3VectorsConfig(
    endpoint: String,
    indexName: String,
    dimensions: Int
)

case class EntityResolutionConfig(
    blockingKeyFields: Vector[String],
    matchThreshold: Double,
    rejectThreshold: Double,
    policyVersion: String
)

case class RetrievalConfig(
    topK: Int,
    maxHops: Int,
    minScore: Double
)

case class ModelsConfig(
    embeddingModelId: String,
    generationModelId: String,
    region: String
)

case class OperationsConfig(
    maxConcurrency: Int,
    timeoutSeconds: Int,
    retryCount: Int
)

case class AppConfig(
    storage: StorageConfig,
    neptune: NeptuneConfig,
    s3Vectors: S3VectorsConfig,
    entityResolution: EntityResolutionConfig,
    retrieval: RetrievalConfig,
    models: ModelsConfig,
    operations: OperationsConfig
)

/** Loads application configuration from HOCON with environment variable overrides. */
object AppConfig:

  /** Load from the default ConfigFactory resolution chain. */
  def load(): AppConfig = load(ConfigFactory.load())

  /** Load from an explicit Config instance (useful for testing). */
  def load(config: Config): AppConfig =
    val sc = config.getConfig("scholarchain")
    AppConfig(
      storage = StorageConfig(
        s3RawBucket = sc.getString("storage.s3-raw-bucket"),
        s3ProcessedBucket = sc.getString("storage.s3-processed-bucket"),
        s3VectorsBucket = sc.getString("storage.s3-vectors-bucket")
      ),
      neptune = NeptuneConfig(
        endpoint = sc.getString("neptune.endpoint"),
        port = sc.getInt("neptune.port"),
        region = sc.getString("neptune.region")
      ),
      s3Vectors = S3VectorsConfig(
        endpoint = sc.getString("s3-vectors.endpoint"),
        indexName = sc.getString("s3-vectors.index-name"),
        dimensions = sc.getInt("s3-vectors.dimensions")
      ),
      entityResolution = EntityResolutionConfig(
        blockingKeyFields =
          sc.getStringList("entity-resolution.blocking-key-fields").asScala.toVector,
        matchThreshold = sc.getDouble("entity-resolution.match-threshold"),
        rejectThreshold = sc.getDouble("entity-resolution.reject-threshold"),
        policyVersion = sc.getString("entity-resolution.policy-version")
      ),
      retrieval = RetrievalConfig(
        topK = sc.getInt("retrieval.top-k"),
        maxHops = sc.getInt("retrieval.max-hops"),
        minScore = sc.getDouble("retrieval.min-score")
      ),
      models = ModelsConfig(
        embeddingModelId = sc.getString("models.embedding-model-id"),
        generationModelId = sc.getString("models.generation-model-id"),
        region = sc.getString("models.region")
      ),
      operations = OperationsConfig(
        maxConcurrency = sc.getInt("operations.max-concurrency"),
        timeoutSeconds = sc.getInt("operations.timeout-seconds"),
        retryCount = sc.getInt("operations.retry-count")
      )
    )
