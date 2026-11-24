package edu.scholarchain.services

import java.util.concurrent.{
  CompletableFuture,
  CompletionException,
  ConcurrentHashMap,
  ExecutionException,
  TimeUnit
}
import java.util.concurrent.atomic.AtomicInteger

/** Single-flight idempotency store keyed by the `Idempotency-Key` header.
  *
  * The first request for a key atomically registers an in-flight entry (via `putIfAbsent`) and
  * becomes the leader that computes the response. Every concurrent or later request for the same
  * key awaits the leader's result instead of executing the workflow again, so all callers observe
  * exactly one response body.
  *
  * If the leader fails, the entry is removed (failures are not cached, matching the previous
  * behaviour) and the same failure is rethrown to any requests that were already waiting.
  *
  * @param awaitTimeoutMs
  *   maximum time a follower waits for the leader before failing with [[TimeoutException]]
  */
final class IdempotencyStore(awaitTimeoutMs: Long = 15000L):

  private final class Entry:
    val result: CompletableFuture[String] = new CompletableFuture[String]()
    val waiters: AtomicInteger            = new AtomicInteger(0)

  private val entries = new ConcurrentHashMap[String, Entry]()

  /** Returns the response for `key`, computing it at most once across concurrent callers. */
  def getOrCompute(key: String)(compute: => String): String =
    val fresh    = new Entry
    val existing = entries.putIfAbsent(key, fresh)
    if existing == null then lead(key, fresh, compute)
    else follow(existing)

  /** Number of requests currently blocked waiting on the in-flight leader for `key`. */
  def waiterCount(key: String): Int =
    Option(entries.get(key)).map(_.waiters.get()).getOrElse(0)

  /** Whether a completed or in-flight entry exists for `key`. */
  def contains(key: String): Boolean = entries.containsKey(key)

  private def lead(key: String, entry: Entry, compute: => String): String =
    try
      val value = compute
      entry.result.complete(value)
      value
    catch
      case t: Throwable =>
        entries.remove(key, entry)
        entry.result.completeExceptionally(t)
        throw t

  private def follow(entry: Entry): String =
    entry.waiters.incrementAndGet()
    try entry.result.get(awaitTimeoutMs, TimeUnit.MILLISECONDS)
    catch
      case _: java.util.concurrent.TimeoutException =>
        throw TimeoutException(
          s"Timed out after ${awaitTimeoutMs}ms waiting for in-flight request with the same Idempotency-Key"
        )
      case e: ExecutionException if e.getCause != null  => throw e.getCause
      case e: CompletionException if e.getCause != null => throw e.getCause
    finally entry.waiters.decrementAndGet()
