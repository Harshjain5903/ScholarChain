# Phase 8 Prompt

Use the storage-engineer and rag-engineer in separate delegated work orders to build the embedding and S3 Vectors ingestion path.

Storage-engineer: EmbeddingQueueManager, EmbeddingBatchWriter, bounded SQS-style queue, batch vector writes, dedup by SHA-256.

Rag-engineer: EmbeddingTemplates, ChunkingPolicy, EmbeddingModelConfig, SemanticContextFormat, retrieval smoke tests.

Run sbt check. All tests must pass.
