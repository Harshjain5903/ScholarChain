package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import upickle.default.*

/** Load Hadoop JSON-line outputs into Spark RDDs of typed domain objects. */
object DataLoader:

  /** Load WorkRecord JSON lines from the given path. */
  def loadWorkRecords(spark: SparkSession, path: String): RDD[WorkRecord] =
    spark.sparkContext.textFile(path).map(line => read[WorkRecord](line))

  /** Load AuthorMention JSON lines from the given path. */
  def loadAuthorMentions(spark: SparkSession, path: String): RDD[AuthorMention] =
    spark.sparkContext.textFile(path).map(line => read[AuthorMention](line))

  /** Load CoauthorPairCandidate JSON lines from the given path. */
  def loadCoauthorPairCandidates(
    spark: SparkSession,
    path: String
  ): RDD[CoauthorPairCandidate] =
    spark.sparkContext.textFile(path).map(line => read[CoauthorPairCandidate](line))

  /** Load MatchDecision JSON lines from the given path. */
  def loadMatchDecisions(spark: SparkSession, path: String): RDD[MatchDecision] =
    spark.sparkContext.textFile(path).map(line => read[MatchDecision](line))

  /** Load Topic JSON lines from the given path. */
  def loadTopics(spark: SparkSession, path: String): RDD[Topic] =
    spark.sparkContext.textFile(path).map(line => read[Topic](line))

  /** Load Institution JSON lines from the given path. */
  def loadInstitutions(spark: SparkSession, path: String): RDD[Institution] =
    spark.sparkContext.textFile(path).map(line => read[Institution](line))
