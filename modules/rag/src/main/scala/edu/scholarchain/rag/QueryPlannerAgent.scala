package edu.scholarchain.rag

import edu.scholarchain.core.*

/** Pure-logic agent that decomposes a natural-language question into a QueryPlan. */
class QueryPlannerAgent:

  private val yearPattern = """((?:19|20)\d{2})""".r

  private val operationKeywords: Vector[(String, QueryOperation)] = Vector(
    ("bridge", QueryOperation.BridgeRanking),
    ("betweenness", QueryOperation.BridgeRanking),
    ("connect.*communit", QueryOperation.BridgeRanking),
    ("path", QueryOperation.ShortestPath),
    ("connected", QueryOperation.ShortestPath),
    ("reach", QueryOperation.ShortestPath),
    ("hop", QueryOperation.ShortestPath),
    ("component", QueryOperation.ComponentLookup),
    ("community", QueryOperation.CommunityComparison),
    ("group", QueryOperation.CommunityComparison),
    ("cluster", QueryOperation.CommunityComparison),
    ("coauthor", QueryOperation.ResearcherRanking),
    ("collaborat", QueryOperation.ResearcherRanking),
    ("breadth", QueryOperation.ResearcherRanking),
    ("distinct", QueryOperation.ResearcherRanking),
    ("temporal", QueryOperation.TemporalComparison),
    ("over time", QueryOperation.TemporalComparison),
    ("trend", QueryOperation.TemporalComparison),
    ("change", QueryOperation.TemporalComparison),
    ("topic", QueryOperation.TopicFilter),
    ("institution", QueryOperation.InstitutionFilter),
    ("university", QueryOperation.InstitutionFilter),
    ("affiliation", QueryOperation.InstitutionFilter)
  )

  /** Classify the question into a QueryPlan. Never produces raw Cypher. */
  def plan(question: String, constraints: Map[String, String]): QueryPlan =
    val sanitized = sanitize(question)
    val lower     = sanitized.toLowerCase

    val ops = operationKeywords
      .filter { case (kw, _) =>
        kw.r.unanchored.findFirstIn(lower).isDefined
      }
      .map(_._2)
      .distinct

    val effectiveOps =
      if ops.isEmpty then Vector(QueryOperation.ResearcherRanking) else ops

    val years       = yearPattern.findAllIn(sanitized).map(_.toInt).toVector.sorted
    val yearFilters = extractYearFilters(years, constraints)

    val nameFilters = extractNames(sanitized)
    val allFilters  = yearFilters ++ nameFilters ++ constraints

    val queryId = IdGen.runId()
    val prov = Provenance(
      sourceDataset = "query-planner",
      sourceRecordId = queryId,
      pipelineRunId = queryId,
      schemaVersion = "query-plan-v1",
      timestamp = java.time.Instant.now().toString
    )

    QueryPlan(
      queryId = queryId,
      originalQuery = sanitized,
      operations = effectiveOps,
      filters = allFilters,
      provenance = prov
    )

  /** Strip anything that looks like injection (Cypher keywords, semicolons). */
  private def sanitize(input: String): String =
    val dangerousPatterns = Vector(
      "(?i)MATCH\\s*\\(",
      "(?i)CREATE\\s",
      "(?i)DELETE\\s",
      "(?i)MERGE\\s",
      "(?i)DETACH\\s",
      "(?i)DROP\\s",
      "(?i)RETURN\\s",
      "(?i)WHERE\\s.*=",
      ";",
      "\\{",
      "\\}"
    )
    dangerousPatterns
      .foldLeft(input) { (text, pattern) =>
        text.replaceAll(pattern, " ")
      }
      .trim

  private def extractYearFilters(
    years: Vector[Int],
    constraints: Map[String, String]
  ): Map[String, String] =
    if years.size >= 2 then Map("yearFrom" -> years.head.toString, "yearTo" -> years.last.toString)
    else if years.size == 1 then
      if constraints.contains("yearTo") then Map("yearFrom" -> years.head.toString)
      else Map("yearFrom"                                   -> years.head.toString)
    else Map.empty

  private val quotedNamePattern = "\"([^\"]+)\"".r
  private val byNamePattern     = "(?i)\\bby\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+)".r

  private def extractNames(text: String): Map[String, String] =
    val quoted = quotedNamePattern.findAllMatchIn(text).map(_.group(1)).toVector
    val byNames =
      byNamePattern.findAllMatchIn(text).map(_.group(1)).toVector
    val allNames = (quoted ++ byNames).distinct
    if allNames.nonEmpty then
      allNames.zipWithIndex.map { case (name, idx) =>
        s"researcherName$idx" -> name
      }.toMap
    else Map.empty
