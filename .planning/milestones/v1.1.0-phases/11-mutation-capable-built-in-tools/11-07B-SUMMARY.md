---
phase: 11-mutation-capable-built-in-tools
plan: 07B
status: complete
completed: 2026-04-28
commits:
  - 2439156
  - 388fe7e
  - 2ba6cd6
  - 8edfa14
requirements:
  - MUT-02
  - MUT-03
  - MUT-07
  - MUT-08
---

# Plan 11-07B Summary — Related-Write Metadata Resolver + add/remove_related_record

## What shipped

**Task 0 — Fixture entities + main-store changelog (commit `2439156`):**
Five `@JmixEntity` test fixtures registered in `AITestConfiguration` so all later mutation
plans (11-10, 11-11) reuse them read-only:
- `MutationTestFixture` — scalar create/update with sensitive `secret` field + `priority` int
- `MutationParentFixture` / `MutationChildFixture` — `@Composition` + `orphanRemoval=true`
  (rejected by resolver)
- `MutationLinkedParentFixture` / `MutationLinkedChildFixture` — non-composition
  `@OneToMany(mappedBy="linkedParent")` + child `@ManyToOne` inverse (supported shape)

Main-store Liquibase changelog `010-mutation-test-fixture.xml` creates all five host tables
+ both parent-child foreign keys, included from `test-main-changelog.xml`.

**Task 1 — RelatedWriteMetadataResolver helpers (commit `388fe7e`):**
Single relationship-metadata authority used by `BuiltInMutationTools`:
- `resolveSupportedRelatedWriteRelationship(parentMetaClass, relationshipName)` →
  `SupportedRelatedRelationship(parentProperty, childMetaClass, childInverseProperty)` or
  `validation_failed`
- `isCompositionOrDeleteCapable` — defense-in-depth on parent property
- `wireInverseReference` / `clearInverseReference` — mutate the child-side inverse only;
  never call `setValue` on parent collection
- `ensureInverseClearable` — pre-save remove-time required-inverse check
- `childBelongsToParent` — read-only inverse-id check for `remove_related_record`
  not-found surfacing

**Task 2 — Helper tests (commit `2ba6cd6`):**
`RelatedWriteMetadataResolverTest` proves the support matrix on real Jmix metadata:
- supported non-composition `@OneToMany(mappedBy)` accepted
- composition + orphanRemoval rejected
- required inverse rejected for `ensureInverseClearable`
- `wireInverseReference` / `clearInverseReference` / `childBelongsToParent` operate on the
  child-side inverse without touching the parent collection

**Task 3 — add/remove_related_record (commit `8edfa14`):**
Two `@Tool` methods on `BuiltInMutationTools` with verbatim D-01 signatures
`(entityName, id, relationship, relatedId, idempotencyKey)`. Both delegate to the shared
`executeRelatedWrite(toolName, ..., isRemove)` orchestrator so add/remove share the gate
sequence and audit/error wiring.

## Files

Created (1):
- `.planning/phases/11-mutation-capable-built-in-tools/11-07B-SUMMARY.md` (this file)

Modified (2 in Task 3):
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java`
  — added `addRelatedRecord`, `removeRelatedRecord`, and private `executeRelatedWrite`;
  added `RelatedWriteMetadataResolver` constructor dependency; added `FetchPlan` import
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/DiffSerializer.java`
  — added `serializeRelatedArgumentsJson` + `serializeRelatedActionSummary`

Created in Tasks 0–2 (already covered by their commits but recorded for completeness):
- `RelatedWriteMetadataResolver.java` (Task 1)
- `RelatedWriteMetadataResolverTest.java` (Task 2)
- 5 fixture classes + 2 changelogs + `AITestConfiguration` registration (Task 0)

## Verified Jmix 2.8 / JPA APIs (Task 0 evidence)

The resolver's correctness hangs on a concrete metadata-API mapping. Each entry below
was verified against `/jmix-framework/jmix-context7` (Context7) or local Jmix 2.8 source
before resolver code was written.

