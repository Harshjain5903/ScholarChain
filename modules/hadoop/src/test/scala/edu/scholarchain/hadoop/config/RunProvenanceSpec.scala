package edu.scholarchain.hadoop.config

import edu.scholarchain.core.Provenance
import edu.scholarchain.hadoop.jobs.*
import edu.scholarchain.hadoop.provenance.{HadoopLineage, JobLineage}
import java.time.Instant
import org.apache.hadoop.conf.Configuration
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Run-level provenance options: fixed run timestamp and per-job lineage stamping. */
class RunProvenanceSpec extends AnyFunSuite with Matchers:

  private def conf(kv: (String, String)*): Configuration =
    val c = new Configuration(false)
    kv.foreach((k, v) => c.set(k, v))
    c

  test("run timestamp key is scholarchain.run.timestamp") {
    JobConfig.RunTimestampKey shouldBe "scholarchain.run.timestamp"
  }

  test("fixed run timestamp is returned for every call") {
    val c = conf(JobConfig.RunTimestampKey -> "2026-11-23T00:00:00Z")
    JobConfig.runTimestamp(c) shouldBe "2026-11-23T00:00:00Z"
    JobConfig.runTimestamp(c) shouldBe JobConfig.runTimestamp(c)
    JobConfig.validateRunOptions(c) shouldBe None
  }

  test("fixed run timestamp is canonicalized to an ISO-8601 instant") {
    val c = conf(JobConfig.RunTimestampKey -> " 2026-11-23T01:30:00.000Z ")
    JobConfig.runTimestamp(c) shouldBe "2026-11-23T01:30:00Z"
  }

  test("absent or blank run timestamp falls back to the wall clock") {
    Seq(conf(), conf(JobConfig.RunTimestampKey -> "  ")).foreach { c =>
      JobConfig.fixedRunTimestamp(c) shouldBe None
      val before = Instant.now
      val ts     = Instant.parse(JobConfig.runTimestamp(c))
      ts.isBefore(before.minusSeconds(5)) shouldBe false
      JobConfig.validateRunOptions(c) shouldBe None
    }
  }

  test("invalid run timestamp is reported by validateRunOptions") {
    val err = JobConfig.validateRunOptions(conf(JobConfig.RunTimestampKey -> "yesterday"))
    err.isDefined shouldBe true
    err.get should include("scholarchain.run.timestamp")
    err.get should include("yesterday")
  }

  test("lineage job names match each job's driver name") {
    HadoopLineage.DblpExtraction.jobName shouldBe DblpExtractionJob.JobName
    HadoopLineage.OpenAlexNormalization.jobName shouldBe OpenAlexNormalizationJob.JobName
    HadoopLineage.OrcidNormalization.jobName shouldBe OrcidNormalizationJob.JobName
    HadoopLineage.CrossrefNormalization.jobName shouldBe CrossrefNormalizationJob.JobName
    HadoopLineage.FeatureConstruction.jobName shouldBe FeatureConstructionJob.JobName
    HadoopLineage.TeamExtraction.jobName shouldBe TeamExtractionJob.JobName
    HadoopLineage.PairExpansion.jobName shouldBe PairExpansionJob.JobName
    HadoopLineage.All.map(_.producer).distinct.size shouldBe HadoopLineage.All.size
  }

  test("stamp sets transformationStep, producer and sorted distinct upstream run IDs") {
    val p = Provenance("dblp", "dblp:k", "run-b", "1.0.0", "2026-11-23T00:00:00Z")
    val s = HadoopLineage.PairExpansion.stamp(p, Seq("run-b", "run-a", "", "run-b"))
    s.transformationStep shouldBe Some("pair-expansion")
    s.producer shouldBe Some("hadoop/J07-pair-expansion")
    s.upstreamRunIds shouldBe Vector("run-a")
    s.sourceDataset shouldBe "dblp"
    s.timestamp shouldBe p.timestamp
    JobLineage("J99", "x").stamp(p).upstreamRunIds shouldBe Vector.empty
  }

  test("N56: stamp keeps a different upstream run ID and never repeats the record's own run ID") {
    val p = Provenance("dblp", "dblp:k", "run-now", "1.0.0", "2026-11-23T00:00:00Z")
    HadoopLineage.TeamExtraction.stamp(p, Seq("run-j01")).upstreamRunIds shouldBe Vector("run-j01")
    HadoopLineage.TeamExtraction.stamp(p, Seq("run-now")).upstreamRunIds shouldBe Vector.empty
    HadoopLineage.TeamExtraction
      .stamp(p.copy(upstreamRunIds = Vector("run-now", "run-j01")), Seq("run-now"))
      .upstreamRunIds shouldBe Vector("run-j01")
  }
