---
phase: 03-metadata-first-runtime-six-tools
plan: 01
subsystem: metadata
tags: [jmix, metadata, schema, access-manager, tool-01, tool-02]
requirements: [TOOL-01, TOOL-02]
dependency_graph:
  requires:
    - io.jmix.core.Metadata
    - io.jmix.core.MetadataTools
    - io.jmix.core.MessageTools
    - io.jmix.core.AccessManager
    - io.jmix.core.accesscontext.CrudEntityContext
    - io.jmix.core.accesscontext.EntityAttributeContext
  provides:
    - com.vn.agent.metadata.CurrentUserSchemaAccess  # thin adapter over Jmix Metadata + AccessManager + MessageTools (collapsed from the original six-file parallel layer post-execute per user feedback "Reuse Jmix built-ins over parallel layers")
  affects:
    - Plan 03-02 StructuredFilterConditionMapper (consumes canReadAttribute / canReadEntity)
    - Plan 03-03 BuiltInDataTools (consumes forCurrentUser / userEditableStringIndex / canReadEntity)  # method name normalized post-execute on CurrentUserSchemaAccess
tech_stack:
  added: []
  patterns:
    - "Jmix metamodel scan gated on ApplicationReadyEvent (NOT @PostConstruct) — Pitfall 1"
    - "Stateless per-request AccessManager filter — no class-level cache (T-03-01)"
    - "Constructor injection only (CLAUDE.md)"
key_files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java  # post-execute refactor: single adapter replacing the original 6-file parallel layer (AiSchema/AiEntityInfo/AiAttributeInfo/UserEditableStringIndex/MetamodelScanner/EffectiveSchemaComputer)
  modified: []
decisions:
  - "D-13 persistence check uses MetadataTools.isJpa(MetaProperty) — portable public API; plan suggested Field + @Column reflection fallback which is brittle."
  - "MetamodelScanner (collapsed post-execute into CurrentUserSchemaAccess) constructor-injects MetadataTools in addition to Metadata to keep the D-13 classification inside the public Jmix API surface."
metrics:
  duration_seconds: 264
  duration_human: "~4 minutes"
  tasks_completed: 3
  files_created: 6
  files_modified: 0
  completed: "2026-04-19"
---

# Phase 03 Plan 01: Metadata Core Summary

**One-liner:** Single request-scoped adapter `CurrentUserSchemaAccess` under `com.vn.agent.metadata` — exposes the metadata surface TOOL-01/TOOL-02 need (user-editable string index, AccessManager-filtered effective schema, MessageTools-resolved labels) as a thin adapter over Jmix `Metadata` + `MetadataTools` + `AccessManager` + `MessageTools`, with no parallel DTO layer.

**Post-execute refactor (2026-04-20):** Collapsed the six-file parallel layer (`AiSchema` / `AiEntityInfo` / `AiAttributeInfo` / `UserEditableStringIndex` / `MetamodelScanner` / `EffectiveSchemaComputer`) into the single adapter `CurrentUserSchemaAccess` per user feedback "Reuse Jmix built-ins over parallel layers"; no behavioural change — TOOL-01/TOOL-02 surface is identical.

## What Was Built

Current code shape (post-execute refactor, 2026-04-20): a single `@Component` adapter `CurrentUserSchemaAccess` constructor-injecting `Metadata`, `MetadataTools`, `AccessManager`, `MessageTools`. It exposes the same TOOL-01 / TOOL-02 surface that the original plan split across six files — walked on demand (or gated by `ApplicationReadyEvent` where inventory-style calls require it, per Pitfall 1), with AccessManager filtering + MessageTools localization rerun on every request (T-03-01: never cached).

Task history (as originally executed — class/DTO names below reflect the pre-refactor shape; the post-execute resync collapsed them all into `CurrentUserSchemaAccess`):

### Task 1 — DTO Records (commit `fec54d5`) — collapsed post-execute

Originally shipped four immutable `record` types (mirrors `ChatResponse`; no Lombok per CLAUDE.md):

