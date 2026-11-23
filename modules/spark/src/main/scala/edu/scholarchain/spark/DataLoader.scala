package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import upickle.default.*

/**
 * Load Hadoop JSON-line outputs into Spark RDDs of typed domain objects.
 *
 * Paths may be directories of (optionally gzip-compressed) part files; Hadoop hidden files (`_` or
 * `.` prefixed, e.g. `_SUCCESS`, `.crc`) are skipped by the input format. Blank lines are ignored;
 * any other line that does not decode fails the job (no silent drops).
 *
 * When `numPartitions` is given and the input has fewer partitions (gzip part files are not
 * splittable, so a single `.gz` file yields a single partition), the RDD is repartitioned so that
 * downstream joins run with the configured parallelism.
 */
object DataLoader:

  private def lines(spark: SparkSession, path: String, numPartitions: Option[Int]): RDD[String] =
    val raw = spark.sparkContext.textFile(path).filter(_.trim.nonEmpty)
    numPartitions match
      case Some(n) if n > raw.getNumPartitions => raw.repartition(n)
      case _                                   => raw

  /** Load WorkRecord JSON lines from the given path. */
  def loadWorkRecords(spark: SparkSession, path: String): RDD[WorkRecord] =
    lines(spark, path, None).map(line => read[WorkRecord](line))

  /** Load AuthorMention JSON lines from the given path. */
  def loadAuthorMentions(spark: SparkSession, path: String): RDD[AuthorMention] =
    lines(spark, path, None).map(line => read[AuthorMention](line))

  /** Load CoauthorPairCandidate JSON lines from the given path. */
  def loadCoauthorPairCandidates(
    spark: SparkSession,
    path: String,
    numPartitions: Option[Int] = None
  ): RDD[CoauthorPairCandidate] =
    lines(spark, path, numPartitions).map(line => read[CoauthorPairCandidate](line))

  /** Load PublicationTeam JSON lines (Hadoop `teams` export) from the given path. */
  def loadPublicationTeams(
    spark: SparkSession,
    path: String,
    numPartitions: Option[Int] = None
  ): RDD[PublicationTeam] =
    lines(spark, path, numPartitions).map(line => read[PublicationTeam](line))

  /** Load MatchDecision JSON lines from the given path. */
  def loadMatchDecisions(
    spark: SparkSession,
    path: String,
    numPartitions: Option[Int] = None
  ): RDD[MatchDecision] =
    lines(spark, path, numPartitions).map(line => read[MatchDecision](line))

  /** Load Topic JSON lines from the given path. */
  def loadTopics(spark: SparkSession, path: String): RDD[Topic] =
    lines(spark, path, None).map(line => read[Topic](line))

  /** Load Institution JSON lines from the given path. */
  def loadInstitutions(spark: SparkSession, path: String): RDD[Institution] =
    lines(spark, path, None).map(line => read[Institution](line))
