package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext

/**
 * Job 9: Temporal collaboration analysis.
 *
 * Recomputes selected metrics over configurable time windows (e.g., 5-year sliding windows). Tracks
 * changes in distinct coauthors, team sizes, component membership, and community membership across
 * windows.
 */
object TemporalAnalysisJob:

  /** A single time window specification. */
  case class TimeWindow(startYear: Int, endYear: Int)

  /** Metric snapshot for a researcher in a specific time window. */
  case class TemporalSnapshot(
    researcherId: String,
    windowStartYear: Int,
    windowEndYear: Int,
    publicationCount: Int,
    distinctCoauthorCount: Int,
    weightedDegree: Double,
    provenance: Provenance
  )

  /**
   * Generate time windows for the given range and window size.
   *
   * @param globalStart
   *   earliest year
   * @param globalEnd
   *   latest year
   * @param windowSize
   *   years per window
   * @param step
   *   step between window starts (default = windowSize for non-overlapping)
   */
  def generateWindows(
    globalStart: Int,
    globalEnd: Int,
    windowSize: Int,
    step: Int
  ): Seq[TimeWindow] =
    (globalStart to globalEnd by step)
      .map(s => TimeWindow(s, math.min(s + windowSize - 1, globalEnd)))
      .filter(w => w.startYear <= globalEnd)

  /**
   * Compute temporal snapshots from canonical pair-level occurrences (the pipeline entry point).
   *
   * Unlike [[compute]], which approximates a window by the span of each aggregated edge, every
   * metric here counts only collaborations whose publication year lies inside the window:
   *   - `distinctCoauthorCount`: distinct coauthors with at least one shared work in the window
   *   - `weightedDegree`: sum over those coauthors of the number of distinct shared works in the
   *     window
   *   - `publicationCount`: distinct works of the researcher in the window
   *
   * All windows are evaluated in one pass: each occurrence is assigned to every window containing
   * its year (windows are a small driver-side list), then reduced by (window, researcher).
   * Researchers with neither a work nor a collaboration in a window have no snapshot for it.
   */
  def computeFromPairs(
    sc: SparkContext,
    pairs: RDD[CanonicalGraphBuilder.CanonicalPair],
    authorships: RDD[CanonicalGraphBuilder.CanonicalAuthorship],
    windows: Seq[TimeWindow],
    runId: String,
    schemaVersion: String,
    timestamp: String
  ): RDD[TemporalSnapshot] =
    if windows.isEmpty then return sc.emptyRDD[TemporalSnapshot]
    val ws = windows.toVector
    def windowsOf(year: Int): Iterator[TimeWindow] =
      ws.iterator.filter(w => year >= w.startYear && year <= w.endYear)

    // ((window, a, b), distinct works in window) -> per researcher (distinct coauthors, weight).
    val edgeMetrics: RDD[((TimeWindow, String), (Int, Double))] = pairs
      .flatMap(p =>
        windowsOf(p.year).map(w => ((w, p.researcherA, p.researcherB), p.workId))
      )
      .distinct()
      .mapValues(_ => 1)
      .reduceByKey(_ + _)
      .flatMap { case ((w, a, b), works) =>
        Iterator(((w, a), (1, works.toDouble)), ((w, b), (1, works.toDouble)))
      }
      .reduceByKey { case ((c1, w1), (c2, w2)) => (c1 + c2, w1 + w2) }

    val pubCounts: RDD[((TimeWindow, String), Int)] = authorships
      .flatMap(a => windowsOf(a.year).map(w => ((w, a.researcherId), a.workId)))
      .distinct()
      .mapValues(_ => 1)
      .reduceByKey(_ + _)

    pubCounts
      .fullOuterJoin(edgeMetrics)
      .map { case ((w, rid), (pubsOpt, edgeOpt)) =>
        val pubs                             = pubsOpt.getOrElse(0)
        val (distinctCoauthors, weightedDeg) = edgeOpt.getOrElse((0, 0.0))
        TemporalSnapshot(
          researcherId = rid,
          windowStartYear = w.startYear,
          windowEndYear = w.endYear,
          publicationCount = pubs,
          distinctCoauthorCount = distinctCoauthors,
          weightedDegree = weightedDeg,
          provenance = GraphUtils.makeProv(
            runId,
            schemaVersion,
            s"$rid:${w.startYear}-${w.endYear}",
            timestamp
          )
        )
      }

  /**
   * Compute temporal snapshots for all researchers across all specified time windows.
   *
   * Approximation: an aggregated edge counts in every window its [first, last] collaboration span
   * overlaps, with its whole joint publication count. Prefer [[computeFromPairs]] when pair-level
   * occurrences are available (the pipeline does).
   *
   * @param allEdges
   *   the full (unfiltered by time) CoauthorEdge RDD; each edge carries year range
   * @param authorships
   *   per-researcher per-work authorship info with year
   * @param windows
   *   time windows to evaluate
   * @param runId
   *   pipeline run identifier
   * @param schemaVersion
   *   schema version for provenance
   * @param timestamp
   *   current timestamp for provenance
   */
  def compute(
    sc: SparkContext,
    allEdges: RDD[CoauthorEdge],
    authorships: RDD[(String, String, Int, Int)],
    windows: Seq[TimeWindow],
    runId: String,
    schemaVersion: String,
    timestamp: String
  ): RDD[TemporalSnapshot] =
    val snapshots = windows.map { w =>
      computeWindow(sc, allEdges, authorships, w, runId, schemaVersion, timestamp)
    }
    if snapshots.isEmpty then sc.emptyRDD[TemporalSnapshot]
    else snapshots.reduce(_ union _)

  /**
   * Compute metrics for a single time window.
   *
   * @param authorships
   *   tuples of (researcherId, workId, coauthorsOnPaper, year)
   */
  private def computeWindow(
    sc: SparkContext,
    allEdges: RDD[CoauthorEdge],
    authorships: RDD[(String, String, Int, Int)],
    window: TimeWindow,
    runId: String,
    schemaVersion: String,
    timestamp: String
  ): RDD[TemporalSnapshot] =
    val windowEdges = allEdges.filter(e =>
      e.lastCollaborationYear >= window.startYear &&
        e.firstCollaborationYear <= window.endYear
    )

    val windowAuthorships = authorships.filter { case (_, _, _, year) =>
      year >= window.startYear && year <= window.endYear
    }

    val edgeMetrics: RDD[(String, (Int, Double))] = windowEdges
      .flatMap { e =>
        Iterator(
          (e.researcherA, (1, e.jointPublicationCount.toDouble)),
          (e.researcherB, (1, e.jointPublicationCount.toDouble))
        )
      }
      .reduceByKey { case ((c1, w1), (c2, w2)) => (c1 + c2, w1 + w2) }

    val pubCounts: RDD[(String, Int)] = windowAuthorships
      .map { case (rid, workId, _, _) => (rid, workId) }
      .distinct()
      .map { case (rid, _) => (rid, 1) }
      .reduceByKey(_ + _)

    edgeMetrics
      .fullOuterJoin(pubCounts)
      .map { case (rid, (edgeOpt, pubOpt)) =>
        val (distinctCoauthors, weightedDeg) = edgeOpt.getOrElse((0, 0.0))
        val pubs                             = pubOpt.getOrElse(0)
        val prov =
          GraphUtils.makeProv(
            runId,
            schemaVersion,
            s"$rid:${window.startYear}-${window.endYear}",
            timestamp
          )
        TemporalSnapshot(
          researcherId = rid,
          windowStartYear = window.startYear,
          windowEndYear = window.endYear,
          publicationCount = pubs,
          distinctCoauthorCount = distinctCoauthors,
          weightedDegree = weightedDeg,
          provenance = prov
        )
      }
