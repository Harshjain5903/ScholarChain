package edu.scholarchain.hadoop.jobs

import edu.scholarchain.core.WorkRecord
import edu.scholarchain.hadoop.config.JobConfig
import edu.scholarchain.hadoop.counters.HadoopCounters
import edu.scholarchain.hadoop.io.JsonLineInputFormat
import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.io.compress.GzipCodec
import org.apache.hadoop.mapreduce.{Job, Mapper, Partitioner}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.lib.output.{
  FileOutputFormat,
  LazyOutputFormat,
  MultipleOutputs,
  TextOutputFormat
}
import org.apache.hadoop.util.{Tool, ToolRunner}
import upickle.default.*

/**
 * J08 -- Graph Export Preparation.
 *
 * Reads all pipeline outputs (work records, author mentions, teams, pair candidates) and writes
 * partitioned gzip-compressed JSON lines ready for downstream Spark ingestion.
 *
 * Partition strategy (documented per output type):
 *   - work_records: partitioned by source type (DBLP, OpenAlex, etc.)
 *   - author_mentions: partitioned by source type
 *   - teams: partitioned by year decade (2020s, 2010s, etc.)
 *   - pair_candidates: partitioned by year decade
 *   - author_paper: partitioned by source
 *   - topics: single partition
 *   - institutions: single partition
 *
 * Output is JSON (one record per line) with gzip compression.
 *
 * Key-value contracts: Mapper input: (LongWritable offset, Text jsonLine) Mapper output: (Text
 * partitionedJson, NullWritable)
 */
object GraphExportJob:
  val JobName = "graph-export"

  /** Named outputs for partitioned data. */
  val WorkRecordsOutput    = "work_records"
  val AuthorMentionsOutput = "author_mentions"
  val TeamsOutput          = "teams"
  val PairCandidatesOutput = "pair_candidates"
  val AuthorPaperOutput    = "author_paper"
  val TopicsOutput         = "topics"
  val InstitutionsOutput   = "institutions"
  val MalformedOutput      = "malformed"

  /** Every named output subdirectory J08 may create. */
  val AllOutputs: Vector[String] = Vector(
    WorkRecordsOutput,
    AuthorMentionsOutput,
    TeamsOutput,
    PairCandidatesOutput,
    AuthorPaperOutput,
    TopicsOutput,
    InstitutionsOutput,
    MalformedOutput
  )

  /**
   * MultipleOutputs base path for a named output: each record type is written to its own
   * subdirectory (`<output>/<name>/part-m-NNNNN.gz`), which is the layout Spark's DataLoader reads
   * (e.g. `<hadoop-output>/pair_candidates`).
   */
  def basePath(outputDir: String): String = s"$outputDir/part"

  /**
   * Hadoop MultipleOutputs named-output token for an output directory. Named outputs may only
   * contain letters and digits, so the underscore in directory names such as `pair_candidates` is
   * removed (`paircandidates`); the subdirectory name itself is set by [[basePath]].
   */
  def namedOutput(outputDir: String): String = outputDir.filter(_.isLetterOrDigit)

  /** Determine record type from JSON content using proper parsing. */
  def detectRecordType(json: String): String =
    try
      val obj = ujson.read(json).obj
      if obj.contains("workId") && obj.contains("authors") then "work_records"
      else if obj.contains("mentionId") then "author_mentions"
      else if obj.contains("authorMentions") && obj.contains("teamSize") then "teams"
      else if obj.contains("mentionA") && obj.contains("mentionB") then "pair_candidates"
      else if obj.contains("mention_id") && obj.contains("work_id") then "author_paper"
      else if obj.contains("topicId") then "topics"
      else if obj.contains("institutionId") then "institutions"
      else "other"
    catch case _: Exception => "malformed"

