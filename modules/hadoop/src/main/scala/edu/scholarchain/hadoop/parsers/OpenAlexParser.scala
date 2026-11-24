package edu.scholarchain.hadoop.parsers

import edu.scholarchain.hadoop.provenance.HadoopLineage
import edu.scholarchain.core.*
import edu.scholarchain.hadoop.util.NormalizationUtils
import ujson.Value

/**
 * Pure-function parser for OpenAlex JSON records. Converts a single JSON object into domain
 * WorkRecord + AuthorMention records.
 */
object OpenAlexParser:

  def parse(
    json: String,
    pipelineRunId: String,
    schemaVersion: String,
    timestamp: String
  ): Either[
    (String, String),
    (WorkRecord, Vector[AuthorMention])
  ] =
    try
      val obj      = ujson.read(json).obj
      val sourceId = obj.get("source_id").map(_.str).getOrElse("")
      if sourceId.isEmpty then return Left(("MISSING_SOURCE_ID", "No source_id in record"))

      val title = obj.get("title").map(_.str).getOrElse("").trim
      if title.isEmpty then
        return Left(
          ("MISSING_TITLE", s"No title in record $sourceId")
        )

      val year = obj.get("year") match
        case Some(v) if !v.isNull => v.num.toInt
        case _ =>
          return Left(
            ("MISSING_YEAR", s"No year in record $sourceId")
          )

      val doi = obj
        .get("doi")
        .filter(!_.isNull)
        .map(_.str)
        .flatMap(NormalizationUtils.normalizeDoi)

      val venue = obj
        .get("venue")
        .filter(!_.isNull)
        .flatMap(v => v.obj.get("name"))
        .filter(!_.isNull)
        .map(_.str)

      val provenance = HadoopLineage.OpenAlexNormalization.stamp(
        Provenance(
          sourceDataset = "openalex",
          sourceRecordId = sourceId,
          pipelineRunId = pipelineRunId,
          schemaVersion = schemaVersion,
          timestamp = timestamp
        )
      )

      val authorsArr = obj
        .get("authors")
        .filter(!_.isNull)
        .map(_.arr.toVector)
        .getOrElse(Vector.empty)

      if authorsArr.isEmpty then
        return Left(
          ("MISSING_AUTHORS", s"No authors in record $sourceId")
        )

      val mentions = authorsArr.zipWithIndex.map { (a, idx) =>
        val aObj    = a.obj
        val rawName = aObj.get("name").map(_.str).getOrElse("")
        val orcid = aObj
          .get("orcid")
          .filter(!_.isNull)
          .map(_.str)
          .flatMap(NormalizationUtils.validateOrcid)
        val openAlexId =
          aObj.get("openalex_id").filter(!_.isNull).map(_.str)
        val affiliation = aObj
          .get("affiliations")
          .filter(!_.isNull)
          .flatMap(_.arr.headOption)
          .flatMap(_.obj.get("name"))
          .filter(!_.isNull)
          .map(_.str)

        AuthorMention(
          mentionId = s"mention:openalex:$sourceId:$idx",
          rawName = rawName,
          normalizedName = NormalizationUtils.normalizeName(rawName),
          position = idx,
          orcid = orcid,
          openAlexId = openAlexId,
          affiliationRaw = affiliation,
          sourceRecordId = sourceId,
          provenance = provenance
        )
      }

      val workId =
        IdGen.workId(SourceType.OpenAlex, sourceId)

      val work = WorkRecord(
        workId = workId,
        source = SourceType.OpenAlex,
        sourceRecordId = sourceId,
        title = title,
        normalizedTitle = NormalizationUtils.normalizeTitle(title),
        year = year,
        venueId = venue.map(NormalizationUtils.normalizeVenue),
        doi = doi,
        arxivId = None,
        authors = mentions,
        abstractTextRef = None,
        provenance = provenance
      )

      Right((work, mentions))
    catch
      case _: ujson.ParseException =>
        Left(("MALFORMED_JSON", "JSON parse error"))
      case e: Exception =>
        Left(("PARSE_ERROR", s"Unexpected error: ${e.getMessage}"))
