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

case class SemanticContextBundle(
    queryId: String,
    researcherContexts: List[SemanticContext],
    communityContexts: List[SemanticContext],
    pathContexts: List[SemanticContext],
    paperContexts: List[SemanticContext],
    topicContexts: List[SemanticContext],
    totalTokenEstimate: Int,
    truncated: Boolean
) derives ReadWriter

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
      val taken = scala.collection.mutable.ListBuffer.empty[SemanticContext]
      for ctx <- sorted do
        val tokens = ChunkingPolicy.estimateTokens(ctx.text)
        if remaining >= tokens then
          taken += ctx
          remaining -= tokens
      taken.toList

    val trimmedResearcher = takeWithinBudget(bundle.researcherContexts)
    val trimmedCommunity = takeWithinBudget(bundle.communityContexts)
    val trimmedPath = takeWithinBudget(bundle.pathContexts)
    val trimmedPaper = takeWithinBudget(bundle.paperContexts)
    val trimmedTopic = takeWithinBudget(bundle.topicContexts)

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