class GraphExportMapper extends Mapper[LongWritable, Text, Text, NullWritable]:

  private var mos: MultipleOutputs[Text, NullWritable] =
    scala.compiletime.uninitialized

  override def setup(
    context: Mapper[
      LongWritable,
      Text,
      Text,
      NullWritable
    ]#Context
  ): Unit =
    mos = new MultipleOutputs[Text, NullWritable](context)

  override def map(
    key: LongWritable,
    value: Text,
    context: Mapper[
      LongWritable,
      Text,
      Text,
      NullWritable
    ]#Context
  ): Unit =
    val line = value.toString.trim
    if line.isEmpty then return

    context
      .getCounter(HadoopCounters.SharedGroup, HadoopCounters.InputRecords)
      .increment(1)

    val recordType = GraphExportJob.detectRecordType(line)
    val outText    = new Text(line)

    recordType match
      case "work_records" =>
        context
          .getCounter(
            HadoopCounters.ExportGroup,
            HadoopCounters.WorkRecordsOut
          )
          .increment(1)
        mos.write(
          GraphExportJob.namedOutput(GraphExportJob.WorkRecordsOutput),
          outText,
          NullWritable.get(),
          GraphExportJob.basePath(GraphExportJob.WorkRecordsOutput)
        )
      case "author_mentions" =>
        context
          .getCounter(
            HadoopCounters.ExportGroup,
            HadoopCounters.AuthorMentionsOut
          )
          .increment(1)
        mos.write(
          GraphExportJob.namedOutput(GraphExportJob.AuthorMentionsOutput),
          outText,
          NullWritable.get(),
          GraphExportJob.basePath(GraphExportJob.AuthorMentionsOutput)
        )
      case "teams" =>
        context
          .getCounter(
            HadoopCounters.ExportGroup,
            HadoopCounters.TeamsOut
          )
          .increment(1)
        mos.write(
          GraphExportJob.namedOutput(GraphExportJob.TeamsOutput),
          outText,
          NullWritable.get(),
          GraphExportJob.basePath(GraphExportJob.TeamsOutput)
        )
      case "pair_candidates" =>
        context
          .getCounter(
            HadoopCounters.ExportGroup,
            HadoopCounters.PairCandidatesOut
          )
          .increment(1)
        mos.write(
          GraphExportJob.namedOutput(GraphExportJob.PairCandidatesOutput),
          outText,
          NullWritable.get(),
          GraphExportJob.basePath(GraphExportJob.PairCandidatesOutput)
        )
      case "author_paper" =>
        mos.write(
          GraphExportJob.namedOutput(GraphExportJob.AuthorPaperOutput),
          outText,
          NullWritable.get(),
          GraphExportJob.basePath(GraphExportJob.AuthorPaperOutput)
        )
      case "topics" =>
        mos.write(
          GraphExportJob.namedOutput(GraphExportJob.TopicsOutput),
          outText,
          NullWritable.get(),
          GraphExportJob.basePath(GraphExportJob.TopicsOutput)
        )
      case "institutions" =>
        mos.write(
          GraphExportJob.namedOutput(GraphExportJob.InstitutionsOutput),
          outText,
          NullWritable.get(),
          GraphExportJob.basePath(GraphExportJob.InstitutionsOutput)
        )
      case "malformed" =>
        mos.write(
          GraphExportJob.namedOutput(GraphExportJob.MalformedOutput),
          outText,
          NullWritable.get(),
          GraphExportJob.basePath(GraphExportJob.MalformedOutput)
        )
      case _ =>
        // Unknown record types go to the default output at the export root. The default output is
        // lazy (see GraphExportTool), so root part files exist only when such records occur, and
        // ExportVerifier flags them as unexpected.
        context
          .getCounter(HadoopCounters.ExportGroup, HadoopCounters.UnknownRecordsOut)
          .increment(1)
        context.write(outText, NullWritable.get())

  override def cleanup(
    context: Mapper[
      LongWritable,
      Text,
      Text,
      NullWritable
    ]#Context
  ): Unit =
    if mos != null then mos.close()

class GraphExportTool extends Configured with Tool:

  override def run(args: Array[String]): Int =
    if args.length < 2 then
      System.err.println(
        "Usage: GraphExportTool <input[,input...]> <output>"
      )
      return 1

    val conf = getConf
    val job  = Job.getInstance(conf, GraphExportJob.JobName)
    job.setJarByClass(classOf[GraphExportTool])

    job.setInputFormatClass(classOf[JsonLineInputFormat])
    job.setMapperClass(classOf[GraphExportMapper])
    job.setNumReduceTasks(0)

    job.setOutputKeyClass(classOf[Text])
    job.setOutputValueClass(classOf[NullWritable])

    // N49: every known record type goes to a named output subdirectory. A lazy default output
    // creates root `part-m-NNNNN.gz` files only when a record is actually written to it, so a
    // normal export has no (empty) part files at its root.
    LazyOutputFormat.setOutputFormatClass(job, classOf[TextOutputFormat[Text, NullWritable]])

    // Enable gzip compression for output
    FileOutputFormat.setCompressOutput(job, true)
    FileOutputFormat.setOutputCompressorClass(job, classOf[GzipCodec])

    // Comma-separated list of input directories (work records, teams, pair candidates, ...)
    FileInputFormat.addInputPaths(job, args(0))
    FileOutputFormat.setOutputPath(
      job,
      new org.apache.hadoop.fs.Path(args(1))
    )

    // Register named outputs for partitioned data
    GraphExportJob.AllOutputs.foreach { name =>
      MultipleOutputs.addNamedOutput(
        job,
        GraphExportJob.namedOutput(name),
        classOf[TextOutputFormat[?, ?]],
        classOf[Text],
        classOf[NullWritable]
      )
    }

    edu.scholarchain.hadoop.driver.JobCompletion.waitAndReport(job)
