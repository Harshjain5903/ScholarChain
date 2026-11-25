package edu.scholarchain.hadoop.jobs

import edu.scholarchain.core.{AuthorMention, WorkRecord}
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
      val fields = ujson.read(line).obj
      val pairs =
        if fields.contains("workId") then
          IdentifierExtractionJob.extractIdentifiers(read[WorkRecord](line))
        else if fields.contains("mentionId") then
          // ORCID normalizer output: the profile mention links its ORCID to the ORCID record.
          val m = read[AuthorMention](line)
          m.orcid
            .flatMap(NormalizationUtils.validateOrcid)
            .map(o => s"orcid:$o" -> m.sourceRecordId)
            .toVector
        else if fields.contains("researcherId") then
          context
            .getCounter(HadoopCounters.IdentifierGroup, HadoopCounters.SkippedResearcherRecords)
            .increment(1)
          return
        else throw new IllegalArgumentException("not a work or mention record")

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

    val idType = key.toString.takeWhile(_ != ':')
    val ids    = sources.toVector
    val byType = ids.groupBy(_.takeWhile(_ != ':'))
    val counter = (name: String, n: Long) =>
      context.getCounter(HadoopCounters.IdentifierGroup, name).increment(n)

    counter(s"${idType}_keys", 1)
    if byType.size > 1 then
      // The same identifier is carried by records of different source datasets: a cross-source
      // link when they describe one work (DOI), a shared person key (ORCID, OpenAlex author).
      counter(HadoopCounters.Conflicts, 1)
      counter(s"${idType}_cross_source", 1)
    else if ids.size > 1 then
      counter(HadoopCounters.Duplicates, 1)
      counter(s"${idType}_same_source_duplicates", 1)
    if byType.values.exists(_.size > 1) && byType.size > 1 then
      counter(s"${idType}_same_source_duplicates", 1)

    if idType == "doi" then
      val dblp  = byType.get("dblp").map(_.size).getOrElse(0)
      val hasCr = byType.contains("crossref")
      val hasOa = byType.contains("openalex")
      if dblp > 0 && hasCr then counter(HadoopCounters.DblpWorksCrossrefDoiMatch, dblp)
      if dblp > 0 && hasOa then counter(HadoopCounters.DblpWorksOpenAlexDoiMatch, dblp)
      if dblp > 0 && hasCr && hasOa then counter(HadoopCounters.DblpWorksBothDoiMatch, dblp)
      if dblp > 0 && (hasCr || hasOa) then counter(HadoopCounters.DblpWorksAnyDoiMatch, dblp)
      if hasCr && hasOa then counter(HadoopCounters.CrossrefOpenAlexDoiMatches, 1)

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

    // A comma-separated list of input paths is accepted (several upstream stage outputs).
    FileInputFormat.addInputPaths(job, args(0))
    FileOutputFormat.setOutputPath(
      job,
      new org.apache.hadoop.fs.Path(args(1))
    )

    edu.scholarchain.hadoop.driver.JobCompletion.waitAndReport(job)
