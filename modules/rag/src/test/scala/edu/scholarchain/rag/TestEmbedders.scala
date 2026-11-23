package edu.scholarchain.rag

import com.typesafe.config.{Config, ConfigFactory, ConfigResolveOptions}
import edu.scholarchain.core.*
import scala.jdk.CollectionConverters.*

/** Deterministic embedding fakes and config helpers for rag tests. No network, no paid calls. */
object TestEmbedders:

  val TitanV2: String = "amazon.titan-embed-text-v2:0"

  /**
   * reference.conf resolved with `env` as the only substitution source (process env ignored), so
   * `${?SCHOLARCHAIN_...}` overrides can be tested without mutating the real environment.
   */
  def referenceConfig(env: Map[String, String] = Map.empty): Config =
    ConfigFactory
      .parseMap(env.asJava)
      .withFallback(ConfigFactory.parseResources("reference.conf"))
      .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))

  /** Embedding config from reference.conf defaults only (independent of the process env). */
  def defaultEmbeddingConfig: EmbeddingModelConfig =
    EmbeddingModelConfig.load(referenceConfig())

  def config(queryModel: String, indexModel: String = TitanV2): EmbeddingModelConfig =
    EmbeddingModelConfig(queryModel, S3VectorsConfig(1024, DistanceMetric.Cosine, indexModel))

  /** Deterministic vector: value i is (seed + i) / 10000, so it is recognisable in assertions. */
  def fixedVector(length: Int, seed: Int = 7): Vector[Float] =
    Vector.tabulate(length)(i => (seed + i).toFloat / 10000.0f)

  /**
   * Fake embedder. Returns `fixedVector(dimensions)` and reports `reportedModel` (defaults to the
   * requested model). Records every call.
   */
  class FakeQueryEmbedder(
    dimensions: Int = 1024,
    reportedModel: Option[String] = None,
    fail: Boolean = false
  ) extends QueryEmbedder:
    var calls: Vector[(String, String)] = Vector.empty

    def embed(text: String, modelId: String): QueryEmbedding =
      calls = calls :+ (text -> modelId)
      if fail then throw new RuntimeException("fake embedding backend unavailable")
      QueryEmbedding(fixedVector(dimensions), reportedModel.getOrElse(modelId))

  /** Vector store that records every query and returns one hit per index. */
  class RecordingVectorStore extends VectorStore:
    var queries: Vector[(String, Vector[Float])] = Vector.empty

    def query(
      indexName: String,
      queryVector: Vector[Float],
      topK: Int,
      filters: Option[ujson.Value]
    ): Vector[VectorSearchResult] =
      queries = queries :+ (indexName -> queryVector)
      Vector(VectorSearchResult(s"key-$indexName", 0.9, "doc", "R001", s"s3://b/$indexName"))

  class ConstTextStore extends TextStore:
    def getText(s3Uri: String): Option[String] = Some(s"text for $s3Uri")
