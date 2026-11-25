package edu.scholarchain.services

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.{CompletableFuture, CompletionException, ExecutionException, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded single-flight idempotency store keyed by the `Idempotency-Key` header (review finding
 * N58).
 *
 *   - Single flight: the first request for a key becomes the leader and computes the response;
 *     concurrent and later requests with the same key and the same request fingerprint await and
 *     replay the leader's result instead of executing the workflow again.
 *   - Fingerprint binding: the key is bound to the fingerprint of the first request (see
 *     [[IdempotencyStore.fingerprint]]). The same key with a different fingerprint is a
 *     [[ConflictException]] (409 CONFLICT), whether the first request is in flight or completed.
 *   - Leader deadline: the leader computes on a [[DeadlineRunner]] and fails with
 *     [[TimeoutException]] (504) after `leaderDeadlineMs`; its waiting followers get the same
 *     failure. Failures are never cached: the entry is removed so a retry recomputes.
 *   - Bounds: a completed entry is replayed for `ttlMs`, then treated as absent. At most
 *     `maxEntries` entries are kept; the least recently used completed entry is evicted first
 *     (in-flight entries are never evicted, and are bounded by the runner's concurrency). Keys are
 *     at most `maxKeyLength` characters; a blank or longer key is a [[BadRequestException]] (400).
 *
 * @param awaitTimeoutMs
 *   maximum time a follower waits for the leader before failing with [[TimeoutException]]
 * @param nowMs
 *   monotonic clock in milliseconds; injectable for TTL tests
 */
final class IdempotencyStore(
  awaitTimeoutMs: Long = 15000L,
  leaderDeadlineMs: Long = 15000L,
  ttlMs: Long = ServiceConfig.DefaultIdempotencyTtlMs,
  maxEntries: Int = ServiceConfig.DefaultIdempotencyMaxEntries,
  maxKeyLength: Int = ServiceConfig.DefaultIdempotencyMaxKeyLength,
  nowMs: () => Long = () => System.nanoTime() / 1000000L,
  runner: DeadlineRunner =
    DeadlineRunner(ServiceConfig.DefaultMaxConcurrentQueries, "idempotent-query")
):
  import IdempotencyStore.Role
  require(maxEntries >= 1, "maxEntries must be >= 1")
  require(maxKeyLength >= 1, "maxKeyLength must be >= 1")
  require(ttlMs >= 1, "ttlMs must be >= 1")

  final private class Entry(val fingerprint: String):
    val result: CompletableFuture[String] = new CompletableFuture[String]()
    val waiters: AtomicInteger            = new AtomicInteger(0)
    @volatile var completedAtMs: Long     = -1L

    def completed: Boolean = completedAtMs >= 0

  // Access-ordered: iteration starts at the least recently used entry. Guarded by `lock`.
  private val entries = new java.util.LinkedHashMap[String, Entry](16, 0.75f, true)
  private val lock    = new Object

  /** As [[getOrCompute(key, fingerprint)]] with an empty fingerprint (store-level use). */
  def getOrCompute(key: String)(compute: => String): String =
    getOrCompute(key, "")(compute)

  /**
   * Returns the response for `key`, computing it at most once across concurrent callers with the
   * same `fingerprint`.
   */
  def getOrCompute(key: String, fingerprint: String)(compute: => String): String =
    validateKey(key)
    val (entry, role) = lock.synchronized {
      val now = nowMs()
      val existing = Option(entries.get(key)).filterNot { e =>
        val expired = e.completed && now - e.completedAtMs >= ttlMs
        if expired then entries.remove(key)
        expired
      }
      existing match
        case Some(e) =>
          if e.fingerprint != fingerprint then
            throw ConflictException(
              "Idempotency-Key was already used with a different request body"
            )
          if e.completed then (e, Role.Replay)
          else
            e.waiters.incrementAndGet()
            (e, Role.Follow)
        case None =>
          val fresh = new Entry(fingerprint)
          entries.put(key, fresh)
          evictIfNeeded()
          (fresh, Role.Lead)
    }
    role match
      case Role.Lead   => lead(key, entry, compute)
      case Role.Replay => entry.result.getNow(null)
      case Role.Follow => follow(entry)

  /** Number of requests currently blocked waiting on the in-flight leader for `key`. */
  def waiterCount(key: String): Int =
    lock.synchronized(Option(entries.get(key))).map(_.waiters.get()).getOrElse(0)

  /** Whether a completed or in-flight entry exists for `key`. */
  def contains(key: String): Boolean = lock.synchronized(entries.containsKey(key))

  /** Number of entries held (completed and in flight). */
  def size: Int = lock.synchronized(entries.size)

  private def validateKey(key: String): Unit =
    if key == null || key.trim.isEmpty then
      throw BadRequestException("Idempotency-Key must not be blank")
    if key.length > maxKeyLength then
      throw BadRequestException(s"Idempotency-Key exceeds $maxKeyLength characters")

  /** Drops least recently used completed entries until at most `maxEntries` remain. */
  private def evictIfNeeded(): Unit =
    if entries.size > maxEntries then
      val it = entries.entrySet().iterator()
      while entries.size > maxEntries && it.hasNext do
        val e = it.next().getValue
        if e.completed then it.remove()

  private def lead(key: String, entry: Entry, compute: => String): String =
    try
      val value = runner.run(leaderDeadlineMs, "Idempotent query")(compute)
      lock.synchronized {
        entry.completedAtMs = nowMs()
        entry.result.complete(value)
        evictIfNeeded()
      }
      value
    catch
      case t: Throwable =>
        lock.synchronized(entries.remove(key, entry))
        entry.result.completeExceptionally(t)
        throw t

  private def follow(entry: Entry): String =
    try entry.result.get(awaitTimeoutMs, TimeUnit.MILLISECONDS)
    catch
      case _: java.util.concurrent.TimeoutException =>
        throw TimeoutException(
          s"Timed out after ${awaitTimeoutMs}ms waiting for in-flight request with the same Idempotency-Key"
        )
      case e: ExecutionException if e.getCause != null  => throw e.getCause
      case e: CompletionException if e.getCause != null => throw e.getCause
    finally entry.waiters.decrementAndGet(): Unit

object IdempotencyStore:

  private enum Role:
    case Lead, Replay, Follow

  /** Lowercase hex SHA-256 of the canonical request body (UTF-8). */
  def fingerprint(canonicalBody: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(canonicalBody.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString
