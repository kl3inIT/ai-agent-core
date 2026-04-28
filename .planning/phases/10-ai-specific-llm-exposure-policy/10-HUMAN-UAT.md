---
status: complete
phase: 10-ai-specific-llm-exposure-policy
source: [10-VERIFICATION.md]
started: 2026-04-28T02:47:51Z
updated: 2026-04-28T09:45:00Z
---

## Current Test

number: 5
name: Visual verification of AiConfigurationView exposure-rule workflow
expected: |
  Admin can open AI > AI configuration/Cấu hình AI, switch to Control rules/Quy tắc kiểm soát, select one or more entities in the chip-style multiSelectComboBox, save, refresh, and see the same selected entities remain hidden from AI. Unselecting an entity and saving makes that entity visible to AI again. The Parameters and Outgoing context tabs remain available from the same view, EN/VI message bundles render correctly, and the Outgoing context must not include AI internal entities or sensitive fields such as User.password.
result: pass

## Tests

### 1. REVIEW BLOCKER-01 — AsyncIngestionWorker.enrich() NPE on null role element
expected: `parseAllowedRoles` may produce a list containing nulls (e.g. `["admin", null]` from malformed JSON). `List.copyOf(allowedRoles)` at AsyncIngestionWorker.java:282 throws NPE on any null element, failing the ingest. Decide whether to fix now or defer to gap-closure.
result: pass

### 2. REVIEW BLOCKER-02 — Liquibase index name vs JPA `@Index` name mismatch on AiExposureRule
expected: 060-ai-exposure-rule.xml creates `UNQ_AI_EXPOSURE_RULE_ENTITY_NAME` via `<addUniqueConstraint>`; entity declares `@Index(name = "IDX_AI_EXPOSURE_RULE_ENTITY_NAME", unique=true)`. Tests pass on hsqldb (no validation), but JPA schema validation would clash. Decide whether to fix now or defer.
result: pass

### 3. REVIEW WARNING-08 — KB upload form passes Collections.emptyList() for allowedRoles
expected: CONTEXT D-07 specified collecting `allowedRoles` from the upload form before ingestion, but plan 10-08 stopped at the service signature. Functional UX gap — admins will hit "every uploaded doc is admin-only by default" on first use.
result: pass

### 4. REVIEW WARNING-01 — partial-failure window in updatePermissionsAndReingest
expected: If reingest fails after permission save commits, stale chunks remain visible with new permissions until manually reingested. Edge-case RAG leak. Confirm acceptable risk or schedule mitigation.
result: pass

### 5. Visual verification of AiConfigurationView exposure-rule workflow
expected: Admin can open AI > AI configuration/Cấu hình AI, switch to Control rules/Quy tắc kiểm soát, select one or more entities in the chip-style multiSelectComboBox, save, refresh, and see the same selected entities remain hidden from AI. Unselecting an entity and saving makes that entity visible to AI again. The Parameters and Outgoing context tabs remain available from the same view, EN/VI message bundles render correctly, and the Outgoing context must not include AI internal entities or sensitive fields such as User.password.
result: pass

### 6. Visual verification of VectorStoreDebugView pagination + FilterExpressionTextParser UX
expected: Admin-only access enforced. Empty-string similaritySearch returns chunk preview. Metadata filter input parses via FilterExpressionTextParser; invalid syntax shows readable error. Read-only — no row mutation actions exposed.
result: skipped
reason: Removed by product decision; Knowledge Document Detail view now replaces the needed vector chunk inspection workflow.

## Summary

total: 6
passed: 5
issues: 0
pending: 0
skipped: 1
blocked: 0

## Gaps
