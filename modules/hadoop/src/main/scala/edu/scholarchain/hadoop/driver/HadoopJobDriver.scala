package edu.scholarchain.hadoop.driver

import edu.scholarchain.hadoop.config.JobConfig
import edu.scholarchain.hadoop.jobs.*
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.util.{Tool, ToolRunner}

/**
 * Command-line entry point for all ScholarChain MapReduce jobs.
 *
 * Usage: `HadoopJobDriver <job-name> [-D key=value ...] <input> <output>`
 *
 * Generic Hadoop options (`-D`, `-fs`, `-jt`, ...) are parsed by ToolRunner. Works with the
 * LocalJobRunner (file:// paths) and on a cluster (`hadoop jar`).
 *
 * Run-level options (all optional, passed as `-D key=value`):
 *   - `scholarchain.pipeline.run-id` -- `provenance.pipelineRunId` of every emitted record
 *   - `scholarchain.pipeline.schema-version` -- `provenance.schemaVersion`
 *   - `scholarchain.run.timestamp` -- fixed ISO-8601 instant used as `provenance.timestamp` for
 *     every record of the run; pass the same value to every job so reruns are byte-reproducible.
 *     When absent, records carry the wall-clock time they were produced. An invalid value fails the
 *     job before submission with exit code 2.
 *   - `scholarchain.counters.output` -- local path for the job's counters JSON
 */
object HadoopJobDriver:

  /** Job name -> tool factory. Names match each job object's `JobName`. */
  val Tools: Map[String, () => Tool] = Map(
    DblpExtractionJob.JobName        -> (() => new DblpExtractionTool),
    CrossrefNormalizationJob.JobName -> (() => new CrossrefNormalizationTool),
    OpenAlexNormalizationJob.JobName -> (() => new OpenAlexNormalizationTool),
    OrcidNormalizationJob.JobName    -> (() => new OrcidNormalizationTool),
    IdentifierExtractionJob.JobName  -> (() => new IdentifierExtractionTool),
    BlockingJob.JobName              -> (() => new BlockingTool),
    FeatureConstructionJob.JobName   -> (() => new FeatureConstructionTool),
    TeamExtractionJob.JobName        -> (() => new TeamExtractionTool),
    PairExpansionJob.JobName         -> (() => new PairExpansionTool),
    GraphExportJob.JobName           -> (() => new GraphExportTool)
  )

  def usage: String =
    s"Usage: HadoopJobDriver <job-name> [generic options] <input> <output>\n" +
      s"Jobs: ${Tools.keys.toSeq.sorted.mkString(", ")}"

  /** Run the named job and return its exit code (2 for an unknown job or missing name). */
  def run(args: Array[String], conf: Configuration = new Configuration()): Int =
    args.headOption.flatMap(Tools.get) match
      case None =>
        System.err.println(usage)
        2
      case Some(factory) =>
        ToolRunner.run(conf, new RunOptionsValidatingTool(factory()), args.drop(1))

  def main(args: Array[String]): Unit =
    sys.exit(run(args))

/**
 * Wraps a job tool and rejects invalid run-level options (see [[JobConfig.validateRunOptions]])
 * after ToolRunner has applied the generic `-D` options, before the job is submitted.
 */
final class RunOptionsValidatingTool(inner: Tool) extends Tool:
  private var conf: Configuration = scala.compiletime.uninitialized

  override def setConf(c: Configuration): Unit =
    conf = c
    if c != null then inner.setConf(c)

  override def getConf: Configuration = conf

  override def run(args: Array[String]): Int =
    JobConfig.validateRunOptions(conf) match
      case Some(error) =>
        System.err.println(error)
        2
      case None => inner.run(args)
