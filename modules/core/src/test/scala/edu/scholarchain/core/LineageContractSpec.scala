package edu.scholarchain.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

/**
 * Feature tests for work order DOM-P12-R08: M_r evidence work IDs and bridge-score method on
 * [[ResearcherMetrics]], and lineage fields on [[Provenance]]. Every added field has a default, so
 * the focus is on backward reads of JSON written before the change and on round-trips with
 * non-default values.
 */
class LineageContractSpec extends AnyFlatSpec with Matchers {
  import TestFixtures.*

  /** Old-shape Provenance JSON, exactly the five original fields. */
  private val legacyProvenanceJson: String =
    """{"sourceDataset":"openalex","sourceRecordId":"W123456","pipelineRunId":"run:test-001",""" +
      """"schemaVersion":"1.0.0","timestamp":"2024-01-15T10:30:00Z"}"""

  /** Old-shape ResearcherMetrics JSON, without maximumTeamWorkIds or bridgeScoreMethod. */
  private def legacyMetricsJson(maxCoauthors: Int, bridgeScore: Double): String =
    s"""{"researcherId":"researcher:orcid:0000-0001-2345-6789","publicationCount":42,
       |"distinctCoauthorCount":15,"totalCoauthorOccurrences":120,"averageCoauthorsPerPaper":2.86,
       |"maximumCoauthorsOnOnePaper":$maxCoauthors,"weightedDegree":35.5,"componentId":"comp:001",
       |"communityId":"comm:003","bridgeScore":$bridgeScore,"coreness":3,"windowStartYear":2019,
       |"windowEndYear":2023,"provenance":$legacyProvenanceJson}""".stripMargin

  private val fullProvenance: Provenance = sampleProvenance.copy(
    transformationStep = Some("entity-resolution"),
    producer = Some("EntityResolutionRunner"),
    upstreamRunIds = Vector("run:hadoop-001", "run:hadoop-002"),
    policyVersion = Some("er-mention-v1-uncalibrated")
  )

  // --- Provenance lineage (Pass 6 N44) ---

  "Provenance" should "read legacy JSON without lineage fields, using the defaults" in {
    val p = read[Provenance](legacyProvenanceJson)
    p shouldBe sampleProvenance
    p.transformationStep shouldBe None
    p.producer shouldBe None
    p.upstreamRunIds shouldBe Vector.empty
    p.policyVersion shouldBe None
  }

  it should "keep the five-argument positional constructor source-compatible" in {
    val p = Provenance("openalex", "W1", "run:1", "1.0.0", "2024-01-15T10:30:00Z")
    p.transformationStep shouldBe None
    p.upstreamRunIds shouldBe empty
  }

  it should "round-trip every lineage field through JSON" in {
    read[Provenance](write(fullProvenance)) shouldBe fullProvenance
  }

  it should "write lineage fields under their declared names when set" in {
    val obj = writeJs(fullProvenance).obj
    obj("transformationStep").str shouldBe "entity-resolution"
    obj("producer").str shouldBe "EntityResolutionRunner"
    obj("upstreamRunIds").arr.map(_.str).toVector shouldBe Vector(
      "run:hadoop-001",
      "run:hadoop-002"
    )
    obj("policyVersion").str shouldBe "er-mention-v1-uncalibrated"
  }

  it should "write default lineage values so legacy-shaped readers still parse the JSON" in {
    val written = ujson.read(write(sampleProvenance)).obj
    // Every original field is still present with its original value.
    written("sourceDataset").str shouldBe "openalex"
    written("sourceRecordId").str shouldBe "W123456"
    written("pipelineRunId").str shouldBe "run:test-001"
    written("schemaVersion").str shouldBe "1.0.0"
    written("timestamp").str shouldBe "2024-01-15T10:30:00Z"
    // upickle omits fields holding their defaults, so the default output is the legacy shape.
    written.keySet.toSet shouldBe
      Set("sourceDataset", "sourceRecordId", "pipelineRunId", "schemaVersion", "timestamp")
    read[Provenance](write(sampleProvenance)) shouldBe sampleProvenance
  }

  it should "let a legacy-shaped reader ignore populated lineage fields" in {
    // A reader compiled against the five original fields must still decode new JSON.
    val legacy = read[LineageContractSpec.LegacyProvenance](write(fullProvenance))
    legacy shouldBe LineageContractSpec.LegacyProvenance(
      "openalex",
      "W123456",
      "run:test-001",
      "1.0.0",
      "2024-01-15T10:30:00Z"
    )
    // And the current reader drops nothing.
    read[Provenance](write(fullProvenance)) shouldBe fullProvenance
  }

  it should "carry lineage through records that embed it" in {
    val edge = sampleCoauthorEdge.copy(provenance = fullProvenance)
    read[CoauthorEdge](write(edge)) shouldBe edge
  }

  it should "reject empty-string lineage values" in {
    an[IllegalArgumentException] should be thrownBy
      sampleProvenance.copy(transformationStep = Some(""))
    an[IllegalArgumentException] should be thrownBy sampleProvenance.copy(producer = Some(""))
    an[IllegalArgumentException] should be thrownBy sampleProvenance.copy(policyVersion = Some(""))
    an[IllegalArgumentException] should be thrownBy
      sampleProvenance.copy(upstreamRunIds = Vector("run:1", ""))
  }

  // --- ResearcherMetrics.maximumTeamWorkIds (Spark review HIGH) ---

