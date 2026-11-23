package edu.scholarchain.hadoop.jobs

import edu.scholarchain.core.WorkRecord
import edu.scholarchain.hadoop.config.JobConfig
import edu.scholarchain.hadoop.counters.HadoopCounters
import edu.scholarchain.hadoop.io.JsonLineInputFormat
import edu.scholarchain.hadoop.util.NormalizationUtils
import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.{Job, Mapper, Reducer}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
import org.apache.hadoop.util.{Tool, ToolRunner}
import upickle.default.*

/**
 * J04 -- Researcher and Work Blocking.
 *
 * Generates blocking keys from normalized WorkRecords and groups records into blocks for pairwise
 * comparison. Oversized blocks are truncated to a configurable maximum (default 1000).
 *
 * Blocking strategies:
 *   - Author blocking: normalized surname + first initial
 *   - Author blocking: surname + coauthor surname
 *   - Work blocking: sorted normalized title tokens (first 5)
 *   - Work blocking: year + venue
 *
 * Key-value contracts: Mapper input: (LongWritable offset, Text workRecordJson) Mapper output:
 * (Text blockKey, Text workRecordJson) Reducer output: (Text blockKey, Text
 * "recordJson1\trecordJson2\t...")
 */
object BlockingJob:
  val JobName = "blocking"

  /** Generate blocking keys for a WorkRecord. */
  def generateBlockingKeys(work: WorkRecord): Vector[String] =
    val keys = Vector.newBuilder[String]

    // Author-based blocking: surname + first initial
    work.authors.foreach { mention =>
      NormalizationUtils
        .extractSurnameAndInitial(mention.rawName)
        .foreach { (surname, initial) =>
          keys += s"author:$surname:$initial"
        }
    }

    // Author-based blocking: surname pairs (coauthor blocking)
    if work.authors.size >= 2 then
      val surnames = work.authors.flatMap { m =>
        NormalizationUtils
          .extractSurnameAndInitial(m.rawName)
          .map(_._1)
      }
      for
        i <- surnames.indices
        j <- (i + 1) until surnames.size
      do
        val pair =
          if surnames(i) <= surnames(j) then s"${surnames(i)}:${surnames(j)}"
          else s"${surnames(j)}:${surnames(i)}"
        keys += s"coauth:$pair"

    // Work blocking: sorted title tokens (first 5)
    val titleTokens =
      NormalizationUtils.sortedTitleTokens(work.title)
    if titleTokens.nonEmpty then keys += s"title:${titleTokens.take(5).mkString(":")}"

    // Work blocking: year + venue
    work.venueId.foreach { v =>
      keys += s"yearvenue:${work.year}:$v"
    }

    keys.result().distinct

class BlockingMapper extends Mapper[LongWritable, Text, Text, Text]:

  private val outKey   = new Text()
  private val outValue = new Text()

  override def map(
    key: LongWritable,
    value: Text,
    context: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    val line = value.toString.trim
    if line.isEmpty then return

    context
      .getCounter(HadoopCounters.SharedGroup, HadoopCounters.InputRecords)
      .increment(1)

    try
      val work       = read[WorkRecord](line)
      val blockKeys  = BlockingJob.generateBlockingKeys(work)
      val recordJson = write(work)

      blockKeys.foreach { bk =>
        outKey.set(bk)
        outValue.set(recordJson)
        context.write(outKey, outValue)
      }
    catch
      case _: Exception =>
        context
          .getCounter(HadoopCounters.SharedGroup, HadoopCounters.Rejected)
          .increment(1)

class BlockingReducer extends Reducer[Text, Text, Text, Text]:

  override def reduce(
    key: Text,
    values: java.lang.Iterable[Text],
    context: Reducer[Text, Text, Text, Text]#Context
  ): Unit =
    val maxBlockSize =
      JobConfig.maxBlockSize(context.getConfiguration)
    val records = scala.collection.mutable.ArrayBuffer[String]()
    val iter    = values.iterator()

    while iter.hasNext && records.size < maxBlockSize do records += iter.next().toString

    context
      .getCounter(HadoopCounters.BlockingGroup, HadoopCounters.TotalBlocks)
      .increment(1)
    context
      .getCounter(
        HadoopCounters.BlockingGroup,
        HadoopCounters.RecordsInBlocks
      )
      .increment(records.size.toLong)

    // Check if there were more records than the limit
    if iter.hasNext then
      context
        .getCounter(
          HadoopCounters.BlockingGroup,
          HadoopCounters.OversizeBlocks
        )
        .increment(1)

    if records.nonEmpty then context.write(key, new Text(records.mkString("\t")))

class BlockingTool extends Configured with Tool:

  override def run(args: Array[String]): Int =
    if args.length < 2 then
      System.err.println("Usage: BlockingTool <input> <output>")
      return 1

    val conf = getConf
    val job  = Job.getInstance(conf, BlockingJob.JobName)
    job.setJarByClass(classOf[BlockingTool])

    job.setInputFormatClass(classOf[JsonLineInputFormat])
    job.setMapperClass(classOf[BlockingMapper])
    job.setReducerClass(classOf[BlockingReducer])

    job.setMapOutputKeyClass(classOf[Text])
    job.setMapOutputValueClass(classOf[Text])
    job.setOutputKeyClass(classOf[Text])
    job.setOutputValueClass(classOf[Text])

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

    edu.scholarchain.hadoop.driver.JobCompletion.waitAndReport(job)
