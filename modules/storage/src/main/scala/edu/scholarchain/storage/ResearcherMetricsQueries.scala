package edu.scholarchain.storage

import edu.scholarchain.core.GraphSchema

/**
 * Metric window of a [[edu.scholarchain.core.ResearcherMetrics]] row: the inclusive publication
 * years `startYear..endYear` the Spark job computed the metrics over (STO-P13-02). Matched exactly
 * against the `windowStartYear` / `windowEndYear` properties of the `Researcher` vertex.
 */
final case class MetricWindow(startYear: Int, endYear: Int):
  require(startYear <= endYear, s"startYear ($startYear) must be <= endYear ($endYear)")

/**
 * Allowlist of researcher metrics that [[NeptuneStorageClient.topResearchersBy]] may rank by
 * (STO-P13-02).
 *
 * The ranking property name has to be placed in the openCypher query text (`ORDER BY r.<key>`
 * cannot be a parameter), so it is only ever taken from [[propertyKey]] of one of these cases,
 * which in turn comes from [[GraphSchema.PropertyKey]]. Caller text is matched against [[wireName]]
 * and never spliced.
 *
 * @param wireName
 *   the public name callers use to select the metric
 * @param propertyKey
 *   the `Researcher` vertex property the ranking orders by
 */
enum RankableResearcherMetric(val wireName: String, val propertyKey: String):

  case DistinctCoauthorCount
      extends RankableResearcherMetric(
        "distinctCoauthorCount",
        GraphSchema.PropertyKey.DistinctCoauthorCount
      )

  case PublicationCount
      extends RankableResearcherMetric("publicationCount", GraphSchema.PropertyKey.PublicationCount)

  case WeightedDegree
      extends RankableResearcherMetric("weightedDegree", GraphSchema.PropertyKey.WeightedDegree)

  case MaximumCoauthorsOnOnePaper
      extends RankableResearcherMetric(
        "maximumCoauthorsOnOnePaper",
        GraphSchema.PropertyKey.MaximumCoauthorsOnOnePaper
      )

  case BridgeScore
      extends RankableResearcherMetric("bridgeScore", GraphSchema.PropertyKey.BridgeScore)
  case Coreness extends RankableResearcherMetric("coreness", GraphSchema.PropertyKey.Coreness)

object RankableResearcherMetric:

  /** Every allowed wire name, in declaration order. */
  val AllowedNames: Vector[String] = values.toVector.map(_.wireName)

  /**
   * The metric whose [[RankableResearcherMetric.wireName]] equals `name` exactly (case-sensitive),
   * or `Left` listing the allowed names. The message does not echo `name`, so hostile caller text
   * never reaches logs through it.
   */
  def fromName(name: String): Either[String, RankableResearcherMetric] =
    values
      .find(_.wireName == name)
      .toRight(
        s"unsupported ranking metric; expected one of ${AllowedNames.mkString(", ")}"
      )
