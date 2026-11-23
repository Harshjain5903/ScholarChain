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


## Exact human work orders (RECONSTRUCTED 2026-11-23)
Copied word for word from the saved Claude Code session transcripts (`~/.claude/projects/-Users-harshjain-Documents-scholarchain/<session>.jsonl`). Text above this section is the original file and is unchanged.

### Work order to storage-engineer (2026-11-19T17:13Z)

Session `7aafb09f-5927-4b74-9c74-70dface1b0d2`, message `3d5ba643-b583-4f56-9e24-90b7f4810562`, sent 2026-11-19T17:13:33.069Z.

````text
Use the storage-engineer agent to implement the ScholarChain storage adapters under modules/storage/.

Build four adapters using the Phase 3 domain contracts from modules/core/:

1. S3Client — raw/curated/graph/RAG zone helpers using AWS SDK v2. Include SHA-256 content hashing so putIfChanged skips unchanged objects. Support run manifests.

2. NeptuneClient — openCypher over HTTPS (java.net.http.HttpClient, not the SDK). Parameterized queries only, no string concatenation. Methods for getResearcher, getWork, getDirectCoauthors, shortestPath (bounded depth 1-10), getNeighbors (bounded depth 1-3, limit 1-500), getComponentMembers, and bulk-load manifest generation.

3. DynamoDbClient — AWS SDK v2 DynamoDB client with 5 tables: RunLedger (PK=runId), EntityLinkCache (PK=sourceId SK=targetId with TTL), QuerySession (PK=queryId), EvidenceBundle (PK=bundleId), ArtifactRegistry (PK=artifactId). Use conditional puts (attribute_not_exists) for idempotency. All entry case classes should derive ReadWriter for uPickle serialization.

4. S3VectorsClient — REST API via java.net.http.HttpClient for 5 indexes: researcher-profile-v1, collaboration-community-v1, paper-abstract-v1, collaboration-path-v1, topic-institution-summary-v1. Batch writes with 3 retries and exponential backoff. Metadata filters on query. Store long text in ordinary S3 and keep only URI references in vector metadata.

Also create:
- modules/storage/src/main/resources/reference.conf with HOCON defaults for all storage config (buckets, neptune endpoint, dynamodb region, s3-vectors settings). All values overridable via env vars.
- Contract tests under modules/storage/src/test/ — at least 15 tests covering serialization round-trips for all DynamoDB entry types, vector metadata, Neptune response types, index completeness, confidence range validation, coauthor edge pair ordering, researcher metrics completeness, graph edge provenance, and evidence item round-trips.

Use the existing version ledger (AWS SDK v2 2.29.26, uPickle 4.0.2, Typesafe Config 1.4.3). Match the core domain types already defined in modules/core/.

Run sbt storage/compile and sbt storage/test. Report schemas, keys, indexes, idempotency strategy, service limits, and cleanup procedures.
````