  "ResearcherMetrics.maximumTeamWorkIds" should "default to empty when absent from legacy JSON" in {
    val m = read[ResearcherMetrics](legacyMetricsJson(maxCoauthors = 8, bridgeScore = 0.72))
    m shouldBe sampleResearcherMetrics
    m.maximumTeamWorkIds shouldBe Vector.empty
  }

  it should "round-trip populated work IDs through JSON" in {
    val m = sampleResearcherMetrics.copy(
      maximumTeamWorkIds = Vector("work:openalex:W1", "work:openalex:W2")
    )
    val back = read[ResearcherMetrics](write(m))
    back shouldBe m
    back.maximumTeamWorkIds shouldBe Vector("work:openalex:W1", "work:openalex:W2")
    writeJs(m).obj("maximumTeamWorkIds").arr.map(_.str).toVector shouldBe m.maximumTeamWorkIds
  }

  it should "be rejected when non-empty while maximumCoauthorsOnOnePaper is 0" in {
    val ex = intercept[IllegalArgumentException] {
      sampleResearcherMetrics.copy(
        maximumCoauthorsOnOnePaper = 0,
        maximumTeamWorkIds = Vector("work:openalex:W1")
      )
    }
    ex.getMessage should include("maximumTeamWorkIds")
  }

  it should "be accepted empty when maximumCoauthorsOnOnePaper is 0 (solo author)" in {
    noException should be thrownBy
      sampleResearcherMetrics.copy(
        maximumCoauthorsOnOnePaper = 0,
        maximumTeamWorkIds = Vector.empty
      )
    noException should be thrownBy
      read[ResearcherMetrics](legacyMetricsJson(maxCoauthors = 0, bridgeScore = 0.0))
  }

  it should "be allowed empty when maximumCoauthorsOnOnePaper is positive (legacy data)" in {
    noException should be thrownBy
      sampleResearcherMetrics.copy(
        maximumCoauthorsOnOnePaper = 5,
        maximumTeamWorkIds = Vector.empty
      )
  }

  // --- ResearcherMetrics.bridgeScoreMethod (Pass 6 N52) ---

  "BridgeScoreMethod" should "pin the exact allowed values" in {
    BridgeScoreMethod.Exact shouldBe "exact"
    BridgeScoreMethod.Sampled shouldBe "sampled"
    BridgeScoreMethod.NotComputed shouldBe "not-computed"
    BridgeScoreMethod.All shouldBe Vector("exact", "sampled", "not-computed")
  }

  it should "validate case-sensitively" in {
    BridgeScoreMethod.All.foreach(m => BridgeScoreMethod.isValid(m) shouldBe true)
    Vector("", "Exact", "EXACT", "skipped", "not_computed", "approx", null)
      .foreach(m => BridgeScoreMethod.isValid(m) shouldBe false)
  }

  "ResearcherMetrics.bridgeScoreMethod" should "default to exact for legacy JSON and constructors" in {
    sampleResearcherMetrics.bridgeScoreMethod shouldBe BridgeScoreMethod.Exact
    read[ResearcherMetrics](legacyMetricsJson(8, 0.72)).bridgeScoreMethod shouldBe "exact"
  }

  it should "read a legacy skipped 0.0 placeholder back as exact (documented N59 caveat)" in {
    val legacy = read[ResearcherMetrics](legacyMetricsJson(8, 0.0))
    legacy.bridgeScore shouldBe 0.0
    legacy.bridgeScoreMethod shouldBe BridgeScoreMethod.Exact
  }

  it should "distinguish a skipped betweenness from a computed zero" in {
    val computedZero = sampleResearcherMetrics.copy(bridgeScore = 0.0)
    val skipped      = computedZero.copy(bridgeScoreMethod = BridgeScoreMethod.NotComputed)
    computedZero should not be skipped
    val skippedBack = read[ResearcherMetrics](write(skipped))
    skippedBack.bridgeScoreMethod shouldBe "not-computed"
    skippedBack.bridgeScore shouldBe 0.0
    writeJs(skipped).obj("bridgeScoreMethod").str shouldBe "not-computed"
  }

  it should "round-trip the sampled method" in {
    val m = sampleResearcherMetrics.copy(bridgeScoreMethod = BridgeScoreMethod.Sampled)
    read[ResearcherMetrics](write(m)) shouldBe m
  }

  it should "reject an unknown method, naming the allowed values" in {
    val ex = intercept[IllegalArgumentException] {
      sampleResearcherMetrics.copy(bridgeScoreMethod = "skipped")
    }
    ex.getMessage should include("skipped")
    ex.getMessage should include("not-computed")
  }

  it should "reject unknown methods when reading JSON" in {
    val bad = ujson.read(write(sampleResearcherMetrics))
    bad("bridgeScoreMethod") = "approx"
    an[Exception] should be thrownBy read[ResearcherMetrics](bad)
  }

  it should "require the 0.0 placeholder when not computed" in {
    an[IllegalArgumentException] should be thrownBy
      sampleResearcherMetrics.copy(
        bridgeScore = 0.5,
        bridgeScoreMethod = BridgeScoreMethod.NotComputed
      )
  }
}

object LineageContractSpec:

  /** The Provenance shape before DOM-P12-R08, used to simulate an un-upgraded reader. */
  final case class LegacyProvenance(
    sourceDataset: String,
    sourceRecordId: String,
    pipelineRunId: String,
    schemaVersion: String,
    timestamp: String
  ) derives ReadWriter
