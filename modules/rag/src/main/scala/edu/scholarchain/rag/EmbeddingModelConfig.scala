package edu.scholarchain.rag

import com.typesafe.config.Config
import edu.scholarchain.core.{AppConfig, S3VectorsConfig}

/**
 * RAG view of the embedding configuration. Holds no settings of its own: both values come from the
 * core `AppConfig`.
 *
 *   - `queryModelId` is `scholarchain.models.embedding-model-id` (env
 *     `SCHOLARCHAIN_EMBEDDING_MODEL_ID`): the model used to embed runtime query text.
 *   - `index` is `scholarchain.s3-vectors` (env `SCHOLARCHAIN_S3_VECTORS_DIMENSIONS`,
 *     `SCHOLARCHAIN_S3_VECTORS_DISTANCE_METRIC`, `SCHOLARCHAIN_S3_VECTORS_EMBEDDING_MODEL_ID`): the
 *     dimension, metric and model that populated the S3 Vectors indexes.
 *
 * Compatibility is decided only by `S3VectorsConfig.isCompatibleModel` (exact model-id match) and
 * `isCompatibleVector` (exact dimension match). There is no model-name pattern table and no default
 * dimension for unknown models: an unknown query model is incompatible unless the index model id is
 * exactly the same string.
 *
 * Fallback-model policy (human decision 2026-11-22): the indexes are Titan v2 (1024, cosine). A
 * fallback such as Voyage may only be used against a separate index set that was re-embedded with
 * that model, and the index model id must be set to that model's id. Changing only the query model
 * makes `isQueryModelCompatible` false, so no Titan index is ever queried with Voyage vectors.
 */
case class EmbeddingModelConfig(queryModelId: String, index: S3VectorsConfig):

  /** Query-time embedding model id. */
  def modelId: String = queryModelId

  /** Dimension every query vector must have (the index dimension). */
  def dimensions: Int = index.dimensions

  /** True when vectors from the configured query model may be used against the indexes. */
  def isQueryModelCompatible: Boolean = index.isCompatibleModel(queryModelId)

object EmbeddingModelConfig:

  def fromAppConfig(app: AppConfig): EmbeddingModelConfig =
    EmbeddingModelConfig(queryModelId = app.models.embeddingModelId, index = app.s3Vectors)

  /** Load through the default config chain (reference.conf plus `SCHOLARCHAIN_*` env overrides). */
  def load(): EmbeddingModelConfig = fromAppConfig(AppConfig.load())

  /** Load from an explicit Config instance (useful for testing). */
  def load(config: Config): EmbeddingModelConfig = fromAppConfig(AppConfig.load(config))

  lazy val Default: EmbeddingModelConfig = load()
