package edu.scholarchain.hadoop.driver

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
        ToolRunner.run(conf, factory(), args.drop(1))

  def main(args: Array[String]): Unit =
    sys.exit(run(args))
