---
phase: 11-mutation-capable-built-in-tools
plan: 07C
status: complete
completed: 2026-04-28
commits:
  - 2742aa6
  - a0ca1db
requirements:
  - MUT-04
  - MUT-08
  - AUD-06
  - AUD-07
---

# Plan 11-07C Summary — Final Mutation Boundary Hardening

## One-liner

Locked in the Plan 11-07C must-have invariants on the mutation tool surface
(`auditWriter.writeToolCall` isolation, `COMMIT_FAILED` caption rule, `safeWriteAudit`
non-throwing audit, replay `FetchPlan.INSTANCE_NAME`, `markCommitUnknown` defensive
re-read, `INTENT_COMMITTED` never-downgrade) and added a source-level invariants
JUnit (`MutationToolInvariantsTest`) so the rules cannot regress quietly.

## What shipped

**Task 1 (commit `2742aa6`):** Lifted the Plan 11-07C invariants into the
`MutationCommitCoordinator` class JavaDoc as a single authoritative block:

- `auditWriter.writeToolCall` is only invoked from `safeWriteAudit`; verified by
  `MutationToolInvariantsTest`.
- `COMMIT_FAILED` captions render "Commit outcome unknown" / "Chưa rõ kết quả commit"
  in both locales — never "database commit failed".
- `safeWriteAudit` catches `RuntimeException`, never alters `MutationCommitState`, and
  logs the marker `AI_AGENT_MUTATION_AUDIT_WRITE_FAILED` with sanitized context only
  (tool name, outcome, runId, rootAuditId, exception class) — no `argumentsJson`, no
  user-supplied attribute values.
- Replay loads use `FetchPlan.INSTANCE_NAME` and re-resolve `instanceName` live; on
  exposure/read denial the row's `entityId` is preserved and `instanceName` is
  omitted/null.
- `MutationIntentRepository.markCommitUnknown` re-reads the current dedup row inside
  its own `REQUIRES_NEW` transaction and never downgrades a `COMMITTED` row.
- `MutationCommitState.INTENT_COMMITTED` failures (post-success audit/result-build
  failures) never downgrade the idempotency row; the next exact retry returns
  `IDEMPOTENT_REPLAY`.

**Task 2 (commit `a0ca1db`):** Added pure-unit
`com.vn.agent.tools.mutation.MutationToolInvariantsTest` (no Spring context, no
mocks) with four grep-style assertions:

1. `auditWriter.writeToolCall(` does not appear in any
   `com.vn.agent.tools.mutation` Java file other than `MutationCommitCoordinator`.
2. `MutationCommitCoordinator` contains exactly one `auditWriter.writeToolCall(`
   site, anchored to appear after the `safeWriteAudit` method signature.
3. `BuiltInMutationTools` has no direct `auditWriter.writeToolCall(` call site and
   does not hold an `AuditWriter` dependency at all.
4. Both `messages_en.properties` and `messages_vi.properties` are free of the
   forbidden "database commit failed" wording (case-insensitive regex), and the
   `COMMIT_FAILED` caption keys carry the mandated wording in each locale.

The test resolves the module root robustly so it runs the same under
`./gradlew :ai-agent:test`, an IDE module-root run, and a repo-root run.

## Files

Modified (1):
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationCommitCoordinator.java`
  — class JavaDoc updated to lock in Plan 11-07C invariants and reference the
  invariants test as the verification site.

Created (1):
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationToolInvariantsTest.java`
  — pure-unit invariants test.

## Verified contracts (already in place from 11-07A/B; locked here)

