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
must_haves:
  truths:
    - "BuiltInMutationTools is a @Component gated by @ConditionalOnProperty(prefix=\"ai-agent.tools.mutation\", name=\"enabled\", havingValue=\"true\") and remains absent by default."
    - "11-07A exposes only create_record and update_record initially, with the exact D-01 signatures and mandatory idempotencyKey; delete_record must not exist."
    - "Both tools enforce AiAgentMutationRole.CODE before entity resolution, idempotency reservation, MutationGuard, or MutationSaveExecutor, using CurrentAuthentication authority strings verified by RoleGrantedAuthorityUtils-created test users."
    - "Task 0 is mandatory: verify actual Jmix 2.8/project metadata APIs for scalar/to-one mutation before implementing mass-assignment validation, then record findings and sources in 11-07A-SUMMARY.md."
    - "create_record uses ToolEntityResolver.resolveCreatableEntityOrThrow; update_record uses resolveUpdatableEntityOrThrow. Create visibility is intentionally conservative: the entity must be LLM-visible and create-permitted, not write-only hidden."
    - "Every expected ToolUserError path flows through MutationErrorTranslator before ToolResultFormatter.error; malformed ids and converter failures normalize to parameter_conversion_error."
    - "Idempotency reservation happens before any host save and only RESERVED may proceed. REPLAY/VIOLATION/PENDING short-circuit without host mutation."
    - "Attribute mutation checks metaClass.findProperty(attributeName) before EntityAttributeContext, then rejects unknown attributes, primary key, version, audit/system fields, read-only fields, non-JPA/transient/calculated fields, and collection relationships before EntityValues.setValue."
    - "To-one relationship attributes accept UUID strings only, load the referenced entity through regular DataManager, enforce target LLM read exposure plus Jmix read permission, then assign the loaded entity instance."
    - "MutationGuard receives post-coercion typed immutable attributes, not raw LLM strings."
    - "MutationSaveExecutor is the only @Transactional save boundary; BuiltInMutationTools itself contains no @Transactional private/self-invoked save."
    - "Commit-state transitions follow the 11-07 reference: NO_HOST_WRITE may mark FAILED; HOST_SAVE_RETURNED may mark COMMIT_UNKNOWN/COMMIT_FAILED; INTENT_COMMITTED is never downgraded."
    - "All mutation audits call safeWriteAudit with full hashed tool arguments; audit failures are logged and never recursively enter the tool catch ladder."
---

<objective>
Implement the create/update mutation core only. This splits the former 11-07 monolith so the executor first lands reusable serialization, request hashing, save boundary, and the two scalar/to-one tools before related-write metadata is introduced.
</objective>

<tasks>
<task type="auto" tdd="false">
  <name>Task 0: Verify scalar/to-one metadata APIs before coding validators</name>
  <action>
Before implementing `validateWritableProperty`, `coerceAttributeValue`, or to-one assignment, inspect actual Jmix 2.8 APIs and local source/Javadocs for:
- `MetaClass.findProperty(...)` behavior for unknown attributes
- primary key lookup via `MetadataTools`
- version/audit/system field detection
- read-only/transient/non-JPA/calculated property detection
- `MetaProperty.getRange()` and cardinality helpers for scalar, to-one, and collection properties
- `EntityValues.setValue(...)` behavior for typed scalar values, enum-backed fields, and loaded entity references
- annotated-element/property annotation access needed by the validator

Record the exact source of each fact in `11-07A-SUMMARY.md`. Use Context7 `/jmix-framework/jmix-context7`, local Jmix source/Javadocs, and existing project usages; do not guess method names from memory. If an API differs from the 11-07 reference snippet, stop and repair this plan before coding.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:compileJava</automated>
  </verify>
</task>

<task type="auto" tdd="false">
  <name>Task 1: DiffSerializer, MutationRequestHasher, and MutationSaveExecutor</name>
  <action>
1. Create `DiffSerializer` with PII hashing via `AiAgentAuditProperties.resolvedSensitiveFields()`. Null sensitive values must hash safely instead of throwing.
2. Extract request hashing into package-private `MutationRequestHasher`; both tools and tests use this class directly. Canonical JSON sorts map keys, preserves value types, and hashes the raw LLM call shape before type coercion.
3. Create `MutationSaveExecutor` with public `@Transactional save(Object)` and `saveAll(Object...)`; no remove method in v1.1.
4. Keep `@Transactional` off `BuiltInMutationTools`; the tool bean must cross the Spring proxy by calling `MutationSaveExecutor`.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:compileJava</automated>
  </verify>
</task>

<task type="auto" tdd="false">
  <name>Task 2: BuiltInMutationTools create_record and update_record</name>
  <action>
1. Create conditional `BuiltInMutationTools` with only `create_record` and `update_record` initially.
2. Use the full fail-closed chain from 11-07: `enforceMutationRole` (`AiAgentMutationRole.CODE`) first, then resolver, AccessManager CRUD/attribute checks, pre-host-save reservation, coercion/mass-assignment validation, `MutationGuard`, `MutationSaveExecutor`, commit-state finalization, `safeWriteAudit`.
3. `update_record` does not accept `expectedVersion` in v1.1 and must not claim stale-read compare-and-swap semantics. It maps only optimistic-lock conflicts detected during its own save.
4. All audit calls go through non-throwing `safeWriteAudit`; expected in-method failures return structured JSON and do not throw through the callback boundary.
5. Implement create/update to-one relationship assignment as UUID-to-loaded-entity assignment, not raw UUID assignment.
6. Use the 11-07 reference as a binding contract for method descriptions, result schema, request-hash inputs, reservation handling, and commit-state finalization.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:compileJava</automated>
  </verify>
</task>
</tasks>

<success_criteria>
- `11-07A-SUMMARY.md` records the verified Jmix metadata/EntityValues APIs used by scalar and to-one mutation validators.
- `create_record` and `update_record` compile and expose stable error JSON for expected failures.
- Users without `AiAgentMutationRole.CODE` receive `access_denied` before idempotency reservation or host save; users still need normal Jmix create/update policies after the marker passes.
- `MutationRequestHasher` is production code, not a private method hidden from tests.
- Audit failure cannot recursively enter the mutation catch ladder.
- Mass-assignment validation rejects unknown attributes, primary key, `version`, audit/system fields, read-only fields, non-JPA/transient/calculated fields, and collection-valued relationships before `EntityValues.setValue`.
- Unknown attributes are rejected by `metaClass.findProperty(...) == null` before any `EntityAttributeContext` is constructed for that attribute.
- `MutationIntent.attributes()` receives post-coercion typed values and is immutable per Plan 11-03.
- `MutationIntent.attributes()` preserves null values so guards can see optional-field clears.
- To-one relationship attribute assignment loads the target through regular `DataManager`, checks target LLM read exposure plus Jmix read permission, and rejects missing target rows as `not_found`.
- Commit-state handling follows the reference contract: `NO_HOST_WRITE` can mark `FAILED`, `HOST_SAVE_RETURNED` can mark `COMMIT_UNKNOWN`, and `INTENT_COMMITTED` is never downgraded.
</success_criteria>
