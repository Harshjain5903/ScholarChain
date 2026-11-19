package edu.scholarchain.core

import upickle.default.*

/** The full author team on a single publication. */
case class PublicationTeam(
  workId: String,
  year: Int,
  authorMentions: Vector[AuthorMention],
  teamSize: Int,
  provenance: Provenance
) derives ReadWriter:

  require(
    teamSize == authorMentions.size,
    s"PublicationTeam teamSize ($teamSize) must equal authorMentions.size (${authorMentions.size})"
  )

/**
 * An unresolved coauthor pair from a single publication. The constructor guarantees mentionA <
 * mentionB lexicographically.
 */
case class CoauthorPairCandidate(
  mentionA: String,
  mentionB: String,
  workId: String,
  year: Int,
  provenance: Provenance
):

  require(
    mentionA < mentionB,
    s"CoauthorPairCandidate: mentionA must be < mentionB, got ($mentionA, $mentionB)"
  )

object CoauthorPairCandidate:

  def apply(
    mentionA: String,
    mentionB: String,
    workId: String,
    year: Int,
    provenance: Provenance
  ): CoauthorPairCandidate =
    require(mentionA != mentionB, "CoauthorPairCandidate: self-pairs not allowed")
    if mentionA < mentionB
    then new CoauthorPairCandidate(mentionA, mentionB, workId, year, provenance)
    else new CoauthorPairCandidate(mentionB, mentionA, workId, year, provenance)

  given ReadWriter[CoauthorPairCandidate] =
    readwriter[ujson.Value].bimap[CoauthorPairCandidate](
      c =>
        ujson.Obj(
          "mentionA"   -> ujson.Str(c.mentionA),
          "mentionB"   -> ujson.Str(c.mentionB),
          "workId"     -> ujson.Str(c.workId),
          "year"       -> ujson.Num(c.year.toDouble),
          "provenance" -> writeJs(c.provenance)
        ),
      json =>
        CoauthorPairCandidate(
          json("mentionA").str,
          json("mentionB").str,
          json("workId").str,
          json("year").num.toInt,
          read[Provenance](json("provenance"))
        )
    )

/**
 * An aggregated collaboration edge between two resolved researchers. The constructor guarantees
 * researcherA < researcherB lexicographically and that firstCollaborationYear <=
 * lastCollaborationYear.
 */
case class CoauthorEdge(
  researcherA: String,
  researcherB: String,
  jointPublicationCount: Int,
  firstCollaborationYear: Int,
  lastCollaborationYear: Int,
  representativeWorkIds: Vector[String],
  provenance: Provenance
):

  require(
    researcherA < researcherB,
    s"CoauthorEdge: researcherA must be < researcherB, got ($researcherA, $researcherB)"
  )

object CoauthorEdge:

  def apply(
    researcherA: String,
    researcherB: String,
    jointPublicationCount: Int,
    firstCollaborationYear: Int,
    lastCollaborationYear: Int,
    representativeWorkIds: Vector[String],
    provenance: Provenance
  ): CoauthorEdge =
    require(
      firstCollaborationYear <= lastCollaborationYear,
      s"firstCollaborationYear ($firstCollaborationYear) must be <= lastCollaborationYear ($lastCollaborationYear)"
    )
    require(researcherA != researcherB, "CoauthorEdge: self-edges not allowed")
    if researcherA < researcherB then
      new CoauthorEdge(
        researcherA,
        researcherB,
        jointPublicationCount,
        firstCollaborationYear,
        lastCollaborationYear,
        representativeWorkIds,
        provenance
      )
    else
      new CoauthorEdge(
        researcherB,
        researcherA,
        jointPublicationCount,
        firstCollaborationYear,
        lastCollaborationYear,
        representativeWorkIds,
        provenance
      )

  given ReadWriter[CoauthorEdge] =
    readwriter[ujson.Value].bimap[CoauthorEdge](
      c =>
        ujson.Obj(
          "researcherA"            -> ujson.Str(c.researcherA),
          "researcherB"            -> ujson.Str(c.researcherB),
          "jointPublicationCount"  -> ujson.Num(c.jointPublicationCount.toDouble),
          "firstCollaborationYear" -> ujson.Num(c.firstCollaborationYear.toDouble),
          "lastCollaborationYear"  -> ujson.Num(c.lastCollaborationYear.toDouble),
          "representativeWorkIds"  -> ujson.Arr(c.representativeWorkIds.map(ujson.Str(_))*),
          "provenance"             -> writeJs(c.provenance)
        ),
      json =>
        CoauthorEdge(
          json("researcherA").str,
          json("researcherB").str,
          json("jointPublicationCount").num.toInt,
          json("firstCollaborationYear").num.toInt,
          json("lastCollaborationYear").num.toInt,
          json("representativeWorkIds").arr.map(_.str).toVector,
          read[Provenance](json("provenance"))
        )
    )

/** Per-researcher collaboration metrics for a time window. */
case class ResearcherMetrics(
  researcherId: String,
  publicationCount: Int,
  distinctCoauthorCount: Int,
  totalCoauthorOccurrences: Int,
  averageCoauthorsPerPaper: Double,
  maximumCoauthorsOnOnePaper: Int,
  weightedDegree: Double,
  componentId: Option[String],
  communityId: Option[String],
  bridgeScore: Double,
  coreness: Int,
  windowStartYear: Int,
  windowEndYear: Int,
  provenance: Provenance
) derives ReadWriter:

  require(
    windowStartYear <= windowEndYear,
    s"windowStartYear ($windowStartYear) must be <= windowEndYear ($windowEndYear)"
  )