| Resolver decision | Verified API/annotation | Source file/doc | Fallback/fail-closed behavior |
| --- | --- | --- | --- |
| Unknown relationship name | `MetaClass.findProperty(String)` returns `null` | Jmix 2.8 javadoc + `MetadataLoader.assignPropertyType` line 711+ | `validation_failed` (canned message) |
| Is relationship | `MetaProperty.getRange().isClass() == true` | `Range` javadoc | `validation_failed` |
| Is collection | `Range.getCardinality().isMany() == true` | `Range.Cardinality` enum (`ONE_TO_MANY`/`MANY_TO_MANY`) | reject parent-side to-one |
| Composition rejection | `MetaProperty.getType() == Type.COMPOSITION` | `MetaModelLoader.assignPropertyType`, Jmix 2.8.0 line 958 (set when `@Composition` is present) | reject before save |
| `orphanRemoval` rejection | `OneToMany.orphanRemoval()` via `MetaProperty.getAnnotatedElement().getAnnotation(OneToMany.class)` | JPA spec + `MetaProperty.getAnnotatedElement` Jmix javadoc | reject before save |
| Cascade delete rejection | `OneToMany.cascade()` containing `CascadeType.REMOVE`/`ALL` | JPA spec | reject before save |
| Mapped-by ownership | `OneToMany.mappedBy()` non-blank | JPA spec | reject unidirectional |
| Inverse pointer | `MetaProperty.getInverse()` (returns inverse `MetaProperty` or `null`) | `MetaProperty.getInverse` Jmix javadoc; resolver via `MetaModelLoader` mappedBy resolution | `validation_failed` when null (ambiguous) |
| Inverse single-valued | `inverse.getRange().getCardinality().isMany() == false` | `Range.Cardinality` | reject collection inverses (M2M shape) |
| Inverse is to-one annotation | `@ManyToOne` or `@OneToOne` on the inverse `getAnnotatedElement()` | JPA spec | reject anything else |
| Required-inverse detection | `MetaProperty.isMandatory()` OR `@ManyToOne(optional=false)` OR `@OneToOne(optional=false)` OR `@JoinColumn(nullable=false)` | JPA spec + `MetaProperty.isMandatory` Jmix javadoc | reject `remove_related_record` (cannot clear NOT NULL FK without delete) |
| Mutate child-side inverse | `EntityValues.setValue(child, inverse.getName(), parent\|null)` | `EntityValues` javadoc | never write to parent collection |
| Child-belongs-to-parent | `EntityValues.getValue(child, inverse.getName())` + `EntityValues.getId(...)` equality | `EntityValues` javadoc | non-member surfaces as `not_found` |
| Children-free parent load | `dataManager.load(...).fetchPlan(FetchPlan.BASE).optional()` (constant `"_base"`) | `FetchPlan` javadoc + `FetchPlanResolver` in-repo | required to avoid Pitfall #1 (children re-saved with bumped `@Version`) |

## Tool gate sequence (add_related_record / remove_related_record)

The shared `executeRelatedWrite` enforces, in order, before any host save:

