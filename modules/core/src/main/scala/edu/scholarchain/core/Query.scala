package edu.scholarchain.core

import upickle.default.*

/** A decomposed query plan describing retrieval and analysis operations. */
case class QueryPlan(
    queryId: String,
    originalQuery: String,
    operations: Vector[QueryOperation],
    filters: Map[String, String],
    provenance: Provenance
) derives ReadWriter

/** A single claim produced in response to a query. */
case class AnswerClaim(
    claimId: String,
    queryId: String,
    statement: String,
    confidence: Double,
    supportingEvidence: Vector[String],
    verificationStatus: VerificationStatus,
    provenance: Provenance
) derives ReadWriter:
  require(
    confidence >= 0.0 && confidence <= 1.0,
    s"AnswerClaim confidence must be in [0.0, 1.0], got $confidence"
  )

/** The full response to a query, bundling claims and evidence. */
case class AnswerResponse(
    responseId: String,
    queryId: String,
    claims: Vector[AnswerClaim],
    evidenceBundle: EvidenceBundle,
    generatedText: String,
    provenance: Provenance
) derives ReadWriter
