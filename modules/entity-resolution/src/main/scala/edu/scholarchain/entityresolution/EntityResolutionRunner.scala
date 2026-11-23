package edu.scholarchain.entityresolution

import edu.scholarchain.core.{MatchDecision, MatchOutcome, WorkRecord}
import upickle.default.*

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Command-line configuration for [[EntityResolutionRunner]]. */
case class RunnerConfig(
  workRecordsDir: Path,
  outputDir: Path,
  runId: String,
  inputRunId: Option[String] = None,
  label: String = "UNLABELED",
  maxBlockSize: Int = 200
)

/** Everything produced by one runner invocation. */
case class RunnerResult(
  decisions: Vector[MatchDecision],
  decisionsFile: Path,
  reviewQueueFile: Path,
  summaryFile: Path,
  summary: ujson.Obj
)

/**
 * Mention-level entity-resolution entry point. Reads Hadoop WorkRecord JSON lines (plain or
 * gzip), blocks author mentions on (surname, first initial), resolves each candidate pair with the
 * mention policy, and writes:
 *   - `<output>/match-decisions/part-00000` -- core MatchDecision JSON lines (Spark input)
 *   - `<output>/match-decisions/_SUCCESS`
 *   - `<output>/review-queue.json` -- Unresolved/Conflict and low-confidence decisions
 *   - `<output>/RUN-SUMMARY.json` -- counts, policy, provenance and sha256 of all files
 *
 * LLM adjudication is always disabled here; the stub adjudicator is wired but never called.
 */