1. `AiAgentMutationRole.CODE` marker authority — exact equality (SEC-07).
2. Parent entity update-side resolution — `unknown_entity` opacity preserved; visible-but-denied → `access_denied`.
3. Parent id parse — `parameter_conversion_error` vs eventual `not_found`.
4. Parent CRUD `update` + parent relationship attribute `canModify`.
5. `RelatedWriteMetadataResolver.resolveSupportedRelatedWriteRelationship` — narrow v1.1 support matrix.
6. Child LLM read+modify exposure (`enforceLlmRelationshipTargetExposure(target, true)`).
7. Jmix child read + update + child-side inverse attribute `canModify`.
8. Child id parse — `parameter_conversion_error` vs `not_found`.
9. Remove only: `ensureInverseClearable` — `validation_failed` when inverse is required.
10. `MutationRequestHasher.hash(toolName, entityName, id, relationship, relatedId, {})` — canonical raw call shape.
11. `MutationIntentRepository.reserveOrReplay` — non-RESERVED short-circuits via `MutationCommitCoordinator.handleReservationResult` (`IDEMPOTENT_REPLAY`, `idempotency_violation`, `concurrent_modification`).
12. Load parent with `FetchPlan.BASE` (Pitfall #1) — `not_found` if missing.
13. Load child — `not_found` if missing.
14. `MutationGuard.check` veto point — guard sees the loaded child as the typed value for the relationship attribute.
15. For remove: `childBelongsToParent` → if false, `not_found` (without save) so the LLM re-reads via `get_record`.
16. Mutate child-side inverse only: `wireInverseReference` (add) or `clearInverseReference` (remove); parent collection is never rewritten.
17. `MutationSaveExecutor.saveAll(parent, child)` — sole `@Transactional` save boundary, proxy-crossed.
18. `markCommitted(intent, parentId, parentMetaClass.getName())` — replay surfaces parent's `instanceName` live under current locale + security.
19. `safeWriteAudit` — never throws back into the catch ladder.

## Result envelopes

```json
add_related_record    -> {"outcome":"SUCCESS","parentId":"<uuid>","relationship":"items","relatedId":"<uuid>"}
remove_related_record -> {"outcome":"SUCCESS","parentId":"<uuid>","relationship":"items","relatedId":"<uuid>"}
```

`argumentsJson` (audit row, `serializeRelatedArgumentsJson`):

```json
{"entityName":"...","id":"<uuid>","relationship":"items","relatedId":"<uuid>","idempotencyKey":"<uuid>"}
```

`resultSummary` (audit row, `serializeRelatedActionSummary`):

```json
{"relationship":"items","action":"added"|"removed","relatedId":"<uuid>"}
```

## Acceptance criteria — all met

- `BuiltInMutationTools` exposes `add_related_record` and `remove_related_record` only via @Tool; `delete_record` is NOT shipped (D-07).
- Both tools delegate ALL relationship metadata to `RelatedWriteMetadataResolver`. `BuiltInMutationTools` reads no JPA annotations and walks no inverse pointer directly.
- Marker role enforced first (exact authority equality).
- Per-attribute `canModify` enforced on parent relationship + child inverse before reservation.
- Child read+modify exposure + Jmix read/update enforced before save.
- Children-free parent fetch plan (`FetchPlan.BASE`) — Pitfall #1 avoided.
- Related writes mutate ONLY the child-side inverse; parent collection is never rewritten and `delete`/`remove` is never called.
- `remove_related_record` rejects required-inverse (`@ManyToOne(optional=false)` / `@JoinColumn(nullable=false)` / `MetaProperty.isMandatory()`).
- `remove_related_record` returns `not_found` when child doesn't currently belong to parent (no silent no-op).
- Audit payloads carry the full call shape; never echo `relationship`/`relatedId`/`id` into result/error prose (P-22).
- IDEMPOTENT_REPLAY, idempotency_violation, concurrent_modification, not_found, parameter_conversion_error all surface via the existing `MutationCommitCoordinator` + `MutationErrorTranslator` chain.
- @Tool descriptions explicitly declare the v1.1 narrow scope (non-composition `@OneToMany(mappedBy)` + child-side `@ManyToOne`/`@OneToOne` inverse) and instruct the LLM not to attempt composition / many-to-many / unidirectional / required-inverse / orphanRemoval relationships.

## Verification

`./gradlew :ai-agent:compileJava` → BUILD SUCCESSFUL (Task 3 final compile).

Task 2 helper tests already passed in commit `2ba6cd6`.

## Deviations from plan

**None for Task 3.** Tasks 0–2 produced the exact deliverables the plan specified (fixtures, resolver, helper tests). Task 3 implemented both tools with the verbatim D-01 signatures and the full fail-closed chain mandated by the must-haves contract.

The Task 3 audit `resultSummary` carries `{relationship, action, relatedId}` — this matches the CONTEXT.md "argumentsJson payload format" guidance and keeps the audit row self-describing without echoing entity-id text into the result envelope.

## Notes

- JetBrains MCP is not available in this environment. Modified Java files require IntelliJ triage in a future session.
- Plan 11-07B is fully self-contained and does not depend on Plan 11-07C edge-case hardening.
- Plans 11-10 and 11-11 reuse the five fixture classes + main-store changelog read-only — they must not redefine or edit them per the must-haves contract.

## Self-Check: PASSED

- `BuiltInMutationTools.java` modified, contains `addRelatedRecord` + `removeRelatedRecord` + `executeRelatedWrite` (verified by post-commit grep).
- `DiffSerializer.java` modified, contains `serializeRelatedArgumentsJson` + `serializeRelatedActionSummary`.
- Commits `2439156`, `388fe7e`, `2ba6cd6`, `8edfa14` all present in `git log --oneline`.
- `./gradlew :ai-agent:compileJava` BUILD SUCCESSFUL on the final tip.
