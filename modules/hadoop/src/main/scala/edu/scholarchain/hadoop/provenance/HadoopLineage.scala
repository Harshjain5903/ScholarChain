package edu.scholarchain.hadoop.provenance

import edu.scholarchain.core.Provenance

/**
 * Lineage identity of one ScholarChain MapReduce job, stamped onto the provenance of every record
 * the job emits (CLAUDE.md "Provenance required", review finding N44).
 *
 * @param jobId
 *   stable job identifier from the Hadoop job catalog, for example `J07`
 * @param jobName
 *   the job's driver name (`<Job>.JobName`), for example `pair-expansion`
 */
final case class JobLineage(jobId: String, jobName: String):

  /** `Provenance.transformationStep` value: the job's driver name. */
  val transformationStep: String = jobName

  /** `Provenance.producer` value, for example `hadoop/J07-pair-expansion`. */
  val producer: String = s"hadoop/$jobId-$jobName"

  /**
   * Return `p` with this job's transformation step and producer. `upstreamRunIds` (pipeline run IDs
   * of the input records) are added when given; empty IDs and the record's own `pipelineRunId` are
   * dropped (an upstream ID equal to the current run adds no lineage, review finding N56), and the
   * result is sorted and de-duplicated so the output is deterministic.
   */
  def stamp(p: Provenance, upstreamRunIds: Iterable[String] = Nil): Provenance =
    val upstream = (p.upstreamRunIds ++ upstreamRunIds)
      .filter(id => id.nonEmpty && id != p.pipelineRunId)
      .distinct
      .sorted
    p.copy(
      transformationStep = Some(transformationStep),
      producer = Some(producer),
      upstreamRunIds = upstream
    )

/** Lineage identities of the Hadoop jobs that emit new or re-stamped records. */
object HadoopLineage:
  val DblpExtraction: JobLineage        = JobLineage("J01", "dblp-extraction")
  val OpenAlexNormalization: JobLineage = JobLineage("J02a", "openalex-normalization")
  val OrcidNormalization: JobLineage    = JobLineage("J02b", "orcid-normalization")
  val CrossrefNormalization: JobLineage = JobLineage("J02c", "crossref-normalization")
  val FeatureConstruction: JobLineage   = JobLineage("J05", "feature-construction")
  val TeamExtraction: JobLineage        = JobLineage("J06", "team-extraction")
  val PairExpansion: JobLineage         = JobLineage("J07", "pair-expansion")

  val All: Vector[JobLineage] = Vector(
    DblpExtraction,
    OpenAlexNormalization,
    OrcidNormalization,
    CrossrefNormalization,
    FeatureConstruction,
    TeamExtraction,
    PairExpansion
  )
