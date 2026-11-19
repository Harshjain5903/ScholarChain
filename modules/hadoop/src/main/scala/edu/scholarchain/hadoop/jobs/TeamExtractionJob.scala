package edu.scholarchain.hadoop.jobs

import edu.scholarchain.core.{
  AuthorMention,
  Provenance,
  PublicationTeam,
  WorkRecord,
}
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
 * J06 -- Publication Team Extraction.
 *
 * Reads normalized WorkRecords and emits one PublicationTeam per work
 * with ordered AuthorMention vector. Also emits author-paper records
 * keyed by (mention_id, work_id).
 *
 * Key-value contracts:
 *   Mapper input:  (LongWritable offset, Text workRecordJson)
 *   Mapper output: (Text teamOrAuthorPaperJson, NullWritable)
 */
object TeamExtractionJob:
  val JobName = "team-extraction"

  /** Build a PublicationTeam from a WorkRecord. */
  def extractTeam(
      work: WorkRecord,
      pipelineRunId: String,
      schemaVersion: String,
      timestamp: String,
  ): PublicationTeam =
    val provenance = Provenance(
      sourceDataset = work.source.toString.toLowerCase,
      sourceRecordId = work.sourceRecordId,
      pipelineRunId = pipelineRunId,
      schemaVersion = schemaVersion,
      timestamp = timestamp,
    )
    PublicationTeam(
      workId = work.workId,
      year = work.year,
      authorMentions = work.authors,
      teamSize = work.authors.size,
      provenance = provenance,
    )

  /** Build an author-paper JSON record with provenance. */
  def authorPaperJson(
      mention: AuthorMention,
      workId: String,
      pipelineRunId: String,
      schemaVersion: String,
      timestamp: String,
      sourceDataset: String,
      sourceRecordId: String,
  ): String =
    val authorPaper = ujson.Obj(
      "mention_id" -> ujson.Str(mention.mentionId),
      "work_id"    -> ujson.Str(workId),
      "position"   -> ujson.Num(mention.position.toDouble),
      "provenance" -> ujson.Obj(
        "sourceDataset"  -> ujson.Str(sourceDataset),
        "sourceRecordId" -> ujson.Str(sourceRecordId),
        "pipelineRunId"  -> ujson.Str(pipelineRunId),
        "schemaVersion"  -> ujson.Str(schemaVersion),
        "timestamp"      -> ujson.Str(timestamp),
      ),
    )
    ujson.write(authorPaper)

class TeamExtractionMapper
    extends Mapper[LongWritable, Text, Text, NullWritable]:

  private val outKey = new Text()

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

    val conf      = context.getConfiguration
    val runId     = JobConfig.pipelineRunId(conf)
    val schemaVer = JobConfig.schemaVersion(conf)
    val threshold = JobConfig.largeTeamThreshold(conf)
    val timestamp = java.time.Instant.now.toString

    context
      .getCounter(HadoopCounters.SharedGroup, HadoopCounters.InputRecords)
      .increment(1)

    try
      val work = read[WorkRecord](line)
      val team = TeamExtractionJob.extractTeam(
        work,
        runId,
        schemaVer,
        timestamp,
      )

      context
        .getCounter(HadoopCounters.TeamGroup, HadoopCounters.TotalTeams)
        .increment(1)

      if team.teamSize > threshold then
        context
          .getCounter(HadoopCounters.TeamGroup, HadoopCounters.LargeTeams)
          .increment(1)

      // Emit the team record
      outKey.set(write(team))
      context.write(outKey, NullWritable.get())

      // Emit author-paper records with provenance
      val sourceDataset = work.source.toString.toLowerCase
      work.authors.foreach { mention =>
        val apJson = TeamExtractionJob.authorPaperJson(
          mention,
          work.workId,
          runId,
          schemaVer,
          timestamp,
          sourceDataset,
          work.sourceRecordId,
        )
        context
          .getCounter(
            HadoopCounters.TeamGroup,
            HadoopCounters.TotalAuthorPaperRecords,
          )
          .increment(1)
        outKey.set(apJson)
        context.write(outKey, NullWritable.get())
      }
    catch
      case _: Exception =>
        context
          .getCounter(HadoopCounters.SharedGroup, HadoopCounters.Rejected)
          .increment(1)

class TeamExtractionTool extends Configured with Tool:
  override def run(args: Array[String]): Int =
    if args.length < 2 then
      System.err.println(
        "Usage: TeamExtractionTool <input> <output>"
      )
      return 1

    val conf = getConf
    val job  = Job.getInstance(conf, TeamExtractionJob.JobName)
    job.setJarByClass(classOf[TeamExtractionTool])

    job.setInputFormatClass(classOf[JsonLineInputFormat])
    job.setMapperClass(classOf[TeamExtractionMapper])
    job.setNumReduceTasks(0)

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

    if job.waitForCompletion(true) then 0 else 1
