package edu.scholarchain.core

import com.typesafe.config.{Config, ConfigException, ConfigFactory, ConfigResolveOptions}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*

/** Feature tests for the LLM, embedding, and synthesis configuration contract. */
class ModelConfigSpec extends AnyFlatSpec with Matchers {

  private val validArn =
    "arn:aws:secretsmanager:us-east-2:123456789012:secret:scholarchain/anthropic-AbCdEf"

  /**
   * Simulate environment variables: the unresolved reference.conf is layered over a map of
   * `SCHOLARCHAIN_*` keys, so `${?VAR}` substitutions pick them up exactly as they would from the
   * process environment.
   */
  private def withEnv(env: (String, String)*): Config =
    ConfigFactory
      .parseMap(env.toMap.asJava)
      .withFallback(ConfigFactory.parseResources(getClass.getClassLoader, "reference.conf"))
      .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))

  private def overrides(hocon: String): Config =
    ConfigFactory.parseString(hocon).withFallback(ConfigFactory.load())

  // ---- defaults -------------------------------------------------------------

  "AppConfig model defaults" should "use Bedrock Claude Haiku 5.5 via the US inference profile" in {
    val c = AppConfig.load()
    c.llm.provider shouldBe LlmProvider.Bedrock
    c.llm.modelId shouldBe "us.anthropic.claude-haiku-5-5"
    c.llm.region shouldBe "us-east-2"
    c.llm.anthropicApiKey shouldBe None
  }

  it should "use Bedrock Titan Text Embeddings v2 with 1024 dimensions in us-east-2" in {
    val c = AppConfig.load()
    c.embedding.provider shouldBe EmbeddingProvider.Bedrock
    c.embedding.modelId shouldBe "amazon.titan-embed-text-v2:0"
    c.embedding.dimensions shouldBe 1024
    c.embedding.region shouldBe "us-east-2"
    c.embedding.voyageApiKey shouldBe None
  }

  it should "default synthesis mode to llm" in {
    AppConfig.load().synthesis.mode shouldBe SynthesisMode.Llm
  }

  it should "have bounded call defaults" in {
    val c = AppConfig.load()
    c.llm.maxConcurrentCalls shouldBe 4
    c.llm.maxOutputTokens shouldBe 1024
    c.llm.maxConcurrentCalls should be <= LlmBounds.MaxConcurrentCalls
    c.llm.maxOutputTokens should be <= LlmBounds.MaxOutputTokens
  }

  it should "derive legacy models.* IDs from the single llm/embedding defaults" in {
    val c = AppConfig.load()
    c.models.generationModelId shouldBe c.llm.modelId
    c.models.embeddingModelId shouldBe c.embedding.modelId
  }

  it should "align the S3 Vectors index dimension with the embedding dimension" in {
    val c = AppConfig.load()
    c.s3Vectors.dimensions shouldBe c.embedding.dimensions
  }

  it should "default the model regions from models.region (us-east-2) with no env set" in {
    val c = AppConfig.load(withEnv())
    c.llm.region shouldBe "us-east-2"
    c.embedding.region shouldBe "us-east-2"
    c.models.region shouldBe "us-east-2"
  }

  it should "keep the index model independent of the query-time embedding model" in {
    val c = AppConfig.load(withEnv())
    c.s3Vectors.isCompatibleModel(c.embedding.modelId) shouldBe true
    val voyage = AppConfig.load(withEnv("SCHOLARCHAIN_EMBEDDING_MODEL_ID" -> "voyage-3"))
    voyage.embedding.modelId shouldBe "voyage-3"
    voyage.models.embeddingModelId shouldBe "voyage-3"
    voyage.s3Vectors.embeddingModelId shouldBe "amazon.titan-embed-text-v2:0"
    voyage.s3Vectors.isCompatibleModel(voyage.embedding.modelId) shouldBe false
  }

  // ---- environment overrides ------------------------------------------------

  "AppConfig env overrides" should "switch to the Anthropic API fallback with an env-var-name ref" in {
    val c = AppConfig.load(
      withEnv(
        "SCHOLARCHAIN_LLM_PROVIDER"              -> "anthropic",
        "SCHOLARCHAIN_LLM_MODEL_ID"              -> "claude-haiku-5-5",
        "SCHOLARCHAIN_ANTHROPIC_API_KEY_ENV_VAR" -> "ANTHROPIC_API_KEY"
      )
    )
    c.llm.provider shouldBe LlmProvider.Anthropic
    c.llm.modelId shouldBe "claude-haiku-5-5"
    c.llm.anthropicApiKey shouldBe Some(SecretRef.EnvVarName("ANTHROPIC_API_KEY"))
  }

  it should "switch to the Voyage fallback with a Secrets Manager ARN ref" in {
    val voyageArn =
      "arn:aws:secretsmanager:us-east-2:123456789012:secret:scholarchain/voyage-XyZ123"
    val c = AppConfig.load(
      withEnv(
        "SCHOLARCHAIN_EMBEDDING_PROVIDER"        -> "voyage",
        "SCHOLARCHAIN_EMBEDDING_MODEL_ID"        -> "voyage-3",
        "SCHOLARCHAIN_VOYAGE_API_KEY_SECRET_ARN" -> voyageArn
      )
    )
    c.embedding.provider shouldBe EmbeddingProvider.Voyage
    c.embedding.modelId shouldBe "voyage-3"
    c.embedding.voyageApiKey shouldBe Some(SecretRef.SecretsManagerArn(voyageArn))
  }

  it should "override synthesis mode and limits" in {
    val c = AppConfig.load(
      withEnv(
        "SCHOLARCHAIN_SYNTHESIS_MODE"           -> "extractive",
        "SCHOLARCHAIN_LLM_MAX_CONCURRENT_CALLS" -> "16",
        "SCHOLARCHAIN_LLM_MAX_OUTPUT_TOKENS"    -> "2048"
      )
    )
    c.synthesis.mode shouldBe SynthesisMode.Extractive
    c.llm.maxConcurrentCalls shouldBe 16
    c.llm.maxOutputTokens shouldBe 2048
  }

  it should "derive the embedding dimension from SCHOLARCHAIN_S3_VECTORS_DIMENSIONS" in {
    val c = AppConfig.load(withEnv("SCHOLARCHAIN_S3_VECTORS_DIMENSIONS" -> "512"))
    c.embedding.dimensions shouldBe 512
    c.s3Vectors.dimensions shouldBe 512
  }

  it should "accept SCHOLARCHAIN_EMBEDDING_DIMENSIONS when it matches the index dimension" in {
    val c = AppConfig.load(
      withEnv(
        "SCHOLARCHAIN_S3_VECTORS_DIMENSIONS" -> "256",
        "SCHOLARCHAIN_EMBEDDING_DIMENSIONS"  -> "256"
      )
    )
    c.embedding.dimensions shouldBe 256
  }

  it should "reject SCHOLARCHAIN_EMBEDDING_DIMENSIONS that differs from the index dimension" in {
    val ex = the[ConfigException.BadValue] thrownBy
      AppConfig.load(withEnv("SCHOLARCHAIN_EMBEDDING_DIMENSIONS" -> "512"))
    ex.getMessage should include("must equal s3-vectors.dimensions")
  }

  it should "honour the legacy SCHOLARCHAIN_GENERATION_MODEL_ID name" in {
    val c = AppConfig.load(withEnv("SCHOLARCHAIN_GENERATION_MODEL_ID" -> "us.anthropic.legacy"))
    c.llm.modelId shouldBe "us.anthropic.legacy"
    c.models.generationModelId shouldBe "us.anthropic.legacy"
  }

  it should "prefer SCHOLARCHAIN_LLM_MODEL_ID over the legacy name" in {
    val c = AppConfig.load(
      withEnv(
        "SCHOLARCHAIN_GENERATION_MODEL_ID" -> "us.anthropic.legacy",
        "SCHOLARCHAIN_LLM_MODEL_ID"        -> "us.anthropic.new"
      )
    )
    c.llm.modelId shouldBe "us.anthropic.new"
    c.models.generationModelId shouldBe "us.anthropic.new"
  }

  it should "override each model region independently of models.region" in {
    val c = AppConfig.load(
      withEnv(
        "SCHOLARCHAIN_LLM_REGION"       -> "us-west-2",
        "SCHOLARCHAIN_EMBEDDING_REGION" -> "eu-west-1"
      )
    )
    c.llm.region shouldBe "us-west-2"
    c.embedding.region shouldBe "eu-west-1"
    c.models.region shouldBe "us-east-2"
    val shared = AppConfig.load(withEnv("SCHOLARCHAIN_MODELS_REGION" -> "ap-south-1"))
    shared.llm.region shouldBe "ap-south-1"
    shared.embedding.region shouldBe "ap-south-1"
  }

  it should "reject an unknown LLM provider from the environment" in {
    val ex = the[IllegalArgumentException] thrownBy
      AppConfig.load(withEnv("SCHOLARCHAIN_LLM_PROVIDER" -> "openai"))
    ex.getMessage should include("llm.provider")
  }

  // ---- enum validation ------------------------------------------------------

  "Provider and mode parsing" should "reject unknown LLM providers" in {
    an[IllegalArgumentException] should be thrownBy
      AppConfig.load(overrides("""scholarchain.llm.provider = "openai""""))
    an[IllegalArgumentException] should be thrownBy LlmProvider.parse("Bedrock")
    an[IllegalArgumentException] should be thrownBy LlmProvider.parse("")
  }

  it should "reject unknown embedding providers" in {
    an[IllegalArgumentException] should be thrownBy
      AppConfig.load(overrides("""scholarchain.embedding.provider = "cohere""""))
  }

  it should "reject unknown synthesis modes" in {
    an[IllegalArgumentException] should be thrownBy
      AppConfig.load(overrides("""scholarchain.synthesis.mode = "abstractive""""))
  }

  it should "parse every supported value" in {
    LlmProvider.parse("bedrock") shouldBe LlmProvider.Bedrock
    LlmProvider.parse("anthropic") shouldBe LlmProvider.Anthropic
    EmbeddingProvider.parse("bedrock") shouldBe EmbeddingProvider.Bedrock
    EmbeddingProvider.parse("voyage") shouldBe EmbeddingProvider.Voyage
    SynthesisMode.parse("llm") shouldBe SynthesisMode.Llm
    SynthesisMode.parse("extractive") shouldBe SynthesisMode.Extractive
  }

  // ---- bounds ---------------------------------------------------------------

  "LLM limits" should "reject out-of-range maxConcurrentCalls" in {
    for v <- Seq(0, -1, LlmBounds.MaxConcurrentCalls + 1) do
      an[IllegalArgumentException] should be thrownBy
        AppConfig.load(overrides(s"scholarchain.llm.max-concurrent-calls = $v"))
  }

  it should "reject out-of-range maxOutputTokens" in {
    for v <- Seq(0, -5, LlmBounds.MaxOutputTokens + 1) do
      an[IllegalArgumentException] should be thrownBy
        AppConfig.load(overrides(s"scholarchain.llm.max-output-tokens = $v"))
  }

  it should "accept the boundary values" in {
    val c = AppConfig.load(
      overrides(
        s"""scholarchain.llm.max-concurrent-calls = ${LlmBounds.MaxConcurrentCalls}
           |scholarchain.llm.max-output-tokens = 1""".stripMargin
      )
    )
    c.llm.maxConcurrentCalls shouldBe LlmBounds.MaxConcurrentCalls
    c.llm.maxOutputTokens shouldBe 1
  }

  "Embedding dimensions" should "reject values outside the index bounds" in {
    def cfg(d: Int) =
      EmbeddingConfig(
        EmbeddingProvider.Bedrock,
        "amazon.titan-embed-text-v2:0",
        d,
        "us-east-2",
        None
      )
    for d <- Seq(0, -1, EmbeddingBounds.MaxDimensions + 1) do
      an[IllegalArgumentException] should be thrownBy cfg(d)
    cfg(EmbeddingBounds.MinDimensions).dimensions shouldBe 1
    cfg(EmbeddingBounds.MaxDimensions).dimensions shouldBe 4096
  }

  it should "reject a mismatch with the S3 Vectors index dimension" in {
    val ex = the[ConfigException.BadValue] thrownBy
      AppConfig.load(overrides("scholarchain.s3-vectors.dimensions = 1536"))
    ex.getMessage should include("must equal s3-vectors.dimensions")
  }

  it should "reject an empty model ID" in {
    an[IllegalArgumentException] should be thrownBy
      AppConfig.load(overrides("""scholarchain.llm.model-id = "  """"))
    an[IllegalArgumentException] should be thrownBy
      AppConfig.load(overrides("""scholarchain.embedding.model-id = """""))
  }

  // ---- secret references ----------------------------------------------------

  "Fallback API keys" should "be required when the Anthropic provider is selected" in {
    val ex = the[IllegalArgumentException] thrownBy
      AppConfig.load(
        overrides("""scholarchain.llm.provider = "anthropic"
                    |scholarchain.llm.model-id = "claude-haiku-5-5"""".stripMargin)
      )
    ex.getMessage should include("requires llm.anthropic.api-key-secret-arn")
  }

  it should "be required when the Voyage provider is selected" in {
    an[IllegalArgumentException] should be thrownBy
      AppConfig.load(
        overrides("""scholarchain.embedding.provider = "voyage"
                    |scholarchain.embedding.model-id = "voyage-3"""".stripMargin)
      )
  }

  it should "accept a Secrets Manager ARN for Anthropic" in {
    val c = AppConfig.load(
      overrides(s"""scholarchain.llm.provider = "anthropic"
                   |scholarchain.llm.model-id = "claude-haiku-5-5"
                   |scholarchain.llm.anthropic.api-key-secret-arn = "$validArn"""".stripMargin)
    )
    c.llm.anthropicApiKey shouldBe Some(SecretRef.SecretsManagerArn(validArn))
  }

  it should "reject a raw key value pasted into the env-var-name field without echoing it" in {
    val pasted = "sk-ant-api03-NOT-A-REAL-KEY-abcdef"
    val ex = the[IllegalArgumentException] thrownBy
      AppConfig.load(
        overrides(s"""scholarchain.llm.anthropic.api-key-env-var = "$pasted"""")
      )
    ex.getMessage should not include pasted
    ex.getMessage should include("environment variable NAME")
  }

  it should "reject a raw key value pasted into the ARN field without echoing it" in {
    val pasted = "pa-NOT-A-REAL-VOYAGE-KEY"
    val ex = the[IllegalArgumentException] thrownBy
      AppConfig.load(
        overrides(s"""scholarchain.embedding.voyage.api-key-secret-arn = "$pasted"""")
      )
    ex.getMessage should not include pasted
  }

  it should "reject ARNs for services other than Secrets Manager" in {
    an[IllegalArgumentException] should be thrownBy
      SecretRef.arn("arn:aws:ssm:us-east-2:123456789012:parameter/anthropic")
  }

  it should "reject setting both an ARN and an env-var name" in {
    an[IllegalArgumentException] should be thrownBy
      AppConfig.load(
        overrides(s"""scholarchain.llm.anthropic.api-key-secret-arn = "$validArn"
                     |scholarchain.llm.anthropic.api-key-env-var = "ANTHROPIC_API_KEY"
                     |""".stripMargin)
      )
  }

  it should "reject Bedrock model IDs when the direct-API fallback is selected" in {
    an[IllegalArgumentException] should be thrownBy
      AppConfig.load(
        overrides("""scholarchain.llm.provider = "anthropic"
                    |scholarchain.llm.anthropic.api-key-env-var = "ANTHROPIC_API_KEY"
                    |""".stripMargin)
      )
    an[IllegalArgumentException] should be thrownBy
      AppConfig.load(
        overrides("""scholarchain.embedding.provider = "voyage"
                    |scholarchain.embedding.voyage.api-key-env-var = "VOYAGE_API_KEY"
                    |""".stripMargin)
      )
  }
}
