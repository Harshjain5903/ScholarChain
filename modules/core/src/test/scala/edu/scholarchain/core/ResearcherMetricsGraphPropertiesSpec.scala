package edu.scholarchain.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

/**
 * Feature tests for work order DOM-P13-02: `ResearcherMetrics.componentSize` and the `Researcher`
 * vertex graph-property wire format shared by Spark (writer) and storage (reader).
 */
class ResearcherMetricsGraphPropertiesSpec extends AnyFlatSpec with Matchers {

  import TestFixtures.*
  import GraphSchema.PropertyKey as K

  private val rid = sampleResearcherMetrics.researcherId

  private val full: ResearcherMetrics = sampleResearcherMetrics.copy(
    maximumTeamWorkIds = Vector("work:openalex:W1", "work:dblp:conf/x/\"quoted\",comma"),
    bridgeScoreMethod = BridgeScoreMethod.Sampled,
    componentSize = Some(5000000000L),
    averageCoauthorsPerPaper = 1.0e-4,
    weightedDegree = 123456.789
  )

  private val minimal: ResearcherMetrics = sampleResearcherMetrics.copy(
    componentId = None,
    communityId = None,
    componentSize = None,
    maximumCoauthorsOnOnePaper = 0,
    maximumTeamWorkIds = Vector.empty,
    bridgeScore = 0.0,
    bridgeScoreMethod = BridgeScoreMethod.NotComputed
  )

  private def props(m: ResearcherMetrics): Map[String, String] =
    ResearcherMetrics.toGraphProperties(m).toMap

  private def decode(p: Map[String, String]): Either[String, ResearcherMetrics] =
    ResearcherMetrics.fromGraphProperties(rid, p, sampleProvenance)

  private def errorOf(p: Map[String, String]): String =
    decode(p) match
      case Left(msg) => msg
      case Right(m)  => fail(s"expected a decode error, got $m")

  // --- componentSize field ---

  "ResearcherMetrics.componentSize" should "default to None for existing constructors and legacy JSON" in {
    sampleResearcherMetrics.componentSize shouldBe None
    read[ResearcherMetrics](write(sampleResearcherMetrics)).componentSize shouldBe None
    writeJs(sampleResearcherMetrics).obj.keySet should not contain "componentSize"
  }

  it should "round-trip through JSON when set" in {
    val m = sampleResearcherMetrics.copy(componentSize = Some(42L))
    writeJs(m).obj("componentSize").num shouldBe 42.0
    read[ResearcherMetrics](write(m)) shouldBe m
  }

  it should "reject a component size below 1" in {
    an[IllegalArgumentException] should be thrownBy sampleResearcherMetrics.copy(componentSize =
      Some(0L)
    )
    an[IllegalArgumentException] should be thrownBy sampleResearcherMetrics.copy(componentSize =
      Some(-3L)
    )
  }

  // --- toGraphProperties ---

  "ResearcherMetrics.toGraphProperties" should "emit every metric key in ResearcherMetricKeys order" in {
    ResearcherMetrics.toGraphProperties(full).map(_._1) shouldBe K.ResearcherMetricKeys
  }

  it should "encode values as plain strings, work IDs as a JSON array" in {
    val p = props(full)
    p(K.PublicationCount) shouldBe "42"
    p(K.AverageCoauthorsPerPaper) shouldBe "1.0E-4"
    p(K.ComponentId) shouldBe "comp:001"
    p(K.ComponentSize) shouldBe "5000000000"
    p(K.BridgeScoreMethod) shouldBe "sampled"
    ujson.read(p(K.MaximumTeamWorkIds)) shouldBe ujson.Arr(
      ujson.Str("work:openalex:W1"),
      ujson.Str("work:dblp:conf/x/\"quoted\",comma")
    )
  }

  it should "omit None options and write an empty work-ID list as []" in {
    val p = props(minimal)
    p.keySet should contain noneOf (K.ComponentId, K.CommunityId, K.ComponentSize)
    p(K.MaximumTeamWorkIds) shouldBe "[]"
    p(K.BridgeScoreMethod) shouldBe "not-computed"
    p.keySet shouldBe (K.ResearcherMetricKeys.toSet -- Set(
      K.ComponentId,
      K.CommunityId,
      K.ComponentSize
    ))
  }

  it should "not emit identity or provenance keys" in {
    props(full).keySet should contain noneOf (K.ResearcherId, K.Provenance, K.NodeId)
  }

  it should "reject Some(\"\") ids that could not round-trip" in {
    val ex = intercept[IllegalArgumentException](
      ResearcherMetrics.toGraphProperties(full.copy(communityId = Some("")))
    )
    ex.getMessage should include("communityId")
    an[IllegalArgumentException] should be thrownBy ResearcherMetrics.toGraphProperties(
      full.copy(componentId = Some(""))
    )
  }

  // --- round trip ---

  "ResearcherMetrics graph properties" should "round-trip a fully populated record" in {
    decode(props(full)) shouldBe Right(full)
  }

  it should "round-trip a record with every option empty" in {
    decode(props(minimal)) shouldBe Right(minimal)
  }

