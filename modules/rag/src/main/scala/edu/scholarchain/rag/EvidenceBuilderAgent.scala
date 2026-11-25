package edu.scholarchain.rag

import edu.scholarchain.core.*
import edu.scholarchain.storage.RankableResearcherMetric

/**
 * Assembles an EvidenceBundle from metrics, coauthor edges, paths, communities, and semantic
 * context.
 *
 * Items are ordered metrics, coauthor edges, paths, communities, then text chunks, truncated to
 * `maxItems`, and then numbered: the item at position `i` gets `IdGen.evidenceId(queryId, i)`. IDs
 * are therefore deterministic for a given query and retrieval result, and are the only IDs a claim
 * may cite. For a ranking (`rankedBy`), there is one metric item per ranked researcher, in rank
 * order, carrying `rank` and `rankedBy`. Structured content uses the [[EvidenceContent]] formats.
 */
class EvidenceBuilderAgent:

  private val defaultMaxItems = 100

  /** Upper bound on coauthor-edge items per bundle (strongest edges first). */
  val MaxCoauthorEdgeItems: Int = 50

  def build(
    queryId: String,
    metrics: Vector[ResearcherMetrics],
    paths: Vector[PathResult],
    communities: Vector[CommunityInfo],
    semanticContext: SemanticContextBundle,
    resolvedResearchers: Vector[ResolvedResearcher],
    runId: String,
    maxItems: Int = defaultMaxItems,
    coauthorEdges: Vector[CoauthorEdge] = Vector.empty,
    rankedBy: Option[RankableResearcherMetric] = None
  ): EvidenceBundle =
    val now   = java.time.Instant.now().toString
    val limit = math.max(0, math.min(maxItems, IdGen.MaxEvidenceOrdinal + 1))

    def prov(dataset: String, record: String, schema: String = "v1"): Provenance =
      Provenance(
        sourceDataset = dataset,
        sourceRecordId = record,
        pipelineRunId = runId,
        schemaVersion = schema,
        timestamp = now
      )

    // Evidence before numbering: (type, sourceEntityId, content, score, provenance)
    type Draft = (EvidenceType, String, String, Double, Provenance)

    // Ranked metrics keep their order; position i is rank i + 1.
    val metricDrafts: Vector[Draft] = metrics.zipWithIndex.map { (m, i) =>
      val content = rankedBy match
        case Some(metric) => EvidenceContent.metric(m, Some(i + 1), Some(metric.wireName))
        case None         => EvidenceContent.metric(m)
      (EvidenceType.MetricRecord, m.researcherId, content, 1.0, m.provenance)
    }

    val edges = (coauthorEdges ++ paths.flatMap(_.coauthorEdges))
      .distinctBy(e => (e.researcherA, e.researcherB))
      .sortBy(e => (-e.jointPublicationCount, e.researcherA, e.researcherB))
      .take(MaxCoauthorEdgeItems)
    val edgeDrafts: Vector[Draft] = edges.map { e =>
      (EvidenceType.GraphPath, e.researcherA, EvidenceContent.coauthor(e), 1.0, e.provenance)
    }

    val pathDrafts: Vector[Draft] = paths.flatMap { p =>
      p.path.map { nodes =>
        (
          EvidenceType.GraphPath,
          p.fromId,
          EvidenceContent.path(p.fromId, p.toId, nodes),
          1.0,
          prov("graph-store", s"${p.fromId}|${p.toId}")
        )
      }
    }

    val communityDrafts: Vector[Draft] = communities.map { c =>
      (
        EvidenceType.MetricRecord,
        c.communityId,
        EvidenceContent.community(c),
        1.0,
        prov("community-analysis", c.communityId)
      )
    }

    val allSemanticContexts =
      semanticContext.researcherContexts ++
        semanticContext.communityContexts ++
        semanticContext.pathContexts ++
        semanticContext.paperContexts ++
        semanticContext.topicContexts

    val textDrafts: Vector[Draft] = allSemanticContexts.map { ctx =>
      (EvidenceType.TextChunk, ctx.entityId, ctx.text, ctx.vectorScore, ctx.provenance)
    }.toVector

    val drafts = (metricDrafts ++ edgeDrafts ++ pathDrafts ++ communityDrafts ++ textDrafts)
      .take(limit)

    val allItems = drafts.zipWithIndex.map { case ((tpe, entity, content, score, p), i) =>
      EvidenceItem(
        evidenceId = IdGen.evidenceId(queryId, i),
        evidenceType = tpe,
        sourceEntityId = entity,
        content = content,
        score = clamp(score),
        provenance = p
      )
    }

    val totalScore = if allItems.nonEmpty then allItems.map(_.score).sum / allItems.size else 0.0

    EvidenceBundle(
      bundleId = s"bundle-$queryId",
      queryId = queryId,
      items = allItems,
      totalScore = clamp(totalScore),
      provenance = prov("evidence-builder", queryId)
    )

  private def clamp(d: Double): Double =
    if d.isNaN then 0.0 else math.max(0.0, math.min(1.0, d))
