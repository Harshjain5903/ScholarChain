package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.rdd.RDD
import upickle.default.*

/**
 * Links canonical researchers to ORCID public records (the third enrichment dataset).
 *
 * Inputs:
 *   - the J02b (`orcid-normalization`) output: JSON lines that are either an `AuthorMention`
 *     (`mention:orcid:<id>:0`, carrying the primary affiliation) or a `CanonicalResearcher`
 *     (carrying the ORCID display name and name variants);
 *   - the publication teams in the window: every DBLP / Crossref / OpenAlex author mention that
 *     carries an ORCID iD;
 *   - the mention-to-canonical mapping from entity resolution.
 *
 * Rule: a canonical researcher's ORCID iD is the iD carried by its author mentions (the most
 * frequent one when several disagree; such researchers are counted as `conflicting`). The
 * researcher is linked when an ORCID record with that iD was fetched. The link carries the ORCID
 * name, name variants and primary affiliation, with provenance back to the ORCID record. Exact iD
 * equality only: no name matching happens here.
 *
 * Fully distributed (joins keyed by researcher ID and by ORCID iD).
 */
object OrcidEnrichment:

  /** One researcher linked to an ORCID record. */
  final case class OrcidLink(
    researcherId: String,
    orcid: String,
    orcidName: String,
    alternateNames: Vector[String],
    affiliation: Option[String],
    orcidSourceRecordId: String
  ) derives ReadWriter

  /** Profile fields read from one ORCID record (J02b output, both line kinds merged). */
  final case class Profile(
    orcid: String,
    name: String,
    alternateNames: Vector[String],
    affiliation: Option[String],
    sourceRecordId: String
  )

  final case class Stats(
    profilesLoaded: Long,
    researchersWithOrcid: Long,
    researchersLinked: Long,
    researchersWithConflictingOrcid: Long
  )

  private val OrcidPattern = """(\d{4}-\d{4}-\d{4}-\d{3}[\dX])""".r

  /** Extract the bare iD (`0000-0002-1825-0097`) from an iD or an `https://orcid.org/` URL. */
  def normalizeOrcid(raw: String): Option[String] =
    OrcidPattern.findFirstIn(Option(raw).getOrElse("").toUpperCase)

  /** Parse J02b lines into one profile per ORCID iD. Unreadable lines are skipped. */
  def profiles(lines: RDD[String]): RDD[(String, Profile)] =
    lines
      .flatMap { line =>
        val t = line.trim
        if t.isEmpty then Iterator.empty
        else
          val json = scala.util.Try(ujson.read(t)).toOption
          json.iterator.flatMap { j =>
            if j.obj.contains("researcherId") then
              scala.util
                .Try(read[CanonicalResearcher](t))
                .toOption
                .flatMap(r => r.orcid.flatMap(normalizeOrcid).map(id => (id, Left(r))))
                .iterator
            else if j.obj.contains("mentionId") then
              scala.util
                .Try(read[AuthorMention](t))
                .toOption
                .flatMap(m => m.orcid.flatMap(normalizeOrcid).map(id => (id, Right(m))))
                .iterator
            else Iterator.empty
          }
      }
      .groupByKey()
      .map { case (id, parts) =>
        val rs = parts.collect { case Left(r) => r }.toVector
        val ms = parts.collect { case Right(m) => m }.toVector
        val name = rs.headOption
          .map(_.displayName)
          .orElse(ms.headOption.map(_.rawName))
          .getOrElse("")
        val alt = rs.flatMap(_.alternateNames).distinct.sorted
        val aff = ms.flatMap(_.affiliationRaw).filter(_.trim.nonEmpty).sorted.headOption
        val src = rs.headOption
          .map(_.provenance.sourceRecordId)
          .orElse(ms.headOption.map(_.sourceRecordId))
          .getOrElse(id)
        (id, Profile(id, name, alt, aff, src))
      }

  /**
   * Canonical researcher -> ORCID iD from the ORCID iDs carried by its author mentions. Returns
   * (researcherId, (orcid, conflicting)).
   */
  def researcherOrcids(
    teams: RDD[PublicationTeam],
    mentionToCanonical: RDD[(String, String)]
  ): RDD[(String, (String, Boolean))] =
    val mapping = mentionToCanonical.reduceByKey((a, b) => if a <= b then a else b)
    teams
      .flatMap(_.authorMentions.iterator.flatMap(m =>
        m.orcid.flatMap(normalizeOrcid).map(o => (m.mentionId, o))
      ))
      .leftOuterJoin(mapping)
      .map { case (mention, (orcid, canonical)) => ((canonical.getOrElse(mention), orcid), 1L) }
      .reduceByKey(_ + _)
      .map { case ((rid, orcid), n) => (rid, (orcid, n)) }
      .groupByKey()
      .mapValues { counts =>
        val v    = counts.toVector
        val best = v.sortBy { case (o, n) => (-n, o) }.head._1
        (best, v.map(_._1).distinct.size > 1)
      }

  /** Join researcher ORCID iDs to fetched ORCID profiles. */
  def link(
    researcherOrcids: RDD[(String, (String, Boolean))],
    profiles: RDD[(String, Profile)]
  ): RDD[OrcidLink] =
    researcherOrcids
      .map { case (rid, (orcid, _)) => (orcid, rid) }
      .join(profiles)
      .map { case (orcid, (rid, p)) =>
        OrcidLink(rid, orcid, p.name, p.alternateNames, p.affiliation, p.sourceRecordId)
      }
