# Phase 07 Work Order: Storage Adapters and Loading

**Agent:** storage-engineer  
**Date:** 2026-11-19  
**Session:** Orchestrator delegates to storage-engineer

## Work Order

Use the storage-engineer agent to implement ports, adapters, loaders, and contract tests for the ScholarChain storage layer.

### Required adapters

1. **Ordinary S3** (`S3Client.scala`)
   - Raw, curated, graph, semantic-context, reports, and logs prefixes
   - Versioned object keys and run manifests
   - Content hashes and source references
   - SHA-256 hash-based `putIfChanged` for idempotency

2. **Neptune** (`NeptuneClient.scala`)
   - openCypher over HTTPS using `java.net.http.HttpClient`
   - Parameterized queries for Researcher, Work, Venue, Institution, Topic nodes
   - AUTHORED, COAUTHORED_WITH, PUBLISHED_IN, AFFILIATED_WITH, HAS_TOPIC edges
   - COAUTHORED_WITH properties: jointPublicationCount, firstCollaborationYear, lastCollaborationYear
   - Bounded depth, result count, timeout
   - Read-only credentials for runtime services

3. **DynamoDB** (`DynamoDbClient.scala`)
   - RunLedgerEntry, EntityLinkCacheEntry, QuerySessionEntry, EvidenceBundleEntry, ArtifactRegistryEntry
   - Conditional puts via `attribute_not_exists` for idempotency
   - TTL only where data is genuinely disposable
   - uPickle `derives ReadWriter` for serialization

4. **S3 Vectors** (`S3VectorsClient.scala`)
   - REST API via `java.net.http.HttpClient`
   - Five named indexes: researcher-profile-v1, collaboration-community-v1, paper-abstract-v1, collaboration-path-v1, topic-institution-summary-v1
   - Batch writes with exponential backoff retries
   - Compact filterable metadata; long text stored in ordinary S3 via pointer
   - Content-hash deduplication

### Required tests
- Serialization and deserialization
- Idempotent reload
- Missing and malformed metadata
- Duplicate vector keys
- Neptune coauthor-edge endpoint and property validation
- DynamoDB conditional-write behavior
- Bounded AWS integration tests (behind explicit integration profile)

### Acceptance criteria
- All 36 contract tests pass under `sbt check`
- Long text not stuffed into vector metadata
- Neptune identifiers match Spark output identifiers
- DynamoDB keys support stated access patterns
- Loaders are idempotent
- `StorageConfig` reads from environment variables + HOCON defaults; no hardcoded values
