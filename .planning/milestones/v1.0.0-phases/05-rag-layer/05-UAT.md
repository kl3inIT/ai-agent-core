---
status: complete
phase: 05-rag-layer
source:
  - 05-01-SUMMARY.md
  - 05-02-SUMMARY.md
  - 05-03-SUMMARY.md
  - 05-04-SUMMARY.md
  - 05-05-SUMMARY.md
started: 2026-04-20T22:15:58.6687800+07:00
updated: 2026-04-20T22:15:58.6687800+07:00
---

## Current Test

[testing complete]

## Tests

### 1. RAG Foundation Boots
expected: Boot an app with `ai-agent-starter` and the Phase 5 defaults; exactly one shared `EmbeddingModel` resolves, `PgVectorStore` binds to `AI_AGENT_KB_VECTOR_STORE`, and the Spring context starts without bean collisions.
result: pass

### 2. Upload Document to READY
expected: Upload a markdown, PDF, text, or HTML knowledge document through `KnowledgeDocumentUploadService`; status transitions `PENDING -> PROCESSING -> READY` and chunks are written with `source`, `documentId`, `embeddingModel`, `allowedRoles`, and `role_*` metadata.
result: pass

### 3. Role-Scoped Retrieval
expected: A user only retrieves chunks whose `allowedRoles` overlap their current Jmix roles; admin bypass returns broader results when enabled; mismatched roles do not leak protected documents.
result: pass

### 4. Fail-Closed Retrieval
expected: Empty `allowedRoles` or null authentication do not return chunks to non-admin callers; the retrieval filter resolves to a no-match expression instead of falling back to broad access.
result: pass

### 5. Delete and Reingest Integrity
expected: Deleting a knowledge document removes both the `AiKnowledgeDocument` row and its vector chunks, and reingest purges old chunks before scheduling a fresh ingest without leaving stale data behind.
result: pass

### 6. Custom Ingester Default Posture
expected: The sample classpath markdown ingester stays disabled by default, and when enabled it feeds the same upload/ingest pipeline without bypassing role tagging or source validation.
result: pass

## Summary

total: 6
passed: 6
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
