package edu.scholarchain.entityresolution

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

import java.nio.file.{Files, Path}

class GoldSetSamplerSpec extends AnyFlatSpec with Matchers {
  import MentionFixtures.work

  /** Runner output over a fixture with several blocks, homonym suffixes and one ORCID pair. */
  private def fixture(): (Path, Path, Path) =
    val tmp = Files.createTempDirectory("gold-sampler-spec")
    val wr  = tmp.resolve("work_records")
    Files.createDirectories(wr)
    val works = Vector(
      work("conf/a/W1", Vector("Wei Wang", "Ana Lima"), year = 2019),
      work("conf/a/W2", Vector("Wei Wang", "Ana Lima"), year = 2020),
      work("conf/b/W3", Vector("Wen Wang", "Bo Chen"), venue = Some("kdd")),
      work("conf/b/W4", Vector("W. Wang", "Bo Chen")),
      work("conf/c/W5", Vector("Wei Wang 0001", "Carla Diaz")),
      work("conf/c/W6", Vector("Wei Wang 0002", "Dan Eve"), year = 2010, venue = Some("icml")),
      work("conf/d/W7", Vector("Wei Wang 0001", "Carla Diaz"), year = 2021),
      work("conf/e/W8", Vector("Ana Lima", "Bo Chen"), orcids = Map(0 -> "0000-0002-1825-0097")),
      work("conf/e/W9", Vector("Ana Lima", "Wu Wang"), orcids = Map(0 -> "0000-0002-1825-0097")),
      work("conf/f/W10", Vector("Bo Chen", "Ana Lima"), title = "Graphs.", venue = None)
    )
    Files.writeString(wr.resolve("part-m-00000"), works.map(w => write(w) + "\n").mkString)
    val er = tmp.resolve("er")
    EntityResolutionRunner.run(RunnerConfig(wr, er, "er-spec", Some("hadoop-spec"), "TEST", 200))
    (wr, er, tmp)

  "GoldSetSampler" should "sample deterministically and record the seed" in {
    val (wr, er, tmp) = fixture()
    val r1 = GoldSetSampler.run(SamplerConfig(er, wr, tmp.resolve("out1/gold.csv"), 7L, 12))
    val r2 = GoldSetSampler.run(SamplerConfig(er, wr, tmp.resolve("out2/gold.csv"), 7L, 12))
    Files.readString(r1.csvFile) shouldBe Files.readString(r2.csvFile)
    r1.manifest("seed").str shouldBe "7"
    val (header, rows) = GoldSetIO.readTable(Files.readString(r1.csvFile))
    header shouldBe GoldSetSampler.Header
    rows.size shouldBe 12
    rows.map(_("sample_seed")).distinct shouldBe Vector("7")
    rows.map(_("pair_id")).distinct.size shouldBe 12
    r1.manifest("counts")("selected").num.toInt shouldBe 12
  }

  it should "never assign labels: human_label and notes are empty" in {
    val (wr, er, tmp) = fixture()
    val r             = GoldSetSampler.run(SamplerConfig(er, wr, tmp.resolve("g.csv"), 1L, 100))
    val (_, rows)     = GoldSetIO.readTable(Files.readString(r.csvFile))
    rows.foreach { m =>
      m("human_label") shouldBe ""
      m("notes") shouldBe ""
    }
    // Fixture has fewer pairs than requested: all usable pairs are taken and the shortfall reported.
    val usable = r.manifest("counts")("usable_pairs").num.toInt
    rows.size shouldBe usable
    r.manifest("counts")("short_of_requested").num.toInt shouldBe (100 - usable)
  }

