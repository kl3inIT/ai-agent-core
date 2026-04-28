---
phase: 11-mutation-capable-built-in-tools
plan: 07A
type: execute
wave: 7
depends_on:
  - 11-06-PLAN.md
files_modified:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/DiffSerializer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationRequestHasher.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationSaveExecutor.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java
autonomous: true
requirements:
  - MUT-01
  - MUT-02
  - MUT-03
  - MUT-04
  - MUT-07
  - MUT-08
  - AUD-07
---

<objective>
Implement the create/update mutation core only. This splits the former 11-07 monolith so the executor first lands reusable serialization, request hashing, save boundary, and the two scalar/to-one tools before related-write metadata is introduced.
</objective>

<tasks>
<task type="auto" tdd="false">
  <name>Task 1: DiffSerializer, MutationRequestHasher, and MutationSaveExecutor</name>
  <action>
1. Create `DiffSerializer` with PII hashing via `AiAgentAuditProperties.resolvedSensitiveFields()`. Null sensitive values must hash safely instead of throwing.
2. Extract request hashing into package-private `MutationRequestHasher`; both tools and tests use this class directly. Canonical JSON sorts map keys and preserves value types.
3. Create `MutationSaveExecutor` with public `@Transactional save(Object)` and `saveAll(Object...)`; no remove method in v1.1.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:compileJava</automated>
  </verify>
</task>

<task type="auto" tdd="false">
  <name>Task 2: BuiltInMutationTools create_record and update_record</name>
  <action>
1. Create conditional `BuiltInMutationTools` with only `create_record` and `update_record` initially.
2. Use the full fail-closed chain from 11-07: resolver, AccessManager CRUD/attribute checks, pre-host-save reservation, coercion/mass-assignment validation, `MutationGuard`, `MutationSaveExecutor`, commit-state finalization, `safeWriteAudit`.
3. `update_record` does not accept `expectedVersion` in v1.1 and must not claim stale-read compare-and-swap semantics. It maps only optimistic-lock conflicts detected during its own save.
4. All audit calls go through non-throwing `safeWriteAudit`; expected in-method failures return structured JSON and do not throw through the callback boundary.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:compileJava</automated>
  </verify>
</task>
</tasks>

<success_criteria>
- `create_record` and `update_record` compile and expose stable error JSON for expected failures.
- `MutationRequestHasher` is production code, not a private method hidden from tests.
- Audit failure cannot recursively enter the mutation catch ladder.
</success_criteria>
