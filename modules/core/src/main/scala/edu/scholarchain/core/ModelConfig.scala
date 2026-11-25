package edu.scholarchain.core

import com.typesafe.config.Config

/** Runtime LLM provider. Bedrock is the default; Anthropic API is the approved fallback. */
enum LlmProvider:
  case Bedrock, Anthropic

object LlmProvider:

  /** Parse a lowercase config value (`bedrock` or `anthropic`); reject anything else. */
  def parse(value: String): LlmProvider =
    value match
      case "bedrock"   => Bedrock
      case "anthropic" => Anthropic
      case other =>
        throw new IllegalArgumentException(
          s"Unknown llm.provider '$other'; expected one of: bedrock, anthropic"
        )

/** Embedding provider. Bedrock is the default; Voyage AI is the approved fallback. */
enum EmbeddingProvider:
  case Bedrock, Voyage

object EmbeddingProvider:

  /** Parse a lowercase config value (`bedrock` or `voyage`); reject anything else. */
  def parse(value: String): EmbeddingProvider =
    value match
      case "bedrock" => Bedrock
      case "voyage"  => Voyage
      case other =>
        throw new IllegalArgumentException(
          s"Unknown embedding.provider '$other'; expected one of: bedrock, voyage"
        )

/** Answer synthesis mode: LLM-generated, or extractive (no model call). */
enum SynthesisMode:
  case Llm, Extractive

object SynthesisMode:

  /** Parse a lowercase config value (`llm` or `extractive`); reject anything else. */
  def parse(value: String): SynthesisMode =
    value match
      case "llm"        => Llm
      case "extractive" => Extractive
      case other =>
        throw new IllegalArgumentException(
          s"Unknown synthesis.mode '$other'; expected one of: llm, extractive"
        )

/**
 * A reference to a secret. Never holds the secret value itself.
 *
 * Only two forms are allowed: an AWS Secrets Manager ARN, or the NAME of an environment variable
 * that will hold the value at runtime. Both are validated so that a pasted key value is rejected at
 * load time.
 */
enum SecretRef:
  case SecretsManagerArn(arn: String)
  case EnvVarName(name: String)

  /** Safe, non-secret textual form for logs. */
  override def toString: String = this match
    case SecretsManagerArn(arn) => s"SecretsManagerArn($arn)"
    case EnvVarName(name)       => s"EnvVarName($name)"

object SecretRef:

  private val ArnPattern =
    "^arn:aws[a-z-]*:secretsmanager:[a-z0-9-]+:[0-9]{12}:secret:[A-Za-z0-9/_+=.@-]+$".r

  private val EnvVarNamePattern = "^[A-Z_][A-Z0-9_]*$".r

  /** Validate and wrap a Secrets Manager ARN. */
  def arn(value: String): SecretRef =
    require(
      ArnPattern.matches(value),
      "api-key-secret-arn must be an AWS Secrets Manager secret ARN"
    )
    SecretsManagerArn(value)

  /** Validate and wrap an environment-variable name (uppercase, digits, underscore). */
  def envVar(value: String): SecretRef =
    require(
      EnvVarNamePattern.matches(value) && value.length <= 128,
      "api-key-env-var must be an environment variable NAME matching [A-Z_][A-Z0-9_]*, " +
        "not a key value"
    )
    EnvVarName(value)

  /**
   * Read an optional secret reference from a config block containing `api-key-secret-arn` and
   * `api-key-env-var`. Empty strings mean unset. Setting both is rejected. Error messages never
   * echo the configured value.
   */
  def fromConfig(c: Config): Option[SecretRef] =
    val arnValue = c.getString("api-key-secret-arn").trim
    val envValue = c.getString("api-key-env-var").trim
    (arnValue.nonEmpty, envValue.nonEmpty) match
      case (true, true) =>
        throw new IllegalArgumentException(
          "Set only one of api-key-secret-arn or api-key-env-var, not both"
        )
      case (true, false) => Some(arn(arnValue))
      case (false, true) => Some(envVar(envValue))
      case _             => None

/** Bounds for LLM call settings. */
object LlmBounds:
  val MaxConcurrentCalls: Int = 64
  val MaxOutputTokens: Int    = 8192

/** Bounds for embedding settings. Same range as the S3 Vectors index contract. */
object EmbeddingBounds:
  val MinDimensions: Int = S3VectorsConfig.MinDimensions
  val MaxDimensions: Int = S3VectorsConfig.MaxDimensions

/**
 * Runtime LLM configuration contract (human decision 2026-11-24).
 *
 * Default: Amazon Bedrock, `us.anthropic.claude-haiku-5-5` (US geo inference profile). Approved
 * fallback: the Anthropic API, whose key is referenced only by Secrets Manager ARN or env-var NAME.
 */
