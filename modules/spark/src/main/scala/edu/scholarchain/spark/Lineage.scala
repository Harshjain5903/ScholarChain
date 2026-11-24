package edu.scholarchain.spark

import edu.scholarchain.core.*

/**
 * Run-level lineage stamped onto every Spark output record (review finding Pass 6 N44, CLAUDE.md
 * "Provenance required").
 *
 * Every value here is derived from the pipeline inputs, never hardcoded: `sourceDataset` is the
 * `sourceDataset` the input records' provenance carries (for example `dblp`), `upstreamRunIds` are
 * the Hadoop and entity-resolution run IDs read from the inputs, and `policyVersion` is the ER
 * policy read from the match-decision records (see [[InputLineage]]).
 *
 * @param runId
 *   this Spark run's ID (`Provenance.pipelineRunId`)
 * @param sourceDataset
 *   upstream source dataset(s) of the records the output was derived from; several distinct values
 *   are joined with `+` in sorted order, and [[InputLineage.UnknownSourceDataset]] is used only
 *   when no input record carries one
 * @param upstreamRunIds
 *   sorted, distinct Hadoop and ER run IDs read from the inputs
 * @param policyVersion
 *   ER match policy version read from the match decisions
 */
final case class Lineage(
  runId: String,
  schemaVersion: String,
  timestamp: String,
  sourceDataset: String,
  upstreamRunIds: Vector[String] = Vector.empty,
  policyVersion: Option[String] = None
):
  require(runId.nonEmpty, "Lineage runId must not be empty")
  require(sourceDataset.nonEmpty, "Lineage sourceDataset must not be empty")

  /** The same lineage for records derived from inputs with a different source dataset. */
  def withSourceDataset(dataset: String): Lineage = copy(sourceDataset = dataset)

  /**
   * Provenance for one output record.
   *
   * @param job
   *   Spark job (object) name; `producer` becomes `spark-analytics/<job>`
   * @param step
   *   transformation step within the job (`transformationStep`)
   * @param sourceRecordId
   *   ID of the output record (or of the input record it mirrors)
   */
  def prov(job: String, step: String, sourceRecordId: String): Provenance =
    Provenance(
      sourceDataset = sourceDataset,
      sourceRecordId = sourceRecordId,
      pipelineRunId = runId,
      schemaVersion = schemaVersion,
      timestamp = timestamp,
      transformationStep = Some(step),
      producer = Some(s"${Lineage.ProducerPrefix}/$job"),
      upstreamRunIds = upstreamRunIds,
      policyVersion = policyVersion
    )

object Lineage:

  /** Producer prefix; `Provenance.producer` is `spark-analytics/<job>`. */
  val ProducerPrefix: String = "spark-analytics"

/**
 * Derivation and validation of the pipeline's input lineage: source dataset, ER policy version and
 * the Hadoop-run / ER-run linkage. Pure functions so that each rule is unit-testable; the pipeline
 * turns a `Left` into an immediate failure before any output is written.
 */
