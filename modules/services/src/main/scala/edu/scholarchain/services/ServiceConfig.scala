package edu.scholarchain.services

/** Configuration for the ScholarChain HTTP service. */
case class ServiceConfig(
  port: Int,
  host: String,
  syncDeadlineMs: Long,
  maxRequestBodyBytes: Int,
  maxGraphDepth: Int,
  maxGraphLimit: Int
)

object ServiceConfig:

  def fromEnv(): ServiceConfig =
    ServiceConfig(
      port = sys.env.getOrElse("PORT", "8080").toInt,
      host = sys.env.getOrElse("HOST", "0.0.0.0"),
      syncDeadlineMs = sys.env.getOrElse("SYNC_DEADLINE_MS", "15000").toLong,
      maxRequestBodyBytes = sys.env.getOrElse("MAX_REQUEST_BODY_BYTES", "1048576").toInt,
      maxGraphDepth = math.min(
        3,
        math.max(1, sys.env.getOrElse("MAX_GRAPH_DEPTH", "3").toInt)
      ),
      maxGraphLimit = math.min(
        200,
        math.max(1, sys.env.getOrElse("MAX_GRAPH_LIMIT", "200").toInt)
      )
    )
