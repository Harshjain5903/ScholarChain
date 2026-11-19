package edu.scholarchain.hadoop.jobs

import edu.scholarchain.core.Provenance
import edu.scholarchain.hadoop.config.JobConfig
import edu.scholarchain.hadoop.counters.HadoopCounters
import edu.scholarchain.hadoop.io.{JsonLineInputFormat, RejectWriter}
import edu.scholarchain.hadoop.parsers.CrossrefParser
import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.{Job, Mapper}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.lib.output.{FileOutputFormat, MultipleOutputs, TextOutputFormat}
import org.apache.hadoop.util.{Tool, ToolRunner}
import upickle.default.*

/**
 * J02c -- Crossref Source Normalization.
 *
 * Reads Crossref JSON Lines, maps each record to WorkRecord + AuthorMention, writes normalized JSON
 * lines.
 *
 * Key-value contracts: Mapper input: (LongWritable offset, Text jsonLine) Mapper output: (Text
 * workRecordJson, NullWritable)
 */
object CrossrefNormalizationJob:
  val JobName = "crossref-normalization"

class CrossrefNormalizationMapper extends Mapper[LongWritable, Text, Text, NullWritable]:

  private val outKey = new Text()

  private var multipleOutputs: MultipleOutputs[Text, NullWritable] =
    scala.compiletime.uninitialized

  override def setup(
    context: Mapper[
      LongWritable,
      Text,
      Text,
      NullWritable
    ]#Context
  ): Unit =
    multipleOutputs = new MultipleOutputs[Text, NullWritable](context)

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

    val conf      = context.getConfiguration
    val runId     = JobConfig.pipelineRunId(conf)
    val schemaVer = JobConfig.schemaVersion(conf)
    val timestamp = java.time.Instant.now.toString

    context
      .getCounter(HadoopCounters.SharedGroup, HadoopCounters.InputRecords)
      .increment(1)

    CrossrefParser.parse(line, runId, schemaVer, timestamp) match
      case Right((work, _)) =>
        context
          .getCounter(HadoopCounters.SharedGroup, HadoopCounters.Accepted)
          .increment(1)
        outKey.set(upickle.default.write(work))
        context.write(outKey, NullWritable.get())

      case Left((code, msg)) =>
        context
          .getCounter(HadoopCounters.SharedGroup, HadoopCounters.Rejected)
          .increment(1)
        if code == "MISSING_TITLE" || code == "MISSING_YEAR"
          || code == "MISSING_SOURCE_ID" || code == "MISSING_AUTHORS"
        then
          context
            .getCounter(
              HadoopCounters.SharedGroup,
              HadoopCounters.MissingRequiredFields
            )
            .increment(1)
        val sourceId = extractSourceId(line)
        val provenance = Provenance(
          sourceDataset = "crossref",
          sourceRecordId = sourceId,
          pipelineRunId = runId,
          schemaVersion = schemaVer,
          timestamp = timestamp
        )
        RejectWriter.writeReject(
          multipleOutputs,
          sourceId,
          code,
          msg,
          line,
          CrossrefNormalizationJob.JobName,
          provenance
        )

  override def cleanup(
    context: Mapper[
      LongWritable,
      Text,
      Text,
      NullWritable
    ]#Context
  ): Unit =
    if multipleOutputs != null then multipleOutputs.close()

  /** Best-effort source ID extraction from raw JSON for reject records. */
  private def extractSourceId(json: String): String =
    try
      val obj = ujson.read(json).obj
      obj.get("source_id").map(_.str).getOrElse("unknown")
    catch case _: Exception => "unknown"

class CrossrefNormalizationTool extends Configured with Tool:

  override def run(args: Array[String]): Int =
    if args.length < 2 then
      System.err.println(
        "Usage: CrossrefNormalizationTool <input> <output>"
      )
      return 1

    val conf = getConf
    val job  = Job.getInstance(conf, CrossrefNormalizationJob.JobName)
    job.setJarByClass(classOf[CrossrefNormalizationTool])

    job.setInputFormatClass(classOf[JsonLineInputFormat])
    job.setMapperClass(classOf[CrossrefNormalizationMapper])
    job.setNumReduceTasks(0)

    job.setOutputKeyClass(classOf[Text])
    job.setOutputValueClass(classOf[NullWritable])

    // L7: Enable gzip compression for output
    conf.set("mapreduce.output.fileoutputformat.compress", "true")
    conf.set(
      "mapreduce.output.fileoutputformat.compress.codec",
      "org.apache.hadoop.io.compress.GzipCodec"
    )

    FileInputFormat.addInputPath(
      job,
      new org.apache.hadoop.fs.Path(args(0))
    )
    FileOutputFormat.setOutputPath(
      job,
      new org.apache.hadoop.fs.Path(args(1))
    )

    MultipleOutputs.addNamedOutput(
      job,
      RejectWriter.RejectOutputName,
      classOf[TextOutputFormat[?, ?]],
      classOf[Text],
      classOf[NullWritable]
    )

    if job.waitForCompletion(true) then 0 else 1
