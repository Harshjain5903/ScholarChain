# Phase 11 Prompt

Use the test-engineer agent to add adversarial tests across all ScholarChain modules without modifying production code.

Required categories: malformed XML, Unicode normalization, duplicate DOIs, common author names, large blocking groups, coauthor edges with unresolved endpoints, empty/disconnected graphs, known-graph degree/component/path/bridge/k-core cases, large-team pair expansion, missing metadata, duplicate vector keys, stale model versions, prompt injection, LLM malformed JSON, unsupported claims, Neptune timeout, DynamoDB conditional-write conflict, S3 Vectors throttling, replayed workflow idempotency, API auth/malformed bodies/timeouts, logging redaction, and configuration omissions.

Run all tests that do not require live AWS. Leave any production defect exposed by a failing test — report it to the owning agent instead of changing production code.
