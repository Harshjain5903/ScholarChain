package edu.scholarchain.hadoop.jobs

import edu.scholarchain.core.{CoauthorPairCandidate, Provenance, PublicationTeam}
import edu.scholarchain.hadoop.config.JobConfig
import edu.scholarchain.hadoop.counters.HadoopCounters
import edu.scholarchain.hadoop.io.JsonLineInputFormat
import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.{Job, Mapper}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
import org.apache.hadoop.util.{Tool, ToolRunner}
import upickle.default.*

/**
 * J07 -- Coauthor Pair Expansion.
 *
 * Reads PublicationTeam records and generates n*(n-1)/2 unordered coauthor pairs per paper. Uses
 * CoauthorPairCandidate with lexicographic canonicalization (mentionA < mentionB). Papers with 0 or
 * 1 authors are skipped.
 *
 * Key-value contracts: Mapper input: (LongWritable offset, Text teamJson) Mapper output: (Text
 * pairCandidateJson, NullWritable)
 */
object PairExpansionJob:
  val JobName = "pair-expansion"

  /**
   * True for the author-paper JSON lines written by J06 (TeamExtractionJob.authorPaperJson), which
   * share J06's output directory with PublicationTeam records.
   */
  def isAuthorPaperRecord(json: ujson.Value): Boolean =
    json.objOpt.exists(o => o.contains("mention_id") && o.contains("work_id"))

  /** Generate all unordered pairs from a PublicationTeam. */
  def expandPairs(
    team: PublicationTeam,
    pipelineRunId: String,
    schemaVersion: String,
    timestamp: String
  ): Vector[CoauthorPairCandidate] =
    val mentions = team.authorMentions
    if mentions.size < 2 then return Vector.empty

    val provenance = Provenance(
      sourceDataset = "scholarchain",
      sourceRecordId = team.workId,
      pipelineRunId = pipelineRunId,
      schemaVersion = schemaVersion,
      timestamp = timestamp
    )

    val pairs = Vector.newBuilder[CoauthorPairCandidate]
    for
      i <- mentions.indices
      j <- (i + 1) until mentions.size
    do
      pairs += CoauthorPairCandidate(
        mentionA = mentions(i).mentionId,
        mentionB = mentions(j).mentionId,
        workId = team.workId,
        year = team.year,
        provenance = provenance
      )

    pairs.result()

class PairExpansionMapper extends Mapper[LongWritable, Text, Text, NullWritable]:

  private val outKey = new Text()

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
    val threshold = JobConfig.largeTeamThreshold(conf)
    val timestamp = java.time.Instant.now.toString

    try
      val json = ujson.read(line)
      // J06 output interleaves author-paper records with team records; skip them explicitly
      // so they are not miscounted as rejected input.
      if PairExpansionJob.isAuthorPaperRecord(json) then
        context
          .getCounter(
            HadoopCounters.PairGroup,
            HadoopCounters.SkippedAuthorPaperRecords
          )
          .increment(1)
        return
      val team = read[PublicationTeam](json)

      context
        .getCounter(
          HadoopCounters.PairGroup,
          HadoopCounters.PapersProcessed
        )
        .increment(1)

      if team.teamSize < 2 then
        context
          .getCounter(
            HadoopCounters.PairGroup,
            HadoopCounters.SkippedSingletonTeams
          )
          .increment(1)
        return

      if team.teamSize > threshold then
        context
          .getCounter(
            HadoopCounters.PairGroup,
            HadoopCounters.LargeTeamExpansions
          )
          .increment(1)

      val pairs = PairExpansionJob.expandPairs(
        team,
        runId,
        schemaVer,
        timestamp
      )

      pairs.foreach { pair =>
        context
          .getCounter(
            HadoopCounters.PairGroup,
            HadoopCounters.TotalPairsEmitted
          )
          .increment(1)
        outKey.set(write(pair))
        context.write(outKey, NullWritable.get())
      }
    catch
      case _: Exception =>
        context
          .getCounter(HadoopCounters.SharedGroup, HadoopCounters.Rejected)
          .increment(1)
        context
          .getCounter(HadoopCounters.RejectReasonGroup, "UNPARSEABLE_TEAM")
          .increment(1)

class PairExpansionTool extends Configured with Tool:

  override def run(args: Array[String]): Int =
    if args.length < 2 then
      System.err.println(
        "Usage: PairExpansionTool <input> <output>"
      )
      return 1

    val conf = getConf
    val job  = Job.getInstance(conf, PairExpansionJob.JobName)
    job.setJarByClass(classOf[PairExpansionTool])

    job.setInputFormatClass(classOf[JsonLineInputFormat])
    job.setMapperClass(classOf[PairExpansionMapper])
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

    edu.scholarchain.hadoop.driver.JobCompletion.waitAndReport(job)