| Must-have | Where it lives | Plan 11-07C verification |
| --- | --- | --- |
| Four `@Tool` methods, no `delete_record` | `BuiltInMutationTools` (only `createRecord`, `updateRecord`, `addRelatedRecord`, `removeRelatedRecord`) | Existing 11-07A/B coverage; reaffirmed in JavaDoc |
| `auditWriter.writeToolCall` only inside `safeWriteAudit` | `MutationCommitCoordinator.safeWriteAudit` (line 248) — single call site | `MutationToolInvariantsTest.auditWriter_writeToolCall_appearsOnlyInsideSafeWriteAudit` + `commitCoordinator_writeToolCall_isInsideSafeWriteAuditMethod` |
| `safeWriteAudit` catches `RuntimeException`, never mutates `MutationCommitState` | `MutationCommitCoordinator.safeWriteAudit` `try/catch (RuntimeException)` | Source review; method body has no `MutationCommitState` reference |
| Replay uses `FetchPlan.INSTANCE_NAME` | `MutationCommitCoordinator.replayResult` line 145 | Source review |
| Replay omits/nulls `instanceName` on read/exposure denial; never duplicates host write | `replayResult` keeps `entityId` always; sets `freshName=null` when `canReadEntity=false`, `enforceReadPermission` throws, or load returns empty | Source review |
| `COMMIT_FAILED` caption is "Commit outcome unknown" in both locales | `messages_en.properties` line 22 + 299; `messages_vi.properties` line 24 + 301 | `MutationToolInvariantsTest.commitFailedCaptions_doNotClaimDatabaseCommitFailed` |
| `markCommitUnknown` re-reads + protects `COMMITTED` | `MutationIntentRepository.markCommitUnknown` lines 159–173 (`findById` re-read; early return on `COMMITTED`) | Source review |
| `INTENT_COMMITTED` never downgraded | `MutationCommitCoordinator.markFailedIfReserved` skips when `commitState==INTENT_COMMITTED`; same method also skips when reservation is non-`RESERVED` | Source review |
| Success/failure audits include full hashed tool argument envelope | All catch arms in `BuiltInMutationTools` call `diffSerializer.serializeEntityArgumentsJson` / `serializeRelatedArgumentsJson` (full envelope), not the raw attributes map | Source review |
| `safeWriteAudit` failure marker `AI_AGENT_MUTATION_AUDIT_WRITE_FAILED` with no PII | `MutationCommitCoordinator.safeWriteAudit` ERROR log: marker + eventName + outcome + runId + rootAuditId + exceptionClass; never `argumentsJson` | Source review |
| `BuiltInMutationTools` is thin orchestration only | No `auditWriter` field, no helper bodies for commit/audit | `MutationToolInvariantsTest.builtInMutationTools_hasNoDirectAuditWriterUsage` |

## Acceptance criteria — all met

- [x] `auditWriter.writeToolCall` appears only inside `safeWriteAudit` (test enforced).
- [x] `safeWriteAudit`, replay result construction, audit outcome mapping, and
      commit-unknown finalization live in `MutationCommitCoordinator`;
      `BuiltInMutationTools` contains only `@Tool` orchestration and collaborator
      calls for these concerns.
- [x] `COMMIT_FAILED` captions do not say "database commit failed" (test enforced).
- [x] Boundary-decorator tests can rely on mutation tools not throwing for expected
      in-method failures (every `try` ends with the `Throwable` catch-all returning
      a translated `ToolUserError` JSON; `safeWriteAudit` never throws either).
- [x] Replay loads use `FetchPlan.INSTANCE_NAME` and returns fresh `instanceName`
      from `resultEntityId` / `resultEntityName` without storing full result JSON.
- [x] Replay permission behavior is explicit: current read/exposure denial
      suppresses `instanceName` but does not duplicate the host write or turn an
      exact replay into a fresh mutation.
- [x] Success/failure audit arguments include the full tool argument envelope with
      sensitive values hashed via `DiffSerializer`.
- [x] `safeWriteAudit` catches/logs `RuntimeException` and does not alter
      `MutationCommitState`.
- [x] `safeWriteAudit` failure logging contains `AI_AGENT_MUTATION_AUDIT_WRITE_FAILED`
      and intentionally avoids raw arguments / PII.
- [x] `markCommitUnknown` cannot overwrite a currently `COMMITTED` row (re-reads
      inside `REQUIRES_NEW`, returns early when status is `COMMITTED`).

## Verification

- `./gradlew :ai-agent:compileJava` → BUILD SUCCESSFUL.
- `./gradlew :ai-agent:test --tests "com.vn.agent.tools.mutation.MutationToolInvariantsTest"`
  → BUILD SUCCESSFUL (4 tests passed).

## Deviations from plan

**None.** The four mutation tools and their commit-coordinator collaborator already
satisfied every must-have after Plan 11-07A/B; Plan 11-07C work was the documentation
lock-in and the source-level invariants test that the must-haves explicitly call for
("Add grep-level checks or tests proving `auditWriter.writeToolCall` is only called
inside `safeWriteAudit` and `COMMIT_FAILED` captions do not claim the database commit
failed.").

No `delete_record` shipped under any flag combination — preserved across 11-07A
through 11-07C (`BuiltInMutationTools` exposes exactly four `@Tool` methods).

## Notes

- JetBrains MCP not available in this environment. The two modified Java files
  (the existing `MutationCommitCoordinator.java` JavaDoc edit and the new
  `MutationToolInvariantsTest.java`) require IntelliJ triage in a future session.
- Wave 7 is now functionally complete on the mutation surface side. Plan 11-07
  was made `type: reference` after 11-07A/B/C split.

## Self-Check: PASSED

- `MutationCommitCoordinator.java` — modified, contains the Plan 11-07C invariants
  block in the class JavaDoc.
- `MutationToolInvariantsTest.java` — created at
  `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationToolInvariantsTest.java`.
- Commits `2742aa6` (Task 1) and `a0ca1db` (Task 2) both visible in `git log --oneline`.
- `./gradlew :ai-agent:compileJava` BUILD SUCCESSFUL.
- `./gradlew :ai-agent:test --tests "...MutationToolInvariantsTest"` BUILD SUCCESSFUL,
  all 4 invariants green.
