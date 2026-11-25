package edu.scholarchain.entityresolution

import edu.scholarchain.core.{MatchDecision, WorkRecord}
import upickle.default.*

import java.nio.file.{Path, Paths}

/** One sampled candidate pair, ready to be written as a gold-candidate CSV row. */
case class GoldCandidate(
  pairId: String,
  stratum: String,
  hardNegativeStratum: String,
  scoreBand: String,
  decision: MatchDecision,
  a: MentionView,
  b: MentionView,
  matcherScore: Double,
  matcherPath: String
)

/** Human-facing view of one author mention and its work. */
case class MentionView(
  mentionId: String,
  sourceRecordId: String,
  rawName: String,
  title: String,
  year: Option[Int],
  venue: Option[String],
  coauthors: Vector[String],
  orcid: Option[String]
)

/** Sampler configuration. */
case class SamplerConfig(
  erOutputDir: Path,
  workRecordsDir: Path,
  output: Path,
  seed: Long = GoldSetSampler.DefaultSeed,
  size: Int = GoldSetSampler.DefaultSize
)

/** Result of a sampler run. */
case class SamplerResult(
  candidates: Vector[GoldCandidate],
  splits: Map[String, String],
  csvFile: Path,
  manifestFile: Path,
  manifest: ujson.Obj
)

/**
 * Selects candidate pairs for HUMAN gold labeling from an EntityResolutionRunner output directory
 * and the Hadoop `work_records` export it was computed from.
 *
 * This is selection only: it never assigns a gold label. Pairs are stratified by (match path, score
 * band, hard-negative stratum); the calibration/holdout split is fixed here, by stratified
 * deterministic hashing over (seed, pair_id), before any label exists. The output CSV carries empty
 * `human_label` and `notes` columns.
 */
