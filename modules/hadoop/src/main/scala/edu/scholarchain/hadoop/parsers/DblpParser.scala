package edu.scholarchain.hadoop.parsers

import edu.scholarchain.core.*
import edu.scholarchain.hadoop.util.NormalizationUtils
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.{Document, Element, NodeList}
import org.xml.sax.InputSource

/**
 * Pure-function parser that converts a DBLP XML record string into domain types (WorkRecord +
 * AuthorMention vector). Returns Either with a reason code on failure.
 */
object DblpParser:

  /** DBLP record types recognized by the pipeline. */
  val RecordTypes: Set[String] = Set(
    "article",
    "inproceedings",
    "proceedings",
    "book",
    "incollection",
    "phdthesis",
    "mastersthesis"
  )

  /**
   * Parse a complete DBLP XML element string into domain records. Returns (WorkRecord,
   * AuthorMentions, recordType) where recordType is the XML element tag (article, inproceedings,
   * proceedings, etc.).
   */
  def parse(
    xml: String,
    pipelineRunId: String,
    schemaVersion: String,
    timestamp: String
  ): Either[
    (String, String),
    (WorkRecord, Vector[AuthorMention], String)
  ] =
    try
      val factory = DocumentBuilderFactory.newInstance()
      // Disable DTD loading to avoid network access and validation errors
      factory.setFeature(
        "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        false
      )
      factory.setFeature(
        "http://xml.org/sax/features/external-general-entities",
        false
      )
      factory.setFeature(
        "http://xml.org/sax/features/external-parameter-entities",
        false
      )
      val builder  = factory.newDocumentBuilder()
      val doc      = builder.parse(new InputSource(new StringReader(xml)))
      val elem     = doc.getDocumentElement
      val elemName = elem.getTagName

      if !RecordTypes.contains(elemName) then
        return Left(("UNSUPPORTED_TYPE", s"Unknown element: $elemName"))

      val dblpKey = elem.getAttribute("key")
      if dblpKey == null || dblpKey.isEmpty then
        return Left(("MISSING_KEY", "No key attribute on element"))

      val sourceRecordId = s"dblp:$dblpKey"
      val title          = getChildText(elem, "title").trim
      if title.isEmpty then return Left(("MISSING_TITLE", s"No title in record $dblpKey"))

      val yearText = getChildText(elem, "year").trim
      val year =
        if yearText.isEmpty then
          return Left(
            ("MISSING_YEAR", s"No year in record $dblpKey")
          )
        else
          try yearText.toInt
          catch
            case _: NumberFormatException =>
              return Left(
                ("INVALID_YEAR", s"Invalid year '$yearText' in $dblpKey")
              )

      val venue = extractVenue(elem)
      val ees   = getChildTexts(elem, "ee")
      val doi = ees
        .find(e => e.contains("doi.org") || e.startsWith("10."))
        .flatMap(NormalizationUtils.normalizeDoi)

      val arxivId = ees
        .find(_.contains("arxiv.org"))
        .flatMap(NormalizationUtils.normalizeArxivId)

      val authorNames = getChildTexts(elem, "author")
      if authorNames.isEmpty then
        return Left(
          ("MISSING_AUTHORS", s"No authors in record $dblpKey")
        )

      val provenance = Provenance(
        sourceDataset = "dblp",
        sourceRecordId = sourceRecordId,
        pipelineRunId = pipelineRunId,
        schemaVersion = schemaVersion,
        timestamp = timestamp
      )

      val mentions = authorNames.zipWithIndex.map { (rawName, idx) =>
        AuthorMention(
          mentionId = s"mention:dblp:$dblpKey:$idx",
          rawName = rawName.trim,
          normalizedName = NormalizationUtils.normalizeName(rawName.trim),
          position = idx,
          orcid = None,
          openAlexId = None,
          affiliationRaw = None,
          sourceRecordId = sourceRecordId,
          provenance = provenance
        )
      }

      val workId          = IdGen.workId(SourceType.DBLP, sourceRecordId)
      val normalizedVenue = venue.map(NormalizationUtils.normalizeVenue)

      val work = WorkRecord(
        workId = workId,
        source = SourceType.DBLP,
        sourceRecordId = sourceRecordId,
        title = title,
        normalizedTitle = NormalizationUtils.normalizeTitle(title),
        year = year,
        venueId = normalizedVenue,
        doi = doi,
        arxivId = arxivId,
        authors = mentions,
        abstractTextRef = None,
        provenance = provenance
      )

      Right((work, mentions, elemName))
    catch
      case e: org.xml.sax.SAXParseException =>
        Left(("MALFORMED_XML", s"XML parse error: ${e.getMessage}"))
      case e: org.xml.sax.SAXException =>
        Left(("MALFORMED_XML", s"XML error: ${e.getMessage}"))
      case e: Exception =>
        Left(("PARSE_ERROR", s"Unexpected error: ${e.getMessage}"))

  /** Get the text content of the first child element with the given tag. */
  private def getChildText(parent: Element, tag: String): String =
    val nodes = parent.getElementsByTagName(tag)
    if nodes.getLength > 0 then nodes.item(0).getTextContent
    else ""

  /** Get text content of all child elements with the given tag. */
  private def getChildTexts(
    parent: Element,
    tag: String
  ): Vector[String] =
    val nodes = parent.getElementsByTagName(tag)
    (0 until nodes.getLength).map { i =>
      nodes.item(i).getTextContent
    }.toVector

  /** Extract the venue name from a DBLP XML element. */
  private def extractVenue(elem: Element): Option[String] =
    val journal   = getChildText(elem, "journal").trim
    val booktitle = getChildText(elem, "booktitle").trim
    if journal.nonEmpty then Some(journal)
    else if booktitle.nonEmpty then Some(booktitle)
    else None
