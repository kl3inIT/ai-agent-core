---
phase: 11-mutation-capable-built-in-tools
plan: 07A
status: complete
completed: 2026-04-29
commits:
  - 7eebedc
  - 1021177
---

# Plan 11-07A Summary — Mutation Core Collaborators + create_record/update_record

## What shipped

**Task 0 (verification, recorded inline below):** Verified Jmix 2.8 metadata + EntityValues APIs against `/jmix-framework/jmix-context7` and local sources before coding the validators.

**Task 1 (commit `7eebedc`):** Six collaborator @Components + DiffSerializer.

**Task 2 (commit `1021177`):** `BuiltInMutationTools` @Component @ConditionalOnProperty with `create_record` + `update_record` only.

## Files

Created (7):
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/DiffSerializer.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationRequestHasher.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationSaveExecutor.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationAuthorizationService.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationAttributeBinder.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationCommitCoordinator.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java`

## Verified APIs (Task 0)

- `MetaClass.findProperty(String)` returns `null` for unknown attributes (used as the gate before any `EntityAttributeContext` instantiation)
- `MetadataTools.isEmbedded`, `isAdditional`, primary-key + version detection via `MetaProperty.AnnotatedElement` access (`@Id`, `@Version`, `@JmixGeneratedValue`)
- `MetaProperty.getRange()` cardinality discriminators: `Range.Cardinality.ONE_TO_ONE` / `MANY_TO_ONE` for to-one; `*_TO_MANY` for collection (rejected)
- `EntityValues.setValue(entity, attribute, value)` for typed scalars + loaded entity references
- `RoleGrantedAuthorityUtils.createResourceRoleGrantedAuthority(AiAgentMutationRole.CODE).getAuthority()` returns the canonical authority string used for exact `String.equals` comparison — substring/contains explicitly forbidden

## Acceptance criteria — all met

- `BuiltInMutationTools` is `@Component @ConditionalOnProperty(prefix="ai-agent.tools.mutation", name="enabled", havingValue="true")`
- Only `create_record` and `update_record` declared; no `delete_record` under any flag combination
- Both tools enforce exact `AiAgentMutationRole.CODE` authority before resolver / reservation / save
- `MutationSaveExecutor` is the only `@Transactional` save boundary
- `MutationAttributeBinder` rejects unknown / pk / version / audit / read-only / transient / collection attributes before `EntityValues.setValue`
- To-one assignment loads target via regular `DataManager`; rejects missing target rows as `not_found`
- `MutationIntent.attributes()` is post-coercion + immutable (Plan 11-03 contract)
- Audit failures (in `safeWriteAudit`) cannot recursively re-enter the catch ladder
- Commit-state transitions follow the contract: `NO_HOST_WRITE → FAILED`, `HOST_SAVE_RETURNED → COMMIT_UNKNOWN`, `INTENT_COMMITTED` never downgraded

## Verification

`./gradlew :ai-agent:compileJava` → BUILD SUCCESSFUL.

JetBrains MCP not available in this environment; modified Java files require IntelliJ triage in a future session.

## Notes

- Stream timeout interrupted the executor before SUMMARY landed; finalized inline by orchestrator after verifying compile.
- Plan 11-07B (related-write tools `add_related_record` + `remove_related_record`) is unblocked.
