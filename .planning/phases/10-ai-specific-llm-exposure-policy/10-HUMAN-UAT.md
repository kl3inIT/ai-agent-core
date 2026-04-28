---
status: partial
phase: 10-ai-specific-llm-exposure-policy
source: [10-VERIFICATION.md]
started: 2026-04-28T02:47:51Z
updated: 2026-04-28T05:53:22Z
---

## Current Test

number: 3
name: REVIEW WARNING-08 — KB upload form passes Collections.emptyList() for allowedRoles
expected: |
  CONTEXT D-07 specified collecting `allowedRoles` from the upload form before ingestion, but plan 10-08 stopped at the service signature. Functional UX gap — admins will hit "every uploaded doc is admin-only by default" on first use.
awaiting: user response

## Tests

### 1. REVIEW BLOCKER-01 — AsyncIngestionWorker.enrich() NPE on null role element
expected: `parseAllowedRoles` may produce a list containing nulls (e.g. `["admin", null]` from malformed JSON). `List.copyOf(allowedRoles)` at AsyncIngestionWorker.java:282 throws NPE on any null element, failing the ingest. Decide whether to fix now or defer to gap-closure.
result: pass

### 2. REVIEW BLOCKER-02 — Liquibase index name vs JPA `@Index` name mismatch on AiExposureRule
expected: 060-ai-exposure-rule.xml creates `UNQ_AI_EXPOSURE_RULE_ENTITY_NAME` via `<addUniqueConstraint>`; entity declares `@Index(name = "IDX_AI_EXPOSURE_RULE_ENTITY_NAME", unique=true)`. Tests pass on hsqldb (no validation), but JPA schema validation would clash. Decide whether to fix now or defer.
result: pass

### 3. REVIEW WARNING-08 — KB upload form passes Collections.emptyList() for allowedRoles
expected: CONTEXT D-07 specified collecting `allowedRoles` from the upload form before ingestion, but plan 10-08 stopped at the service signature. Functional UX gap — admins will hit "every uploaded doc is admin-only by default" on first use.
result: [pending]

### 4. REVIEW WARNING-01 — partial-failure window in updatePermissionsAndReingest
expected: If reingest fails after permission save commits, stale chunks remain visible with new permissions until manually reingested. Edge-case RAG leak. Confirm acceptable risk or schedule mitigation.
result: pass

### 5. Visual verification of AiExposureRuleListView + AiExposureRuleDetailView admin workflow
expected: Admin can list, filter (genericFilter+propertyFilter), create, edit, toggle, and delete exposure rules. Toggle action flips `enabled` and persists. Detail view enforces required fields and unique entityName constraint. Both EN and VI message bundles render correctly.
result: [pending]

### 6. Visual verification of VectorStoreDebugView pagination + FilterExpressionTextParser UX
expected: Admin-only access enforced. Empty-string similaritySearch returns chunk preview. Metadata filter input parses via FilterExpressionTextParser; invalid syntax shows readable error. Read-only — no row mutation actions exposed.
result: [pending]

## Summary

total: 6
passed: 3
issues: 0
pending: 3
skipped: 0
blocked: 0

## Gaps
