package edu.scholarchain.entityresolution

import edu.scholarchain.core.{MatchDecision, MatchOutcome}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPOutputStream

class EntityResolutionRunnerSpec extends AnyFlatSpec with Matchers {
  import MentionFixtures.work

  private def writeInput(dir: Path, gz: Boolean): Unit =
    val works = Vector(
      work("conf/a/W1", Vector("Marlon Dumas", "Ana Lima", "Wei Wang 0001")),
      work("conf/b/W2", Vector("Marlon Dumas", "Ana Lima"), year = 2021),
      work("conf/c/W3", Vector("Wei Wang 0002", "Håvard Fjær Grip"))
    )
    val text = works.map(w => write(w) + "\n").mkString
    Files.createDirectories(dir)
    Files.write(dir.resolve("_SUCCESS"), Array.emptyByteArray)
    if gz then
      val out = GZIPOutputStream(Files.newOutputStream(dir.resolve("part-m-00002.gz")))
      try out.write(text.getBytes(StandardCharsets.UTF_8))
      finally out.close()
    else Files.writeString(dir.resolve("part-r-00000"), text)

  "parseArgs" should "require work-records, output and run-id" in {
    EntityResolutionRunner.parseArgs(Vector("--output", "o", "--run-id", "r")).isLeft shouldBe true
    EntityResolutionRunner.parseArgs(Vector("--work-records")).isLeft shouldBe true
    EntityResolutionRunner
      .parseArgs(Vector("--work-records", "w", "--output", "o", "--run-id", "r", "--bogus", "x"))
      .isLeft shouldBe true
    EntityResolutionRunner
      .parseArgs(
        Vector("--work-records", "w", "--output", "o", "--run-id", "r", "--max-block-size", "1")
      )
      .isLeft shouldBe true
    val cfg = EntityResolutionRunner
      .parseArgs(Vector("--work-records", "w", "--output", "o", "--run-id", "r", "--label", "L"))
      .toOption
      .get
    cfg.runId shouldBe "r"
    cfg.label shouldBe "L"
    cfg.maxBlockSize shouldBe 200
  }

  "run" should "write Spark-readable MatchDecision JSON lines plus summary and review queue" in {
    val tmp = Files.createTempDirectory("er-runner-spec")
    val in  = tmp.resolve("work_records")
    writeInput(in, gz = true)
    val out = tmp.resolve("er")
    val res = EntityResolutionRunner.run(
      RunnerConfig(in, out, "er-test", Some("hadoop-test"), "TEST FIXTURE", 200)
    )

    res.decisionsFile shouldBe out.resolve("match-decisions").resolve("part-00000")
    Files.exists(out.resolve("match-decisions").resolve("_SUCCESS")) shouldBe true
    val lines = Files.readAllLines(res.decisionsFile).toArray.toVector.map(_.toString)
    val back  = lines.map(read[MatchDecision](_))
    back shouldBe res.decisions
    back.foreach { d =>
      d.sourceId should startWith("mention:dblp:")
      d.targetId should startWith("mention:dblp:")
      d.sourceId should be < d.targetId
      d.runId shouldBe "er-test"
      d.reasonCodes should contain("input_run:hadoop-test")
    }
    val byPair = back.map(d => (d.sourceId, d.targetId) -> d.decision).toMap
    byPair(("mention:dblp:conf/a/W1:0", "mention:dblp:conf/b/W2:0")) shouldBe MatchOutcome.Accepted
    byPair(("mention:dblp:conf/a/W1:2", "mention:dblp:conf/c/W3:0")) shouldBe MatchOutcome.Rejected

    val s = res.summary
    s("label").str shouldBe "TEST FIXTURE"
    s("calibration")("status").str shouldBe "UNCALIBRATED"
    s("llm_adjudication")("enabled").bool shouldBe false
    s("counts")("work_records").num shouldBe 3
    s("counts")("decisions").num shouldBe back.size
    s("counts")("by_decision")("MATCH").num shouldBe back.count(_.decision == MatchOutcome.Accepted)
    s("counts")("by_rule_reason")("dblp_homonym_suffix_mismatch").num shouldBe 1
    s("data_quality_effects")("mentions_with_non_decomposing_letters").num shouldBe 1
    s("data_quality_effects")("mentions_with_dblp_homonym_suffix").num shouldBe 2
    val outSha = s("outputs").arr.map(o => o("path").str -> o("sha256").str).toMap
    outSha(res.decisionsFile.toString) shouldBe EntityResolutionRunner.sha256(res.decisionsFile)
    s("inputs")("files").arr.size shouldBe 1

    val persisted = ujson.read(Files.readString(res.summaryFile))
    persisted("run_id").str shouldBe "er-test"
    Files.exists(res.reviewQueueFile) shouldBe true
  }

  it should "read plain-text Hadoop part files and be deterministic across runs" in {
    val tmp = Files.createTempDirectory("er-runner-spec-plain")
    val in  = tmp.resolve("wr")
    writeInput(in, gz = false)
    val a = EntityResolutionRunner.run(RunnerConfig(in, tmp.resolve("a"), "r"))
    val b = EntityResolutionRunner.run(RunnerConfig(in, tmp.resolve("b"), "r"))
    a.decisions should not be empty
    Files.readString(a.decisionsFile) shouldBe Files.readString(b.decisionsFile)
  }

  it should "fail when the input directory has no data files" in {
    val tmp = Files.createTempDirectory("er-runner-spec-empty")
    Files.write(tmp.resolve("_SUCCESS"), Array.emptyByteArray)
    an[IllegalArgumentException] should be thrownBy
      EntityResolutionRunner.run(RunnerConfig(tmp, tmp.resolve("o"), "r"))
  }
}
