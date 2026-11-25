package edu.scholarchain.hadoop.parsers

import edu.scholarchain.hadoop.provenance.HadoopLineage
import edu.scholarchain.core.*
import edu.scholarchain.hadoop.util.NormalizationUtils

/**
 * Pure-function parser for ORCID JSON records. Converts a single JSON object into an AuthorMention
 * (representing the ORCID profile) and optionally a CanonicalResearcher record.
 */
object OrcidParser:

  private def nested(
    m: collection.Map[String, ujson.Value],
    path: String*
  ): Option[collection.Map[String, ujson.Value]] =
    path.foldLeft(Option(m))((acc, k) => acc.flatMap(RawJson.obj(_, k)))

  private def value(m: Option[collection.Map[String, ujson.Value]], key: String): Option[String] =
    m.flatMap(RawJson.obj(_, key)).flatMap(RawJson.str(_, "value")).filter(_.trim.nonEmpty)

  /**
   * The flat record shape this parser reads. A real ORCID public API record (`GET
   * https://pub.orcid.org/v3.0/<orcid>/record`) is adapted to it: `orcid-identifier.path` becomes
   * `orcid` and `source_id` (`orcid:<id>`), the name is `person.name.credit-name` or `given-names
   * family-name`, `person.other-names.other-name[].content` the name variants, and the
   * organizations of `activities-summary.employments` the affiliations. A record that already has
   * `source_id` is returned unchanged. A record with no readable name (private) is rejected as
   * MISSING_NAME by the caller.
   */
  def flatten(
    root: collection.mutable.Map[String, ujson.Value]
  ): collection.Map[String, ujson.Value] =
    if root.contains("source_id") || !root.contains("orcid-identifier") then root
    else
      val out  = collection.mutable.LinkedHashMap.empty[String, ujson.Value]
      val path = nested(root, "orcid-identifier").flatMap(RawJson.str(_, "path"))
      path.foreach { p =>
        out("source_id") = s"orcid:$p"
        out("orcid") = p
      }
      val name   = nested(root, "person", "name")
      val givenN = value(name, "given-names")
      val famN   = value(name, "family-name")
      givenN.foreach(g => out("given_name") = g)
      famN.foreach(f => out("family_name") = f)
      value(name, "credit-name")
        .orElse(Option(Seq(givenN, famN).flatten.mkString(" ")).filter(_.nonEmpty))
        .foreach(n => out("name") = n)
      val variants = nested(root, "person", "other-names").toVector
        .flatMap(o => RawJson.arr(o, "other-name"))
        .collect { case x: ujson.Obj => RawJson.str(x.value, "content") }
        .flatten
      if variants.nonEmpty then out("name_variants") = ujson.Arr.from(variants.map(ujson.Str(_)))
      val orgs = nested(root, "activities-summary", "employments").toVector
        .flatMap(e => RawJson.arr(e, "affiliation-group"))
        .collect { case g: ujson.Obj => RawJson.arr(g.value, "summaries") }
        .flatten
        .collect { case s: ujson.Obj => nested(s.value, "employment-summary", "organization") }
        .flatten
        .flatMap(RawJson.str(_, "name"))
      if orgs.nonEmpty then
        out("affiliations") = ujson.Arr.from(orgs.map(n => ujson.Obj("organization" -> n)))
      out

  def parse(
    json: String,
    pipelineRunId: String,
    schemaVersion: String,
    timestamp: String
  ): Either[
    (String, String),
    (AuthorMention, CanonicalResearcher)
  ] =
    try
      val obj      = flatten(ujson.read(json).obj)
      val sourceId = obj.get("source_id").map(_.str).getOrElse("")
      if sourceId.isEmpty then return Left(("MISSING_SOURCE_ID", "No source_id"))

      val orcidRaw = obj.get("orcid").filter(!_.isNull).map(_.str)
      val orcid    = orcidRaw.flatMap(NormalizationUtils.validateOrcid)
      if orcidRaw.isDefined && orcid.isEmpty then
        return Left(
          (
            "INVALID_ORCID",
            s"Invalid ORCID format: ${orcidRaw.get}"
          )
        )

      val name = obj.get("name").filter(!_.isNull).map(_.str).getOrElse("")
      if name.isEmpty then return Left(("MISSING_NAME", s"No name in record $sourceId"))

      val familyName =
        obj.get("family_name").filter(!_.isNull).map(_.str).getOrElse("")

      val givenName =
        obj.get("given_name").filter(!_.isNull).map(_.str).getOrElse("")

      val nameVariants = obj
        .get("name_variants")
        .filter(!_.isNull)
        .map(_.arr.map(_.str).toVector)
        .getOrElse(Vector.empty)

      val affiliations = obj
        .get("affiliations")
        .filter(!_.isNull)
        .map(_.arr.toVector)
        .getOrElse(Vector.empty)

      val primaryAffiliation = affiliations.headOption
        .flatMap(_.obj.get("organization"))
        .filter(!_.isNull)
        .map(_.str)

      val provenance = HadoopLineage.OrcidNormalization.stamp(
        Provenance(
          sourceDataset = "orcid",
          sourceRecordId = sourceId,
          pipelineRunId = pipelineRunId,
          schemaVersion = schemaVersion,
          timestamp = timestamp
        )
      )

      val mention = AuthorMention(
        mentionId = s"mention:orcid:$sourceId:0",
        rawName = name,
        normalizedName = NormalizationUtils.normalizeName(name),
        position = 0,
        orcid = orcid,
        openAlexId = None,
        affiliationRaw = primaryAffiliation,
        sourceRecordId = sourceId,
        provenance = provenance
      )

      val researcher = CanonicalResearcher(
        researcherId = IdGen.researcherId(orcid, None, name),
        displayName = name,
        orcid = orcid,
        openAlexId = None,
        semanticScholarId = None,
        alternateNames = nameVariants,
        provenance = provenance
      )

      Right((mention, researcher))
    catch
      case _: ujson.ParseException =>
        Left(("MALFORMED_JSON", "JSON parse error"))
      case e: Exception =>
        Left(("PARSE_ERROR", s"Unexpected error: ${e.getMessage}"))
