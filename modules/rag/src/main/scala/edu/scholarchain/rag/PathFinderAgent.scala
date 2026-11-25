package edu.scholarchain.rag

import edu.scholarchain.core.*
import upickle.default.*

case class PathResult(
  fromId: String,
  toId: String,
  path: Option[Vector[String]],
  coauthorEdges: Vector[CoauthorEdge]
) derives ReadWriter

/**
 * Finds shortest coauthorship paths between researcher pairs, and direct coauthors.
 *
 * @param defaultMaxDepth
 *   path depth used when the caller gives none; every depth is clamped to 1..10 (the graph store
 *   may bound it further)
 */
class PathFinderAgent(graph: GraphStore, defaultMaxDepth: Int = 6):

  /** Upper bound on pairs searched per call (paths are pairwise and each is a graph query). */
  val MaxPairs: Int = 10

  def findPaths(
    pairs: Vector[(String, String)],
    maxDepth: Int = defaultMaxDepth
  ): Vector[PathResult] =
    val boundedDepth = math.max(1, math.min(10, maxDepth))
    pairs.take(MaxPairs).map { case (fromId, toId) =>
      PathResult(
        fromId = fromId,
        toId = toId,
        path = graph.shortestPath(fromId, toId, boundedDepth),
        coauthorEdges = Vector.empty
      )
    }

  /** Strongest direct coauthor edges of a researcher; `limit` is clamped to 1..100. */
  def directCoauthors(researcherId: String, limit: Int): Vector[CoauthorEdge] =
    graph.getDirectCoauthors(researcherId, math.max(1, math.min(limit, 100)))
