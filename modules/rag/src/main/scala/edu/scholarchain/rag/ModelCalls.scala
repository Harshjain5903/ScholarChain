package edu.scholarchain.rag

import java.util.concurrent.{Semaphore, TimeUnit}
import java.time.Duration
import scala.util.control.NonFatal

/**
 * A failed model or embedding call. The message never contains an API key, a request body or
 * retrieved text: only the provider, the operation and a short error class or status.
 *
 * @param retryable
 *   true for throttling and transient service errors
 */
final class ModelCallException(
  val provider: String,
  val retryable: Boolean,
  message: String,
  cause: Throwable = null
) extends RuntimeException(message, cause)

/** An embedding whose length differs from the configured dimension. Never used as a vector. */
final class EmbeddingDimensionException(val expected: Int, val actual: Int, provider: String)
    extends RuntimeException(
      s"$provider returned an embedding of length $actual; expected $expected"
    )

/** Bounds shared by every model adapter. */
object ModelCallBounds:

  /** Upper bound on retries after the first attempt, whatever the configuration says. */
  val MaxRetries: Int = 3

  /** Base backoff; attempt n waits `base * 2^(n-1)`, capped at [[MaxBackoffMs]]. */
  val BaseBackoffMs: Long = 250L

  val MaxBackoffMs: Long = 4000L

  /** Upper bound on a single call timeout. */
  val MaxTimeout: Duration = Duration.ofSeconds(120)

  def clampRetries(configured: Int): Int = math.max(0, math.min(configured, MaxRetries))

  def clampTimeout(seconds: Int): Duration =
    val d = Duration.ofSeconds(math.max(1, seconds).toLong)
    if d.compareTo(MaxTimeout) > 0 then MaxTimeout else d

/**
 * Bounded retry with exponential backoff. Only errors for which `isRetryable` is true are retried,
 * at most `maxRetries` times; the last error is rethrown.
 *
 * @param sleep
 *   injected so tests do not wait
 */
final class BoundedRetry(
  maxRetries: Int,
  baseDelayMs: Long = ModelCallBounds.BaseBackoffMs,
  maxDelayMs: Long = ModelCallBounds.MaxBackoffMs,
  sleep: Long => Unit = ms => Thread.sleep(ms)
):

  val retries: Int = ModelCallBounds.clampRetries(maxRetries)

  def run[A](isRetryable: Throwable => Boolean)(call: => A): A =
    def attempt(n: Int): A =
      try call
      catch
        case NonFatal(e) if n < retries && isRetryable(e) =>
          sleep(math.min(maxDelayMs, baseDelayMs * (1L << n)))
          attempt(n + 1)
    attempt(0)

/**
 * Caps the number of in-flight model calls. A caller that cannot get a permit within
 * `acquireTimeout` fails with a retryable [[ModelCallException]] instead of queueing forever.
 */
final class ConcurrencyLimiter(val maxConcurrent: Int, acquireTimeout: Duration):
  require(maxConcurrent >= 1, s"maxConcurrent must be positive, got $maxConcurrent")

  private val permits = Semaphore(maxConcurrent, true)

  def available: Int = permits.availablePermits()

  def withPermit[A](provider: String)(call: => A): A =
    if !permits.tryAcquire(acquireTimeout.toMillis, TimeUnit.MILLISECONDS) then
      throw ModelCallException(
        provider,
        retryable = true,
        s"$provider: no call slot free within ${acquireTimeout.toMillis} ms " +
          s"(max concurrent calls $maxConcurrent)"
      )
    try call
    finally permits.release()
