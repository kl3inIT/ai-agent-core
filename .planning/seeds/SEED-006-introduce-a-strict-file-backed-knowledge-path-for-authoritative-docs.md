---
id: SEED-006
status: dormant
planted: 2026-04-24
planted_during: v1.0 / strict-knowledge exploration
trigger_when: retrieval drift or hallucinated domain answers become a recurring production issue, or the team identifies a set of authoritative documents that must be answered only from approved sources
scope: Large
---

# SEED-006: Introduce a strict file-backed knowledge path for authoritative docs

## Why This Matters

The current RAG layer is the right tool for fuzzy recall over reference material, but it is a bad
fit for the parts of the system that must stay exact: entity semantics, business rules, policy
documents, mappings, and other domain explanations where the agent must not improvise.

Chunking, embedding, and retrieval improve breadth, but they also introduce drift:

- authoritative material gets fragmented into chunks instead of staying as one approved source
- retrieval may pull a nearby but not exact passage
- the model may synthesize across chunks and overstate confidence
- structured task requests such as `xlsx` processing get mixed into the same retrieval path even
  though they are really capability-routing problems, not knowledge-recall problems

This seed captures a future architecture change:

- keep approved markdown documents as file-backed source material
- let the agent read them directly through controlled tools when the question falls into a strict
  domain
- fail closed when no authoritative source is found
- keep vector retrieval for fuzzy reference material only
- route structured tasks like `xlsx` handling to dedicated tool workflows instead of generic RAG

That split aligns the mechanism with the trust level of the content instead of forcing one storage
model to solve every problem.

## When to Surface

**Trigger:** retrieval drift or hallucinated domain answers become a recurring production issue, or
the team identifies a set of authoritative documents that must be answered only from approved
sources

This seed should be presented during `$gsd-new-milestone` when the milestone scope matches any of
these conditions:

- production or dogfooding shows repeated wrong answers about entities, business rules, or system
  behavior even though those facts exist in internal documentation
- the team decides some documents must become authoritative and fail closed instead of being treated
  like ordinary RAG material
- operators need a cleaner split between exact domain documentation and general knowledge-base
  content
- file-oriented or structured tasks such as `xlsx` import, validation, or analysis need to route to
  a dedicated workflow instead of going through retrieval first
- future work starts around enterprise memory, knowledge governance, routing, or specialist
  toolchains

## Scope Estimate

**Large** — this is more than a narrow patch:

- define which docs are authoritative and how they are stored in the repo or filesystem
- add a deterministic lookup layer for strict docs using metadata / ids / aliases / headings, not
  embeddings
- add controlled read tools so the agent can resolve and read approved files or sections without
  guessing paths
- enforce fail-closed behavior when no authoritative source is found
- keep the existing vector store path for fuzzy reference material
- add routing rules so structured tasks like `xlsx` processing go to a dedicated workflow instead of
  the generic retrieval path
- decide whether any authoritative docs are written by hand only or partially generated from code
  and then reviewed

The first cut should stay narrow: authoritative markdown, deterministic lookup, fail-closed answer
policy, and one example of specialist routing. Do not try to redesign all memory or all ingestion
at once.

## Breadcrumbs

Related code and decisions found in the current codebase:

- [.planning/PROJECT.md](D:/DTH/ai-agent-core/.planning/PROJECT.md)
  Already states that structured data should stay on the `DataManager` path and explicitly rejects
  vector-store-as-source-of-truth for host entity records.
- [.planning/research/PITFALLS.md](D:/DTH/ai-agent-core/.planning/research/PITFALLS.md)
  Contains the exact architectural warning about vector store drift versus the real source of truth.
  This seed extends that reasoning from entity records to authoritative documentation.
- [.planning/ROADMAP.md](D:/DTH/ai-agent-core/.planning/ROADMAP.md)
  Phase 5 established the current RAG layer and `CustomIngester` path. This seed is a deliberate
  split, not a rejection of that work.
- [.planning/research/STACK.md](D:/DTH/ai-agent-core/.planning/research/STACK.md)
  Notes Apache Tika support for formats such as `docx` and `xlsx`, which is relevant when deciding
  whether spreadsheet handling belongs in generic ingestion or in a dedicated workflow.
- [.planning/seeds/SEED-001-reviewed-learning-loop-for-agent-failures-evaluation-cases-and-routing-rules.md](D:/DTH/ai-agent-core/.planning/seeds/SEED-001-reviewed-learning-loop-for-agent-failures-evaluation-cases-and-routing-rules.md)
  Adjacent seed: repeated routing or retrieval mistakes are one of the clearest triggers for this
  stricter architecture.
- [DefaultChatServiceImpl.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java)
  Current orchestration path where retrieval is wired per request. Any strict-document path or
  specialist routing should integrate here instead of creating a parallel chat stack.
- [RetrievalAugmentationAdvisorFactory.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/rag/advisor/RetrievalAugmentationAdvisorFactory.java)
  Current fuzzy retrieval surface. This is exactly the path that should remain for reference
  material while authoritative docs move to a stricter lookup/read flow.
- [KnowledgeDocumentUploadService.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java)
  Current admin-upload entry point into the vector-store-backed KB. Useful baseline when deciding
  whether authoritative markdown belongs in the same pipeline or in a separate approved-doc store.
- [CustomIngester.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/spi/CustomIngester.java)
  Existing extension seam. A future specialist workflow for `xlsx` may reuse the spirit of this SPI
  while avoiding generic embedding-first ingestion.
- [ClasspathMarkdownIngester.java](D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/rag/ClasspathMarkdownIngester.java)
  Existing markdown ingestion sample. Good reference point for what to keep in fuzzy RAG versus what
  should become authoritative file-backed memory.

## Notes

- The key design boundary is trust level, not file type. A markdown file can be either:
  - authoritative source material that must be read directly and cited exactly
  - fuzzy reference material that is safe to chunk and embed
- The agent should not search the filesystem freely. It should use deterministic lookup tools over
  an approved registry of authoritative documents.
- "Fail closed" here means: if no authoritative source is found, the assistant says it lacks enough
  approved documentation instead of filling the gap from model knowledge or vector retrieval.
- `xlsx` is a strong example of why this split matters. Spreadsheet work is often procedural and
  schema-sensitive; it usually wants routing to a dedicated toolchain, not semantic retrieval over
  embedded chunks.
- Keep naming plain when this seed is eventually implemented. The architecture is important; fancy
  subsystem names are not.
