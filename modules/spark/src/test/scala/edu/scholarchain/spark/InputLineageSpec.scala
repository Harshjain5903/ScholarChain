package edu.scholarchain.spark

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/**
 * Feature tests for input-lineage derivation and validation (review finding N44) and the run
 * manifest's best-effort code/jar/input facts.
 */
class InputLineageSpec extends AnyFunSuite with Matchers:

  private val L = InputLineage

  test("source dataset is read from the inputs, never invented") {
    L.sourceDatasetOf(Seq("dblp", "dblp")) shouldBe "dblp"
    L.sourceDatasetOf(Seq("scholarchain")) shouldBe "scholarchain"
    L.sourceDatasetOf(Seq("scholarchain", "dblp", "")) shouldBe "dblp+scholarchain"
    L.sourceDatasetOf(Seq.empty) shouldBe L.UnknownSourceDataset
  }

  test("ER policy is derived from a single-policy decision input") {
    L.resolvePolicyVersion(None, Seq("er-mention-v1-uncalibrated")) shouldBe
      Right("er-mention-v1-uncalibrated")
    L.resolvePolicyVersion(Some("er-mention-v1-uncalibrated"), Seq("er-mention-v1-uncalibrated")) shouldBe
      Right("er-mention-v1-uncalibrated")
  }

  test("an explicit --policy-version that disagrees with the decisions fails fast") {
    val err = L.resolvePolicyVersion(Some("v1"), Seq("er-mention-v1-uncalibrated")).left.toOption.get
    err should include("--policy-version 'v1' disagrees")
    err should include("er-mention-v1-uncalibrated")
  }

  test("decisions from more than one policy are refused") {
    val err = L.resolvePolicyVersion(None, Seq("p2", "p1", "p1")).left.toOption.get
    err should include("mixes 2 ER policy versions (p1, p2)")
    L.resolvePolicyVersion(Some("p1"), Seq("p1", "p2")).isLeft shouldBe true
  }

  test("an empty decision input requires an explicit policy version") {
    L.resolvePolicyVersion(None, Seq.empty).left.toOption.get should include("--policy-version")
    L.resolvePolicyVersion(Some("p"), Seq.empty) shouldBe Right("p")
  }

  test("input_run reason codes name the Hadoop run") {
    L.erInputRunIds(Seq("score:0.1", "input_run:rs-1", "input_run:")) shouldBe Seq("rs-1")
  }

  test("ER decisions linked to the pairs' Hadoop run are verified") {
    L.checkErLinkage(Set("rs-1"), Set("rs-1"), 10, 0, allowUnlinked = false) shouldBe
      Right(L.Linkage.Verified)
    L.checkErLinkage(Set("rs-1"), Set.empty, 0, 0, allowUnlinked = false) shouldBe
      Right(L.Linkage.NoDecisions)
  }

  test("ER decisions from a different Hadoop run fail fast, even when unlinked input is allowed") {
    Seq(false, true).foreach { allow =>
      val err = L.checkErLinkage(Set("rs-1"), Set("rs-2"), 10, 0, allow).left.toOption.get
      err should include("Hadoop run(s) rs-2")
      err should include("pair candidates come from run(s) rs-1")
    }
  }

  test("decisions that do not name their input run fail unless explicitly allowed") {
    L.checkErLinkage(Set("rs-1"), Set.empty, 5, 5, allowUnlinked = false).left.toOption.get should
      include("5 of 5 match decisions carry no 'input_run:<hadoop run id>'")
    L.checkErLinkage(Set("rs-1"), Set("rs-1"), 5, 2, allowUnlinked = true) shouldBe
      Right(L.Linkage.UnverifiedAllowed)
  }

  // ---- Run manifest facts.

  private val noGit = () => None

  test("code version: flag wins, then environment, then git probe; never fails") {
    val env = Map(RunManifest.GitShaEnv -> "envsha", RunManifest.GitDirtyEnv -> "false")
    RunManifest.resolveCodeVersion(Some("flagsha"), Some(true), env, noGit) shouldBe
      RunManifest.CodeVersion(Some("flagsha"), Some(true), "flag")
    RunManifest.resolveCodeVersion(None, None, env, noGit) shouldBe
      RunManifest.CodeVersion(Some("envsha"), Some(false), "env")
    RunManifest.resolveCodeVersion(None, None, Map.empty, () => Some(("a" * 40, true))) shouldBe
      RunManifest.CodeVersion(Some("a" * 40), Some(true), "git")
    RunManifest.resolveCodeVersion(None, None, Map.empty, noGit) shouldBe
      RunManifest.CodeVersion(None, None, "unavailable")
    val throwing = () => throw new RuntimeException("no git binary")
    RunManifest.resolveCodeVersion(None, None, Map.empty, throwing).source shouldBe "unavailable"
  }

  test("jar info reports null with a reason when not running from a jar") {
    val info = RunManifest.jarInfo()
    info("sha256") shouldBe ujson.Null
    info("reason").str shouldBe "not running from a jar"
  }

  test("input checksums cover data files only, sorted, with the expected SHA-256") {
    val root = Files.createTempDirectory("manifest-inputs")
    val sub  = Files.createDirectories(root.resolve("pairs"))
    Files.writeString(sub.resolve("part-00001"), "abc")
    Files.writeString(sub.resolve("part-00000"), "")
    Files.writeString(sub.resolve("_SUCCESS"), "")
    Files.writeString(sub.resolve(".part-00000.crc"), "x")
    val files = RunManifest.inputFiles(
      new org.apache.hadoop.conf.Configuration(),
      Seq(sub.toUri.toString, root.resolve("missing").toUri.toString)
    )
    files.map(f => f.path.split('/').last) shouldBe Vector("part-00000", "part-00001")
    files(1).sha256 shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    files(1).sizeBytes shouldBe 3L
    files(0).sha256 shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  }
