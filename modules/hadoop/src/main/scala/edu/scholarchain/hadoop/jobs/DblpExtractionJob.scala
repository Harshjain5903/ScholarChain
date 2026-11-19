package edu.scholarchain.hadoop.jobs

import edu.scholarchain.hadoop.config.JobConfig
import edu.scholarchain.hadoop.counters.HadoopCounters
import edu.scholarchain.hadoop.io.{DblpInputFormat, RejectWriter}
import edu.scholarchain.hadoop.parsers.DblpParser
import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.io.compress.GzipCodec
import org.apache.hadoop.mapreduce.{Job, Mapper, Reducer}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.lib.output.{
  FileOutputFormat,
  MultipleOutputs,
  TextOutputFormat,
}
import org.apache.hadoop.util.{Tool, ToolRunner}
import upickle.default.*
import edu.scholarchain.core.{Provenance, WorkRecord}

/**
 * J01 -- DBLP Record Extraction.
 *
 * Reads raw DBLP XML using DblpInputFormat, parses each record into
 * WorkRecord + AuthorMention domain objects, and writes normalized
 * JSON lines. Malformed records go to a reject side output.
 *
 * Key-value contracts:
 *   Mapper input:  (LongWritable offset, Text xmlRecord)
 *   Mapper output: (Text sourceRecordId, Text workRecordJson)
 *   Reducer input:  (Text sourceRecordId, Iterable[Text] workRecordJsons)
 *   Reducer output: (Text workRecordJson, NullWritable)
 */
object DblpExtractionJob:
  val JobName = "dblp-extraction"

  /** Map a parsed DBLP record type to its counter name. */
  def counterForRecordType(recordType: String): String =
    recordType match
      case "article"        => HadoopCounters.ArticleCount
      case "inproceedings"  => HadoopCounters.InproceedingsCount
      case "proceedings"    => HadoopCounters.ProceedingsCount
      case "book"           => HadoopCounters.BookCount
      case "incollection"   => HadoopCounters.IncollectionCount
      case "phdthesis"      => HadoopCounters.PhdthesisCount
      case "mastersthesis"  => HadoopCounters.MastersthesisCount
      case _                => HadoopCounters.ArticleCount

class DblpExtractionMapper
    extends Mapper[LongWritable, Text, Text, Text]:

  private val outKey   = new Text()
  private val outValue = new Text()
  private var multipleOutputs: MultipleOutputs[Text, Text] =
    scala.compiletime.uninitialized

  override def setup(
      context: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    multipleOutputs = new MultipleOutputs[Text, Text](context)

  override def map(
      key: LongWritable,
      value: Text,
      context: Mapper[LongWritable, Text, Text, Text]#Context,
  ): Unit =
    val conf        = context.getConfiguration
    val runId       = JobConfig.pipelineRunId(conf)
    val schemaVer   = JobConfig.schemaVersion(conf)
    val timestamp   = java.time.Instant.now.toString
    val xmlStr      = value.toString

    context
      .getCounter(HadoopCounters.SharedGroup, HadoopCounters.InputRecords)
      .increment(1)

    DblpParser.parse(xmlStr, runId, schemaVer, timestamp) match
      case Right((work, _, recordType)) =>
        context
          .getCounter(HadoopCounters.SharedGroup, HadoopCounters.Accepted)
          .increment(1)
        context
          .getCounter(
            HadoopCounters.DblpGroup,
            DblpExtractionJob.counterForRecordType(recordType),
          )
          .increment(1)
        outKey.set(work.sourceRecordId)
        outValue.set(upickle.default.write(work))
        context.write(outKey, outValue)

      case Left((code, msg)) =>
        context
          .getCounter(HadoopCounters.SharedGroup, HadoopCounters.Rejected)
          .increment(1)
        val sourceId = extractSourceId(xmlStr)
        val provenance = Provenance(
          sourceDataset = "dblp",
          sourceRecordId = sourceId,
          pipelineRunId = runId,
          schemaVersion = schemaVer,
          timestamp = timestamp,
        )
        RejectWriter.writeReject(
          multipleOutputs,
          sourceId,
          code,
          msg,
          xmlStr,
          DblpExtractionJob.JobName,
          provenance,
        )

  override def cleanup(
      context: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    if multipleOutputs != null then multipleOutputs.close()

  /** Best-effort extraction of key attribute from raw XML for reject records. */
  private def extractSourceId(xml: String): String =
    val keyPattern = """key="([^"]+)"""".r
    keyPattern.findFirstMatchIn(xml) match
      case Some(m) => s"dblp:${m.group(1)}"
      case None    => "unknown"

/**
 * Deduplication reducer: keeps the first record per DBLP key.
 */
class DblpExtractionReducer
    extends Reducer[Text, Text, Text, NullWritable]:

  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      context: Reducer[Text, Text, Text, NullWritable]#Context,
  ): Unit =
    val iter = values.iterator()
    if iter.hasNext then
      context.write(new Text(iter.next().toString), NullWritable.get())

class DblpExtractionTool extends Configured with Tool:
  override def run(args: Array[String]): Int =
    if args.length < 2 then
      System.err.println("Usage: DblpExtractionTool <input> <output>")
      return 1

    val conf = getConf
    val job  = Job.getInstance(conf, DblpExtractionJob.JobName)
    job.setJarByClass(classOf[DblpExtractionTool])

    job.setInputFormatClass(classOf[DblpInputFormat])
    job.setMapperClass(classOf[DblpExtractionMapper])
    job.setReducerClass(classOf[DblpExtractionReducer])

    job.setMapOutputKeyClass(classOf[Text])
    job.setMapOutputValueClass(classOf[Text])
    job.setOutputKeyClass(classOf[Text])
    job.setOutputValueClass(classOf[NullWritable])

    // L7: Enable gzip compression for output
    conf.set("mapreduce.output.fileoutputformat.compress", "true")
    conf.set(
      "mapreduce.output.fileoutputformat.compress.codec",
      "org.apache.hadoop.io.compress.GzipCodec",
    )

    FileInputFormat.addInputPath(
      job,
      new org.apache.hadoop.fs.Path(args(0)),
    )
    FileOutputFormat.setOutputPath(
      job,
      new org.apache.hadoop.fs.Path(args(1)),
    )

    MultipleOutputs.addNamedOutput(
      job,
      RejectWriter.RejectOutputName,
      classOf[TextOutputFormat[?, ?]],
      classOf[Text],
      classOf[NullWritable],
    )

    if job.waitForCompletion(true) then 0 else 1
