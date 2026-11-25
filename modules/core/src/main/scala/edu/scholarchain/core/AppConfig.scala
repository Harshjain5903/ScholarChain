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

/**
 * Retrieval settings.
 *
 * @param metricWindowStart
 *   first year of the metric window the runtime uses when a question names no years
 *   (`retrieval.metric-window-start`, env `SCHOLARCHAIN_METRIC_WINDOW_START`). Must equal the Spark
 *   metrics run's `--window-start`, because Neptune metrics are matched to the window exactly.
 * @param metricWindowEnd
 *   last year of that window (`retrieval.metric-window-end`, env `SCHOLARCHAIN_METRIC_WINDOW_END`);
 *   must equal the Spark run's `--window-end`
 */
case class RetrievalConfig(
  topK: Int,
  maxHops: Int,
  minScore: Double,
  metricWindowStart: Int = RetrievalConfig.DefaultMetricWindowStart,
  metricWindowEnd: Int = RetrievalConfig.DefaultMetricWindowEnd
)

object RetrievalConfig:
  /** Defaults match the bounded Spark runs (`--window-start 1980 --window-end 2026`). */
  val DefaultMetricWindowStart: Int = 1980
  val DefaultMetricWindowEnd: Int   = 2026

  /** Plausible bounds for a metric-window year. */
  val MinWindowYear: Int = 1900
  val MaxWindowYear: Int = 2100

  /** Validates a metric window: both years in 1900..2100 and start <= end. */
  def validateMetricWindow(start: Int, end: Int): Either[String, (Int, Int)] =
    def inRange(name: String, y: Int): Either[String, Int] =
      if y >= MinWindowYear && y <= MaxWindowYear then Right(y)
      else Left(s"invalid retrieval.$name $y; expected $MinWindowYear..$MaxWindowYear")
    for
      s <- inRange("metric-window-start", start)
      e <- inRange("metric-window-end", end)
      _ <-
        if s <= e then Right(())
        else
          Left(
            s"invalid metric window: retrieval.metric-window-start ($s) must be <= " +
              s"retrieval.metric-window-end ($e)"
          )
    yield (s, e)

/**
 * Legacy model settings. The model IDs are derived in `reference.conf` from `llm.model-id` and
 * `embedding.model-id`; new code should read [[AppConfig.llm]] and [[AppConfig.embedding]].
 */
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
  operations: OperationsConfig,
  llm: LlmConfig,
  embedding: EmbeddingConfig,
  synthesis: SynthesisConfig
)

/**
 * Loads application configuration from HOCON with environment variable overrides.
 *
 * Sources, highest precedence first: `SCHOLARCHAIN_*` environment variables, then the versioned
 * `reference.conf` under `modules/core/src/main/resources`. Each model ID has exactly one default,
 * in `reference.conf`. Provider and synthesis-mode values are validated and unknown values
 * rejected. Fallback-provider API keys are referenced only by Secrets Manager ARN or env-var NAME.
 */
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
      retrieval = loadRetrieval(sc),
      models = ModelsConfig(
        embeddingModelId = sc.getString("models.embedding-model-id"),
        generationModelId = sc.getString("models.generation-model-id"),
        region = sc.getString("models.region")
      ),
      operations = OperationsConfig(
        maxConcurrency = sc.getInt("operations.max-concurrency"),
        timeoutSeconds = sc.getInt("operations.timeout-seconds"),
        retryCount = sc.getInt("operations.retry-count")
      ),
      llm = ModelConfigLoader.llm(sc),
      embedding = loadEmbedding(sc),
      synthesis = ModelConfigLoader.synthesis(sc)
    )

  /**
   * Reads `scholarchain.embedding` and checks its dimension against the S3 Vectors index contract.
   * Throws ConfigException.BadValue on a mismatch: a query vector of any other length can never be
   * used against the configured indexes.
   */
  private def loadEmbedding(sc: Config): EmbeddingConfig =
    val embedding = ModelConfigLoader.embedding(sc)
    val indexDims = sc.getInt("s3-vectors.dimensions")
    if embedding.dimensions != indexDims then
      throw new ConfigException.BadValue(
        "scholarchain.embedding.dimensions",
        s"embedding.dimensions (${embedding.dimensions}) must equal s3-vectors.dimensions " +
          s"($indexDims)"
      )
    embedding

  /**
   * Reads `scholarchain.retrieval` and validates the metric window; throws ConfigException.BadValue
   * if a window year is outside 1900..2100 or start > end.
   */
  private def loadRetrieval(sc: Config): RetrievalConfig =
    RetrievalConfig.validateMetricWindow(
      sc.getInt("retrieval.metric-window-start"),
      sc.getInt("retrieval.metric-window-end")
    ) match
      case Left(err) => throw new ConfigException.BadValue("scholarchain.retrieval", err)
      case Right((start, end)) =>
        RetrievalConfig(
          topK = sc.getInt("retrieval.top-k"),
          maxHops = sc.getInt("retrieval.max-hops"),
          minScore = sc.getDouble("retrieval.min-score"),
          metricWindowStart = start,
          metricWindowEnd = end
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