object EntityResolutionRunner:

  val Producer = "entity-resolution-engineer/EntityResolutionRunner"

  val Usage: String =
    "Usage: EntityResolutionRunner --work-records <dir> --output <dir> --run-id <id> " +
      "[--input-run-id <id>] [--label <text>] [--max-block-size <n>]"

  def main(args: Array[String]): Unit =
    parseArgs(args.toVector) match
      case Left(err) =>
        System.err.println(err)
        System.err.println(Usage)
        sys.exit(2)
      case Right(cfg) =>
        val result = run(cfg)
        println(ujson.write(result.summary("counts"), indent = 2))
        println(s"decisions: ${result.decisionsFile}")
        println(s"summary:   ${result.summaryFile}")

  def parseArgs(args: Vector[String]): Either[String, RunnerConfig] =
    if args.size % 2 != 0 then Left("Arguments must be --key value pairs")
    else
      val kv      = args.grouped(2).map(v => v(0) -> v(1)).toMap
      val allowed =
        Set(
          "--work-records",
          "--output",
          "--run-id",
          "--input-run-id",
          "--label",
          "--max-block-size"
        )
      val unknown = kv.keySet -- allowed
      if unknown.nonEmpty then Left(s"Unknown arguments: ${unknown.toVector.sorted.mkString(", ")}")
      else
        for
          wr  <- kv.get("--work-records").toRight("Missing --work-records")
          out <- kv.get("--output").toRight("Missing --output")
          rid <- kv.get("--run-id").filter(_.nonEmpty).toRight("Missing --run-id")
          mbs <- kv.get("--max-block-size") match
            case None    => Right(200)
            case Some(s) => s.toIntOption.filter(_ >= 2).toRight(s"Invalid --max-block-size: $s")
        yield RunnerConfig(
          workRecordsDir = Paths.get(wr),
          outputDir = Paths.get(out),
          runId = rid,
          inputRunId = kv.get("--input-run-id"),
          label = kv.getOrElse("--label", "UNLABELED"),
          maxBlockSize = mbs
        )

  /** Data files of a Hadoop output directory (skips `_SUCCESS`, `_rejects`, `.crc`, hidden). */
  def inputFiles(dir: Path): Vector[Path] =
    require(Files.isDirectory(dir), s"Not a directory: $dir")
    Using.resource(Files.list(dir)) { s =>
      s.iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .filterNot { p =>
          val n = p.getFileName.toString
          n.startsWith("_") || n.startsWith(".") || n.endsWith(".crc")
        }
        .toVector
        .sortBy(_.getFileName.toString)
    }

  /** Read non-blank lines from a plain or `.gz` file as UTF-8. */
  def readLines(file: Path): Vector[String] =
    val raw = Files.newInputStream(file)
    val in  = if file.getFileName.toString.endsWith(".gz") then GZIPInputStream(raw) else raw
    Using.resource(BufferedReader(InputStreamReader(in, StandardCharsets.UTF_8))) { r =>
      Iterator.continually(r.readLine()).takeWhile(_ != null).filter(_.trim.nonEmpty).toVector
    }

  def sha256(file: Path): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(Files.readAllBytes(file)).map("%02x".format(_)).mkString

  /** Map core outcomes onto the MATCH / NON_MATCH / REVIEW vocabulary used in run reports. */
  def reportLabel(o: MatchOutcome): String = o match
    case MatchOutcome.Accepted   => "MATCH"
    case MatchOutcome.Rejected   => "NON_MATCH"
    case MatchOutcome.Unresolved => "REVIEW"
    case MatchOutcome.Conflict   => "REVIEW"

  def run(cfg: RunnerConfig): RunnerResult =
    val files = inputFiles(cfg.workRecordsDir)
    require(files.nonEmpty, s"No input files in ${cfg.workRecordsDir}")
    val works    = files.flatMap(readLines).map(read[WorkRecord](_))
    val mentions = works.flatMap(MentionContext.fromWork)
    val upstream = works.flatMap(_.authors)

    val blocking = MentionBlocker.block(mentions, cfg.maxBlockSize)
    val policy   = MentionResolver.mentionPolicy
    val resolver = MentionResolver(
      policy = policy,
      adjudicator = StubLlmAdjudicator(),
      llmEnabled = false,
      runId = cfg.runId,
      extraReasonCodes = cfg.inputRunId.map(r => s"input_run:$r").toVector
    )
    val decisions = blocking.pairs.map(resolver.resolve).sortBy(d => (d.sourceId, d.targetId))

    val decisionsDir = cfg.outputDir.resolve("match-decisions")
    Files.createDirectories(decisionsDir)
    val decisionsFile = decisionsDir.resolve("part-00000")
    Files.writeString(
      decisionsFile,
      decisions.map(d => write(d) + "\n").mkString,
      StandardCharsets.UTF_8
    )
    val successFile = decisionsDir.resolve("_SUCCESS")
    Files.write(successFile, Array.emptyByteArray)

    val queue = ReviewQueueGenerator.generate(decisions, maxItems = math.max(1, decisions.size))
    val reviewQueueFile = cfg.outputDir.resolve("review-queue.json")
    Files.writeString(reviewQueueFile, ReviewQueueGenerator.toJson(queue), StandardCharsets.UTF_8)

    val summary = buildSummary(
      cfg,
      files,
      works.size,
      mentions,
      upstream.size,
      upstream.count(_.orcid.exists(_.trim.nonEmpty)),
      upstream.count(a =>
        IdentifierNormalizer.hasNonDecomposingLetters(a.rawName)
      ),
      blocking,
      decisions,
      queue.items.size,
      Vector(decisionsFile, successFile, reviewQueueFile)
    )
    val summaryFile = cfg.outputDir.resolve("RUN-SUMMARY.json")
    Files.writeString(summaryFile, ujson.write(summary, indent = 2) + "\n", StandardCharsets.UTF_8)
    RunnerResult(decisions, decisionsFile, reviewQueueFile, summaryFile, summary)

  private def countBy[A](xs: Iterable[A])(key: A => String): ujson.Obj =
    val grouped = xs.groupMapReduce(key)(_ => 1)(_ + _)
    ujson.Obj.from(grouped.toVector.sortBy(_._1).map((k, v) => k -> ujson.Num(v)))

  private def buildSummary(
    cfg: RunnerConfig,
    files: Vector[Path],
    workCount: Int,
    mentions: Vector[MentionContext],
    upstreamMentionCount: Int,
    upstreamOrcidCount: Int,
    nonDecomposingCount: Int,
    blocking: MentionBlockingResult,
    decisions: Vector[MatchDecision],
    reviewQueueSize: Int,
    outputs: Vector[Path]
  ): ujson.Obj =
    val policy = MentionResolver.mentionPolicy
    def path(d: MatchDecision) =
      d.reasonCodes.find(_.startsWith("path:")).map(_.stripPrefix("path:")).getOrElse("unknown")
    val accepted     = decisions.filter(_.decision == MatchOutcome.Accepted)
    val acceptedIds  = accepted.flatMap(d => Vector(d.sourceId, d.targetId)).toSet
    val reasonFilter = Set(
      "dblp_person_key_match",
      "dblp_homonym_suffix_mismatch",
      "dblp_homonym_one_sided",
      "exact_id_match:orcid",
      "orcid_mismatch",
      "given_name_incompatible",
      "llm_disabled"
    )
    ujson.Obj(
      "label"            -> cfg.label,
      "producer"         -> Producer,
      "run_id"           -> cfg.runId,
      "input_run_id"     -> cfg.inputRunId.map(ujson.Str(_)).getOrElse(ujson.Null),
      "generated_at_utc" -> java.time.Instant.now().toString,
      "resolution_level" -> "author-mention (mention:dblp:<key>:<pos>)",
      "calibration" -> ujson.Obj(
        "status" -> "UNCALIBRATED",
        "note" -> ("Weights and thresholds are engineering defaults. They have not been fitted " +
          "or validated against a human-reviewed gold set; precision/recall are unknown.")
      ),
      "llm_adjudication" -> ujson.Obj(
        "enabled"     -> false,
        "adjudicator" -> "StubLlmAdjudicator (never invoked; no model calls)"
      ),
      "policy" -> ujson.Obj(
        "policy_version" -> policy.policyVersion,
        "weights" -> ujson.Obj.from(
          policy.weights.toVector.sortBy(_._1).map((k, v) => k -> ujson.Num(v))
        ),
        "accept_threshold"    -> policy.acceptThreshold,
        "reject_threshold"    -> policy.rejectThreshold,
        "unresolved_band_min" -> policy.unresolvedBandMin,
        "unresolved_band_max" -> policy.unresolvedBandMax,
        "deterministic_rules" -> ujson.Arr(
          "orcid both present: equal -> Accepted, different -> Rejected",
          "DBLP homonym suffix both present: same base+suffix -> Accepted, different -> Rejected",
          "given-name gate: incompatible first given names -> Rejected",
          "exactly one DBLP homonym suffix: score-Accepted downgraded to Unresolved"
        ),
        "blocking" -> ("name:<surname>:<first-initial> of suffix-stripped re-normalized raw " +
          s"name; same-work pairs excluded; blocks larger than ${cfg.maxBlockSize} skipped")
      ),
      "inputs" -> ujson.Obj(
        "work_records_dir" -> cfg.workRecordsDir.toString,
        "files" -> ujson.Arr.from(files.map { f =>
          ujson.Obj("path" -> f.toString, "sha256" -> sha256(f), "size_bytes" -> Files.size(f))
        })
      ),
      "counts" -> ujson.Obj(
        "work_records"              -> workCount,
        "upstream_author_mentions"  -> upstreamMentionCount,
        "resolvable_mentions"       -> mentions.size,
        "blocks"                    -> blocking.blockCount,
        "blocks_with_2plus"         -> blocking.blockSizes.count(_._2 >= 2),
        "oversized_blocks_skipped"  -> blocking.oversizedBlocks.size,
        "same_work_pairs_skipped"   -> blocking.sameWorkPairsSkipped,
        "candidate_pairs"           -> blocking.pairs.size,
        "decisions"                 -> decisions.size,
        "by_decision"               -> countBy(decisions)(d => reportLabel(d.decision)),
        "by_core_outcome"           -> countBy(decisions)(_.decision.toString),
        "by_path"                   -> countBy(decisions)(path),
        "by_rule_reason"            -> countBy(
          decisions.flatMap(_.reasonCodes.filter(reasonFilter.contains))
        )(identity),
        "mentions_in_accepted_pairs" -> acceptedIds.size,
        "review_queue_items"        -> reviewQueueSize
      ),
      "data_quality_effects" -> ujson.Obj(
        "upstream_mentions_with_orcid" -> upstreamOrcidCount,
        "mentions_with_dblp_homonym_suffix" -> mentions.count(_.dblpHomonymSuffix.isDefined),
        "mentions_with_non_decomposing_letters" -> nonDecomposingCount,
        "note" -> ("Names are re-normalized from rawName: the DBLP homonym suffix that the Hadoop " +
          "normalizedName strips is kept as a separate signal, and ae/o-stroke etc. are " +
          "transliterated. ORCID-based rules cannot fire when upstream ORCIDs are absent.")
      ),
      "outputs" -> ujson.Arr.from(outputs.map { f =>
        ujson.Obj("path" -> f.toString, "sha256" -> sha256(f), "size_bytes" -> Files.size(f))
      }),
      "spark_match_decisions_arg" -> cfg.outputDir.resolve("match-decisions").toString
    )
