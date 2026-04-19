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
    - com.vn.agent.metadata.AiSchema
    - com.vn.agent.metadata.AiEntityInfo
    - com.vn.agent.metadata.AiAttributeInfo
    - com.vn.agent.metadata.UserEditableStringIndex
    - com.vn.agent.metadata.MetamodelScanner
    - com.vn.agent.metadata.EffectiveSchemaComputer
  affects:
    - Plan 03-02 FilterDslMapper (consumes canReadAttribute / canReadEntity)
    - Plan 03-03 BuiltInDataTools (consumes forCurrentUser / getUserEditableStringIndex / canReadEntity)
tech_stack:
  added: []
  patterns:
    - "Jmix metamodel scan gated on ApplicationReadyEvent (NOT @PostConstruct) — Pitfall 1"
    - "Stateless per-request AccessManager filter — no class-level cache (T-03-01)"
    - "Constructor injection only (CLAUDE.md)"
key_files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiSchema.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiEntityInfo.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiAttributeInfo.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/UserEditableStringIndex.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/MetamodelScanner.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/EffectiveSchemaComputer.java
  modified: []
decisions:
  - "D-13 persistence check uses MetadataTools.isJpa(MetaProperty) — portable public API; plan suggested Field + @Column reflection fallback which is brittle."
  - "MetamodelScanner constructor-injects MetadataTools in addition to Metadata to keep the D-13 classification inside the public Jmix API surface."
metrics:
  duration_seconds: 264
  duration_human: "~4 minutes"
  tasks_completed: 3
  files_created: 6
  files_modified: 0
  completed: "2026-04-19"
---

# Phase 03 Plan 01: Metadata Core Summary

**One-liner:** Six Java files under `com.vn.agent.metadata` — immutable DTOs (`AiSchema`/`AiEntityInfo`/`AiAttributeInfo`/`UserEditableStringIndex`), `MetamodelScanner` producing the raw inventory on `ApplicationReadyEvent` (TOOL-01), and a stateless `EffectiveSchemaComputer` that reruns `AccessManager` filtering + `MessageTools` localization on every call (TOOL-02).

## What Was Built

### Task 1 — DTO Records (commit `fec54d5`)

Four immutable `record` types (mirrors `ChatResponse`; no Lombok per CLAUDE.md):

- **`AiSchema`** — `Map<String, AiEntityInfo>` keyed by Jmix entity name (D-11).
- **`AiEntityInfo`** — carries `MetaClass`, `entityName`, `localizedLabel` (null in scanner output, resolved per-request), `attributes` list; plus `withLocalizedLabel` / `withAttributes` copy helpers for the computer to build filtered snapshots.
- **`AiAttributeInfo`** — `name`, `typeLabel`, `nullable`, `enumValues` (nullable), `relationshipTarget` (nullable), `validationConstraints`, `localizedLabel` (D-02).
- **`UserEditableStringIndex`** — `Map<String, Set<String>>` by entity name with `forEntity(String)` / `forEntity(MetaClass)` lookups; `ToolResultFormatter` in Plan 03 consumes this to drive `<data>…</data>` wrapping (D-13).

### Task 2 — `MetamodelScanner` (commit `0856763`)

`@Component` with constructor-injected `Metadata` + `MetadataTools`. On `@EventListener(ApplicationReadyEvent.class)` (deliberately NOT `@PostConstruct` per Pitfall 1):

1. Walks `metadata.getSession().getClasses()`, skipping `@SystemLevel` classes.
2. Builds `AiAttributeInfo` per `MetaProperty`:
   - `typeLabel`: `"enum:<JavaSimpleName>"`, `"ref:<jmixEntityName>"`, or the Java simple name for datatypes.
   - `enumValues`: enum constant names via `javaClass.getEnumConstants()`.
   - `relationshipTarget`: `MetaClass.getName()` for associations; null otherwise.
   - `validationConstraints`: `NotNull`, `Size(min=..,max=..)`, `Pattern(regexp=..)` harvested via `mp.getAnnotatedElement()`.
   - `localizedLabel`: always `null` — scanner is locale-agnostic by design (D-04).
