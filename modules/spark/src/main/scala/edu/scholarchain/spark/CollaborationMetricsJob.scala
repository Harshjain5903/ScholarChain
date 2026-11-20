package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext

/**
 * Job 3: Researcher collaboration breadth and team-size statistics.
 *
 * For each researcher in the given time window compute: P_r (publicationCount), C_r
 * (distinctCoauthorCount), T_r (totalCoauthorOccurrences), A_r (averageCoauthorsPerPaper), M_r
 * (maximumCoauthorsOnOnePaper), W_r (weightedDegree = sum of edge weights).
 *
 * Component, community, bridge, and coreness fields are left as defaults and filled by later jobs.
 */
object CollaborationMetricsJob:

  /**
   * Per-researcher authorship info for a single work: how many coauthors that researcher had on
   * this publication (teamSize - 1, clamped to 0).
   */
  case class AuthorshipInfo(
    researcherId: String,
    workId: String,
    coauthorsOnPaper: Int
  )

  /**
   * Compute researcher metrics from coauthor edges and authorship information.
   *
   * @param edges
   *   aggregated CoauthorEdge RDD (from CanonicalGraphBuilder)
   * @param authorships
   *   per-researcher per-work coauthor count
   */
  def compute(
    sc: SparkContext,
    edges: RDD[CoauthorEdge],
    authorships: RDD[AuthorshipInfo],
    windowStartYear: Int,
    windowEndYear: Int,
    runId: String,
    schemaVersion: String,
    timestamp: String
  ): RDD[ResearcherMetrics] =
    val edgeMetrics: RDD[(String, (Int, Double))] = edges.flatMap { e =>
      Iterator(
        (e.researcherA, (1, e.jointPublicationCount.toDouble)),
        (e.researcherB, (1, e.jointPublicationCount.toDouble))
      )
    }

    val perResearcherEdge: RDD[(String, (Int, Double))] =
      edgeMetrics.reduceByKey { case ((c1, w1), (c2, w2)) =>
        (c1 + c2, w1 + w2)
      }

    val paperStats: RDD[(String, (Int, Int, Int))] = authorships
      .map(a => (a.researcherId, (1, a.coauthorsOnPaper, a.coauthorsOnPaper)))
      .reduceByKey { case ((cnt1, sum1, max1), (cnt2, sum2, max2)) =>
        (cnt1 + cnt2, sum1 + sum2, math.max(max1, max2))
      }

    val allResearchers = perResearcherEdge
      .fullOuterJoin(paperStats)
      .map { case (rid, (edgeOpt, paperOpt)) =>
        val (distinctCoauthors, weightedDeg) = edgeOpt.getOrElse((0, 0.0))
        val (pubCount, totalCoauthorOcc, maxCoauthors) = paperOpt.getOrElse((0, 0, 0))
        val avgCoauthors =
          if pubCount > 0 then totalCoauthorOcc.toDouble / pubCount.toDouble
          else 0.0
        val prov = GraphUtils.makeProv(runId, schemaVersion, rid, timestamp)
        ResearcherMetrics(
          researcherId = rid,
          publicationCount = pubCount,
          distinctCoauthorCount = distinctCoauthors,
          totalCoauthorOccurrences = totalCoauthorOcc,
          averageCoauthorsPerPaper = avgCoauthors,
          maximumCoauthorsOnOnePaper = maxCoauthors,
          weightedDegree = weightedDeg,
          componentId = None,
          communityId = None,
          bridgeScore = 0.0,
          coreness = 0,
          windowStartYear = windowStartYear,
          windowEndYear = windowEndYear,
          provenance = prov
        )
      }

    allResearchers
