package edu.scholarchain.rag

import edu.scholarchain.core.*

/** Verifies answer claims against an evidence bundle. */
class ClaimVerificationAgent:

  def verify(
    claims: Vector[AnswerClaim],
    evidence: EvidenceBundle
  ): Vector[AnswerClaim] =
    val evidenceIds = evidence.items.map(_.evidenceId).toSet
    claims.map(claim => verifySingle(claim, evidenceIds))

  private def verifySingle(
    claim: AnswerClaim,
    evidenceIds: Set[String]
  ): AnswerClaim =
    if claim.supportingEvidence.isEmpty then
      claim.copy(verificationStatus = VerificationStatus.Unsupported)
    else
      val existing = claim.supportingEvidence.filter(evidenceIds.contains)
      val missing  = claim.supportingEvidence.filterNot(evidenceIds.contains)

      if missing.isEmpty then claim.copy(verificationStatus = VerificationStatus.Verified)
      else if existing.nonEmpty then
        claim.copy(verificationStatus = VerificationStatus.PartiallySupported)
      else claim.copy(verificationStatus = VerificationStatus.Unsupported)
