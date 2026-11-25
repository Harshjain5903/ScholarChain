package edu.scholarchain.rag

import edu.scholarchain.core.*
import edu.scholarchain.storage.{NeptuneStorageClient, RankableResearcherMetric}

/**
 * Researchers ranked by one allowlisted metric for one window.
 *
 * @param ranked
 *   stored metrics, highest first; position `i` is rank `i + 1`
 */
final case class MetricRanking(
  metric: RankableResearcherMetric,
  windowStart: Int,
  windowEnd: Int,
  ranked: Vector[ResearcherMetrics]
)

/**
 * Retrieves stored collaboration metrics. Never fabricates values for missing researchers.
 *
 * The returned [[ResearcherMetrics]] are exactly the stored values: publications P_r, distinct
 * coauthors C_r, total coauthor occurrences T_r, average coauthors per paper A_r, maximum coauthors
 * on one paper M_r with the work IDs responsible for it, weighted degree W_r, component ID and
 * size, community ID, bridge score with its method, coreness, the window, and the provenance
 * (metric version and pipeline run ID).
 */
class CollaborationMetricsAgent(metrics: MetricsStore):

  /** Stored metrics for each ID that has them, in the order of `researcherIds`. */
  def retrieve(
    researcherIds: Vector[String],
    windowStart: Int,
    windowEnd: Int
  ): Vector[ResearcherMetrics] =
    val ids = researcherIds.filter(_.trim.nonEmpty).distinct
    val found =
      if ids.isEmpty then Map.empty else metrics.getMetricsBatch(ids, windowStart, windowEnd)
    ids.flatMap(found.get)

  /**
   * Top researchers by an allowlisted metric. `limit` is clamped to
   * `1..NeptuneStorageClient.MaxRankingLimit`. Rows from another window are dropped, and for
   * [[RankableResearcherMetric.BridgeScore]] rows whose bridge score was not computed are dropped:
   * such a 0.0 placeholder is never ranked as a measured zero.
   */
  def rank(
    metric: RankableResearcherMetric,
    windowStart: Int,
    windowEnd: Int,
    limit: Int
  ): MetricRanking =
    val bounded = CollaborationMetricsAgent.clampLimit(limit)
    val rows = metrics
      .topResearchers(metric, windowStart, windowEnd, bounded)
      .filter(m => m.windowStartYear == windowStart && m.windowEndYear == windowEnd)
      .filterNot(m =>
        metric == RankableResearcherMetric.BridgeScore &&
          m.bridgeScoreMethod == BridgeScoreMethod.NotComputed
      )
      .distinctBy(_.researcherId)
      .take(bounded)
    MetricRanking(metric, windowStart, windowEnd, rows)

object CollaborationMetricsAgent:

  def clampLimit(limit: Int): Int =
    math.max(1, math.min(limit, NeptuneStorageClient.MaxRankingLimit))
