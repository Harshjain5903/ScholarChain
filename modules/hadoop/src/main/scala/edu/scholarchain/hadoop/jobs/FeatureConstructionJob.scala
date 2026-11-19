package edu.scholarchain.hadoop.jobs

import edu.scholarchain.core.{Provenance, WorkRecord}
import edu.scholarchain.hadoop.config.JobConfig
import edu.scholarchain.hadoop.counters.HadoopCounters
import edu.scholarchain.hadoop.io.JsonLineInputFormat
import edu.scholarchain.hadoop.model.MatchCandidate
import edu.scholarchain.hadoop.util.NormalizationUtils
import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.{Job, Mapper, Reducer}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
import org.apache.hadoop.util.{Tool, ToolRunner}
import upickle.default.*

/**
 * J05 -- Candidate Feature Construction.
 *
 * Reads blocked groups (block_key \t record1 \t record2 ...) and computes
 * deterministic pairwise features for every record pair in each block.
 * Emits MatchCandidate records (no accept/reject decision).
 *
 * Features computed:
 *   - title_jaccard: Jaccard similarity on normalized title token sets
 *   - author_similarity: best-match score across author lists
 *   - year_distance: absolute year difference
 *   - venue_match: 1.0 if normalized venues match, 0.0 otherwise
 *   - coauthor_overlap: count of shared normalized coauthor names
 *
 * Key-value contracts:
 *   Mapper input:  (LongWritable offset, Text "blockKey\trecord1\trecord2...")
 *   Mapper output: (Text blockKey, Text workRecordJson)  [pass-through]
 *   Reducer output: (Text matchCandidateJson, NullWritable)
 */
object FeatureConstructionJob:
  val JobName = "feature-construction"

  /** Jaccard similarity on two sets. */
  def jaccard(a: Set[String], b: Set[String]): Double =
    if a.isEmpty && b.isEmpty then 1.0
    else
      val intersection = a.intersect(b).size.toDouble
      val union        = a.union(b).size.toDouble
      if union == 0.0 then 0.0 else intersection / union

  /**
   * Best-match author name similarity: for each name in the shorter
   * list, find the best Jaccard match in the longer list, then average.
   */
  def authorSimilarity(
      authorsA: Vector[String],
      authorsB: Vector[String],
  ): Double =
    if authorsA.isEmpty || authorsB.isEmpty then 0.0
    else
      val (shorter, longer) =
        if authorsA.size <= authorsB.size
        then (authorsA, authorsB)
        else (authorsB, authorsA)
      val scores = shorter.map { a =>
        val aTokens = a.split("\\s+").toSet
        longer
          .map { b =>
            val bTokens = b.split("\\s+").toSet
            jaccard(aTokens, bTokens)
          }
          .max
      }
      scores.sum / scores.size.toDouble

  /** Count of shared normalized author names between two lists. */
  def coauthorOverlap(
      authorsA: Vector[String],
      authorsB: Vector[String],
  ): Int =
    authorsA.toSet.intersect(authorsB.toSet).size

  /** Compute all features for a pair of WorkRecords. */
  def computeFeatures(
      a: WorkRecord,
      b: WorkRecord,
  ): Map[String, Double] =
    val titleTokensA =
      NormalizationUtils.sortedTitleTokens(a.title).toSet
    val titleTokensB =
      NormalizationUtils.sortedTitleTokens(b.title).toSet
    val titleJaccard = jaccard(titleTokensA, titleTokensB)

    val namesA = a.authors.map(_.normalizedName)
    val namesB = b.authors.map(_.normalizedName)
    val authorSim = authorSimilarity(namesA, namesB)

    val yearDist = math.abs(a.year - b.year).toDouble

    val venueMatch =
      (a.venueId, b.venueId) match
        case (Some(va), Some(vb)) if va == vb => 1.0
        case _                                => 0.0

    val coauthOverlap =
      coauthorOverlap(namesA, namesB).toDouble

    Map(
      "title_jaccard"    -> titleJaccard,
      "author_similarity" -> authorSim,
      "year_distance"    -> yearDist,
      "venue_match"      -> venueMatch,
      "coauthor_overlap"  -> coauthOverlap,
    )

