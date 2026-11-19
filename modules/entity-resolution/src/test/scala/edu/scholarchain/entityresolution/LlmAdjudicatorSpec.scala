package edu.scholarchain.entityresolution

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

class LlmAdjudicatorSpec extends AnyFlatSpec with Matchers {

  val adjudicator = new StubLlmAdjudicator()

  val request: AdjudicationRequest = AdjudicationRequest(
    sourceId = "src-1",
    targetId = "tgt-1",
    features = Map("title_jaccard" -> 0.8),
    sourceFields = Map("title" -> "Deep Learning"),
    targetFields = Map("title" -> "Deep Learning Survey"),
    policyVersion = "er-v1",
    promptVersion = "adjudication-v1"
  )

  "StubLlmAdjudicator" should "always return Abstain" in {
    val response = adjudicator.adjudicate(request)
    response.decision shouldBe AdjudicationDecision.Abstain
    response.reasonCodes should contain("stub_adjudicator")
    response.confidence shouldBe 0.0
    response.modelVersion shouldBe "stub"
  }

  it should "cache adjudication results" in {
    val response1 = adjudicator.adjudicate(request)
    val cached    = adjudicator.lookupCache(request)
    cached shouldBe defined
    cached.get.decision shouldBe response1.decision
  }

  it should "return None for uncached requests" in {
    val newAdj = new StubLlmAdjudicator()
    val different = request.copy(sourceId = "src-999")
    newAdj.lookupCache(different) shouldBe None
  }

  it should "produce different cache entries for same pair with different features" in {
    val adj = new StubLlmAdjudicator()
    val request1 = request.copy(features = Map("title_jaccard" -> 0.8))
    val request2 = request.copy(features = Map("title_jaccard" -> 0.5))
    adj.adjudicate(request1)
    adj.adjudicate(request2)
    // Both should be cached independently
    adj.lookupCache(request1) shouldBe defined
    adj.lookupCache(request2) shouldBe defined
    // A request with yet different features should not be cached
    val request3 = request.copy(features = Map("title_jaccard" -> 0.3))
    adj.lookupCache(request3) shouldBe None
  }

  "AdjudicationRequest" should "serialize to JSON" in {
    val json = write(request)
    json should include("src-1")
    json should include("tgt-1")
    val parsed = read[AdjudicationRequest](json)
    parsed shouldBe request
  }

  "AdjudicationResponse" should "serialize to JSON" in {
    val response = AdjudicationResponse(
      decision = AdjudicationDecision.Abstain,
      reasonCodes = Vector("test"),
      confidence = 0.5,
      modelVersion = "stub"
    )
    val json   = write(response)
    val parsed = read[AdjudicationResponse](json)
    parsed shouldBe response
  }

  "AdjudicationCacheEntry" should "serialize to JSON" in {
    val entry = AdjudicationCacheEntry(
      inputHash = "abc123",
      promptVersion = "v1",
      modelVersion = "stub",
      policyVersion = "er-v1",
      response = AdjudicationResponse(
        AdjudicationDecision.Abstain,
        Vector.empty,
        0.0,
        "stub"
      )
    )
    val json   = write(entry)
    val parsed = read[AdjudicationCacheEntry](json)
    parsed shouldBe entry
  }

  "AdjudicationDecision" should "round-trip through JSON" in {
    for d <- AdjudicationDecision.values do
      val json   = write(d)
      val parsed = read[AdjudicationDecision](json)
      parsed shouldBe d
  }
}
