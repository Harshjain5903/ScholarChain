package edu.scholarchain.rag

case class EmbeddingModelConfig(
  modelId: String,
  modelVersion: String,
  dimensions: Int,
  maxInputTokens: Int,
  provider: String
)

object EmbeddingModelConfig:

  def fromEnv(): EmbeddingModelConfig =
    val modelId = sys.env.getOrElse(
      "EMBEDDING_MODEL_ID",
      "amazon.titan-embed-text-v2:0"
    )
    modelId match
      case id if id.contains("titan-embed-text-v2") =>
        EmbeddingModelConfig(id, "2.0", 1024, 8192, "amazon")
      case id if id.contains("titan-embed-text-v1") =>
        EmbeddingModelConfig(id, "1.0", 1536, 8192, "amazon")
      case id if id.contains("cohere.embed") =>
        EmbeddingModelConfig(id, "3.0", 1024, 2048, "cohere")
      case id =>
        EmbeddingModelConfig(id, "unknown", 1024, 8192, "unknown")

  val Default: EmbeddingModelConfig = fromEnv()
