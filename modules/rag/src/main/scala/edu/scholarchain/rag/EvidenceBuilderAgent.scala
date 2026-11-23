package edu.scholarchain.rag

import edu.scholarchain.core.*

/** Assembles an EvidenceBundle from metrics, paths, communities, and semantic context. */
class EvidenceBuilderAgent:

  private val defaultMaxItems = 100

  def build(
    queryId: String,
    metrics: Vector[ResearcherMetrics],
    paths: Vector[PathResult],
    communities: Vector[CommunityInfo],
    semanticContext: SemanticContextBundle,
    resolvedResearchers: Vector[ResolvedResearcher],
    runId: String
  ): EvidenceBundle =
    build(
      queryId,
      metrics,
      paths,
      communities,
      semanticContext,
      resolvedResearchers,
      runId,
      defaultMaxItems
    )

  def build(
    queryId: String,
    metrics: Vector[ResearcherMetrics],
    paths: Vector[PathResult],
    communities: Vector[CommunityInfo],
    semanticContext: SemanticContextBundle,
    resolvedResearchers: Vector[ResolvedResearcher],
    runId: String,
    maxItems: Int
  ): EvidenceBundle =
    val now = java.time.Instant.now().toString

    val metricItems = metrics.map { m =>
      EvidenceItem(
        evidenceId = s"ev-metric-${m.researcherId}",
        evidenceType = EvidenceType.MetricRecord,
        sourceEntityId = m.researcherId,
        content = formatMetrics(m),
        score = 1.0,
        provenance = Provenance(
          sourceDataset = "metrics-store",
          sourceRecordId = m.researcherId,
          pipelineRunId = runId,
          schemaVersion = "v1",
          timestamp = now
        )
      )
    }

    val pathItems = paths.flatMap { p =>
      p.path.map { pathNodes =>
        EvidenceItem(
          evidenceId = s"ev-path-${p.fromId}-${p.toId}",
          evidenceType = EvidenceType.GraphPath,
          sourceEntityId = p.fromId,
          content = s"Path from ${p.fromId} to ${p.toId}: ${pathNodes.mkString(" -> ")}",
          score = 1.0,
          provenance = Provenance(
            sourceDataset = "graph-store",
            sourceRecordId = s"${p.fromId}-${p.toId}",
            pipelineRunId = runId,
            schemaVersion = "v1",
            timestamp = now
          )
        )
      }
    }

    val allSemanticContexts =
      semanticContext.researcherContexts ++
        semanticContext.communityContexts ++
        semanticContext.pathContexts ++
        semanticContext.paperContexts ++
        semanticContext.topicContexts

    val textItems = allSemanticContexts.map { ctx =>
      EvidenceItem(
        evidenceId = s"ev-text-${ctx.contextId}",
        evidenceType = EvidenceType.TextChunk,
        sourceEntityId = ctx.entityId,
        content = ctx.text,
        score = ctx.vectorScore,
        provenance = ctx.provenance
      )
    }.toVector

    val allItems     = (metricItems ++ pathItems ++ textItems).take(maxItems)
    val totalScore   = if allItems.nonEmpty then allItems.map(_.score).sum / allItems.size else 0.0
    val clampedScore = math.max(0.0, math.min(1.0, totalScore))

    EvidenceBundle(
      bundleId = s"bundle-$queryId",
      queryId = queryId,
      items = allItems,
      totalScore = clampedScore,
      provenance = Provenance(
        sourceDataset = "evidence-builder",
        sourceRecordId = queryId,
        pipelineRunId = runId,
        schemaVersion = "v1",
        timestamp = now
      )
    )

  private def formatMetrics(m: ResearcherMetrics): String =
    s"Researcher ${m.researcherId}: publications=${m.publicationCount}, " +
      s"distinctCoauthors=${m.distinctCoauthorCount}, " +
      s"weightedDegree=${m.weightedDegree}, " +
      s"bridgeScore=${m.bridgeScore}, coreness=${m.coreness}, " +
      s"window=${m.windowStartYear}-${m.windowEndYear}"
