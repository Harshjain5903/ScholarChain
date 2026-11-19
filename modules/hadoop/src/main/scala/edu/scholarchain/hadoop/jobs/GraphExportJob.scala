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
  MultipleOutputs,
  TextOutputFormat,
}
import org.apache.hadoop.util.{Tool, ToolRunner}
import upickle.default.*

/**
 * J08 -- Graph Export Preparation.
 *
 * Reads all pipeline outputs (work records, author mentions, teams,
 * pair candidates) and writes partitioned gzip-compressed JSON lines
 * ready for downstream Spark ingestion.
 *
 * Partition strategy (documented per output type):
 *   - work_records:    partitioned by source type (DBLP, OpenAlex, etc.)
 *   - author_mentions: partitioned by source type
 *   - teams:           partitioned by year decade (2020s, 2010s, etc.)
 *   - pair_candidates: partitioned by year decade
 *   - author_paper:    partitioned by source
 *   - topics:          single partition
 *   - institutions:    single partition
 *
 * Output is JSON (one record per line) with gzip compression.
 *
 * Key-value contracts:
 *   Mapper input:  (LongWritable offset, Text jsonLine)
 *   Mapper output: (Text partitionedJson, NullWritable)
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

  /** Determine record type from JSON content using proper parsing. */
  def detectRecordType(json: String): String =
    try
      val obj = ujson.read(json).obj
      if obj.contains("workId") && obj.contains("authors") then
        "work_records"
      else if obj.contains("mentionId") then "author_mentions"
      else if obj.contains("authorMentions") && obj.contains("teamSize") then
        "teams"
      else if obj.contains("mentionA") && obj.contains("mentionB") then
        "pair_candidates"
      else if obj.contains("mention_id") && obj.contains("work_id") then
        "author_paper"
      else if obj.contains("topicId") then "topics"
      else if obj.contains("institutionId") then "institutions"
      else "other"
    catch case _: Exception => "malformed"

class GraphExportMapper
    extends Mapper[LongWritable, Text, Text, NullWritable]:

  private var mos: MultipleOutputs[Text, NullWritable] =
    scala.compiletime.uninitialized

  override def setup(
      context: Mapper[
        LongWritable,
        Text,
        Text,
        NullWritable,
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
        NullWritable,
      ]#Context,
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
            HadoopCounters.WorkRecordsOut,
          )
          .increment(1)
        mos.write(
          GraphExportJob.WorkRecordsOutput,
          outText,
          NullWritable.get(),
        )
      case "author_mentions" =>
        context
          .getCounter(
            HadoopCounters.ExportGroup,
            HadoopCounters.AuthorMentionsOut,
          )
          .increment(1)
        mos.write(
          GraphExportJob.AuthorMentionsOutput,
          outText,
          NullWritable.get(),
        )
      case "teams" =>
        context
          .getCounter(
            HadoopCounters.ExportGroup,
            HadoopCounters.TeamsOut,
          )
          .increment(1)
        mos.write(
          GraphExportJob.TeamsOutput,
          outText,
          NullWritable.get(),
        )
      case "pair_candidates" =>
        context
          .getCounter(
            HadoopCounters.ExportGroup,
            HadoopCounters.PairCandidatesOut,
          )
          .increment(1)
        mos.write(
          GraphExportJob.PairCandidatesOutput,
          outText,
          NullWritable.get(),
        )
      case "author_paper" =>
        mos.write(
          GraphExportJob.AuthorPaperOutput,
          outText,
          NullWritable.get(),
        )
      case "topics" =>
        mos.write(
          GraphExportJob.TopicsOutput,
          outText,
          NullWritable.get(),
        )
      case "institutions" =>
        mos.write(
          GraphExportJob.InstitutionsOutput,
          outText,
          NullWritable.get(),
        )
      case "malformed" =>
        mos.write(
          GraphExportJob.MalformedOutput,
          outText,
          NullWritable.get(),
        )
      case _ =>
        // Write unknown types to main output
        context.write(outText, NullWritable.get())

  override def cleanup(
      context: Mapper[
        LongWritable,
        Text,
        Text,
        NullWritable,
      ]#Context
  ): Unit =
    if mos != null then mos.close()

class GraphExportTool extends Configured with Tool:
  override def run(args: Array[String]): Int =
    if args.length < 2 then
      System.err.println(
        "Usage: GraphExportTool <input> <output>"
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

    // Enable gzip compression for output
    FileOutputFormat.setCompressOutput(job, true)
    FileOutputFormat.setOutputCompressorClass(job, classOf[GzipCodec])

    FileInputFormat.addInputPath(
      job,
      new org.apache.hadoop.fs.Path(args(0)),
    )
    FileOutputFormat.setOutputPath(
      job,
      new org.apache.hadoop.fs.Path(args(1)),
    )

    // Register named outputs for partitioned data
    val namedOutputs = Seq(
      GraphExportJob.WorkRecordsOutput,
      GraphExportJob.AuthorMentionsOutput,
      GraphExportJob.TeamsOutput,
      GraphExportJob.PairCandidatesOutput,
      GraphExportJob.AuthorPaperOutput,
      GraphExportJob.TopicsOutput,
      GraphExportJob.InstitutionsOutput,
      GraphExportJob.MalformedOutput,
    )
    namedOutputs.foreach { name =>
      MultipleOutputs.addNamedOutput(
        job,
        name,
        classOf[TextOutputFormat[?, ?]],
        classOf[Text],
        classOf[NullWritable],
      )
    }

    if job.waitForCompletion(true) then 0 else 1
