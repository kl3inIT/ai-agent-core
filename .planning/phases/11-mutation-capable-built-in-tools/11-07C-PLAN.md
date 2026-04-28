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
</success_criteria>