3. In the same pass, builds `UserEditableStringIndex` using `MetadataTools.isJpa` + `!isSystemLevel` + `!isSystem` + not in framework-managed set + `String.class` datatype (D-13).
4. Stores both in `volatile` fields behind accessors that throw `IllegalStateException("scan() not yet run")` pre-event.

### Task 3 — `EffectiveSchemaComputer` (commit `00960b7`)

Stateless `@Component`; no class-level cache (T-03-01). Constructor-injects `AccessManager`, `MetamodelScanner`, `MessageTools`. Three public methods:

- **`forCurrentUser()`** — every entity: `CrudEntityContext` → `isReadPermitted()`; every surviving attribute: `EntityAttributeContext` → `canView()`; localizes kept entity/attribute labels via `MessageTools` on each call (TOOL-02: never cached).
- **`canReadAttribute(MetaClass, String attrPath)`** — helper for Plan 02's `FilterDslMapper` depth-cap path validation (D-08). Accepts dotted paths (e.g. `customer.region.code`) since `EntityAttributeContext` takes property paths.
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
- **Fix:** Updated the import in `EffectiveSchemaComputer.java`. No logic change.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/EffectiveSchemaComputer.java`.
- **Commit:** `00960b7` (same commit as Task 3 — fix was inline before the task landed).

### Implementation choice beyond the plan text

**D-13 persistence check via `MetadataTools.isJpa`** — the plan text acknowledged that `MetaProperty.isPersistent()` is not a public API and proposed a brittle reflection-based fallback (inspecting `AnnotatedElement` for `@Column` / `@Transient`). `MetadataTools.isJpa(MetaProperty)` is the canonical public check and is what the codebase already relies on (CLAUDE.md: public APIs only). Added `MetadataTools` as a second constructor dependency. This is the authoritative Jmix way to answer "is this property JPA-persistent?".

## Entry Points for Plan 03

| Symbol | Consumer |
|--------|----------|
| `MetamodelScanner.getRawSchema()` | internal only — not surfaced to any tool path |
| `MetamodelScanner.getUserEditableStringIndex()` | `ToolResultFormatter` (Plan 03) for `<data>` wrapping (D-13) |
| `EffectiveSchemaComputer.forCurrentUser()` | `list_entities` / `describe_entity` tools (Plan 03) |
| `EffectiveSchemaComputer.canReadAttribute(mc, path)` | `FilterDslMapper` depth-cap + path validation (Plan 02, D-08) |
| `EffectiveSchemaComputer.canReadEntity(mc)` | `FilterDslMapper` relationship-hop walk + `BuiltInDataTools.resolveOrError` (Plan 03) |

## Threat Flags

None. All new surface sits behind `EffectiveSchemaComputer` and is covered by the plan's threat model (T-03-01..T-03-04).

## Known Stubs

None. Plan 04 (tests) will pin the runtime behavior; there is no UI or data-binding in this plan.

## Commits

| # | Hash | Message |
|---|------|---------|
| 1 | `fec54d5` | feat(03-01): add AiSchema/AiEntityInfo/AiAttributeInfo/UserEditableStringIndex DTOs |
| 2 | `0856763` | feat(03-01): add MetamodelScanner for TOOL-01 raw inventory + D-13 UEI |
| 3 | `00960b7` | feat(03-01): add EffectiveSchemaComputer for TOOL-02 per-request filtering |

## Self-Check

- [x] `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiSchema.java` exists
- [x] `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiEntityInfo.java` exists
- [x] `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiAttributeInfo.java` exists
- [x] `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/UserEditableStringIndex.java` exists
- [x] `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/MetamodelScanner.java` exists
- [x] `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/EffectiveSchemaComputer.java` exists
- [x] Commits `fec54d5`, `0856763`, `00960b7` present in `git log`
- [x] `./gradlew :ai-agent:ai-agent:compileJava` → BUILD SUCCESSFUL

## Self-Check: PASSED
