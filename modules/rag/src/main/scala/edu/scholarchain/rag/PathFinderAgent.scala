package edu.scholarchain.rag

import edu.scholarchain.core.*
import upickle.default.*

case class PathResult(
  fromId: String,
  toId: String,
  path: Option[Vector[String]],
  coauthorEdges: Vector[CoauthorEdge]
) derives ReadWriter

/** Finds shortest paths between researcher pairs in the collaboration graph. */
class PathFinderAgent(graph: GraphStore):

  def findPaths(
    pairs: Vector[(String, String)],
    maxDepth: Int = 6
  ): Vector[PathResult] =
    val boundedDepth = math.max(1, math.min(10, maxDepth))
    pairs.map { case (fromId, toId) =>
      val pathOpt      = graph.shortestPath(fromId, toId, boundedDepth)
      val coauthorEdgs = graph.getDirectCoauthors(fromId, 100)
      PathResult(
        fromId = fromId,
        toId = toId,
        path = pathOpt,
        coauthorEdges = coauthorEdgs
      )
    }
