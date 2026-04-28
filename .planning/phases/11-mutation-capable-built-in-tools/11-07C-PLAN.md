---
phase: 11-mutation-capable-built-in-tools
plan: 07C
type: execute
wave: 7
depends_on:
  - 11-07B-PLAN.md
files_modified:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
autonomous: true
requirements:
  - MUT-04
  - MUT-08
  - AUD-06
  - AUD-07
must_haves:
  truths:
    - "11-07C is the final hardening pass over all four mutation tools, not a cosmetic cleanup."
    - "After 11-07C, BuiltInMutationTools exposes exactly four mutation tools and no delete_record."
    - "Every success/failure/replay path writes audit only through safeWriteAudit; auditWriter.writeToolCall must appear nowhere else in BuiltInMutationTools."
    - "safeWriteAudit catches/logs RuntimeException, never changes MutationCommitState, and never causes expected in-method failures to throw through MutationToolCallbackBoundaryDecorator."
    - "Replay is live: use resultEntityName/resultEntityId with FetchPlan.INSTANCE_NAME and current security/locale; do not store or replay full result JSON."
    - "COMMIT_FAILED means commit outcome unknown after host save returned, not a known database rollback. Locale captions must not say database commit failed."
    - "Known save-time rollback failures map to stable error JSON and ERROR audit; post-host-save finalization failures use COMMIT_FAILED and leave the intent non-reclaimable."
    - "INTENT_COMMITTED later audit/result failures never downgrade the idempotency row; exact retry returns IDEMPOTENT_REPLAY."
    - "Success, error, blocked, replay, and commit-failed audits include full hashed tool arguments, not only the attributes map."
---

<objective>
Harden the final mutation boundary: commit-state handling, idempotent replay fetch plan, non-recursive audit failure behavior, locale captions, and grep-level invariants.
</objective>

<tasks>
<task type="auto" tdd="false">
  <name>Task 1: Commit-state and replay hardening</name>
  <action>
1. Ensure all four tools use `MutationCommitState.NO_HOST_WRITE`, `HOST_SAVE_RETURNED`, and `INTENT_COMMITTED`.
2. Only `HOST_SAVE_RETURNED` failures may mark `COMMIT_UNKNOWN` and audit `COMMIT_FAILED`.
3. `INTENT_COMMITTED` failures must never downgrade the idempotency row; exact retry must replay.
4. Replay loads use `FetchPlan.INSTANCE_NAME`.
5. Preserve the 11-07 reference finalization order: host save/saveAll returns, markCommitted succeeds, success audit is attempted, success result is returned.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:compileJava</automated>
  </verify>
</task>

<task type="auto" tdd="false">
  <name>Task 2: Non-throwing audit and captions</name>
  <action>
1. All mutation audit writes call `safeWriteAudit`, which catches/logs `RuntimeException` and never re-enters the tool catch ladder.
2. No expected mutation/audit/idempotency failure should throw through `MutationToolCallbackBoundaryDecorator`.
3. Use "Commit outcome unknown" captions in both locales.
4. Add grep-level checks or tests proving `auditWriter.writeToolCall` is only called inside `safeWriteAudit` and `COMMIT_FAILED` captions do not claim the database commit failed.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:compileJava</automated>
  </verify>
</task>
</tasks>

<success_criteria>
- `auditWriter.writeToolCall` appears only inside `safeWriteAudit`.
- `COMMIT_FAILED` captions do not say "database commit failed".
- Boundary-decorator tests can rely on mutation tools not throwing for expected in-method failures.
- Replay loads use `FetchPlan.INSTANCE_NAME` and returns fresh `instanceName` from `resultEntityId/resultEntityName` without storing full result JSON.
- Success/failure audit arguments include the full tool argument envelope with sensitive values hashed.
- `safeWriteAudit` catches/logs `RuntimeException` and must not alter `MutationCommitState`.
</success_criteria>