  it should "round-trip the shared fixture and non-finite or negative doubles" in {
    decode(props(sampleResearcherMetrics)) shouldBe Right(sampleResearcherMetrics)
    val odd = sampleResearcherMetrics.copy(
      averageCoauthorsPerPaper = Double.NaN,
      weightedDegree = Double.PositiveInfinity,
      bridgeScore = -0.5
    )
    val back = decode(props(odd)).toOption.get
    back.averageCoauthorsPerPaper.isNaN shouldBe true
    back.weightedDegree shouldBe Double.PositiveInfinity
    back.bridgeScore shouldBe -0.5
  }

  it should "take researcherId and provenance from the arguments and ignore unrelated keys" in {
    val other = sampleProvenance.copy(producer = Some("NeptuneClient"))
    val p     = props(full) + (K.DisplayName -> "Alice") + (K.ResearcherId -> "ignored")
    val back  = ResearcherMetrics.fromGraphProperties("researcher:x", p, other).toOption.get
    back.researcherId shouldBe "researcher:x"
    back.provenance shouldBe other
  }

  // --- missing optional keys ---

  "ResearcherMetrics.fromGraphProperties" should "treat absent optional keys as None or empty" in {
    val p    = props(full) -- Set(K.ComponentId, K.CommunityId, K.ComponentSize)
    val back = decode(p).toOption.get
    back.componentId shouldBe None
    back.communityId shouldBe None
    back.componentSize shouldBe None
  }

  it should "treat empty-string optional values as None or empty" in {
    val p = props(minimal) ++ Map(
      K.ComponentId        -> "",
      K.CommunityId        -> "",
      K.ComponentSize      -> "",
      K.MaximumTeamWorkIds -> ""
    )
    decode(p) shouldBe Right(minimal)
    decode(props(minimal) - K.MaximumTeamWorkIds) shouldBe Right(minimal)
  }

  // --- missing required keys and malformed values ---

  it should "require bridgeScoreMethod instead of defaulting to exact (N59)" in {
    val msg = errorOf(props(full) - K.BridgeScoreMethod)
    msg should include("missing required key 'bridgeScoreMethod'")
    msg should include(rid)
  }

  it should "name every missing required key" in {
    val msg = errorOf(Map.empty)
    (K.ResearcherMetricKeys.toSet -- Set(
      K.ComponentId,
      K.CommunityId,
      K.ComponentSize,
      K.MaximumTeamWorkIds
    )).foreach(k => msg should include(s"missing required key '$k'"))
  }

  it should "report malformed values with key, expectation and offending value" in {
    val cases = Vector(
      K.PublicationCount           -> "forty-two",
      K.PublicationCount           -> "",
      K.PublicationCount           -> "3.0",
      K.PublicationCount           -> " 42",
      K.PublicationCount           -> "99999999999",
      K.Coreness                   -> "0x10",
      K.WindowStartYear            -> "+2019",
      K.AverageCoauthorsPerPaper   -> "abc",
      K.AverageCoauthorsPerPaper   -> "1.5d",
      K.WeightedDegree             -> " 1.0",
      K.BridgeScore                -> "",
      K.ComponentSize              -> "1.5",
      K.ComponentSize              -> "99999999999999999999",
      K.BridgeScoreMethod          -> "EXACT",
      K.BridgeScoreMethod          -> "",
      K.MaximumTeamWorkIds         -> "W1,W2",
      K.MaximumTeamWorkIds         -> "[1,2]",
      K.MaximumTeamWorkIds         -> "{\"a\":\"b\"}",
      K.MaximumTeamWorkIds         -> "[\"W1\"",
      K.MaximumCoauthorsOnOnePaper -> "-"
    )
    cases.foreach { case (key, value) =>
      withClue(s"$key='$value': ") {
        val msg = errorOf(props(full) + (key -> value))
        msg should include(s"key '$key' must be")
        msg should include(s"got '$value'")
      }
    }
  }

  it should "report several malformed keys together" in {
    val msg =
      errorOf(props(full) ++ Map(K.Coreness -> "x", K.BridgeScore -> "y") - K.WindowEndYear)
    msg should include("key 'coreness' must be an integer, got 'x'")
    msg should include("key 'bridgeScore' must be a number, got 'y'")
    msg should include("missing required key 'windowEndYear'")
  }

  it should "truncate very long offending values in the message" in {
    val msg = errorOf(props(full) + (K.Coreness -> ("9" * 500)))
    msg.length should be < 400
    msg should include("...")
  }

  it should "surface ResearcherMetrics invariant violations as Left, not exceptions" in {
    val inverted = props(full) ++ Map(K.WindowStartYear -> "2024", K.WindowEndYear -> "2020")
    errorOf(inverted) should include("windowStartYear (2024) must be <= windowEndYear (2020)")

    val placeholder =
      props(full) ++ Map(K.BridgeScoreMethod -> "not-computed", K.BridgeScore -> "0.3")
    errorOf(placeholder) should include("not-computed")

    errorOf(props(full) + (K.ComponentSize -> "0")) should include("componentSize must be >= 1")

    val noEvidence = props(full) + (K.MaximumCoauthorsOnOnePaper -> "0")
    errorOf(noEvidence) should include("maximumTeamWorkIds must be empty")
  }
}