object InputLineage:

  /** Used as `sourceDataset` only when no input record carries a source dataset. */
  val UnknownSourceDataset: String = "unknown"

  /**
   * Reason-code prefix the entity-resolution runner writes onto every match decision to name the
   * Hadoop run whose records it resolved (`input_run:<hadoop run id>`).
   */
  val InputRunReasonPrefix: String = "input_run:"

  /** Upper bound on distinct run IDs / policies / datasets collected to the driver. */
  val MaxDistinctValues: Int = 1000

  /**
   * The source dataset value for records derived from inputs whose provenance carried `observed`.
   * Distinct non-empty values are sorted and joined with `+`; no value is invented.
   */
  def sourceDatasetOf(observed: Iterable[String]): String =
    val values = observed.iterator.filter(v => v != null && v.nonEmpty).toVector.distinct.sorted
    if values.isEmpty then UnknownSourceDataset else values.mkString("+")

  /**
   * The ER policy version for this run, read from the match decisions.
   *
   *   - exactly one observed policy: it is used; an explicit `--policy-version` must equal it
   *   - more than one observed policy: error (decisions from different policies cannot be mixed)
   *   - no decisions: the explicit `--policy-version` is required
   */
  def resolvePolicyVersion(
    configured: Option[String],
    observed: Seq[String]
  ): Either[String, String] =
    val policies = observed.distinct.sorted
    policies match
      case Seq(only) =>
        configured match
          case Some(c) if c != only =>
            Left(
              s"--policy-version '$c' disagrees with the match-decision input, whose policyVersion " +
                s"is '$only'; omit --policy-version or pass the policy that produced the decisions"
            )
          case _ => Right(only)
      case Seq() =>
        configured.toRight(
          "the match-decision input contains no decisions, so the ER policy version cannot be " +
            "derived; pass --policy-version explicitly"
        )
      case many =>
        Left(
          s"the match-decision input mixes ${many.size} ER policy versions " +
            s"(${many.mkString(", ")}); refusing to build one graph from several policies"
        )

  /** Hadoop run IDs named by `input_run:` reason codes of one decision. */
  def erInputRunIds(reasonCodes: Seq[String]): Seq[String] =
    reasonCodes.collect {
      case c if c.startsWith(InputRunReasonPrefix) && c.length > InputRunReasonPrefix.length =>
        c.stripPrefix(InputRunReasonPrefix)
    }

  /** Outcome of a successful ER linkage check. */
  object Linkage:

    /** Every decision names its Hadoop input run, and each named run is a pair-candidate run. */
    val Verified: String = "verified"

    /** There are no decisions, so there is nothing to link. */
    val NoDecisions: String = "no-decisions"

    /** Some decisions name no input run and `--allow-unlinked-er-input true` accepted that. */
    val UnverifiedAllowed: String = "unverified-allowed"

  /**
   * Check that the match decisions were produced from the same Hadoop run as the pair candidates.
   *
   * @param pairRunIds
   *   `pipelineRunId` values of the pair-candidate records
   * @param erInputRunIds
   *   Hadoop run IDs named by the decisions' `input_run:` reason codes
   * @param decisionCount
   *   number of match decisions
   * @param decisionsWithoutInputRun
   *   number of decisions that carry no `input_run:` reason code
   * @param allowUnlinked
   *   accept decisions that name no input run (the linkage then cannot be verified)
   * @return
   *   a [[Linkage]] status, or an error when the runs disagree or cannot be verified
   */
  def checkErLinkage(
    pairRunIds: Set[String],
    erInputRunIds: Set[String],
    decisionCount: Long,
    decisionsWithoutInputRun: Long,
    allowUnlinked: Boolean
  ): Either[String, String] =
    val foreign = erInputRunIds.diff(pairRunIds)
    if decisionCount == 0L then Right(Linkage.NoDecisions)
    else if foreign.nonEmpty then
      Left(
        s"match decisions were produced from Hadoop run(s) ${foreign.toSeq.sorted.mkString(", ")}" +
          s", but the pair candidates come from run(s) ${pairRunIds.toSeq.sorted.mkString(", ")};" +
          " rerun entity resolution on this Hadoop export"
      )
    else if decisionsWithoutInputRun > 0L && !allowUnlinked then
      Left(
        s"$decisionsWithoutInputRun of $decisionCount match decisions carry no " +
          s"'$InputRunReasonPrefix<hadoop run id>' reason code, so they cannot be verified to " +
          "belong to the Hadoop run of the pair candidates; rerun entity resolution with " +
          "--input-run-id, or pass --allow-unlinked-er-input true to accept unverified decisions"
      )
    else if decisionsWithoutInputRun > 0L then Right(Linkage.UnverifiedAllowed)
    else Right(Linkage.Verified)
