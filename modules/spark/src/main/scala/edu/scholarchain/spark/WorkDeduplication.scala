package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.rdd.RDD

/**
 * Cross-dataset work deduplication, applied before the coauthorship graph is built.
 *
 * The enrichment sources (Crossref, OpenAlex) are fetched by the DOIs of the selected DBLP works,
 * so the Hadoop export holds the same paper up to three times: `work:dblp:...`,
 * `work:crossref:<doi>` and `work:openalex:<id>`. Without this step every copy contributes its own
 * publication team and coauthor pairs, so a single paper is counted up to three times in
 * `jointPublicationCount`, `P_r`, `T_r` and `W_r`.
 *
 * Rule: work records are grouped by normalized DOI. In a group with more than one work ID, exactly
 * one work is kept: the DBLP work when present (DBLP is the seed dataset), otherwise the
 * lexicographically smallest work ID. The publication teams and pair candidates of every other work
 * in the group are dropped. The dropped copies still contribute to entity resolution (their
 * mentions are in the match decisions) and their records stay in the Hadoop export; they only stop
 * creating additional works, authorships and coauthor pairs. Works without a DOI are never merged.
 *
 * Fully distributed: groups are keyed by DOI (one group per paper, at most a few members).
 */
object WorkDeduplication:

  /** Normalize a DOI for grouping: trim, lowercase, strip resolver and `doi:` prefixes. */
  def normalizeDoi(doi: String): String =
    val d = doi.trim.toLowerCase
    val stripped =
      Seq(
        "https://doi.org/",
        "http://doi.org/",
        "https://dx.doi.org/",
        "http://dx.doi.org/",
        "doi:"
      )
        .find(d.startsWith)
        .map(p => d.substring(p.length))
        .getOrElse(d)
    stripped.trim

  /**
   * Map of duplicate work ID -> kept work ID, for every work that shares a normalized DOI with a
   * preferred work. Works that are kept do not appear as keys.
   */
  def duplicateWorks(works: RDD[WorkRecord]): RDD[(String, String)] =
    works
      .flatMap(w =>
        w.doi
          .map(normalizeDoi)
          .filter(_.nonEmpty)
          // ordinal, not ==: Java-serialized Scala 3 enum cases are not reference-equal on executors
          .map(d => (d, (w.workId, w.source.ordinal == SourceType.DBLP.ordinal)))
      )
      .distinct()
      .groupByKey()
      .flatMap { case (_, members) =>
        val v = members.toVector.distinct
        if v.map(_._1).distinct.size < 2 then Iterator.empty
        else
          val keep = v.minBy { case (id, isDblp) => (if isDblp then 0 else 1, id) }._1
          v.iterator.map(_._1).filter(_ != keep).map(id => (id, keep))
      }
      .reduceByKey((a, b) => if a <= b then a else b)

  /** Teams whose work is not a duplicate. */
  def keepTeams(teams: RDD[PublicationTeam], dups: RDD[(String, String)]): RDD[PublicationTeam] =
    teams.keyBy(_.workId).subtractByKey(dups).values

  /** Pair candidates whose work is not a duplicate. */
  def keepPairs(
    pairs: RDD[CoauthorPairCandidate],
    dups: RDD[(String, String)]
  ): RDD[CoauthorPairCandidate] =
    pairs.keyBy(_.workId).subtractByKey(dups).values