case class LlmConfig(
  provider: LlmProvider,
  modelId: String,
  region: String,
  maxConcurrentCalls: Int,
  maxOutputTokens: Int,
  anthropicApiKey: Option[SecretRef]
):

  require(modelId.trim.nonEmpty, "llm.modelId must not be empty")
  require(region.trim.nonEmpty, "llm.region must not be empty")

  require(
    maxConcurrentCalls >= 1 && maxConcurrentCalls <= LlmBounds.MaxConcurrentCalls,
    s"llm.maxConcurrentCalls must be in [1, ${LlmBounds.MaxConcurrentCalls}], " +
      s"got $maxConcurrentCalls"
  )

  require(
    maxOutputTokens >= 1 && maxOutputTokens <= LlmBounds.MaxOutputTokens,
    s"llm.maxOutputTokens must be in [1, ${LlmBounds.MaxOutputTokens}], got $maxOutputTokens"
  )

  if provider == LlmProvider.Anthropic then
    require(
      anthropicApiKey.isDefined,
      "llm.provider=anthropic requires llm.anthropic.api-key-secret-arn or " +
        "llm.anthropic.api-key-env-var"
    )
    require(
      !LlmConfig.BedrockModelIdPattern.matches(modelId),
      "llm.provider=anthropic requires an Anthropic API model ID, " +
        "but llm.modelId is a Bedrock model or inference-profile ID"
    )

object LlmConfig:

  /** Bedrock Anthropic model IDs: optional geo prefix, then the `anthropic.` namespace. */
  private[core] val BedrockModelIdPattern = "^([a-z]{2,4}\\.)?anthropic\\..*$".r

/**
 * Query-time embedding model configuration contract (human decision 2026-11-24).
 *
 * Default: Amazon Bedrock Titan Text Embeddings v2 (`amazon.titan-embed-text-v2:0`), 1024
 * dimensions, us-east-2. Approved fallback: Voyage AI, key referenced only by Secrets Manager ARN
 * or env-var NAME. `dimensions` must equal `s3-vectors.dimensions` (checked by [[AppConfig.load]]).
 * Which index a model may query is decided separately by [[S3VectorsConfig.isCompatibleModel]].
 */
case class EmbeddingConfig(
  provider: EmbeddingProvider,
  modelId: String,
  dimensions: Int,
  region: String,
  voyageApiKey: Option[SecretRef]
):

  require(modelId.trim.nonEmpty, "embedding.modelId must not be empty")
  require(region.trim.nonEmpty, "embedding.region must not be empty")

  require(
    dimensions >= EmbeddingBounds.MinDimensions && dimensions <= EmbeddingBounds.MaxDimensions,
    s"embedding.dimensions must be in [${EmbeddingBounds.MinDimensions}, " +
      s"${EmbeddingBounds.MaxDimensions}], got $dimensions"
  )

  if provider == EmbeddingProvider.Voyage then
    require(
      voyageApiKey.isDefined,
      "embedding.provider=voyage requires embedding.voyage.api-key-secret-arn or " +
        "embedding.voyage.api-key-env-var"
    )
    require(
      !modelId.startsWith("amazon."),
      "embedding.provider=voyage requires a Voyage model ID, " +
        "but embedding.modelId is a Bedrock model ID"
    )

/** Answer synthesis configuration contract. */
case class SynthesisConfig(mode: SynthesisMode)

/** Reads the `llm`, `embedding`, and `synthesis` blocks of the `scholarchain` config root. */
object ModelConfigLoader:

  /** Read the `llm` block (relative to the `scholarchain` root). */
  def llm(sc: Config): LlmConfig =
    LlmConfig(
      provider = LlmProvider.parse(sc.getString("llm.provider").trim),
      modelId = sc.getString("llm.model-id"),
      region = sc.getString("llm.region"),
      maxConcurrentCalls = sc.getInt("llm.max-concurrent-calls"),
      maxOutputTokens = sc.getInt("llm.max-output-tokens"),
      anthropicApiKey = SecretRef.fromConfig(sc.getConfig("llm.anthropic"))
    )

  /** Read the `embedding` block (relative to the `scholarchain` root). */
  def embedding(sc: Config): EmbeddingConfig =
    EmbeddingConfig(
      provider = EmbeddingProvider.parse(sc.getString("embedding.provider").trim),
      modelId = sc.getString("embedding.model-id"),
      dimensions = sc.getInt("embedding.dimensions"),
      region = sc.getString("embedding.region"),
      voyageApiKey = SecretRef.fromConfig(sc.getConfig("embedding.voyage"))
    )

  /** Read the `synthesis` block (relative to the `scholarchain` root). */
  def synthesis(sc: Config): SynthesisConfig =
    SynthesisConfig(mode = SynthesisMode.parse(sc.getString("synthesis.mode").trim))
