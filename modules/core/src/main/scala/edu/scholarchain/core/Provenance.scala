package edu.scholarchain.core

import upickle.default.*

/**
 * Tracks the origin and lineage of every domain record.
 *
 * The first five fields are the original contract. The lineage fields (review finding Pass 6 N44,
 * CLAUDE.md "Provenance required") were added later with defaults, so JSON written before they
 * existed still reads, existing positional and named constructor calls still compile, and upickle
 * omits them from output while they hold their default values.
 *
 * @param transformationStep
 *   the transformation step that produced the record, for example `entity-resolution`
 * @param producer
 *   the component or job name that produced the record, for example `ScholarChainPipeline`
 * @param upstreamRunIds
 *   pipeline run IDs of the inputs this record was derived from
 * @param policyVersion
 *   version of the decision policy applied, for example `er-mention-v1-uncalibrated`
 */
case class Provenance(
  sourceDataset: String,
  sourceRecordId: String,
  pipelineRunId: String,
  schemaVersion: String,
  timestamp: String,
  transformationStep: Option[String] = None,
  producer: Option[String] = None,
  upstreamRunIds: Vector[String] = Vector.empty,
  policyVersion: Option[String] = None
) derives ReadWriter:
  require(sourceDataset.nonEmpty, "sourceDataset must not be empty")
  require(pipelineRunId.nonEmpty, "pipelineRunId must not be empty")
  require(schemaVersion.nonEmpty, "Provenance schemaVersion must not be empty")
  require(transformationStep.forall(_.nonEmpty), "transformationStep must not be Some(\"\")")
  require(producer.forall(_.nonEmpty), "producer must not be Some(\"\")")
  require(policyVersion.forall(_.nonEmpty), "policyVersion must not be Some(\"\")")
  require(upstreamRunIds.forall(_.nonEmpty), "upstreamRunIds must not contain empty strings")
