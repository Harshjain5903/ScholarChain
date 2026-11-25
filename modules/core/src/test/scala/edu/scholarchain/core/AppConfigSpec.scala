package edu.scholarchain.core

import com.typesafe.config.{Config, ConfigException, ConfigFactory, ConfigResolveOptions}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*

/** Tests for HOCON configuration loading. */
class AppConfigSpec extends AnyFlatSpec with Matchers {

  "AppConfig.load" should "load defaults from reference.conf" in {
    val config = AppConfig.load(ConfigFactory.load())

    config.storage.s3RawBucket shouldBe "scholarchain-raw"
    config.storage.s3ProcessedBucket shouldBe "scholarchain-processed"
    config.storage.s3VectorsBucket shouldBe "scholarchain-vectors"

    config.neptune.endpoint shouldBe "localhost"
    config.neptune.port shouldBe 8182
    config.neptune.region shouldBe "us-east-2"

    // 1024 / cosine / Titan v2: human decision 2026-11-22 (was 1536, which
    // matched no deployed index and not the Titan v2 default model).
    config.s3Vectors.dimensions shouldBe 1024
    config.s3Vectors.distanceMetric shouldBe DistanceMetric.Cosine
    config.s3Vectors.embeddingModelId shouldBe "amazon.titan-embed-text-v2:0"

    config.entityResolution.blockingKeyFields shouldBe Vector(
      "normalizedName",
      "year"
    )
    config.entityResolution.matchThreshold shouldBe 0.85
    config.entityResolution.rejectThreshold shouldBe 0.3
    config.entityResolution.policyVersion shouldBe "v1"

    config.retrieval.topK shouldBe 10
    config.retrieval.maxHops shouldBe 3
    config.retrieval.minScore shouldBe 0.5

    config.models.embeddingModelId shouldBe "amazon.titan-embed-text-v2:0"
    // Claude Haiku 5.5 via the Bedrock US inference profile: human decision 2026-11-24.
    config.models.generationModelId shouldBe "us.anthropic.claude-haiku-5-5"
    config.models.region shouldBe "us-east-2"

    config.operations.maxConcurrency shouldBe 8
    config.operations.timeoutSeconds shouldBe 30
    config.operations.retryCount shouldBe 3
  }

  it should "throw when loading from empty config" in {
    an[Exception] should be thrownBy {
      AppConfig.load(ConfigFactory.empty())
    }
  }

