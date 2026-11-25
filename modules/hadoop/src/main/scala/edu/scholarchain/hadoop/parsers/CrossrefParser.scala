package edu.scholarchain.hadoop.parsers

import edu.scholarchain.hadoop.provenance.HadoopLineage
import edu.scholarchain.core.*
import edu.scholarchain.hadoop.util.NormalizationUtils

/**
 * Pure-function parser for Crossref JSON records. Converts a single JSON object into domain
 * WorkRecord + AuthorMention records. Preserves DOI as primary key, references, ISSN, and
 * publisher.
 */
object CrossrefParser:

  /**
   * The flat record shape this parser reads. Real Crossref `/works/<doi>` responses
   * (`{"status":..., "message": {work}}`, or the bare work object) are adapted to it: DOI becomes
   * `source_id` (`crossref:<normalized doi>`), `title[0]` the title, the first of `issued`,
   * `published-print`, `published-online`, `published` the year, `container-title[0]` the venue,
   * and `author[]` (`givenN`, `family`, `name` for organizations, `ORCID`, `affiliation[].name`)
   * the authors. A record that already has `source_id` is returned unchanged.
   */
  def flatten(
    root: collection.mutable.Map[String, ujson.Value]
  ): collection.Map[String, ujson.Value] =
    if root.contains("source_id") then root
    else
      RawJson.obj(root, "message") match
        case Some(m)                      => fromWork(m)
        case None if root.contains("DOI") => fromWork(root)
        case None                         => root

  private def fromWork(
    m: collection.Map[String, ujson.Value]
  ): collection.Map[String, ujson.Value] =
    val out = collection.mutable.LinkedHashMap.empty[String, ujson.Value]
    val doi = RawJson.str(m, "DOI").flatMap(NormalizationUtils.normalizeDoi)
    doi.foreach { d =>
      out("source_id") = s"crossref:$d"
      out("doi") = d
    }
    RawJson.firstStr(m, "title").foreach(t => out("title") = t)
    List("issued", "published-print", "published-online", "published").iterator
      .flatMap(k => RawJson.datePartsYear(m, k))
      .nextOption()
      .foreach(y => out("year") = y)
    RawJson.firstStr(m, "container-title").foreach { v =>
      val venue = ujson.Obj("name" -> v)
      RawJson.str(m, "publisher").foreach(p => venue("publisher") = p)
      out("venue") = venue
    }
    val authors = RawJson.arr(m, "author").collect { case a: ujson.Obj =>
      val av     = a.value
      val givenN = RawJson.str(av, "given").filter(_.trim.nonEmpty)
      val fam    = RawJson.str(av, "family").filter(_.trim.nonEmpty)
      val org    = RawJson.str(av, "name").filter(_.trim.nonEmpty)
      val o      = ujson.Obj()
      // Organizational authors carry only `name`; keep it as the givenN name.
      if givenN.isEmpty && fam.isEmpty then org.foreach(n => o("given_name") = n)
      else
        givenN.foreach(g => o("given_name") = g)
        fam.foreach(f => o("family_name") = f)
      RawJson.str(av, "ORCID").foreach(v => o("orcid") = v)
      val affs = RawJson
        .arr(av, "affiliation")
        .collect { case x: ujson.Obj => RawJson.str(x.value, "name") }
        .flatten
      if affs.nonEmpty then o("affiliations") = ujson.Arr.from(affs.map(ujson.Str(_)))
      o
    }
    if authors.nonEmpty then out("authors") = ujson.Arr.from(authors)
    out

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
      val obj      = flatten(ujson.read(json).obj)
      val sourceId = obj.get("source_id").map(_.str).getOrElse("")
      if sourceId.isEmpty then return Left(("MISSING_SOURCE_ID", "No source_id in record"))

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

      val provenance = HadoopLineage.CrossrefNormalization.stamp(
        Provenance(
          sourceDataset = "crossref",
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
          normalizedName = NormalizationUtils.normalizeName(rawName),
          position = idx,
          orcid = orcid,
          openAlexId = None,
          affiliationRaw = affiliation,
          sourceRecordId = sourceId,
          provenance = provenance
        )
      }

      val workId =
        WorkIds.workId(SourceType.Crossref, sourceId)

      val work = WorkRecord(
        workId = workId,
        source = SourceType.Crossref,
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
