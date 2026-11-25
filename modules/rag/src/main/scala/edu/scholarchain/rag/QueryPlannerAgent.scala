package edu.scholarchain.rag

import edu.scholarchain.core.*
import edu.scholarchain.storage.RankableResearcherMetric

/**
 * Pure-logic agent that decomposes a natural-language question into a QueryPlan.
 *
 * Ranking questions ("which ten researchers have the broadest coauthorship networks since 2015")
 * get the filters [[QueryPlannerAgent.RankingRequestedKey]], [[QueryPlannerAgent.RankingLimitKey]]
 * and, when the wording maps to one, [[QueryPlannerAgent.RankingMetricKey]] holding the wire name
 * of an allowlisted [[RankableResearcherMetric]]. The metric is only ever chosen from fixed phrase
 * patterns; question text is never copied into it.
 */
class QueryPlannerAgent:
  import QueryPlannerAgent.*

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

    val nameFilters    = extractNames(sanitized)
    val rankingFilters = extractRanking(lower)
    val allFilters     = yearFilters ++ nameFilters ++ rankingFilters ++ constraints

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

  private def extractRanking(lower: String): Map[String, String] =
    if !isRankingQuestion(lower) then Map.empty
    else
      val metric = rankingMetric(lower).map(m => RankingMetricKey -> m.wireName)
      Map(
        RankingRequestedKey -> "true",
        RankingLimitKey     -> rankingLimit(lower).getOrElse(DefaultRankingLimit).toString
      ) ++ metric

object QueryPlannerAgent:

  /** Filter: wire name of the allowlisted ranking metric. */
  val RankingMetricKey: String = "rankingMetric"

  /** Filter: number of researchers to rank (1..100). */
  val RankingLimitKey: String = "rankingLimit"

  /** Filter: `true` when the question asks for a ranking of researchers. */
  val RankingRequestedKey: String = "rankingRequested"

  val DefaultRankingLimit: Int = 10

  private val People = """(?:researchers|authors|scholars|scientists|people)"""

  /** "which / what / top / rank / list ... researchers". */
  private val RankingCue = s"""\\b(?:which|what|top|rank\\w*|list)\\b.*\\b$People\\b""".r

  /**
   * Phrase patterns per allowlisted metric, checked in this order (team size before coauthor
   * breadth, so "most coauthors on one paper" is not read as breadth).
   */
  val MetricPhrases: Vector[(RankableResearcherMetric, scala.util.matching.Regex)] = Vector(
    RankableResearcherMetric.BridgeScore ->
      """bridg|broker|betweenness|connect\w*\b.*\bcommunit|span\w*\b.*\bcommunit""".r,
    RankableResearcherMetric.MaximumCoauthorsOnOnePaper ->
      ("""(?:largest|biggest)\s+(?:author\s+|research\s+|co-?author(?:ship)?\s+)?teams?\b|""" +
        """team\s+size|most\s+co-?authors\s+on\s+(?:a|one)\s+(?:single\s+)?paper""").r,
    RankableResearcherMetric.Coreness ->
      """coreness|\bk-?core|core\s+of\s+the\s+(?:\w+\s+)?network""".r,
    RankableResearcherMetric.WeightedDegree ->
      ("""weighted\s+degree|collaboration\s+(?:strength|intensity)|""" +
        """most\s+(?:repeated|frequent|intense)\s+collaborations|strongest\s+collaboration\s+ties""").r,
    RankableResearcherMetric.PublicationCount ->
      ("""most\s+(?:publications|papers)|most\s+prolific|published\s+the\s+most|""" +
        """publication\s+counts?|highest\s+output""").r,
    RankableResearcherMetric.DistinctCoauthorCount ->
      ("""broad\w*\s+(?:co-?authorship|collaboration|collaborator)|""" +
        """(?:largest|biggest|widest|broadest)\s+(?:co-?authorship|collaboration)\s+networks?|""" +
        """most\s+(?:distinct\s+|different\s+|unique\s+)?(?:co-?authors|collaborators)|""" +
        """distinct\s+co-?authors|collaboration\s+breadth|breadth""").r
  )

  private val NumberWords: Map[String, Int] = Map(
    "one"     -> 1,
    "two"     -> 2,
    "three"   -> 3,
    "four"    -> 4,
    "five"    -> 5,
    "six"     -> 6,
    "seven"   -> 7,
    "eight"   -> 8,
    "nine"    -> 9,
    "ten"     -> 10,
    "eleven"  -> 11,
    "twelve"  -> 12,
    "fifteen" -> 15,
    "twenty"  -> 20,
    "thirty"  -> 30,
    "fifty"   -> 50,
    "hundred" -> 100
  )

  private val Count = s"""(\\d{1,3}|${NumberWords.keys.toVector.sorted.mkString("|")})"""

  private val LimitPatterns = Vector(
    s"""\\btop\\s+$Count\\b""".r,
    s"""\\b$Count\\s+(?:\\w+\\s+)?$People\\b""".r
  )

  def isRankingQuestion(lower: String): Boolean = RankingCue.findFirstIn(lower).isDefined

  /** The allowlisted metric the wording asks to rank by, if any. */
  def rankingMetric(lower: String): Option[RankableResearcherMetric] =
    MetricPhrases.collectFirst { case (m, p) if p.findFirstIn(lower).isDefined => m }

  /** Requested ranking size, clamped to 1..100. */
  def rankingLimit(lower: String): Option[Int] =
    LimitPatterns.iterator
      .flatMap(_.findFirstMatchIn(lower))
      .map(_.group(1))
      .map(t => t.toIntOption.orElse(NumberWords.get(t)).getOrElse(DefaultRankingLimit))
      .nextOption()
      .map(n => math.max(1, math.min(n, 100)))
