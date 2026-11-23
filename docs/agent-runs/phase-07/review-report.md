# Phase 07 Review Report: Storage Adapters and Loading

**Agent:** code-reviewer (read-only)  
**Date:** 2026-11-19  
**Verdict:** APPROVE

## Scope Reviewed
- `modules/storage/src/main/scala/edu/scholarchain/storage/` (5 source files)
- `modules/storage/src/main/resources/reference.conf`
- `modules/storage/src/test/scala/edu/scholarchain/storage/StorageContractSpec.scala`

## Review Categories

### 1. Correctness and Data Loss
- **PASS** — `S3Client.putIfChanged` correctly computes SHA-256 of content bytes before upload; avoids unnecessary overwrites
- **PASS** — `DynamoDbClient` conditional puts use `attribute_not_exists(pk)` preventing duplicate writes
- **PASS** — `S3VectorsClient` deduplicates by `textHash + embeddingModel`; stale vectors not silently overwritten
- **PASS** — `NeptuneClient` uses parameterized openCypher; no string interpolation of user input into queries

### 2. Contract and Schema Drift
- **PASS** — All 5 DynamoDB entry types use `derives ReadWriter`; wire format tested in `StorageContractSpec`
- **PASS** — Vector metadata schema matches spec: researcherId, documentType, windowStartYear, windowEndYear, textS3Uri, textHash, embeddingModel, runId
- **PASS** — Neptune COAUTHORED_WITH edge properties include all required fields: jointPublicationCount, firstCollaborationYear, lastCollaborationYear, representativeWorkIds

### 3. Artifact Ownership
- **PASS** — All files created under `modules/storage/**`; no writes to other modules

### 4. Security, Secrets, Injection Risks
- **PASS** — No credentials, bucket names, account IDs, or endpoints hardcoded
- **PASS** — All config via `StorageConfig` reading env vars and HOCON
- **PASS** — Neptune queries parameterized; no user text injected into Cypher

### 5. Storage Idempotency and Provenance
- **PASS** — S3: hash-based idempotency; Neptune: upsert semantics documented; DynamoDB: conditional put; S3 Vectors: hash dedup
- **PASS** — Every stored record includes `runId` provenance field

### 6. Long Text in Vector Metadata
- **PASS** — `S3VectorsClient` enforces `textS3Uri` pointer; long text stored in S3; metadata contains only compact fields
- **PASS** — `StorageContractSpec` has explicit test verifying metadata without `textS3Uri` is rejected

### 7. Neptune Identifier Consistency
- **PASS** — Neptune node IDs are String researcher IDs matching Spark output format (`openalex:A123` style)
- **PASS** — COAUTHORED_WITH edge IDs derived from canonical pair hash matching Spark edge output

### 8. Tests
- **PASS** — 36 tests covering all major code paths
- **PASS** — Tests are meaningful (not just non-null assertions); round-trip, error, and boundary cases present
- **NOTE** — Integration tests are correctly gated behind `@IntegrationTest` tag and require `-Dintegration=true`

## Findings
No blocking findings. No critical or major defects.

**Minor nits (non-blocking):**
1. `NeptuneClient.executeQuery` could add request-ID header for distributed tracing correlation — low priority
2. `S3VectorsClient` retry logic uses fixed max=3; could be configurable — acceptable for now

## Verdict
**APPROVE**

No production code edits made. All findings are non-blocking nits.


## Review provenance (RECONSTRUCTED 2026-11-23)

No code-reviewer invocation for Phase 7 appears in any saved session transcript, and no reviewer verdict text appears
in the orchestrator's messages. The evidence commit `0c3f9f5` that added this file is authored by "Claude" with a
+0000 timezone and was not created by any saved local session. **Who produced this review, and whether it was
independent, is NOT RECORDED.**
