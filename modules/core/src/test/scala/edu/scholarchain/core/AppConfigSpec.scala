package edu.scholarchain.core

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for HOCON configuration loading. */
class AppConfigSpec extends AnyFlatSpec with Matchers {

  "AppConfig.load" should "load defaults from reference.conf" in {
    val config = AppConfig.load(ConfigFactory.load())

    config.storage.s3RawBucket shouldBe "scholarchain-raw"
    config.storage.s3ProcessedBucket shouldBe "scholarchain-processed"
    config.storage.s3VectorsBucket shouldBe "scholarchain-vectors"

    config.neptune.endpoint shouldBe "localhost"
    config.neptune.port shouldBe 8182
    config.neptune.region shouldBe "us-east-1"

    config.s3Vectors.endpoint shouldBe "localhost"
    config.s3Vectors.indexName shouldBe "scholarchain-vectors"
    config.s3Vectors.dimensions shouldBe 1536

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
    config.models.generationModelId shouldBe
      "anthropic.claude-3-sonnet-20240229-v1:0"
    config.models.region shouldBe "us-east-1"

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
    val bad = ConfigFactory.parseString("""
      scholarchain.storage.s3-raw-bucket = "scholarchain-raw"
      scholarchain.storage.s3-processed-bucket = "scholarchain-processed"
      scholarchain.storage.s3-vectors-bucket = "scholarchain-vectors"
      scholarchain.neptune.endpoint = "localhost"
      scholarchain.neptune.port = "not-a-number"
      scholarchain.neptune.region = "us-east-1"
      scholarchain.s3-vectors.endpoint = "localhost"
      scholarchain.s3-vectors.index-name = "scholarchain-vectors"
      scholarchain.s3-vectors.dimensions = 1536
      scholarchain.entity-resolution.blocking-key-fields = ["normalizedName"]
      scholarchain.entity-resolution.match-threshold = 0.85
      scholarchain.entity-resolution.reject-threshold = 0.3
      scholarchain.entity-resolution.policy-version = "v1"
      scholarchain.retrieval.top-k = 10
      scholarchain.retrieval.max-hops = 3
      scholarchain.retrieval.min-score = 0.5
      scholarchain.models.embedding-model-id = "model"
      scholarchain.models.generation-model-id = "model"
      scholarchain.models.region = "us-east-1"
      scholarchain.operations.max-concurrency = 8
      scholarchain.operations.timeout-seconds = 30
      scholarchain.operations.retry-count = 3
    """)
    an[Exception] should be thrownBy {
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
}