- **`AiSchema`** (previously) — `Map<String, AiEntityInfo>` keyed by Jmix entity name (D-11).
- **`AiEntityInfo`** (previously) — carried `MetaClass`, `entityName`, `localizedLabel` (null at scan time, resolved per-request), `attributes` list; plus `withLocalizedLabel` / `withAttributes` copy helpers to build filtered snapshots.
- **`AiAttributeInfo`** (previously) — `name`, `typeLabel`, `nullable`, `enumValues` (nullable), `relationshipTarget` (nullable), `validationConstraints`, `localizedLabel` (D-02).
- **`UserEditableStringIndex`** (previously) — `Map<String, Set<String>>` by entity name with `forEntity(String)` / `forEntity(MetaClass)` lookups; `ToolResultFormatter` in Plan 03 consumes this to drive `<data>…</data>` wrapping (D-13).

Post-execute: these four DTOs were folded into internal record types / `Map`-returning methods on `CurrentUserSchemaAccess` (e.g. `userEditableStringIndexForEntity(...)`). Shapes returned to TOOL-01/TOOL-02 callers are unchanged.

### Task 2 — Metamodel inventory scan (commit `0856763`) — collapsed post-execute

Previously a `@Component` (`MetamodelScanner`, collapsed post-execute) with constructor-injected `Metadata` + `MetadataTools`. On `@EventListener(ApplicationReadyEvent.class)` (deliberately NOT `@PostConstruct` per Pitfall 1):

1. Walks `metadata.getSession().getClasses()`, skipping `@SystemLevel` classes.
2. Builds attribute descriptors per `MetaProperty`:
   - `typeLabel`: `"enum:<JavaSimpleName>"`, `"ref:<jmixEntityName>"`, or the Java simple name for datatypes.
   - `enumValues`: enum constant names via `javaClass.getEnumConstants()`.
   - `relationshipTarget`: `MetaClass.getName()` for associations; null otherwise.
   - `validationConstraints`: `NotNull`, `Size(min=..,max=..)`, `Pattern(regexp=..)` harvested via `mp.getAnnotatedElement()`.
   - `localizedLabel`: always `null` at scan time — scan is locale-agnostic by design (D-04).
3. In the same pass, builds the user-editable string index using `MetadataTools.isJpa` + `!isSystemLevel` + `!isSystem` + not in framework-managed set + `String.class` datatype (D-13).
4. Stores both behind accessors that throw `IllegalStateException("scan() not yet run")` pre-event.

Post-execute: this logic lives inside `CurrentUserSchemaAccess`; the `ApplicationReadyEvent` gating and Pitfall-1 guarantee are preserved.

### Task 3 — Per-request AccessManager filter (commit `00960b7`) — collapsed post-execute

Previously a stateless `@Component` (`EffectiveSchemaComputer`, collapsed post-execute); no class-level cache (T-03-01). Constructor-injected `AccessManager`, the scanner, `MessageTools`. Three public methods — now methods on `CurrentUserSchemaAccess`:

- **`forCurrentUser()`** — every entity: `CrudEntityContext` → `isReadPermitted()`; every surviving attribute: `EntityAttributeContext` → `canView()`; localizes kept entity/attribute labels via `MessageTools` on each call (TOOL-02: never cached).
- **`canReadAttribute(MetaClass, String attrPath)`** — helper for Plan 02's filter-mapper depth-cap path validation (D-08). Accepts dotted paths (e.g. `customer.region.code`) since `EntityAttributeContext` takes property paths.
- **`canReadEntity(MetaClass)`** — helper for Plan 02 filter hops and Plan 03 `BuiltInDataTools.resolveOrError`.

## Verification

```bash
./gradlew :ai-agent:ai-agent:compileJava   # BUILD SUCCESSFUL (≤5s after Task 1 daemon warmup)
```

Plan-level greps (all pass):

