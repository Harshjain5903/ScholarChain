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
 * J03 -- Exact Identifier Extraction.
 *
 * Reads normalized WorkRecord JSON, extracts and normalizes identifiers (DOI, arXiv, ORCID,
 * OpenAlex), emits (identifier_key, source_record_id) pairs. The reducer groups by identifier and
 * detects duplicates/conflicts.
 *
 * Key-value contracts: Mapper input: (LongWritable offset, Text workRecordJson) Mapper output:
 * (Text "type:value", Text sourceRecordId) Reducer output: (Text "type:value", Text
 * "source_id1,source_id2,...")
 */
object IdentifierExtractionJob:
  val JobName = "identifier-extraction"

  /** Extract identifier pairs from a WorkRecord. */
  def extractIdentifiers(
    work: WorkRecord
  ): Vector[(String, String)] =
    val buf = Vector.newBuilder[(String, String)]

    work.doi.foreach { d =>
      NormalizationUtils.normalizeDoi(d).foreach { nd =>
        buf += (s"doi:$nd" -> work.sourceRecordId)
      }
    }

    work.arxivId.foreach { a =>
      NormalizationUtils.normalizeArxivId(a).foreach { na =>
        buf += (s"arxiv:$na" -> work.sourceRecordId)
      }
    }

    work.authors.foreach { mention =>
      mention.orcid.foreach { o =>
        NormalizationUtils.validateOrcid(o).foreach { vo =>
          buf += (s"orcid:$vo" -> work.sourceRecordId)
        }
      }
      mention.openAlexId.foreach { oaid =>
        buf += (s"openalex:$oaid" -> work.sourceRecordId)
      }
    }

    buf.result()

class IdentifierExtractionMapper extends Mapper[LongWritable, Text, Text, Text]:

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
      val work  = read[WorkRecord](line)
      val pairs = IdentifierExtractionJob.extractIdentifiers(work)

      if pairs.isEmpty then
        context
          .getCounter(
            HadoopCounters.IdentifierGroup,
            HadoopCounters.MissingKeys
          )
          .increment(1)

      pairs.foreach { (idKey, srcId) =>
        context
          .getCounter(
            HadoopCounters.IdentifierGroup,
            HadoopCounters.TotalIdentifiers
          )
          .increment(1)
        outKey.set(idKey)
        outValue.set(srcId)
        context.write(outKey, outValue)
      }
    catch
      case _: Exception =>
        context
          .getCounter(HadoopCounters.SharedGroup, HadoopCounters.Rejected)
          .increment(1)

class IdentifierExtractionReducer extends Reducer[Text, Text, Text, Text]:

  override def reduce(
    key: Text,
    values: java.lang.Iterable[Text],
    context: Reducer[Text, Text, Text, Text]#Context
  ): Unit =
    val sources = scala.collection.mutable.LinkedHashSet[String]()
    val iter    = values.iterator()
    while iter.hasNext do sources += iter.next().toString

    if sources.size > 1 then
      // Multiple sources map to the same identifier -- could be
      // duplicate or conflict depending on whether source types differ
      val sourceTypes = sources.map(_.split(":").head).toSet
      if sourceTypes.size > 1 then
        context
          .getCounter(
            HadoopCounters.IdentifierGroup,
            HadoopCounters.Conflicts
          )
          .increment(1)
      else
        context
          .getCounter(
            HadoopCounters.IdentifierGroup,
            HadoopCounters.Duplicates
          )
          .increment(1)

    context.write(key, new Text(sources.mkString(",")))

class IdentifierExtractionTool extends Configured with Tool:

  override def run(args: Array[String]): Int =
    if args.length < 2 then
      System.err.println(
        "Usage: IdentifierExtractionTool <input> <output>"
      )
      return 1

    val conf = getConf
    val job  = Job.getInstance(conf, IdentifierExtractionJob.JobName)
    job.setJarByClass(classOf[IdentifierExtractionTool])

    job.setInputFormatClass(classOf[JsonLineInputFormat])
    job.setMapperClass(classOf[IdentifierExtractionMapper])
    job.setReducerClass(classOf[IdentifierExtractionReducer])

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

    if job.waitForCompletion(true) then 0 else 1
