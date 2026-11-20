package edu.scholarchain.rag

import upickle.default.*

case class PathHop(
    researcherId: String,
    displayName: String,
    jointPublications: Int,
    years: List[Int]
) derives ReadWriter

object EmbeddingTemplates:

  def researcherProfile(
      researcherId: String,
      displayName: String,
      topics: List[String],
      institutions: List[String],
      windowStartYear: Int,
      windowEndYear: Int,
      publicationCount: Int,
      distinctCoauthorCount: Int,
      representativeTitles: List[String]
  ): String =
    val topicStr =
      if topics.nonEmpty then s"Research topics: ${topics.mkString(", ")}."
      else ""
    val instStr =
      if institutions.nonEmpty then
        s"Affiliated with: ${institutions.mkString(", ")}."
      else ""
    val titleStr =
      if representativeTitles.nonEmpty then
        s"Representative works: ${representativeTitles.mkString("; ")}."
      else ""
    s"""Researcher $displayName ($researcherId).
       |Period: $windowStartYear to $windowEndYear.
       |Publications: $publicationCount. Distinct coauthors: $distinctCoauthorCount.
       |$topicStr
       |$instStr
       |$titleStr""".stripMargin.trim

  def communityProfile(
      communityId: String,
      dominantTopics: List[String],
      memberCount: Int,
      windowStartYear: Int,
      windowEndYear: Int,
      representativeTitles: List[String]
  ): String =
    val topicStr =
      if dominantTopics.nonEmpty then
        s"Dominant topics: ${dominantTopics.mkString(", ")}."
      else ""
    val titleStr =
      if representativeTitles.nonEmpty then
        s"Representative works: ${representativeTitles.mkString("; ")}."
      else ""
    s"""Collaboration community $communityId.
       |Period: $windowStartYear to $windowEndYear.
       |Members: $memberCount.
       |$topicStr
       |$titleStr""".stripMargin.trim

  def collaborationPath(
      startResearcherId: String,
      endResearcherId: String,
      hops: List[PathHop],
      windowStartYear: Int,
      windowEndYear: Int
  ): String =
    val hopDescriptions = hops.map { h =>
      val yearsStr =
        if h.years.nonEmpty then s" (years: ${h.years.mkString(", ")})"
        else ""
      s"${h.displayName} [${h.researcherId}] with ${h.jointPublications} joint publications$yearsStr"
    }
    s"""Collaboration path from $startResearcherId to $endResearcherId.
       |Period: $windowStartYear to $windowEndYear.
       |Path length: ${hops.size} hops.
       |${hopDescriptions.mkString(" -> ")}""".stripMargin.trim

  def paperSummary(
      workId: String,
      title: String,
      year: Int,
      venue: Option[String],
      abstractText: Option[String],
      topics: List[String]
  ): String =
    val venueStr = venue.map(v => s"Published in: $v.").getOrElse("")
    val abstractStr = abstractText.map(a => s"Abstract: $a").getOrElse("")
    val topicStr =
      if topics.nonEmpty then s"Topics: ${topics.mkString(", ")}." else ""
    s"""$title ($workId, $year).
       |$venueStr
       |$topicStr
       |$abstractStr""".stripMargin.trim

  def topicInstitutionSummary(
      entityId: String,
      entityType: String,
      name: String,
      description: String,
      relatedTopics: List[String],
      windowStartYear: Int,
      windowEndYear: Int
  ): String =
    val relatedStr =
      if relatedTopics.nonEmpty then
        s"Related topics: ${relatedTopics.mkString(", ")}."
      else ""
    s"""$entityType: $name ($entityId).
       |Period: $windowStartYear to $windowEndYear.
       |$description
       |$relatedStr""".stripMargin.trim
