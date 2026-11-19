package edu.scholarchain.core

import upickle.default.*

/** Tracks the origin and lineage of every domain record. */
case class Provenance(
  sourceDataset: String,
  sourceRecordId: String,
  pipelineRunId: String,
  schemaVersion: String,
  timestamp: String
) derives ReadWriter:
  require(sourceDataset.nonEmpty, "sourceDataset must not be empty")
  require(pipelineRunId.nonEmpty, "pipelineRunId must not be empty")
  require(schemaVersion.nonEmpty, "Provenance schemaVersion must not be empty")
