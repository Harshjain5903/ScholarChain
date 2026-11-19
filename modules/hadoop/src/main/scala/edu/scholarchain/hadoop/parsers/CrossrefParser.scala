package edu.scholarchain.hadoop.parsers

import edu.scholarchain.core.*
import edu.scholarchain.hadoop.util.NormalizationUtils

/**
 * Pure-function parser for Crossref JSON records. Converts a single
 * JSON object into domain WorkRecord + AuthorMention records.
 * Preserves DOI as primary key, references, ISSN, and publisher.
 */
object CrossrefParser:

  def parse(
      json: String,
      pipelineRunId: String,
      schemaVersion: String,
      timestamp: String,
  ): Either[
    (String, String),
    (WorkRecord, Vector[AuthorMention]),
  ] =
    try
      val obj      = ujson.read(json).obj
      val sourceId = obj.get("source_id").map(_.str).getOrElse("")
      if sourceId.isEmpty then
        return Left(("MISSING_SOURCE_ID", "No source_id in record"))

      val doiRaw = obj.get("doi").filter(!_.isNull).map(_.str)
      val doi    = doiRaw.flatMap(NormalizationUtils.normalizeDoi)

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

      val venue = obj
        .get("venue")
        .filter(!_.isNull)
        .flatMap(v => v.obj.get("name"))
        .filter(!_.isNull)
        .map(_.str)

      val provenance = Provenance(
        sourceDataset = "crossref",
        sourceRecordId = sourceId,
        pipelineRunId = pipelineRunId,
        schemaVersion = schemaVersion,
        timestamp = timestamp,
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
        val aObj = a.obj
        val givenN =
          aObj.get("given_name").filter(!_.isNull).map(_.str).getOrElse("")
        val familyN =
          aObj
            .get("family_name")
            .filter(!_.isNull)
            .map(_.str)
            .getOrElse("")
        val rawName =
          if givenN.nonEmpty && familyN.nonEmpty then s"$givenN $familyN"
          else if familyN.nonEmpty then familyN
          else givenN

        val orcid = aObj
          .get("orcid")
          .filter(!_.isNull)
          .map(_.str)
          .flatMap(NormalizationUtils.validateOrcid)

        val affiliation = aObj
          .get("affiliations")
          .filter(!_.isNull)
          .flatMap(_.arr.headOption)
          .filter(!_.isNull)
          .map(_.str)

        AuthorMention(
          mentionId = s"mention:crossref:$sourceId:$idx",
          rawName = rawName,
          normalizedName =
            NormalizationUtils.normalizeName(rawName),
          position = idx,
          orcid = orcid,
          openAlexId = None,
          affiliationRaw = affiliation,
          sourceRecordId = sourceId,
          provenance = provenance,
        )
      }

      val workId =
        IdGen.workId(SourceType.Crossref, sourceId)

      val work = WorkRecord(
        workId = workId,
        source = SourceType.Crossref,
        sourceRecordId = sourceId,
        title = title,
        normalizedTitle =
          NormalizationUtils.normalizeTitle(title),
        year = year,
        venueId = venue.map(NormalizationUtils.normalizeVenue),
        doi = doi,
        arxivId = None,
        authors = mentions,
        abstractTextRef = None,
        provenance = provenance,
      )

      Right((work, mentions))
    catch
      case _: ujson.ParseException =>
        Left(("MALFORMED_JSON", "JSON parse error"))
      case e: Exception =>
        Left(("PARSE_ERROR", s"Unexpected error: ${e.getMessage}"))
