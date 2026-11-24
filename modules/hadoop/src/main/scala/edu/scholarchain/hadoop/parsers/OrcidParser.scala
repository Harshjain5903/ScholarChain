package edu.scholarchain.hadoop.parsers

import edu.scholarchain.hadoop.provenance.HadoopLineage
import edu.scholarchain.core.*
import edu.scholarchain.hadoop.util.NormalizationUtils

/**
 * Pure-function parser for ORCID JSON records. Converts a single JSON object into an AuthorMention
 * (representing the ORCID profile) and optionally a CanonicalResearcher record.
 */
object OrcidParser:

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
      val obj      = ujson.read(json).obj
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
