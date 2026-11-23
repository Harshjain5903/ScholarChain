package edu.scholarchain.rag

import edu.scholarchain.core.Provenance
import upickle.default.*

case class SemanticContext(
  contextId: String,
  entityId: String,
  documentType: String,
  text: String,
  textS3Uri: String,
  vectorScore: Double,
  embeddingModel: String,
  windowStartYear: Int,
  windowEndYear: Int,
  provenance: Provenance
) derives ReadWriter

/** Why semantic retrieval abstained. `code` is the stable reason code. */
enum SemanticFailureReason(val code: String):
  /** Query model id differs from the model that populated the indexes. */
  case EmbeddingModelMismatch extends SemanticFailureReason("EMBEDDING_MODEL_MISMATCH")

  /** Query vector length differs from the index dimension. */
  case EmbeddingDimensionMismatch extends SemanticFailureReason("EMBEDDING_DIMENSION_MISMATCH")

  /** The embedding backend failed; no vector was produced. */
  case EmbeddingUnavailable extends SemanticFailureReason("EMBEDDING_UNAVAILABLE")

object SemanticFailureReason:

  def fromCode(code: String): Option[SemanticFailureReason] =
    SemanticFailureReason.values.find(_.code == code)

  given ReadWriter[SemanticFailureReason] =
    readwriter[String].bimap(
      _.code,
      c =>
        fromCode(c).getOrElse(
          throw new IllegalArgumentException(s"unknown semantic failure reason '$c'")
        )
    )

/** Typed abstention of semantic retrieval; no vector index was queried when this is present. */
case class SemanticRetrievalFailure(
  reason: SemanticFailureReason,
  detail: String
) derives ReadWriter

case class SemanticContextBundle(
  queryId: String,
  researcherContexts: List[SemanticContext],
  communityContexts: List[SemanticContext],
  pathContexts: List[SemanticContext],
  paperContexts: List[SemanticContext],
  topicContexts: List[SemanticContext],
  totalTokenEstimate: Int,
  truncated: Boolean,
  failure: Option[SemanticRetrievalFailure] = None
) derives ReadWriter:

  /** True when retrieval abstained (contexts are empty by construction). */
  def abstained: Boolean = failure.isDefined

object SemanticContextFormat:

  def rankByRelevance(
    contexts: List[SemanticContext]
  ): List[SemanticContext] =
    contexts.sortBy(-_.vectorScore)

  def trimToTokenBudget(
    bundle: SemanticContextBundle,
    maxTokens: Int
  ): SemanticContextBundle =
    var remaining = maxTokens

    def takeWithinBudget(
      contexts: List[SemanticContext]
    ): List[SemanticContext] =
      val sorted = rankByRelevance(contexts)
      val taken  = scala.collection.mutable.ListBuffer.empty[SemanticContext]
      for ctx <- sorted do
        val tokens = ChunkingPolicy.estimateTokens(ctx.text)
        if remaining >= tokens then
          taken += ctx
          remaining -= tokens
      taken.toList

    val trimmedResearcher = takeWithinBudget(bundle.researcherContexts)
    val trimmedCommunity  = takeWithinBudget(bundle.communityContexts)
    val trimmedPath       = takeWithinBudget(bundle.pathContexts)
    val trimmedPaper      = takeWithinBudget(bundle.paperContexts)
    val trimmedTopic      = takeWithinBudget(bundle.topicContexts)

    val totalUsed = maxTokens - remaining
    val originalCount = bundle.researcherContexts.size +
      bundle.communityContexts.size +
      bundle.pathContexts.size +
      bundle.paperContexts.size +
      bundle.topicContexts.size
    val trimmedCount = trimmedResearcher.size +
      trimmedCommunity.size +
      trimmedPath.size +
      trimmedPaper.size +
      trimmedTopic.size
    val wasTruncated = originalCount > trimmedCount

    bundle.copy(
      researcherContexts = trimmedResearcher,
      communityContexts = trimmedCommunity,
      pathContexts = trimmedPath,
      paperContexts = trimmedPaper,
      topicContexts = trimmedTopic,
      totalTokenEstimate = totalUsed,
      truncated = wasTruncated
    )
