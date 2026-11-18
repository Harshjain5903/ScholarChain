package edu.scholarchain.core

import upickle.default.*

/** A scholarly work ingested from one of the source datasets. */
case class WorkRecord(
    workId: String,
    source: SourceType,
    sourceRecordId: String,
    title: String,
    normalizedTitle: String,
    year: Int,
    venueId: Option[String],
    doi: Option[String],
    arxivId: Option[String],
    authors: Vector[AuthorMention],
    abstractTextRef: Option[String],
    provenance: Provenance
) derives ReadWriter:
  require(year >= 0, s"WorkRecord year must be non-negative, got $year")

/** A publication venue (journal, conference, workshop). */
case class Venue(
    venueId: String,
    name: String,
    normalizedName: String,
    source: SourceType,
    provenance: Provenance
) derives ReadWriter

/** An academic institution or organization. */
case class Institution(
    institutionId: String,
    name: String,
    normalizedName: String,
    source: SourceType,
    provenance: Provenance
) derives ReadWriter

/** A research topic or subject area. */
case class Topic(
    topicId: String,
    name: String,
    normalizedName: String,
    source: SourceType,
    provenance: Provenance
) derives ReadWriter