object GoldSetSampler:

  val Producer: String  = "entity-resolution-engineer/GoldSetSampler"
  val DefaultSeed: Long = 20261124L
  val DefaultSize: Int  = 170

  /** Fraction of the sample assigned to calibration (102 of 170). */
  val CalibrationFraction: Double = 0.6

  val Calibration = "calibration"
  val Holdout     = "holdout"

  /** Pairs whose decision depends on accept/reject thresholds (the weighted-score paths). */
  val ScoredPaths: Set[String] = Set(
    "deterministic_bibliographic",
    "high_probabilistic",
    "unresolved_band",
    "low_probabilistic",
    "rejected"
  )

  /**
   * Hard-negative strata (pre-label, selection only). All pairs share surname + first initial by
   * construction of the blocking key.
   */
  val HardNegativeStrata: Set[String] =
    Set("dblp_suffix_differs", "dblp_suffix_one_sided", "same_full_name", "compatible_given_name")

  val Header: Vector[String] = Vector(
    "pair_id",
    "split",
    "stratum",
    "hard_negative_stratum",
    "score_band",
    "mention_id_a",
    "mention_id_b",
    "source_record_a",
    "source_record_b",
    "raw_name_a",
    "raw_name_b",
    "title_a",
    "title_b",
    "year_a",
    "year_b",
    "venue_a",
    "venue_b",
    "coauthors_a",
    "coauthors_b",
    "orcid_a",
    "orcid_b",
    "matcher_score",
    "matcher_confidence",
    "matcher_decision",
    "matcher_path",
    "policy_version",
    "er_run_id",
    "sample_seed",
    "human_label",
    "notes"
  )

  val Usage: String =
    "Usage: GoldSetSampler --er-output <runner output dir> --work-records <dir> " +
      "--output <csv path outside modules/> [--seed <long>] [--size <n>]"

  def main(args: Array[String]): Unit =
    parseArgs(args.toVector) match
      case Left(err) =>
        System.err.println(err)
        System.err.println(Usage)
        sys.exit(2)
      case Right(cfg) =>
        val res = run(cfg)
        println(ujson.write(res.manifest("counts"), indent = 2))
        println(s"csv:      ${res.csvFile}")
        println(s"manifest: ${res.manifestFile}")

  def parseArgs(args: Vector[String]): Either[String, SamplerConfig] =
    if args.size % 2 != 0 then Left("Arguments must be --key value pairs")
    else
      val kv      = args.grouped(2).map(v => v(0) -> v(1)).toMap
      val allowed = Set("--er-output", "--work-records", "--output", "--seed", "--size")
      val unknown = kv.keySet -- allowed
      if unknown.nonEmpty then Left(s"Unknown arguments: ${unknown.toVector.sorted.mkString(", ")}")
      else
        for
          er   <- kv.get("--er-output").toRight("Missing --er-output")
          wr   <- kv.get("--work-records").toRight("Missing --work-records")
          out  <- kv.get("--output").toRight("Missing --output")
          safe <- GoldSetIO.outsideModules(Paths.get(out))
          seed <- kv.get("--seed") match
            case None    => Right(DefaultSeed)
            case Some(s) => s.toLongOption.toRight(s"Invalid --seed: $s")
          size <- kv.get("--size") match
            case None    => Right(DefaultSize)
            case Some(s) => s.toIntOption.filter(_ >= 2).toRight(s"Invalid --size: $s")
        yield SamplerConfig(Paths.get(er), Paths.get(wr), safe, seed, size)

  /** Stable pair identifier derived only from the two mention ids. */
  def pairId(sourceId: String, targetId: String): String =
    "gp-" + GoldSetIO.sha256Hex(s"$sourceId|$targetId").take(16)

  def pathOf(d: MatchDecision): String =
    d.reasonCodes.find(_.startsWith("path:")).map(_.stripPrefix("path:")).getOrElse("unknown")

  /** Score band of the weighted feature score (selection stratum only). */
  def scoreBand(score: Double): String =
    if score < 0.20 then "s0.00-0.20"
    else if score < 0.35 then "s0.20-0.35"
    else if score < 0.50 then "s0.35-0.50"
    else if score < 0.70 then "s0.50-0.70"
    else "s0.70-1.00"

  /** Hard-negative stratum of a mention pair, from name structure only (never from labels). */
  def hardNegativeStratum(a: MentionContext, b: MentionContext): String =
    (a.dblpHomonymSuffix, b.dblpHomonymSuffix) match
      case (Some(x), Some(y)) if x != y              => "dblp_suffix_differs"
      case (Some(_), None) | (None, Some(_))         => "dblp_suffix_one_sided"
      case _ if a.normalizedName == b.normalizedName => "same_full_name"
      case _ if MentionResolver.givenNamesCompatible(a.normalizedName, b.normalizedName) =>
        "compatible_given_name"
      case _ => "incompatible_given_name"

  private def hash(seed: Long, purpose: String, id: String): String =
    GoldSetIO.sha256Hex(s"$seed|$purpose|$id")

  /**
   * Stratified deterministic selection. Strata are visited in key order; within a stratum, pairs
   * are ordered by sha256(seed|select|pair_id). Round-robin across strata until `size` pairs are
   * taken (or all pairs are exhausted), so small strata are fully represented.
   */
  def select[A](items: Vector[A], size: Int, seed: Long)(
    id: A => String,
    stratum: A => String
  ): Vector[A] =
    val queues = items
      .groupBy(stratum)
      .toVector
      .sortBy(_._1)
      .map((_, xs) => xs.sortBy(x => (hash(seed, "select", id(x)), id(x))))
    val out   = Vector.newBuilder[A]
    var taken = 0
    var round = 0
    val max   = if queues.isEmpty then 0 else queues.map(_.size).max
    while taken < size && round < max do
      queues.foreach { q =>
        if taken < size && round < q.size then
          out += q(round)
          taken += 1
      }
      round += 1
    out.result()

  /**
   * Stratified deterministic split. Depends ONLY on (pair_id, stratum, seed) of the selected rows,
   * never on labels. The calibration total is exactly round(0.6 * n); every stratum with two or
   * more members gets at least one pair in each split; remaining seats go by largest fractional
   * share. Returns pair_id -> "calibration" | "holdout".
   */
  def assignSplits(rows: Vector[(String, String)], seed: Long): Map[String, String] =
    val n = rows.size
    if n == 0 then Map.empty
    else
      val nc     = math.round(n * CalibrationFraction).toInt
      val strata = rows.groupBy(_._2).toVector.sortBy(_._1)
      case class Alloc(key: String, size: Int, ideal: Double, lo: Int, hi: Int, var c: Int)
      val allocs = strata.map { (key, members) =>
        val s     = members.size
        val ideal = s.toDouble * nc / n
        val lo    = if s >= 2 then 1 else 0
        val hi    = if s >= 2 then s - 1 else s
        Alloc(key, s, ideal, lo, hi, math.min(hi, math.max(lo, math.floor(ideal).toInt)))
      }
      var diff = nc - allocs.map(_.c).sum
      while diff > 0 && allocs.exists(a => a.c < a.hi) do
        val a = allocs.filter(a => a.c < a.hi).sortBy(a => (-(a.ideal - a.c), a.key)).head
        a.c += 1
        diff -= 1
      while diff < 0 && allocs.exists(a => a.c > a.lo) do
        val a = allocs.filter(a => a.c > a.lo).sortBy(a => (a.ideal - a.c, a.key)).head
        a.c -= 1
        diff += 1
      // Bounds could not be met exactly (degenerate tiny samples): relax them.
      while diff > 0 && allocs.exists(a => a.c < a.size) do
        allocs.filter(a => a.c < a.size).sortBy(_.key).head.c += 1
        diff -= 1
      while diff < 0 && allocs.exists(_.c > 0) do
        allocs.filter(_.c > 0).sortBy(_.key).head.c -= 1
        diff += 1
      val byKey = allocs.map(a => a.key -> a.c).toMap
      strata.flatMap { (key, members) =>
        val ordered = members.map(_._1).sortBy(id => (hash(seed, "split", id), id))
        ordered.zipWithIndex.map((id, i) => id -> (if i < byKey(key) then Calibration else Holdout))
      }.toMap

  private def view(m: MentionContext, work: WorkRecord): MentionView =
    MentionView(
      mentionId = m.mentionId,
      sourceRecordId = m.sourceRecordId,
      rawName = m.rawName,
      title = m.title,
      year = m.year,
      venue = m.venue,
      coauthors = work.authors.filter(_.mentionId != m.mentionId).map(_.rawName),
      orcid = m.orcid
    )

  /** Build candidates from decisions and works. Returns (candidates, unjoinable decision count). */
  def candidates(
    decisions: Vector[MatchDecision],
    works: Vector[WorkRecord]
  ): (Vector[GoldCandidate], Int) =
    val policy = MentionResolver.mentionPolicy
    val byMention = works.flatMap { w =>
      MentionContext.fromWork(w).map(m => m.mentionId -> (m, w))
    }.toMap
    var unjoinable = 0
    val out = decisions.flatMap { d =>
      (byMention.get(d.sourceId), byMention.get(d.targetId)) match
        case (Some((ma, wa)), Some((mb, wb))) =>
          val score = policy.computeScore(d.features)
          val path  = pathOf(d)
          val band  = scoreBand(score)
          val hn    = hardNegativeStratum(ma, mb)
          Some(
            GoldCandidate(
              pairId = pairId(d.sourceId, d.targetId),
              stratum = s"$path|$band|$hn",
              hardNegativeStratum = hn,
              scoreBand = band,
              decision = d,
              a = view(ma, wa),
              b = view(mb, wb),
              matcherScore = score,
              matcherPath = path
            )
          )
        case _ =>
          unjoinable += 1
          None
    }
    (out, unjoinable)

  private def fmt(d: Double): String = "%.6f".format(d)

  def toRow(c: GoldCandidate, split: String, seed: Long): Vector[String] =
    Vector(
      c.pairId,
      split,
      c.stratum,
      c.hardNegativeStratum,
      c.scoreBand,
      c.a.mentionId,
      c.b.mentionId,
      c.a.sourceRecordId,
      c.b.sourceRecordId,
      c.a.rawName,
      c.b.rawName,
      c.a.title,
      c.b.title,
      c.a.year.map(_.toString).getOrElse(""),
      c.b.year.map(_.toString).getOrElse(""),
      c.a.venue.getOrElse(""),
      c.b.venue.getOrElse(""),
      c.a.coauthors.mkString("; "),
      c.b.coauthors.mkString("; "),
      c.a.orcid.getOrElse(""),
      c.b.orcid.getOrElse(""),
      fmt(c.matcherScore),
      fmt(c.decision.confidence),
      c.decision.decision.toString,
      c.matcherPath,
      c.decision.policyVersion,
      c.decision.runId,
      seed.toString,
      "",
      ""
    )

  private def countBy[A](xs: Iterable[A])(key: A => String): ujson.Obj =
    ujson.Obj.from(
      xs.groupMapReduce(key)(_ => 1)(_ + _).toVector.sortBy(_._1).map((k, v) => k -> ujson.Num(v))
    )

  def run(cfg: SamplerConfig): SamplerResult =
    val out =
      GoldSetIO.outsideModules(cfg.output).fold(e => throw IllegalArgumentException(e), identity)
    val decisionsDir  = cfg.erOutputDir.resolve("match-decisions")
    val decisionFiles = GoldSetIO.dataFiles(decisionsDir)
    require(decisionFiles.nonEmpty, s"No decision files in $decisionsDir")
    val workFiles = GoldSetIO.dataFiles(cfg.workRecordsDir)
    require(workFiles.nonEmpty, s"No work-record files in ${cfg.workRecordsDir}")

    val decisions = decisionFiles.flatMap(GoldSetIO.readLines).map(read[MatchDecision](_))
    val works     = workFiles.flatMap(GoldSetIO.readLines).map(read[WorkRecord](_))
    val policy    = MentionResolver.mentionPolicy
    val versions  = decisions.map(_.policyVersion).distinct
    require(
      versions.forall(_ == policy.policyVersion),
      s"Decisions use policy ${versions.mkString(", ")}; sampler scores with ${policy.policyVersion}"
    )

    val (all, unjoinable) = candidates(decisions, works)
    val selected          = select(all, cfg.size, cfg.seed)(_.pairId, _.stratum)
    val splits            = assignSplits(selected.map(c => c.pairId -> c.stratum), cfg.seed)
    val ordered           = selected.sortBy(c => (splits(c.pairId), c.stratum, c.pairId))
    val csv = GoldSetIO.writeCsv(Header, ordered.map(c => toRow(c, splits(c.pairId), cfg.seed)))
    GoldSetIO.writeString(out, csv)

    val nCal = splits.values.count(_ == Calibration)
    val manifest = ujson.Obj(
      "producer"         -> Producer,
      "generated_at_utc" -> java.time.Instant.now().toString,
      "purpose" -> ("Candidate pairs for HUMAN gold labeling. Selection and split only; " +
        "no labels assigned by software."),
      "seed"                 -> cfg.seed.toString,
      "requested_size"       -> cfg.size,
      "calibration_fraction" -> CalibrationFraction,
      "policy_version"       -> policy.policyVersion,
      "er_run_ids"           -> ujson.Arr.from(decisions.map(_.runId).distinct.sorted),
      "selection" -> ("stratum = match_path|score_band|hard_negative_stratum; within-stratum " +
        "order sha256(seed|select|pair_id); round-robin across strata in key order"),
      "split" -> ("per-stratum allocation of round(0.6n) calibration seats (>=1 per split for " +
        "strata of size>=2, largest remainder), within-stratum order sha256(seed|split|pair_id)"),
      "inputs" -> ujson.Obj(
        "er_output_dir"    -> cfg.erOutputDir.toString,
        "work_records_dir" -> cfg.workRecordsDir.toString,
        "files" -> ujson.Arr.from((decisionFiles ++ workFiles).map { f =>
          ujson.Obj("path" -> f.toString, "sha256" -> GoldSetIO.sha256File(f))
        })
      ),
      "output" -> ujson.Obj("path" -> out.toString, "sha256" -> GoldSetIO.sha256Hex(csv)),
      "counts" -> ujson.Obj(
        "decisions"                         -> decisions.size,
        "unjoinable_decisions"              -> unjoinable,
        "usable_pairs"                      -> all.size,
        "selected"                          -> selected.size,
        "short_of_requested"                -> math.max(0, cfg.size - selected.size),
        "calibration"                       -> nCal,
        "holdout"                           -> (selected.size - nCal),
        "usable_by_path"                    -> countBy(all)(_.matcherPath),
        "selected_by_path"                  -> countBy(selected)(_.matcherPath),
        "selected_by_score_band"            -> countBy(selected)(_.scoreBand),
        "selected_by_hard_negative_stratum" -> countBy(selected)(_.hardNegativeStratum),
        "selected_by_matcher_decision"      -> countBy(selected)(_.decision.decision.toString),
        "selected_by_split_and_hard_negative_stratum" -> countBy(selected)(c =>
          s"${splits(c.pairId)}|${c.hardNegativeStratum}"
        ),
        "selected_by_split_and_path" -> countBy(selected)(c =>
          s"${splits(c.pairId)}|${c.matcherPath}"
        ),
        "selected_by_stratum" -> countBy(selected)(_.stratum)
      )
    )
    val manifestFile = Paths.get(out.toString + ".manifest.json")
    GoldSetIO.writeString(manifestFile, ujson.write(manifest, indent = 2) + "\n")
    SamplerResult(selected, splits, out, manifestFile, manifest)
