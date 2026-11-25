package edu.scholarchain.rag

import edu.scholarchain.core.*
import edu.scholarchain.rag.RagFixtures.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ClaimVerificationSpec extends AnyFlatSpec with Matchers:

  private val metricItem =
    item("evidence:q1:0000", EvidenceContent.metric(metrics(adaId, 42, 10)))

  private val pathItem = item(
    "evidence:q1:0001",
    EvidenceContent.path(adaId, alanId, Vector(adaId, graceId, alanId)),
    EvidenceType.GraphPath
  )

  private val graceMetric =
    item("evidence:q1:0002", EvidenceContent.metric(metrics(graceId, 7, 3)), source = graceId)
  private val evidence = bundle(metricItem, pathItem, graceMetric)

  private def claim(statement: String, ids: String*): AnswerClaim =
    AnswerClaim("c1", "q1", statement, 0.9, ids.toVector, VerificationStatus.Unsupported, prov)

  private def status(statement: String, ids: String*): ClaimCheck =
    ClaimVerificationAgent().verifyDetailed(Vector(claim(statement, ids*)), evidence).head

  "ClaimVerificationAgent" should "verify numbers, IDs and windows that match the cited evidence" in {
    status(
      s"$adaId had 42 publications and 10 distinct coauthors in 2015-2024.",
      "evidence:q1:0000"
    ).status shouldBe
      VerificationStatus.Verified
  }

  it should "mark a fabricated metric value as CONFLICTING" in {
    val c = status(s"$adaId had 420 publications.", "evidence:q1:0000")
    c.status shouldBe VerificationStatus.Conflicting
    c.reasons.head should include("stated 420")
  }

  it should "mark a fabricated number with no metric context as UNSUPPORTED" in {
    status("Ada won 17 awards.", "evidence:q1:0000").status shouldBe VerificationStatus.Unsupported
  }

  it should "mark a partly fabricated claim as PARTIALLY_SUPPORTED" in {
    status(s"$adaId had 42 publications and 17 awards.", "evidence:q1:0000").status shouldBe
      VerificationStatus.PartiallySupported
  }

  it should "reject a researcher ID that is not in the cited evidence" in {
    val fabricated = "researcher:orcid:9999-9999-9999-9999"
    status(s"$fabricated had 42 publications.", "evidence:q1:0000").status shouldBe
      VerificationStatus.PartiallySupported
    status(
      s"$fabricated is a coauthor.",
      "evidence:q1:0000"
    ).status shouldBe VerificationStatus.Unsupported
  }

  it should "reject a real researcher ID whose evidence was not cited" in {
    status(
      s"$graceId is connected.",
      "evidence:q1:0000"
    ).status shouldBe VerificationStatus.Unsupported
  }

  it should "check path hops against the cited path" in {
    status(s"Path: $adaId -> $graceId -> $alanId (2 hops).", "evidence:q1:0001").status shouldBe
      VerificationStatus.Verified
    status(s"Path: $adaId -> $alanId.", "evidence:q1:0001").status shouldBe
      VerificationStatus.PartiallySupported
    status(
      "Direct hop R7 -> R8.",
      "evidence:q1:0001"
    ).status shouldBe VerificationStatus.Unsupported
  }

  it should "flag a wrong hop count as CONFLICTING" in {
    status(s"$adaId reaches $alanId in 5 hops.", "evidence:q1:0001").status shouldBe
      VerificationStatus.Conflicting
  }

  it should "reject years outside the cited window" in {
    status(
      "Ada was active in 1990.",
      "evidence:q1:0000"
    ).status shouldBe VerificationStatus.Unsupported
    status(
      "Ada was active in 2019.",
      "evidence:q1:0000"
    ).status shouldBe VerificationStatus.Verified
  }

  it should "mark claims with no existing evidence as UNSUPPORTED" in {
    status(
      "Ada had 42 publications.",
      "evidence:q1:7777"
    ).status shouldBe VerificationStatus.Unsupported
    status("Ada had 42 publications.").status shouldBe VerificationStatus.Unsupported
  }

  it should "only accept numbers from cited items, not from other bundle items" in {
    // 7 is Grace's publication count (evidence 0002) but the claim cites only Ada's item
    status(
      "Ada had 7 publications.",
      "evidence:q1:0000"
    ).status shouldBe VerificationStatus.Conflicting
  }