| Check | Result |
|-------|--------|
| `@EventListener(ApplicationReadyEvent.class)` in scanner | match (Pitfall 1 respected) |
| `@PostConstruct` annotation anywhere in package | none (only a Javadoc reference explaining what NOT to use) |
| `metadata.getSession().getClasses()` in scanner | match |
| `@Autowired` / `@Inject` field injection | none (constructor-only per CLAUDE.md) |
| `CrudEntityContext` + `EntityAttributeContext` in computer | both matched |
| `applyRegisteredConstraints` occurrences in computer | 3 (one per helper + main loop) |
| `getEntityCaption` / `getPropertyCaption` in computer | both matched (D-04) |
| `@Cacheable` / `private static final Map` cache in computer | none (TOOL-02 "never cached") |
| Lombok imports anywhere in package | none |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 — Bug] `AccessManager` FQCN corrected**
- **Found during:** Task 3 first compile (`cannot find symbol: class AccessManager`).
- **Issue:** Plan `<interfaces>` listed `io.jmix.core.security.AccessManager`. In Jmix 2.8 the class lives at `io.jmix.core.AccessManager` (verified against `jmix-core-2.8.0.jar` contents).
- **Fix:** Updated the import in `EffectiveSchemaComputer.java` (previously; class collapsed post-execute into `CurrentUserSchemaAccess`). No logic change.
- **Files modified (previously):** `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/EffectiveSchemaComputer.java`.
- **Commit:** `00960b7` (same commit as Task 3 — fix was inline before the task landed).

### Implementation choice beyond the plan text

**D-13 persistence check via `MetadataTools.isJpa`** — the plan text acknowledged that `MetaProperty.isPersistent()` is not a public API and proposed a brittle reflection-based fallback (inspecting `AnnotatedElement` for `@Column` / `@Transient`). `MetadataTools.isJpa(MetaProperty)` is the canonical public check and is what the codebase already relies on (CLAUDE.md: public APIs only). Added `MetadataTools` as a second constructor dependency. This is the authoritative Jmix way to answer "is this property JPA-persistent?".

## Entry Points for Plan 03

Post-execute: the adapter `CurrentUserSchemaAccess` exposes three public methods (inventory scan + user-editable-string bookkeeping were folded into the `<data>`-wrapping code path on `ToolResultFormatter` / its collaborators — see 03-03-SUMMARY).

| Symbol | Consumer |
|--------|----------|
| `CurrentUserSchemaAccess.getReadableSchema()` | `list_entities` / `describe_entity` tools (Plan 03); returns `Map<MetaClass, Set<String>>` of readable entities + attribute names |
| `CurrentUserSchemaAccess.canReadAttribute(mc, path)` | `StructuredFilterConditionMapper` depth-cap + path validation (Plan 02, D-08) |
| `CurrentUserSchemaAccess.canReadEntity(mc)` | `StructuredFilterConditionMapper` relationship-hop walk + `BuiltInDataTools.resolveReadableEntityOrThrow` (Plan 03) |

## Threat Flags

None. All new surface sits behind `CurrentUserSchemaAccess` and is covered by the plan's threat model (T-03-01..T-03-04).

## Known Stubs

None. Plan 04 (tests) will pin the runtime behavior; there is no UI or data-binding in this plan.

## Commits

Commit messages below are preserved verbatim as executed (historical record — classes named in these messages were collapsed post-execute into `CurrentUserSchemaAccess`):

| # | Hash | Message (historical, pre-refactor) |
|---|------|---------|
| 1 | `fec54d5` | feat(03-01): add AiSchema/AiEntityInfo/AiAttributeInfo/UserEditableStringIndex DTOs (previously; collapsed post-execute) |
| 2 | `0856763` | feat(03-01): add MetamodelScanner for TOOL-01 raw inventory + D-13 UEI (previously; collapsed post-execute) |
| 3 | `00960b7` | feat(03-01): add EffectiveSchemaComputer for TOOL-02 per-request filtering (previously; collapsed post-execute) |

## Self-Check

Originally verified (pre-refactor):

- [x] `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiSchema.java` existed at commit `fec54d5` (previously)
- [x] `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiEntityInfo.java` existed at commit `fec54d5` (previously)
- [x] `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiAttributeInfo.java` existed at commit `fec54d5` (previously)
- [x] `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/UserEditableStringIndex.java` existed at commit `fec54d5` (previously)
- [x] `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/MetamodelScanner.java` existed at commit `0856763` — collapsed post-execute into `CurrentUserSchemaAccess`
- [x] `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/EffectiveSchemaComputer.java` existed at commit `00960b7` — collapsed post-execute into `CurrentUserSchemaAccess`
- [x] Commits `fec54d5`, `0856763`, `00960b7` present in `git log`
- [x] `./gradlew :ai-agent:ai-agent:compileJava` → BUILD SUCCESSFUL

Current code (post-refactor, 2026-04-20):

- [x] `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java` exists (single adapter replacing the six files listed above)

## Self-Check: PASSED
