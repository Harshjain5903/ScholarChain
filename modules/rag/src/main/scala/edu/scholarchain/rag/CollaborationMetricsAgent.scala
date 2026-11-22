package edu.scholarchain.rag

import edu.scholarchain.core.*

/** Retrieves stored collaboration metrics. Never fabricates values for missing researchers. */
class CollaborationMetricsAgent(metrics: MetricsStore):

  def retrieve(
      researcherIds: Vector[String],
      windowStart: Int,
      windowEnd: Int
  ): Vector[ResearcherMetrics] =
    researcherIds.flatMap(id => metrics.getMetrics(id, windowStart, windowEnd))
