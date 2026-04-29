---
phase: 11-mutation-capable-built-in-tools
plan: 08
subsystem: tools
tags: [link-tools, always-on, opacity-r4]
requires:
  - 11-04-PLAN.md (ToolEntityResolver.resolveReadableEntityOrThrow)
  - 11-04-PLAN.md (LlmExposurePolicy.canReadEntity)
provides:
  - BuiltInLinkTools @Component (always-on)
  - generate_entity_list_link @Tool
  - generate_entity_detail_link @Tool
affects:
  - AgentToolCallbacks.forCurrentUser (Plan 11-09 will register these tools)
tech-stack:
  added:
    - org.springframework.boot.autoconfigure.web.ServerProperties (already on classpath)
    - io.jmix.flowui.view.ViewRegistry (already on classpath)
    - com.vaadin.flow.router.Route (already on classpath)
  patterns:
    - 5-section @Tool description (MEMORY feedback_rich_tool_descriptions)
    - Uniform unknown_entity opacity collapse for hidden entities AND missing views
    - UUID validation + URL-encoding on detail-link entityId
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/link/BuiltInLinkTools.java
  modified:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
decisions:
  - D-05 honored — link tools always-on, independent of mutation.enabled
  - viewRegistry.getListViewId/getDetailViewId always synthesize a String id (Customer.list / Customer.detail), so the meaningful no-view check is findViewInfo(id).isEmpty(); we collapse both an empty Optional and a missing @Route to unknown_entity per D-05 uniform-opacity
  - Detail-link normalises null entityId via NPE catch -> parameter_conversion_error (UUID.fromString throws NPE on null)
  - URL encoding kept on the UUID string even though hyphenated UUIDs are URL-safe — defensive against any future non-UUID id type
metrics:
  duration: ~25min
  completed: 2026-04-28
  task_count: 1
  file_count: 3
---

# Phase 11 Plan 08: BuiltInLinkTools (Wave 8) Summary

Shipped `BuiltInLinkTools` — an always-on `@Component` exposing 2 `@Tool` methods (`generate_entity_list_link`, `generate_entity_detail_link`) backed by Jmix `ViewRegistry` route lookups and Spring Boot `ServerProperties` context-path. Both tools route entity resolution through `ToolEntityResolver.resolveReadableEntityOrThrow` so unknown entities, hidden entities, and entities without registered views all return a uniform `unknown_entity` error per Phase 10 R4. Detail-link validates the supplied id as a UUID and URL-encodes it before appending to the route, but does NOT verify row existence — that responsibility belongs to `get_record` (D-05).

## Tasks

### Task 1: Create BuiltInLinkTools with 2 @Tool methods + locale captions
- **Status:** Done
- **Commit:** `7629530`
- **Files:** `BuiltInLinkTools.java` (new), `messages_en.properties`, `messages_vi.properties`
- **Verification:** `./gradlew :ai-agent:compileJava` BUILD SUCCESSFUL in 13s.

## Acceptance Criteria

| Criterion | Result |
| --------- | ------ |
| File `BuiltInLinkTools.java` exists | PASS |
| Contains literal `@Component` | PASS |
| Does NOT contain `@ConditionalOnProperty` (always-on) | PASS |
| Exactly 2 `@Tool(name = ...)` annotations | PASS (`grep -c` returned 2) |
| Contains `@Tool(name = "generate_entity_list_link"` | PASS |
| Contains `@Tool(name = "generate_entity_detail_link"` | PASS |
| Contains `toolEntityResolver.resolveReadableEntityOrThrow(` | PASS |
| Contains `viewRegistry.getListViewId(` | PASS |
| Contains `viewRegistry.getDetailViewId(` | PASS |
| Contains `StringUtils.substringBeforeLast(` | PASS |
| Contains `URLEncoder.encode(` | PASS |
| Contains `UUID.fromString(entityId)` | PASS |
| Contains `serverProperties.getServlet().getContextPath()` | PASS |
| Does NOT contain `dataManager` | PASS |
| `messages_en.properties` contains `ai-agent.tool.link.urlLabel=` | PASS |
| `messages_vi.properties` contains `ai-agent.tool.link.urlLabel=` | PASS |
| `./gradlew :ai-agent:compileJava` exits 0 | PASS |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - API contract correction] `viewRegistry.getListViewId/getDetailViewId` never return null**

- **Found during:** Task 1 (pre-write API verification against `jmix-flowui-2.8.0-sources.jar`)
- **Issue:** Plan code sketch checked `if (listViewId == null) throw unknown_entity`, but `ViewRegistry.getListViewId(MetaClass)` always synthesizes `<EntityClass>.list` (or equivalent) and returns it as a non-null `String`. The dead null-branch would never fire, so the "no list view registered" path would propagate a `NoSuchViewException` later or silently emit a URL pointing to a missing route.
- **Fix:** Moved the existence check into `findRouteForViewId(...)` — when `viewRegistry.findViewInfo(viewId).isEmpty()` OR the controller carries no `@Route` annotation, we return null from the helper, and the caller collapses to `unknown_entity` per D-05 uniform-opacity. The plan's contract (collapse "no list view" to `unknown_entity`) is preserved; only the mechanism shifted from a stale null-check on the synthesized id to the real existence check on the registered view.
- **Files modified:** `BuiltInLinkTools.java` (new file — corrected within initial write)
- **Commit:** `7629530`

**2. [Rule 2 - Critical functionality] NPE-safe handling of null entityId on detail-link**

- **Found during:** Task 1 implementation review
- **Issue:** `UUID.fromString(null)` throws `NullPointerException`, NOT `IllegalArgumentException`. The plan only caught the latter, so a null `entityId` from the LLM (e.g. due to a Spring AI Map-coercion edge case) would have escaped as an unhandled exception, leaking a stack trace and bypassing the `parameter_conversion_error` envelope.
- **Fix:** Added a separate `catch (NullPointerException nullId)` branch returning `parameter_conversion_error` with the same recovery hint shape. Defensive correctness — keeps `ToolUserError` envelope opacity intact (D-07 fail-closed posture).
- **Files modified:** `BuiltInLinkTools.java`
- **Commit:** `7629530`

## Authentication Gates

None.

## Known Stubs

None.

## Threat Flags

None — link tools are read-only, return URL strings only, and reuse the `LlmExposurePolicy.canReadEntity` opacity gate already established by `BuiltInDataTools`. No new network endpoint, no new auth path, no new schema, no new file access.

## Decisions Made

- Use `Optional<ViewInfo>` shape for `findViewInfo(...)` confirmed via `javap -p` against `jmix-flowui-2.8.0.jar` — matches the jmix-crm exemplar.
- `getListViewId`/`getDetailViewId` always return a synthesized id (never null) — null/empty handling moved into `findRouteForViewId(viewId)` helper.
- UUID hyphenated form is URL-safe; `URLEncoder.encode(uuid.toString(), UTF_8)` is defensive but kept per plan acceptance criterion.
- Locale captions limited to `ai-agent.tool.link.urlLabel` (one key per locale) — link-tool surface is LLM-protocol, not user UI; captions are symbolic for symmetry with the mutation-tool error keys.

## Self-Check: PASSED

- File `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/link/BuiltInLinkTools.java` — FOUND
- File `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` — FOUND (contains `ai-agent.tool.link.urlLabel=Open record`)
- File `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` — FOUND (contains `ai-agent.tool.link.urlLabel=Mở bản ghi`)
- Commit `7629530` — FOUND in branch history
- `./gradlew :ai-agent:compileJava` — BUILD SUCCESSFUL
