package edu.scholarchain.entityresolution

import edu.scholarchain.core.MatchOutcome

import java.nio.file.{Files, Path, Paths}

/** Evaluator configuration. */
case class EvaluationConfig(
  gold: Path,
  outputDir: Path,
  preliminary: Boolean = false,
  minPrecision: Double = GoldSetCalibration.DefaultMinPrecision,
  maxFalseOmission: Double = GoldSetCalibration.DefaultMaxFalseOmission
)

/** Files and JSON produced by one evaluation. */
case class EvaluationOutput(
  json: ujson.Obj,
  markdown: String,
  jsonFile: Path,
  markdownFile: Path,
  calibration: CalibrationResult
)

/**
 * Evaluates the matcher against a HUMAN-labeled gold CSV produced by [[GoldSetSampler]].
 *
 * Gates (refused unless `--preliminary`): at least 170 labeled rows, exactly 102 labeled
 * calibration and 68 labeled holdout rows, and both splits containing at least one MATCH and at
 * least one hard negative. Always refused: invalid labels, duplicate pair ids, mixed policy
 * versions or seeds, and a `split` column that does not equal the split recomputed from (pair_id,
 * stratum, seed) -- i.e. a split edited after sampling.
 *
 * Thresholds are chosen by [[GoldSetCalibration.calibrate]] on calibration rows only, frozen, and
 * then applied once to the holdout rows.
 */
