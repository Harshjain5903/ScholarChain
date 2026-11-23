package edu.scholarchain.core

import com.typesafe.config.{Config, ConfigException, ConfigFactory}
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

/** Distance metric of an S3 Vectors index. `configValue` is the HOCON/env spelling. */
enum DistanceMetric(val configValue: String):
  case Cosine    extends DistanceMetric("cosine")
  case Euclidean extends DistanceMetric("euclidean")

object DistanceMetric:

  /** Parse a config value (case-sensitive, exact match). */
  def parse(value: String): Either[String, DistanceMetric] =
    DistanceMetric.values.find(_.configValue == value) match
      case Some(m) => Right(m)
      case None =>
        Left(
          s"invalid s3-vectors.distance-metric '$value'; expected one of " +
            DistanceMetric.values.map(_.configValue).mkString(", ")
        )

/**
 * Index contract shared by every ScholarChain S3 Vectors index.
 *
 * @param dimensions
 *   vector dimension of every index (1..4096)
 * @param distanceMetric
 *   distance metric of every index
 * @param embeddingModelId
 *   identity of the model that populated the indexes
 */
case class S3VectorsConfig(
  dimensions: Int,
  distanceMetric: DistanceMetric,
  embeddingModelId: String
):

  /** True when vectors from `queryModelId` may be queried against or written to these indexes. */
  def isCompatibleModel(queryModelId: String): Boolean =
    queryModelId == embeddingModelId

  /** True when a vector has exactly the index dimension. */
  def isCompatibleVector(vectorLength: Int): Boolean =
    vectorLength == dimensions

object S3VectorsConfig:
  val MinDimensions: Int = 1
  val MaxDimensions: Int = 4096

  /** Validated constructor: rejects out-of-range dimensions and blank model ids. */
  def validated(
    dimensions: Int,
    distanceMetric: String,
    embeddingModelId: String
  ): Either[String, S3VectorsConfig] =
    for
      dims <-
        if dimensions >= MinDimensions && dimensions <= MaxDimensions then Right(dimensions)
        else
          Left(
            s"invalid s3-vectors.dimensions $dimensions; expected $MinDimensions..$MaxDimensions"
          )
      metric <- DistanceMetric.parse(distanceMetric)
      model <-
        if embeddingModelId.trim.nonEmpty then Right(embeddingModelId)
        else Left("invalid s3-vectors.embedding-model-id: must be non-empty")
    yield S3VectorsConfig(dims, metric, model)

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
      s3Vectors = loadS3Vectors(sc),
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

  /** Reads and validates `scholarchain.s3-vectors`; throws ConfigException.BadValue if invalid. */
  private def loadS3Vectors(sc: Config): S3VectorsConfig =
    S3VectorsConfig.validated(
      dimensions = sc.getInt("s3-vectors.dimensions"),
      distanceMetric = sc.getString("s3-vectors.distance-metric"),
      embeddingModelId = sc.getString("s3-vectors.embedding-model-id")
    ) match
      case Right(c)  => c
      case Left(err) => throw new ConfigException.BadValue("scholarchain.s3-vectors", err)
