package edu.scholarchain.services

/**
 * Configuration for the ScholarChain HTTP service.
 *
 * @param syncDeadlineMs
 *   deadline of one synchronous query (the idempotency leader and requests without a key); also the
 *   longest a follower waits for an in-flight leader
 * @param idempotencyTtlMs
 *   how long a completed `Idempotency-Key` response is replayed
 * @param idempotencyMaxEntries
 *   most keys kept; the least recently used completed entry is evicted first
 * @param idempotencyMaxKeyLength
 *   longest accepted `Idempotency-Key` header value (longer keys are a 400)
 * @param resultTtlDays
 *   retention of durable query results, job records and audit records
 * @param maxConcurrentQueries
 *   queries executing at once; further queries get 503 SERVICE_UNAVAILABLE
 */
case class ServiceConfig(
  port: Int,
  host: String,
  syncDeadlineMs: Long,
  maxRequestBodyBytes: Int,
  maxGraphDepth: Int,
  maxGraphLimit: Int,
  idempotencyTtlMs: Long = ServiceConfig.DefaultIdempotencyTtlMs,
  idempotencyMaxEntries: Int = ServiceConfig.DefaultIdempotencyMaxEntries,
  idempotencyMaxKeyLength: Int = ServiceConfig.DefaultIdempotencyMaxKeyLength,
  resultTtlDays: Int = ServiceConfig.DefaultResultTtlDays,
  maxConcurrentQueries: Int = ServiceConfig.DefaultMaxConcurrentQueries
)

object ServiceConfig:

  val DefaultIdempotencyTtlMs: Long       = 24L * 60 * 60 * 1000
  val DefaultIdempotencyMaxEntries: Int   = 10000
  val DefaultIdempotencyMaxKeyLength: Int = 255
  val DefaultResultTtlDays: Int           = 30
  val DefaultMaxConcurrentQueries: Int    = 16

  def fromEnv(): ServiceConfig = fromEnv(sys.env)

  def fromEnv(env: Map[String, String]): ServiceConfig =
    def int(name: String, default: Int): Int = env.get(name).map(_.trim.toInt).getOrElse(default)
    def long(name: String, default: Long): Long =
      env.get(name).map(_.trim.toLong).getOrElse(default)
    def clamp(v: Int, lo: Int, hi: Int): Int      = math.min(hi, math.max(lo, v))
    def clampL(v: Long, lo: Long, hi: Long): Long = math.min(hi, math.max(lo, v))
    ServiceConfig(
      port = int("PORT", 8080),
      host = env.getOrElse("HOST", "0.0.0.0"),
      syncDeadlineMs = long("SYNC_DEADLINE_MS", 15000L),
      maxRequestBodyBytes = int("MAX_REQUEST_BODY_BYTES", 1048576),
      maxGraphDepth = clamp(int("MAX_GRAPH_DEPTH", 3), 1, 3),
      maxGraphLimit = clamp(int("MAX_GRAPH_LIMIT", 200), 1, 200),
      idempotencyTtlMs =
        clampL(long("IDEMPOTENCY_TTL_SECONDS", DefaultIdempotencyTtlMs / 1000), 1L, 7L * 86400) *
          1000,
      idempotencyMaxEntries =
        clamp(int("IDEMPOTENCY_MAX_ENTRIES", DefaultIdempotencyMaxEntries), 1, 100000),
      idempotencyMaxKeyLength =
        clamp(int("IDEMPOTENCY_MAX_KEY_LENGTH", DefaultIdempotencyMaxKeyLength), 1, 1024),
      resultTtlDays = clamp(int("RESULT_TTL_DAYS", DefaultResultTtlDays), 1, 365),
      maxConcurrentQueries =
        clamp(int("MAX_CONCURRENT_QUERIES", DefaultMaxConcurrentQueries), 1, 256)
    )