  it should "throw on type mismatch (string where int expected)" in {
    val bad = ConfigFactory
      .parseString("""scholarchain.neptune.port = "not-a-number"""")
      .withFallback(ConfigFactory.load())
    an[ConfigException.WrongType] should be thrownBy {
      AppConfig.load(bad)
    }
  }

  it should "support overrides from inline HOCON" in {
    val overrides = ConfigFactory.parseString("""
      scholarchain.storage.s3-raw-bucket = "custom-bucket"
      scholarchain.neptune.port = 9999
      scholarchain.retrieval.top-k = 20
    """)
    val config = AppConfig.load(overrides.withFallback(ConfigFactory.load()))

    config.storage.s3RawBucket shouldBe "custom-bucket"
    config.neptune.port shouldBe 9999
    config.retrieval.topK shouldBe 20
    // unchanged values still use defaults
    config.storage.s3ProcessedBucket shouldBe "scholarchain-processed"
  }

  // --- S3 Vectors index contract ---

  /**
   * core reference.conf parsed unresolved, so `${?ENV}` substitutions can be satisfied by
   * root-level keys in tests without touching the process env.
   */
  private def unresolvedReference: Config =
    ConfigFactory.parseResources(getClass.getClassLoader, "reference.conf")

  private def resolveWithEnv(env: (String, String)*): Config =
    val envConfig = ConfigFactory.parseMap(
      env.toMap.asInstanceOf[Map[String, AnyRef]].asJava
    )
    envConfig
      .withFallback(unresolvedReference)
      .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))

  /**
   * Overrides layered over the unresolved reference, so `embedding.dimensions` (which defaults to
   * `s3-vectors.dimensions`) follows the overridden index dimension.
   */
  private def withS3Vectors(hocon: String): Config =
    ConfigFactory
      .parseString(hocon)
      .atPath("scholarchain.s3-vectors")
      .withFallback(unresolvedReference)
      .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))

  "S3 Vectors config" should "default to dimension 1024, cosine, Titan v2 with no env set" in {
    val config = AppConfig.load(resolveWithEnv())
    config.s3Vectors shouldBe S3VectorsConfig(
      1024,
      DistanceMetric.Cosine,
      "amazon.titan-embed-text-v2:0"
    )
  }

  it should "honour SCHOLARCHAIN_S3_VECTORS_DIMENSIONS" in {
    val config = AppConfig.load(
      resolveWithEnv("SCHOLARCHAIN_S3_VECTORS_DIMENSIONS" -> "512")
    )
    config.s3Vectors.dimensions shouldBe 512
  }

  it should "honour SCHOLARCHAIN_S3_VECTORS_DISTANCE_METRIC" in {
    val config = AppConfig.load(
      resolveWithEnv("SCHOLARCHAIN_S3_VECTORS_DISTANCE_METRIC" -> "euclidean")
    )
    config.s3Vectors.distanceMetric shouldBe DistanceMetric.Euclidean
  }

  it should "honour SCHOLARCHAIN_S3_VECTORS_EMBEDDING_MODEL_ID independently of the query model" in {
    val config = AppConfig.load(
      resolveWithEnv(
        "SCHOLARCHAIN_S3_VECTORS_EMBEDDING_MODEL_ID" -> "index-model",
        "SCHOLARCHAIN_EMBEDDING_MODEL_ID"            -> "query-model"
      )
    )
    config.s3Vectors.embeddingModelId shouldBe "index-model"
    config.models.embeddingModelId shouldBe "query-model"
  }

  it should "keep the index model fixed when only the query model is overridden" in {
    val config = AppConfig.load(
      resolveWithEnv("SCHOLARCHAIN_EMBEDDING_MODEL_ID" -> "voyage-3")
    )
    config.s3Vectors.embeddingModelId shouldBe "amazon.titan-embed-text-v2:0"
    config.s3Vectors.isCompatibleModel(config.models.embeddingModelId) shouldBe false
  }

  it should "reject an invalid distance metric from the env var" in {
    val ex = the[ConfigException.BadValue] thrownBy AppConfig.load(
      resolveWithEnv("SCHOLARCHAIN_S3_VECTORS_DISTANCE_METRIC" -> "dotproduct")
    )
    ex.getMessage should include("dotproduct")
  }

  it should "reject metrics that are not exactly cosine or euclidean" in {
    for bad <- Seq("Cosine", "COSINE", " cosine", "", "manhattan") do
      withClue(s"metric '$bad': ") {
        an[ConfigException.BadValue] should be thrownBy AppConfig.load(
          withS3Vectors(s"""distance-metric = "$bad"""")
        )
      }
  }

  it should "reject dimension 0" in {
    val ex = the[ConfigException.BadValue] thrownBy AppConfig.load(
      withS3Vectors("dimensions = 0")
    )
    ex.getMessage should include("1..4096")
  }

  it should "reject dimension 5000" in {
    an[ConfigException.BadValue] should be thrownBy AppConfig.load(
      withS3Vectors("dimensions = 5000")
    )
  }

  it should "reject a negative dimension and dimension 4097 from the env var" in {
    for bad <- Seq("-1", "4097") do
      withClue(s"dimension $bad: ") {
        an[ConfigException.BadValue] should be thrownBy AppConfig.load(
          resolveWithEnv("SCHOLARCHAIN_S3_VECTORS_DIMENSIONS" -> bad)
        )
      }
  }

  it should "accept the boundary dimensions 1 and 4096" in {
    AppConfig.load(withS3Vectors("dimensions = 1")).s3Vectors.dimensions shouldBe 1
    AppConfig
      .load(withS3Vectors("dimensions = 4096"))
      .s3Vectors
      .dimensions shouldBe 4096
  }

  it should "reject a blank embedding model id" in {
    an[ConfigException.BadValue] should be thrownBy AppConfig.load(
      withS3Vectors("""embedding-model-id = "  """")
    )
  }

  it should "require distance-metric and embedding-model-id to be present" in {
    val base = ConfigFactory.load()
    an[ConfigException.Missing] should be thrownBy AppConfig.load(
      base.withoutPath("scholarchain.s3-vectors.distance-metric")
    )
    an[ConfigException.Missing] should be thrownBy AppConfig.load(
      base.withoutPath("scholarchain.s3-vectors.embedding-model-id")
    )
  }

  it should "no longer expose the removed endpoint and index-name keys" in {
    val sv = ConfigFactory.load().getConfig("scholarchain.s3-vectors")
    sv.hasPath("endpoint") shouldBe false
    sv.hasPath("index-name") shouldBe false
  }

  // --- Region defaults (human decision 2026-11-23: us-east-2 for initial deployment) ---

  "Region config" should "default Neptune and models regions to us-east-2 with no env set" in {
    val config = AppConfig.load(resolveWithEnv())
    config.neptune.region shouldBe "us-east-2"
    config.models.region shouldBe "us-east-2"
  }

  it should "not default any region to us-east-1" in {
    val config = AppConfig.load(resolveWithEnv())
    Seq(config.neptune.region, config.models.region) should not contain "us-east-1"
  }

  it should "honour SCHOLARCHAIN_NEPTUNE_REGION" in {
    val config = AppConfig.load(
      resolveWithEnv("SCHOLARCHAIN_NEPTUNE_REGION" -> "eu-west-1")
    )
    config.neptune.region shouldBe "eu-west-1"
    config.models.region shouldBe "us-east-2"
  }

  it should "honour SCHOLARCHAIN_MODELS_REGION" in {
    val config = AppConfig.load(
      resolveWithEnv("SCHOLARCHAIN_MODELS_REGION" -> "us-west-2")
    )
    config.models.region shouldBe "us-west-2"
    config.neptune.region shouldBe "us-east-2"
  }

  "S3VectorsConfig compatibility checks" should "match only the index model and dimension" in {
    val sv = AppConfig.load().s3Vectors
    sv.isCompatibleModel("amazon.titan-embed-text-v2:0") shouldBe true
    sv.isCompatibleModel("voyage-3") shouldBe false
    sv.isCompatibleModel("amazon.titan-embed-text-v1") shouldBe false
    sv.isCompatibleVector(1024) shouldBe true
    sv.isCompatibleVector(1536) shouldBe false
    sv.isCompatibleVector(256) shouldBe false
  }

  "DistanceMetric.parse" should "round-trip every config value and reject others" in {
    DistanceMetric.values.foreach { m =>
      DistanceMetric.parse(m.configValue) shouldBe Right(m)
    }
    DistanceMetric.parse("cosine") shouldBe Right(DistanceMetric.Cosine)
    DistanceMetric.parse("euclidean") shouldBe Right(DistanceMetric.Euclidean)
    DistanceMetric.parse("dot").isLeft shouldBe true
  }

  "S3VectorsConfig.validated" should "report each invalid field" in {
    S3VectorsConfig.validated(0, "cosine", "m").isLeft shouldBe true
    S3VectorsConfig.validated(1024, "bogus", "m").isLeft shouldBe true
    S3VectorsConfig.validated(1024, "cosine", "").isLeft shouldBe true
    S3VectorsConfig.validated(1024, "cosine", "m") shouldBe Right(
      S3VectorsConfig(1024, DistanceMetric.Cosine, "m")
    )
  }

  // --- Metric window (must equal the Spark run's --window-start / --window-end) ---

  "Metric window config" should "default to 1980..2026 with no env set" in {
    val config = AppConfig.load(resolveWithEnv())
    config.retrieval.metricWindowStart shouldBe 1980
    config.retrieval.metricWindowEnd shouldBe 2026
  }

  it should "default to 1980..2026 from ConfigFactory.load()" in {
    val config = AppConfig.load(ConfigFactory.load())
    config.retrieval.metricWindowStart shouldBe RetrievalConfig.DefaultMetricWindowStart
    config.retrieval.metricWindowEnd shouldBe RetrievalConfig.DefaultMetricWindowEnd
  }

  it should "be overridden by SCHOLARCHAIN_METRIC_WINDOW_START and _END" in {
    val config = AppConfig.load(
      resolveWithEnv(
        "SCHOLARCHAIN_METRIC_WINDOW_START" -> "2000",
        "SCHOLARCHAIN_METRIC_WINDOW_END"   -> "2024"
      )
    )
    config.retrieval.metricWindowStart shouldBe 2000
    config.retrieval.metricWindowEnd shouldBe 2024
    // unrelated retrieval settings keep their defaults
    config.retrieval.topK shouldBe 10
  }

  it should "be overridden by inline HOCON" in {
    val overrides = ConfigFactory.parseString("""
      scholarchain.retrieval.metric-window-start = 2015
      scholarchain.retrieval.metric-window-end = 2015
    """)
    val config = AppConfig.load(overrides.withFallback(ConfigFactory.load()))
    config.retrieval.metricWindowStart shouldBe 2015
    config.retrieval.metricWindowEnd shouldBe 2015
  }

  it should "reject start > end" in {
    val ex = the[ConfigException.BadValue] thrownBy AppConfig.load(
      resolveWithEnv(
        "SCHOLARCHAIN_METRIC_WINDOW_START" -> "2030",
        "SCHOLARCHAIN_METRIC_WINDOW_END"   -> "2020"
      )
    )
    ex.getMessage should include("metric-window-start (2030) must be <=")
  }

  it should "reject years outside 1900..2100" in {
    Seq(
      "SCHOLARCHAIN_METRIC_WINDOW_START" -> "1899",
      "SCHOLARCHAIN_METRIC_WINDOW_END"   -> "2101",
      "SCHOLARCHAIN_METRIC_WINDOW_START" -> "-5",
      "SCHOLARCHAIN_METRIC_WINDOW_END"   -> "0"
    ).foreach { kv =>
      withClue(kv) {
        an[ConfigException.BadValue] should be thrownBy AppConfig.load(resolveWithEnv(kv))
      }
    }
  }

  it should "accept the boundary years 1900 and 2100" in {
    val config = AppConfig.load(
      resolveWithEnv(
        "SCHOLARCHAIN_METRIC_WINDOW_START" -> "1900",
        "SCHOLARCHAIN_METRIC_WINDOW_END"   -> "2100"
      )
    )
    config.retrieval.metricWindowStart shouldBe 1900
    config.retrieval.metricWindowEnd shouldBe 2100
  }

  it should "reject a non-integer env value" in {
    an[ConfigException.WrongType] should be thrownBy AppConfig.load(
      resolveWithEnv("SCHOLARCHAIN_METRIC_WINDOW_START" -> "nineteen-eighty")
    )
  }

  "RetrievalConfig.validateMetricWindow" should "return the window or an error" in {
    RetrievalConfig.validateMetricWindow(1980, 2026) shouldBe Right((1980, 2026))
    RetrievalConfig.validateMetricWindow(2026, 1980).isLeft shouldBe true
    RetrievalConfig.validateMetricWindow(1800, 2026).isLeft shouldBe true
    RetrievalConfig.validateMetricWindow(1980, 2200).isLeft shouldBe true
  }
}