object GoldSetEvaluation:

  val Producer: String         = "entity-resolution-engineer/GoldSetEvaluation"
  val RequiredLabeled: Int     = 170
  val RequiredCalibration: Int = 102
  val RequiredHoldout: Int     = 68
  val PreliminaryStamp: String = "PRELIMINARY"

  val Usage: String =
    "Usage: GoldSetEvaluation --gold <labeled csv> --output <dir outside modules/> " +
      "[--preliminary] [--min-precision <p>] [--max-false-omission <f>]"

  def main(args: Array[String]): Unit =
    parseArgs(args.toVector) match
      case Left(err) =>
        System.err.println(err)
        System.err.println(Usage)
        sys.exit(2)
      case Right(cfg) =>
        evaluate(cfg) match
          case Left(errors) =>
            System.err.println("REFUSED: gold set does not meet evaluation gates:")
            errors.foreach(e => System.err.println(s"  - $e"))
            sys.exit(3)
          case Right(out) =>
            val stamp = if cfg.preliminary then s"[$PreliminaryStamp] " else ""
            val t     = out.calibration.thresholds
            println(s"${stamp}frozen thresholds: accept=${t.accept} reject=${t.reject}")
            println(s"${stamp}evaluation.json: ${out.jsonFile}")
            println(s"${stamp}evaluation.md:   ${out.markdownFile}")

  def parseArgs(args: Vector[String]): Either[String, EvaluationConfig] =
    val preliminary = args.contains("--preliminary")
    val rest        = args.filterNot(_ == "--preliminary")
    if rest.size % 2 != 0 then Left("Arguments must be --key value pairs (plus --preliminary)")
    else
      val kv      = rest.grouped(2).map(v => v(0) -> v(1)).toMap
      val allowed = Set("--gold", "--output", "--min-precision", "--max-false-omission")
      val unknown = kv.keySet -- allowed
      def prob(k: String, d: Double): Either[String, Double] = kv.get(k) match
        case None    => Right(d)
        case Some(s) => s.toDoubleOption.filter(x => x >= 0 && x <= 1).toRight(s"Invalid $k: $s")
      if unknown.nonEmpty then Left(s"Unknown arguments: ${unknown.toVector.sorted.mkString(", ")}")
      else
        for
          gold <- kv.get("--gold").toRight("Missing --gold")
          out  <- kv.get("--output").toRight("Missing --output")
          safe <- GoldSetIO.outsideModules(Paths.get(out))
          mp   <- prob("--min-precision", GoldSetCalibration.DefaultMinPrecision)
          mf   <- prob("--max-false-omission", GoldSetCalibration.DefaultMaxFalseOmission)
        yield EvaluationConfig(Paths.get(gold), safe, preliminary, mp, mf)

  val RequiredColumns: Vector[String] = Vector(
    "pair_id",
    "split",
    "stratum",
    "hard_negative_stratum",
    "matcher_score",
    "matcher_decision",
    "matcher_path",
    "policy_version",
    "sample_seed",
    "human_label"
  )

  /** Parse labeled CSV text into rows; collects every structural error. */
  def parseRows(text: String): Either[Vector[String], Vector[GoldRow]] =
    val (header, maps) = GoldSetIO.readTable(text)
    val missing        = RequiredColumns.filterNot(header.contains)
    if missing.nonEmpty then Left(Vector(s"missing CSV columns: ${missing.mkString(", ")}"))
    else
      val parsed = maps.zipWithIndex.map { (m, i) =>
        val line             = i + 2
        def err(msg: String) = Left(s"line $line (${m("pair_id")}): $msg")
        for
          label <- HumanLabel.parse(m("human_label")).left.map(e => s"line $line: $e")
          split <- m("split").trim match
            case s @ (GoldSetSampler.Calibration | GoldSetSampler.Holdout) => Right(s)
            case other => err(s"invalid split '$other'")
          score <- m("matcher_score").trim.toDoubleOption.toRight(s"line $line: bad matcher_score")
          seed  <- m("sample_seed").trim.toLongOption.toRight(s"line $line: bad sample_seed")
          dec <- MatchOutcome.values
            .find(_.toString == m("matcher_decision").trim)
            .toRight(s"line $line: bad matcher_decision '${m("matcher_decision")}'")
        yield GoldRow(
          pairId = m("pair_id").trim,
          split = split,
          stratum = m("stratum").trim,
          hardNegativeStratum = m("hard_negative_stratum").trim,
          matcherScore = score,
          matcherDecision = dec,
          matcherPath = m("matcher_path").trim,
          policyVersion = m("policy_version").trim,
          seed = seed,
          label = label
        )
      }
      val errors = parsed.collect { case Left(e) => e }
      if errors.nonEmpty then Left(errors)
      else
        val rows  = parsed.collect { case Right(r) => r }
        val dupes = rows.groupBy(_.pairId).collect { case (id, v) if v.size > 1 => id }
        val pv    = rows.map(_.policyVersion).distinct
        val seeds = rows.map(_.seed).distinct
        val structural = Vector(
          Option.when(rows.isEmpty)("CSV has no data rows"),
          Option.when(dupes.nonEmpty)(
            s"duplicate pair_id: ${dupes.toVector.sorted.mkString(", ")}"
          ),
          Option.when(pv.size > 1)(s"mixed policy_version: ${pv.mkString(", ")}"),
          Option.when(seeds.size > 1)(s"mixed sample_seed: ${seeds.mkString(", ")}")
        ).flatten
        if structural.nonEmpty then Left(structural) else Right(rows)

  /**
   * The split must be exactly what the sampler assigned from (pair_id, stratum, seed) before
   * labeling. Rows must not be added, removed or re-split after sampling.
   */
  def splitIntegrityErrors(rows: Vector[GoldRow]): Vector[String] =
    if rows.isEmpty then Vector.empty
    else
      val expected =
        GoldSetSampler.assignSplits(rows.map(r => r.pairId -> r.stratum), rows.head.seed)
      val bad = rows.filter(r => expected.get(r.pairId).forall(_ != r.split))
      if bad.isEmpty then Vector.empty
      else
        Vector(
          s"split column differs from the pre-label split recomputed from (pair_id, stratum, " +
            s"seed) for ${bad.size} row(s), e.g. ${bad.take(5).map(_.pairId).mkString(", ")}; " +
            "rows must not be added, removed or re-split after sampling"
        )

  /** Size, split and content gates; violations are refused unless --preliminary. */
  def gateErrors(rows: Vector[GoldRow]): Vector[String] =
    val labeled = rows.filter(_.label.isDefined)
    val cal     = labeled.filter(_.split == GoldSetSampler.Calibration)
    val hold    = labeled.filter(_.split == GoldSetSampler.Holdout)
    def content(name: String, xs: Vector[GoldRow]) = Vector(
      Option.when(!xs.exists(_.isMatch))(s"$name split has no MATCH (positive) labels"),
      Option.when(!xs.exists(_.isHardNegative))(
        s"$name split has no hard negatives (NON_MATCH in " +
          s"${GoldSetSampler.HardNegativeStrata.toVector.sorted.mkString("/")})"
      )
    ).flatten
    Vector(
      Option.when(labeled.size < RequiredLabeled)(
        s"only ${labeled.size} labeled rows; at least $RequiredLabeled required"
      ),
      Option.when(cal.size != RequiredCalibration || hold.size != RequiredHoldout)(
        s"labeled split is ${cal.size}/${hold.size}; required " +
          s"$RequiredCalibration calibration / $RequiredHoldout holdout"
      )
    ).flatten ++ content("calibration", cal) ++ content("holdout", hold)

  def evaluate(cfg: EvaluationConfig): Either[Vector[String], EvaluationOutput] =
    val outDir = GoldSetIO.outsideModules(cfg.outputDir).left.map(Vector(_))
    outDir.flatMap { dir =>
      val bytes = Files.readAllBytes(cfg.gold)
      parseRows(String(bytes, java.nio.charset.StandardCharsets.UTF_8)).flatMap { rows =>
        val integrity = splitIntegrityErrors(rows)
        val gates     = gateErrors(rows)
        if integrity.nonEmpty then Left(integrity)
        else if gates.nonEmpty && !cfg.preliminary then Left(gates)
        else Right(report(cfg, dir, rows, GoldSetIO.sha256Hex(bytes), gates))
      }
    }

  // ---------------------------------------------------------------------------------------------
  // Reporting
  // ---------------------------------------------------------------------------------------------

  private def num(d: Option[Double]): ujson.Value = GoldSetIO.optNum(d)

  def metricsJson(m: GoldMetrics): ujson.Obj =
    val c = m.confusion
    ujson.Obj(
      "n"               -> c.n,
      "precision"       -> num(m.precision),
      "recall"          -> num(m.recall),
      "f1"              -> num(m.f1),
      "coverage"        -> num(m.coverage),
      "unresolved_rate" -> num(m.unresolvedRate),
      "confusion" -> ujson.Obj(
        "tp"                   -> c.tp,
        "fp"                   -> c.fp,
        "tn"                   -> c.tn,
        "fn"                   -> c.fn,
        "unresolved_match"     -> c.unresolvedMatch,
        "unresolved_non_match" -> c.unresolvedNonMatch
      )
    )

  private def pointJson(p: SensitivityPoint): ujson.Obj =
    ujson.Obj(
      "accept"                -> p.accept,
      "reject"                -> p.reject,
      "feasible"              -> p.feasible,
      "scored_precision"      -> num(p.scoredPrecision),
      "scored_false_omission" -> num(p.scoredFalseOmission),
      "metrics"               -> metricsJson(p.metrics)
    )

  private def labelCounts(xs: Vector[GoldRow]): ujson.Obj =
    ujson.Obj(
      "rows"           -> xs.size,
      "labeled"        -> xs.count(_.label.isDefined),
      "MATCH"          -> xs.count(_.label.contains(HumanLabel.Match)),
      "NON_MATCH"      -> xs.count(_.label.contains(HumanLabel.NonMatch)),
      "UNSURE"         -> xs.count(_.label.contains(HumanLabel.Unsure)),
      "unlabeled"      -> xs.count(_.label.isEmpty),
      "hard_negatives" -> xs.count(_.isHardNegative)
    )

  private def f(d: Option[Double]): String = d.map("%.4f".format(_)).getOrElse("n/a")

  private def metricsTableRow(name: String, m: GoldMetrics): String =
    val c = m.confusion
    s"| $name | ${c.n} | ${c.tp} | ${c.fp} | ${c.tn} | ${c.fn} | ${c.unresolvedMatch} | " +
      s"${c.unresolvedNonMatch} | ${f(m.precision)} | ${f(m.recall)} | ${f(m.f1)} | " +
      s"${f(m.coverage)} | ${f(m.unresolvedRate)} |"

  private val MetricsHeader =
    "| scope | n | TP | FP | TN | FN | Unres. MATCH | Unres. NON_MATCH | precision | recall | " +
      "F1 | coverage | unresolved rate |\n" +
      "|---|---|---|---|---|---|---|---|---|---|---|---|---|"

  private def sweepTable(title: String, pts: Vector[SensitivityPoint]): String =
    val rows = pts.map { p =>
      val m = p.metrics
      s"| ${"%.3f".format(p.accept)} | ${"%.3f".format(p.reject)} | ${m.confusion.decided} | " +
        s"${m.confusion.unresolved} | ${f(p.scoredPrecision)} | ${f(p.scoredFalseOmission)} | " +
        s"${f(m.precision)} | ${f(m.recall)} | ${f(m.f1)} | ${f(m.coverage)} | " +
        s"${if p.feasible then "yes" else "no"} |"
    }
    (Vector(
      s"#### $title",
      "",
      "| accept | reject | decided | unresolved | scored precision | scored false omission | " +
        "precision | recall | F1 | coverage | feasible |",
      "|---|---|---|---|---|---|---|---|---|---|---|"
    ) ++ rows).mkString("\n")

  def report(
    cfg: EvaluationConfig,
    dir: Path,
    rows: Vector[GoldRow],
    goldSha: String,
    gateProblems: Vector[String]
  ): EvaluationOutput =
    val status = if cfg.preliminary then PreliminaryStamp else "FINAL"
    val tag    = if cfg.preliminary then s"[$PreliminaryStamp] " else ""
    val cal    = rows.filter(_.split == GoldSetSampler.Calibration)
    val hold   = rows.filter(_.split == GoldSetSampler.Holdout)
    val result = GoldSetCalibration.calibrate(rows, cfg.minPrecision, cfg.maxFalseOmission)
    val frozen = result.thresholds
    val base   = GoldSetCalibration.baseThresholds
    val (accSweep, rejSweep) =
      GoldSetCalibration.sensitivity(rows, frozen, cfg.minPrecision, cfg.maxFalseOmission)

    // Held-out evaluation happens strictly after the thresholds above are frozen.
    val tunedOverall  = GoldSetCalibration.metricsAt(hold, frozen)
    val tunedByPath   = GoldSetCalibration.byPath(hold, r => GoldSetCalibration.decide(r, frozen))
    val baseOverall   = GoldSetCalibration.baselineMetrics(hold)
    val baseByPath    = GoldSetCalibration.byPath(hold, r => (r.matcherDecision, r.matcherPath))
    val policyVersion = rows.head.policyVersion
    val seed          = rows.head.seed

    def byPathJson(xs: Vector[(String, GoldMetrics)]) =
      ujson.Obj.from(xs.map((p, m) => p -> metricsJson(m)))

    val json = ujson.Obj(
      "status"           -> status,
      "preliminary"      -> cfg.preliminary,
      "gate_problems"    -> ujson.Arr.from(gateProblems.map(ujson.Str(_))),
      "producer"         -> Producer,
      "generated_at_utc" -> java.time.Instant.now().toString,
      "gold_file"        -> ujson.Obj("path" -> cfg.gold.toString, "sha256" -> goldSha),
      "policy_version"   -> policyVersion,
      "sample_seed"      -> seed.toString,
      "label_counts" -> ujson
        .Obj("calibration" -> labelCounts(cal), "holdout" -> labelCounts(hold)),
      "calibration" -> ujson.Obj(
        "uses_splits" -> ujson.Arr(GoldSetSampler.Calibration),
        "objective" -> ("maximize calibration F1, then coverage, then higher accept, then lower " +
          "reject; subject to scored-accept precision >= min_precision and scored-reject false " +
          "omission <= max_false_omission"),
        "min_precision"      -> cfg.minPrecision,
        "max_false_omission" -> cfg.maxFalseOmission,
        "accept_grid" -> s"${GoldSetCalibration.AcceptGrid.min}..${GoldSetCalibration.AcceptGrid.max} step 0.025",
        "reject_grid" -> s"${GoldSetCalibration.RejectGrid.min}..${GoldSetCalibration.RejectGrid.max} step 0.025",
        "grid_points"         -> result.gridSize,
        "feasible_points"     -> result.feasibleCount,
        "source"              -> result.source,
        "feasible"            -> result.feasible,
        "base_thresholds"     -> ujson.Obj("accept" -> base.accept, "reject" -> base.reject),
        "frozen_thresholds"   -> ujson.Obj("accept" -> frozen.accept, "reject" -> frozen.reject),
        "calibration_metrics" -> metricsJson(result.calibrationMetrics)
      ),
      "threshold_sensitivity" -> ujson.Obj(
        "subset"       -> GoldSetSampler.Calibration,
        "accept_sweep" -> ujson.Arr.from(accSweep.map(pointJson)),
        "reject_sweep" -> ujson.Arr.from(rejSweep.map(pointJson))
      ),
      "holdout" -> ujson.Obj(
        "tuned" -> ujson.Obj(
          "thresholds" -> ujson.Obj("accept" -> frozen.accept, "reject" -> frozen.reject),
          "overall"    -> metricsJson(tunedOverall),
          "by_path"    -> byPathJson(tunedByPath)
        ),
        "baseline_as_sampled" -> ujson.Obj(
          "thresholds" -> ujson.Obj("accept" -> base.accept, "reject" -> base.reject),
          "overall"    -> metricsJson(baseOverall),
          "by_path"    -> byPathJson(baseByPath)
        )
      ),
      "definitions" -> ujson.Obj(
        "decisive" -> "rows labeled MATCH or NON_MATCH; UNSURE and unlabeled rows are excluded from metrics",
        "precision"     -> "TP / (TP + FP)",
        "recall"        -> "TP / (all gold MATCH); Unresolved gold MATCH counts as missed",
        "coverage"      -> "decided (Accepted or Rejected) / decisive rows",
        "unresolved"    -> "Unresolved or Conflict decisions / decisive rows",
        "hard_negative" -> "NON_MATCH in a hard-negative stratum (pre-label, name-structure based)",
        "rule_paths" -> ("exact_id, dblp_person_key and name_gate are threshold-independent; " +
          "their metrics use identifier/name rules and labelers must not rely on the same " +
          "identifier alone (see GOLD-SET-LABELING.md)"),
        "tuned_paths" -> ("weighted-score pairs are re-labelled deterministic_bibliographic " +
          "(>= accept), unresolved_band (between) or rejected (<= reject) under frozen thresholds")
      ),
      "provenance" -> ujson.Obj(
        "source_dataset"      -> "human-labeled gold CSV (see gold_file)",
        "transformation_step" -> "gold-set calibration and held-out evaluation",
        "producer"            -> Producer
      )
    )

    val md = Vector(
      s"# ${tag}Entity-resolution gold-set evaluation",
      "",
      if cfg.preliminary then
        s"> **$PreliminaryStamp** -- gates not met; not a release-quality result:\n" +
          gateProblems.map(p => s"> - $p").mkString("\n")
      else "> Status: FINAL (all gold-set gates met).",
      "",
      s"- Gold file: `${cfg.gold}`",
      s"- Gold sha256: `$goldSha`",
      s"- Policy version (as sampled): `$policyVersion`",
      s"- Sample seed: `$seed`",
      s"- Producer: `$Producer`",
      "",
      s"## ${tag}Label counts",
      "",
      "| split | rows | labeled | MATCH | NON_MATCH | UNSURE | unlabeled | hard negatives |",
      "|---|---|---|---|---|---|---|---|",
      Vector(GoldSetSampler.Calibration -> cal, GoldSetSampler.Holdout -> hold)
        .map { (n, xs) =>
          val j = labelCounts(xs)
          s"| $n | ${j("rows").num.toInt} | ${j("labeled").num.toInt} | ${j("MATCH").num.toInt} | " +
            s"${j("NON_MATCH").num.toInt} | ${j("UNSURE").num.toInt} | ${j("unlabeled").num.toInt} | " +
            s"${j("hard_negatives").num.toInt} |"
        }
        .mkString("\n"),
      "",
      s"## ${tag}Calibration (calibration split only)",
      "",
      s"- Base thresholds: accept=${base.accept}, reject=${base.reject}",
      s"- **Frozen thresholds: accept=${frozen.accept}, reject=${frozen.reject}** (${result.source})",
      s"- Constraints: scored-accept precision >= ${cfg.minPrecision}, scored-reject false omission <= ${cfg.maxFalseOmission}",
      s"- Grid points: ${result.gridSize}; feasible: ${result.feasibleCount}",
      "",
      MetricsHeader,
      metricsTableRow("calibration @ frozen", result.calibrationMetrics),
      "",
      s"### ${tag}Threshold sensitivity (calibration split)",
      "",
      sweepTable(s"${tag}Accept sweep at reject=${frozen.reject}", accSweep),
      "",
      sweepTable(s"${tag}Reject sweep at accept=${frozen.accept}", rejSweep),
      "",
      s"## ${tag}Held-out results (thresholds frozen before held-out labels were read)",
      "",
      s"### ${tag}Tuned (accept=${frozen.accept}, reject=${frozen.reject})",
      "",
      MetricsHeader,
      metricsTableRow("overall", tunedOverall),
      tunedByPath.map((p, m) => metricsTableRow(s"path:$p", m)).mkString("\n"),
      "",
      s"### ${tag}Baseline as sampled (`$policyVersion`, accept=${base.accept}, reject=${base.reject})",
      "",
      MetricsHeader,
      metricsTableRow("overall", baseOverall),
      baseByPath.map((p, m) => metricsTableRow(s"path:$p", m)).mkString("\n"),
      "",
      s"## ${tag}Definitions",
      "",
      "- Metrics use decisive labels only (MATCH / NON_MATCH); UNSURE rows are counted above but excluded.",
      "- recall = TP / all gold MATCH (Unresolved gold matches count as missed).",
      "- coverage = decided / decisive; unresolved rate = Unresolved / decisive.",
      "- exact_id, dblp_person_key and name_gate paths are threshold-independent rule paths.",
      "",
      if cfg.preliminary then s"_${PreliminaryStamp}: not a release-quality result._" else ""
    ).mkString("\n") + "\n"

    Files.createDirectories(dir)
    val jsonFile = dir.resolve("evaluation.json")
    val mdFile   = dir.resolve("evaluation.md")
    GoldSetIO.writeString(jsonFile, ujson.write(json, indent = 2) + "\n")
    GoldSetIO.writeString(mdFile, md)
    EvaluationOutput(json, md, jsonFile, mdFile, result)
