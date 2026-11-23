package edu.scholarchain.hadoop.driver

import org.apache.hadoop.mapreduce.{Counters, Job}
import scala.jdk.CollectionConverters.*

/**
 * Shared completion step for every ScholarChain MapReduce tool: waits for the job and, when the
 * configuration key [[CountersOutputKey]] is set, writes the job's final counters as a JSON file to
 * that local path. This gives run evidence (input, output, accepted, rejected counts) without
 * depending on log scraping.
 */
object JobCompletion:

  /** Local filesystem path where the counters JSON should be written (optional). */
  val CountersOutputKey = "scholarchain.counters.output"

  /** Wait for completion, optionally report counters, and return a process exit code. */
  def waitAndReport(job: Job): Int =
    val ok = job.waitForCompletion(true)
    Option(job.getConfiguration.get(CountersOutputKey)).filter(_.nonEmpty).foreach { path =>
      val json = countersJson(job.getJobName, ok, job.getCounters)
      val p    = java.nio.file.Paths.get(path)
      Option(p.getParent).foreach(java.nio.file.Files.createDirectories(_))
      java.nio.file.Files.writeString(p, ujson.write(json, indent = 2) + "\n")
    }
    if ok then 0 else 1

  /**
   * Render counters as `{"job": ..., "succeeded": ..., "counters": {group: {name: value}}}` with
   * groups and names sorted for deterministic output.
   */
  def countersJson(jobName: String, succeeded: Boolean, counters: Counters): ujson.Obj =
    val groups =
      if counters == null then Seq.empty
      else
        counters.asScala.toSeq
          .map { g =>
            val entries = g.asScala.toSeq
              .map(c => c.getName -> ujson.Num(c.getValue.toDouble))
              .sortBy(_._1)
            g.getName -> ujson.Obj.from(entries)
          }
          .sortBy(_._1)
    ujson.Obj(
      "job"       -> ujson.Str(jobName),
      "succeeded" -> ujson.Bool(succeeded),
      "counters"  -> ujson.Obj.from(groups)
    )
