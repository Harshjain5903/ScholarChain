package edu.scholarchain.services

import java.util.concurrent.{
  Callable,
  ExecutionException,
  RejectedExecutionException,
  SynchronousQueue,
  ThreadFactory,
  ThreadPoolExecutor,
  TimeUnit
}
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a computation with a deadline on a bounded pool.
 *
 * The caller blocks for at most `timeoutMs`. On expiry the task is cancelled with an interrupt and
 * the caller gets [[TimeoutException]] (504). When all `maxConcurrent` threads are busy the call
 * fails at once with [[UnavailableException]] (503) instead of queueing, so slow downstreams cannot
 * pile up unbounded work. A computation that ignores interrupts keeps its pool thread until it
 * returns; the pool bound still holds.
 */
final class DeadlineRunner(maxConcurrent: Int, name: String = "query"):
  require(maxConcurrent >= 1, s"maxConcurrent must be >= 1, got $maxConcurrent")

  private val counter = new AtomicInteger(0)

  private val pool = new ThreadPoolExecutor(
    0,
    maxConcurrent,
    60L,
    TimeUnit.SECONDS,
    new SynchronousQueue[Runnable](),
    new ThreadFactory:
      def newThread(r: Runnable): Thread =
        val t = new Thread(r, s"$name-deadline-${counter.incrementAndGet()}")
        t.setDaemon(true)
        t
  )

  /** Threads currently running a computation. */
  def active: Int = pool.getActiveCount

  def run[A](timeoutMs: Long, what: String)(f: => A): A =
    val task =
      try pool.submit(new Callable[A] { def call(): A = f })
      catch
        case _: RejectedExecutionException =>
          throw UnavailableException(s"Too many concurrent ${name}s; retry later")
    try task.get(math.max(1L, timeoutMs), TimeUnit.MILLISECONDS)
    catch
      case _: java.util.concurrent.TimeoutException =>
        task.cancel(true)
        throw TimeoutException(s"$what exceeded its ${timeoutMs}ms deadline")
      case e: ExecutionException if e.getCause != null => throw e.getCause
      case _: InterruptedException =>
        task.cancel(true)
        Thread.currentThread().interrupt()
        throw TimeoutException(s"$what was interrupted")

  def shutdown(): Unit = pool.shutdownNow(): Unit