  it should "carry raw names, titles, coauthors, ORCIDs and matcher outputs" in {
    val (wr, er, tmp) = fixture()
    val r             = GoldSetSampler.run(SamplerConfig(er, wr, tmp.resolve("g.csv"), 1L, 100))
    val (_, rows)     = GoldSetIO.readTable(Files.readString(r.csvFile))
    val orcidRow      = rows.find(m => m("orcid_a").nonEmpty && m("orcid_b").nonEmpty).get
    orcidRow("matcher_path") shouldBe "exact_id"
    orcidRow("matcher_decision") shouldBe "Accepted"
    orcidRow("raw_name_a") shouldBe "Ana Lima"
    orcidRow("coauthors_a").nonEmpty shouldBe true
    val suffixRow = rows.find(_("hard_negative_stratum") == "dblp_suffix_differs").get
    suffixRow("matcher_path") shouldBe "dblp_person_key"
    rows.foreach { m =>
      m("title_a").nonEmpty shouldBe true
      m("matcher_score").toDouble should (be >= 0.0 and be <= 1.0)
      m("policy_version") shouldBe MentionResolver.mentionPolicy.policyVersion
    }
  }

  it should "refuse to write output inside a modules directory" in {
    val (wr, er, tmp) = fixture()
    val bad           = tmp.resolve("modules/entity-resolution/gold.csv")
    an[IllegalArgumentException] should be thrownBy GoldSetSampler.run(SamplerConfig(er, wr, bad))
    Files.exists(bad) shouldBe false
    GoldSetSampler
      .parseArgs(Vector("--er-output", "e", "--work-records", "w", "--output", "modules/x.csv"))
      .isLeft shouldBe true
    GoldSetSampler
      .parseArgs(Vector("--er-output", "e", "--work-records", "w", "--output", "/tmp/x.csv"))
      .map(_.seed) shouldBe Right(GoldSetSampler.DefaultSeed)
  }

  "select" should "cover every stratum before taking a second item from any" in {
    val items =
      (0 until 50).map(i => s"p$i" -> (if i < 40 then "big" else s"small${i % 3}")).toVector
    val picked = GoldSetSampler.select(items, 6, 3L)(_._1, _._2)
    picked.map(_._2).toSet shouldBe Set("big", "small0", "small1", "small2")
    GoldSetSampler.select(items, 6, 3L)(_._1, _._2) shouldBe picked
  }

  "assignSplits" should "give exactly 102/68 for 170 rows and both splits in every stratum of 2+" in {
    val rows    = (0 until 170).map(i => f"gp-$i%04d" -> s"stratum${i % 13}").toVector :+
      ("gp-solo" -> "lonely")
    val rows170 = rows.take(170)
    val s       = GoldSetSampler.assignSplits(rows170, 42L)
    s.values.count(_ == GoldSetSampler.Calibration) shouldBe 102
    s.values.count(_ == GoldSetSampler.Holdout) shouldBe 68
    rows170.groupBy(_._2).foreach { (_, members) =>
      members.map(m => s(m._1)).toSet shouldBe Set(
        GoldSetSampler.Calibration,
        GoldSetSampler.Holdout
      )
    }
    GoldSetSampler.assignSplits(rows170, 42L) shouldBe s
    GoldSetSampler.assignSplits(rows170.reverse, 42L) shouldBe s
  }

  it should "be fixed before labeling: labels never change the recomputed split" in {
    val (wr, er, tmp) = fixture()
    val r             = GoldSetSampler.run(SamplerConfig(er, wr, tmp.resolve("g.csv"), 9L, 100))
    val text          = Files.readString(r.csvFile)
    val rows          = GoldSetEvaluation.parseRows(text).toOption.get
    GoldSetEvaluation.splitIntegrityErrors(rows) shouldBe empty
    val rnd = scala.util.Random(5)
    val labelled = rows.map(
      _.copy(label =
        Some(Vector(HumanLabel.Match, HumanLabel.NonMatch, HumanLabel.Unsure)(rnd.nextInt(3)))
      )
    )
    GoldSetEvaluation.splitIntegrityErrors(labelled) shouldBe empty
    labelled.map(x => x.pairId -> x.split).toMap shouldBe r.splits
    val tampered = labelled.updated(
      0,
      labelled(0).copy(split =
        if labelled(0).split == GoldSetSampler.Calibration then GoldSetSampler.Holdout
        else GoldSetSampler.Calibration
      )
    )
    GoldSetEvaluation.splitIntegrityErrors(tampered) should not be empty
  }
}