class FeatureConstructionMapper
    extends Mapper[LongWritable, Text, Text, Text]:

  private val outKey   = new Text()
  private val outValue = new Text()

  override def map(
      key: LongWritable,
      value: Text,
      context: Mapper[LongWritable, Text, Text, Text]#Context,
  ): Unit =
    val line = value.toString.trim
    if line.isEmpty then return

    // Input format: blockKey \t record1Json \t record2Json \t ...
    val parts = line.split("\t", 2)
    if parts.length < 2 then return

    val blockKey = parts(0)
    val records  = parts(1).split("\t")

    records.foreach { r =>
      outKey.set(blockKey)
      outValue.set(r)
      context.write(outKey, outValue)
    }

class FeatureConstructionReducer
    extends Reducer[Text, Text, Text, NullWritable]:

  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      context: Reducer[Text, Text, Text, NullWritable]#Context,
  ): Unit =
    val conf      = context.getConfiguration
    val runId     = JobConfig.pipelineRunId(conf)
    val schemaVer = JobConfig.schemaVersion(conf)
    val timestamp = java.time.Instant.now.toString

    val maxBlockSize =
      JobConfig.maxBlockSize(conf)

    val records = scala.collection.mutable.ArrayBuffer[WorkRecord]()
    val iter    = values.iterator()
    while iter.hasNext do
      try records += read[WorkRecord](iter.next().toString)
      catch case _: Exception => () // skip unparseable

    // M4: Skip oversize blocks to prevent O(n^2) memory/time explosion
    if records.size > maxBlockSize then
      context
        .getCounter(
          HadoopCounters.FeatureGroup,
          HadoopCounters.OversizeFeatureBlocks,
        )
        .increment(1)
      System.err.println(
        s"WARNING: Oversize feature block '${key.toString}' " +
          s"with ${records.size} records (limit=$maxBlockSize). Skipping."
      )
      return

    // Generate all pairs within the block
    for
      i <- records.indices
      j <- (i + 1) until records.size
    do
      val a        = records(i)
      val b        = records(j)
      val features = FeatureConstructionJob.computeFeatures(a, b)

      context
        .getCounter(
          HadoopCounters.FeatureGroup,
          HadoopCounters.PairsCompared,
        )
        .increment(1)
      context
        .getCounter(
          HadoopCounters.FeatureGroup,
          HadoopCounters.FeaturesComputed,
        )
        .increment(features.size.toLong)

      val provenance = Provenance(
        sourceDataset = "scholarchain",
        sourceRecordId =
          s"${a.sourceRecordId}|${b.sourceRecordId}",
        pipelineRunId = runId,
        schemaVersion = schemaVer,
        timestamp = timestamp,
      )

      val candidate = MatchCandidate(
        sourceId = a.sourceRecordId,
        targetId = b.sourceRecordId,
        features = features,
        blockKey = key.toString,
        provenance = provenance,
      )

      context.write(
        new Text(write(candidate)),
        NullWritable.get(),
      )

class FeatureConstructionTool extends Configured with Tool:
  override def run(args: Array[String]): Int =
    if args.length < 2 then
      System.err.println(
        "Usage: FeatureConstructionTool <input> <output>"
      )
      return 1

    val conf = getConf
    val job  = Job.getInstance(conf, FeatureConstructionJob.JobName)
    job.setJarByClass(classOf[FeatureConstructionTool])

    job.setInputFormatClass(classOf[JsonLineInputFormat])
    job.setMapperClass(classOf[FeatureConstructionMapper])
    job.setReducerClass(classOf[FeatureConstructionReducer])

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

    if job.waitForCompletion(true) then 0 else 1
