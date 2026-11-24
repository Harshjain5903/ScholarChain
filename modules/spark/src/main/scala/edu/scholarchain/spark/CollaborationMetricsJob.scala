package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext

/**
 * Job 3: Researcher collaboration breadth and team-size statistics.
 *
 * For each researcher in the given time window compute: P_r (publicationCount), C_r
 * (distinctCoauthorCount), T_r (totalCoauthorOccurrences), A_r (averageCoauthorsPerPaper), M_r
 * (maximumCoauthorsOnOnePaper), W_r (weightedDegree = sum of edge weights), and the evidence behind
 * M_r: `maximumTeamWorkIds`, the work IDs on which the researcher had M_r coauthors (sorted, capped
 * at [[MaxTeamWorkIdsCap]], empty when M_r is 0).
 *
 * Component, community, bridge, and coreness fields are left as defaults and filled by later jobs.
 */
object CollaborationMetricsJob:

  /** Job name recorded in the `producer` of the metrics provenance. */
  val Job: String = "CollaborationMetricsJob"

  /** Transformation step of the metrics provenance. */
  val Step: String = "researcher-metrics"

  /**
   * Maximum number of work IDs kept in `maximumTeamWorkIds`. When more works tie for M_r, the
   * lexicographically smallest [[MaxTeamWorkIdsCap]] are kept, so the list is deterministic and a
   * prolific researcher cannot blow up a metrics row.
   */
  val MaxTeamWorkIdsCap: Int = 20

  /** (M_r, work IDs achieving it), merged associatively: higher max wins, ties unite the IDs. */
  private[spark] def mergeMaxTeam(
    a: (Int, Vector[String]),
    b: (Int, Vector[String])
  ): (Int, Vector[String]) =
    if a._1 > b._1 then a
    else if b._1 > a._1 then b
    else (a._1, (a._2 ++ b._2).distinct.sorted.take(MaxTeamWorkIdsCap))

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
   * @param lineage
   *   provenance lineage; `sourceDataset` should be the publication teams' source dataset
   */
  def compute(
    sc: SparkContext,
    edges: RDD[CoauthorEdge],
    authorships: RDD[AuthorshipInfo],
    windowStartYear: Int,
    windowEndYear: Int,
    lineage: Lineage
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

    // (publications, total coauthor occurrences, (M_r, works achieving M_r)).
    val paperStats: RDD[(String, (Int, Int, (Int, Vector[String])))] = authorships
      .map { a =>
        val works = if a.coauthorsOnPaper > 0 then Vector(a.workId) else Vector.empty
        (a.researcherId, (1, a.coauthorsOnPaper, (a.coauthorsOnPaper, works)))
      }
      .reduceByKey { case ((cnt1, sum1, max1), (cnt2, sum2, max2)) =>
        (cnt1 + cnt2, sum1 + sum2, mergeMaxTeam(max1, max2))
      }

    val allResearchers = perResearcherEdge
      .fullOuterJoin(paperStats)
      .map { case (rid, (edgeOpt, paperOpt)) =>
        val (distinctCoauthors, weightedDeg) = edgeOpt.getOrElse((0, 0.0))
        val (pubCount, totalCoauthorOcc, (maxCoauthors, maxWorks)) =
          paperOpt.getOrElse((0, 0, (0, Vector.empty[String])))
        val avgCoauthors =
          if pubCount > 0 then totalCoauthorOcc.toDouble / pubCount.toDouble
          else 0.0
        val prov = lineage.prov(Job, Step, rid)
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
          provenance = prov,
          maximumTeamWorkIds = if maxCoauthors > 0 then maxWorks else Vector.empty
        )
      }

    allResearchers
