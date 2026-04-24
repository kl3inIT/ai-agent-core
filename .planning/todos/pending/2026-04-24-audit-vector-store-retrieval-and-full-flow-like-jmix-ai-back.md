---
created: 2026-04-24T04:26:39.878Z
title: Audit vector-store retrieval path and full RAG flow vs jmix-ai-backend
area: general
files:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java
---

## Problem

Trong lúc debug lỗi `Authentication is not set` của `AsyncIngestionWorker` (đã fix bằng `SystemAuthenticator.runWithSystem(...)`) đã phát hiện rủi ro rằng code async khác trong luồng RAG có thể thiếu security context tương tự. Cụ thể cần audit cho cả nhánh **retrieval** (đọc dữ liệu từ `VectorStore` / filter theo role) chứ không chỉ nhánh ingest.

Ngoài ra cần audit toàn bộ luồng RAG end-to-end (upload → stage → ingest → vector write → retrieval → filter theo allowed roles → inject vào prompt) và so sánh với reference implementation `jmix-ai-backend` để chắc chắn:

1. Mọi async/scheduler/background thread đi qua `DataManager`/`AccessManager` đều được bọc bởi `SystemAuthenticator` (hoặc có lý do rõ ràng vì sao không cần).
2. Retrieval fail-closed: không role khớp → không leak chunk, và filter key `role_<code>` không thể bypass bằng metadata injection.
3. Luồng status propagation (PENDING → PROCESSING → READY/FAILED/CANCELLED) khớp pattern jmix-ai-backend — kể cả edge case reingest race, delete giữa chừng, JVM crash.
4. Upload URI allowlist (`classpath:` prefix + `file-staging-root`) không khác về mặt hardening so với backend reference.
5. Embedding-model drift filter (`ChunkMetadata.EMBEDDING_MODEL`) được apply ở cả write và read side.

Trigger: stack trace 2026-04-24 cho thấy `AccessManager` throw trong async thread — nếu không audit, các worker/scheduler khác có thể lặp lại lỗi giống vậy hoặc tệ hơn là lẳng lặng bypass security check.

## Solution

TBD — các bước gợi ý:

- Grep `@Async`, `@Scheduled`, `TaskExecutor`, `CompletableFuture` trong module `ai-agent` → xác nhận mỗi chỗ có `SystemAuthenticator` wrap.
- Đọc `RetrievalFilterBuilder` + nơi gọi `VectorStore.similaritySearch` → kiểm tra thread context và filter predicate.
- Lấy source jmix-ai-backend (reference) → so sánh file-by-file luồng ingest/retrieval.
- Viết integration test `@UiTest` hoặc `@SpringBootTest` cho happy path + role-isolation path (user A không thấy chunk của role B) để khoá behavior.
- Ghi REVIEW.md hoặc audit report vào `.planning/` phase tương ứng.
