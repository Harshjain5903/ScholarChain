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

  private val IdPrefix = "https://openalex.org/"

  /**
   * Split one input blob into single-work JSON strings. A real OpenAlex API response (`{"meta":...,
   * "results":[work,...]}`) yields one string per element of `results`; any other input (one work,
   * or unparseable text, which the parser then rejects) is returned unchanged.
   */
  def splitRecords(blob: String): Vector[String] =
    try
      ujson.read(blob) match
        case o: ujson.Obj if o.value.get("results").exists(_.isInstanceOf[ujson.Arr]) =>
          RawJson.arr(o.value, "results").map(ujson.write(_))
        case _ => Vector(blob)
    catch case _: Exception => Vector(blob)

  /**
   * The flat record shape this parser reads. A real OpenAlex work (`id`, `doi`, `title`,
   * `publication_year`, `authorships[]`, `primary_location.source`) is adapted to it: `id` becomes
   * `source_id` (`openalex:W...`), each authorship an author (`author.id` short ID,
   * `author.display_name` or `raw_author_name`, `author.orcid`, first institution name or raw
   * affiliation string). A record that already has `source_id` is returned unchanged.
   */
  def flatten(
    root: collection.mutable.Map[String, ujson.Value]
  ): collection.Map[String, ujson.Value] =
    if root.contains("source_id") || !root.contains("authorships") && !root.contains("id") then root
    else
      val out = collection.mutable.LinkedHashMap.empty[String, ujson.Value]
      RawJson.str(root, "id").foreach(i => out("source_id") = "openalex:" + i.stripPrefix(IdPrefix))
      RawJson.str(root, "doi").foreach(d => out("doi") = d)
      RawJson
        .str(root, "title")
        .orElse(RawJson.str(root, "display_name"))
        .foreach(t => out("title") = t)
      root
        .get("publication_year")
        .collect { case ujson.Num(n) => n.toInt }
        .foreach(y => out("year") = y)
      RawJson
        .obj(root, "primary_location")
        .flatMap(l => RawJson.obj(l, "source"))
        .flatMap(s => RawJson.str(s, "display_name"))
        .foreach(v => out("venue") = ujson.Obj("name" -> v))
      val authors = RawJson.arr(root, "authorships").collect { case a: ujson.Obj =>
        val av     = a.value
        val author = RawJson.obj(av, "author").getOrElse(Map.empty[String, ujson.Value])
        val name = RawJson
          .str(author, "display_name")
          .filter(_.trim.nonEmpty)
          .orElse(RawJson.str(av, "raw_author_name"))
          .getOrElse("")
        val o = ujson.Obj("name" -> name)
        RawJson.str(author, "id").foreach(i => o("openalex_id") = i.stripPrefix(IdPrefix))
        RawJson
          .str(author, "orcid")
          .orElse(RawJson.str(av, "raw_orcid"))
          .foreach(v => o("orcid") = v)
        val inst = RawJson
          .arr(av, "institutions")
          .collect { case x: ujson.Obj => RawJson.str(x.value, "display_name") }
          .flatten
        val raw  = RawJson.arr(av, "raw_affiliation_strings").collect { case ujson.Str(s) => s }
        val affs = if inst.nonEmpty then inst else raw
        if affs.nonEmpty then
          o("affiliations") = ujson.Arr.from(affs.map(n => ujson.Obj("name" -> n)))
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
        WorkIds.workId(SourceType.OpenAlex, sourceId)

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
