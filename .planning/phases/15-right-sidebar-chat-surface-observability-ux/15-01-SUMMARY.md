---
phase: 15-right-sidebar-chat-surface-observability-ux
plan: 01
subsystem: ui
tags: [jmix, enum, vaadin-flow, chat-surface, i18n]

# Dependency graph
requires:
  - phase: 12-configurable-chat-surfaces
    provides: AiChatSurface (EnumClass<String>), AiUiSettings singleton, AiUiSettingsService, AiUiSettingsDetailView, ChatPanelFragment/ChatSurfaceMounter
provides:
  - "AiChatSurface.SIDEBAR enum constant (getId()/fromId() round-trip)"
  - "com.vn.agent.entity/AiChatSurface.SIDEBAR localized label in messages_en.properties + messages_vi.properties"
  - "SIDEBAR auto-listed in the admin enabled-surface checkbox group and defaultSurface radio group"
  - "Fresh-install AiUiSettings seed now ships all three surfaces enabled (EnumSet.allOf) with FULL_ROUTE as default"
affects: [15-02, 15-03, ChatSurfaceMounter, side-panel mount gating]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Surface-control item population stays via setItems(AiChatSurface.class) + setItemLabelGenerator(messages::getMessage) so new enum values are auto-listed — only the msg:// label key is new"

key-files:
  created: []
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiChatSurface.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
    - ai-agent/ai-agent/src/test/java/com/vn/agent/entity/AiUiSettingsModelTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/uisettings/AiUiSettingsDetailViewTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/AiUiSettingsServiceSingletonTest.java

key-decisions:
  - "RESEARCH Open Q2 resolved: keep AiUiSettingsService.createDefaultSettings() as EnumSet.allOf(AiChatSurface.class) so a fresh install ships all three surfaces enabled; defaultSurface stays FULL_ROUTE. Rationale: the SIDEBAR panel starts closed per D-04, so enabled-by-default only means the navbar toggle is present — parity with HEADER_BUTTON shipping a navbar button by default."
  - "No new @Entity/@Table/Liquibase changelog — ENABLED_SURFACE_IDS/DEFAULT_SURFACE are existing varchar columns holding comma-joined ids (OBS-04 satisfied by construction)."
  - "No production code change beyond the enum constant + label keys: AiUiSettings.java, AiUiSettingsService.java, AiUiSettingsDetailView.java and the XML descriptor were unchanged — the detail view already calls setItems(AiChatSurface.class) for both controls, and AiUiSettings#enabledSurfaceIds default already delegates to EnumSet.allOf(AiChatSurface.class)."

patterns-established:
  - "Adding a new chat surface = one enum constant + one msg:// key per locale + extend the model/view tests; no DDL, no view-wiring change."

requirements-completed: [SURF-11, OBS-04]

# Metrics
duration: 30min
completed: 2026-05-11
---

# Phase 15 Plan 01: AiChatSurface.SIDEBAR Foundation Summary

**Added `AiChatSurface.SIDEBAR` as a first-class chat surface — enum constant + en/vi labels — auto-listed in the admin UI-settings controls and shipped enabled-by-default in the seed, with zero schema change.**

## Performance

- **Duration:** ~30 min
- **Started:** 2026-05-11T14:55:00Z (approx)
- **Completed:** 2026-05-11T15:13:00Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments
- `AiChatSurface.SIDEBAR("SIDEBAR")` added as the third enum constant; `getId()`/`fromId()` round-trip works.
- `com.vn.agent.entity/AiChatSurface.SIDEBAR` label added to both locale bundles (`Right sidebar` / `Thanh bên phải`).
- Confirmed the admin enabled-surface checkbox group and `defaultSurface` radio group auto-include `SIDEBAR` (no Java/XML change needed — both use `setItems(AiChatSurface.class)`).
- Resolved RESEARCH Open Q2: `createDefaultSettings()` stays `EnumSet.allOf(AiChatSurface.class)`, so fresh installs ship all three surfaces enabled with `FULL_ROUTE` default.
- Extended `AiUiSettingsModelTest` (enum size 3, id round-trip, `enabledSurfaceIds` parse round-trip incl. SIDEBAR) and `AiUiSettingsDetailViewTest` (new test: SIDEBAR in both control item sets + default settings contain SIDEBAR); fixed default-seed assertions in `AiUiSettingsDetailViewTest` and `AiUiSettingsServiceSingletonTest`.
- No new `@Entity`/`@Table`/Liquibase changelog; `git diff` shows no change to any `liquibase/changelog/**` or `*changelog.xml`.

## Task Commits

1. **Task 1: Add AiChatSurface.SIDEBAR and its locale labels** — `efddda1` (feat) — combined RED/GREEN: the test references `AiChatSurface.SIDEBAR`, so the RED step is a compile failure and the source + test land in one commit.
2. **Task 2: Make SIDEBAR selectable in the admin UI-settings view and confirm the seed default** — `3b43a83` (test) — no production code change needed beyond the Task-1 label; commit is test-only (new selectability test + updated seed assertions).

**Plan metadata:** (final docs commit — this SUMMARY + STATE.md + ROADMAP.md + REQUIREMENTS.md)

## Files Created/Modified
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiChatSurface.java` — added `SIDEBAR("SIDEBAR")` constant.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` — added `com.vn.agent.entity/AiChatSurface.SIDEBAR=Right sidebar`.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` — added `com.vn.agent.entity/AiChatSurface.SIDEBAR=Thanh bên phải`.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/entity/AiUiSettingsModelTest.java` — enum-size-3 + SIDEBAR id/parse round-trip assertions.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/uisettings/AiUiSettingsDetailViewTest.java` — new `sidebarSurfaceIsSelectableInBothControlsAndShipsEnabledByDefault` test + `itemsOf(...)` helper; updated default-seed assertions to include SIDEBAR.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/AiUiSettingsServiceSingletonTest.java` — updated `firstLoadCreatesSingletonWithDefaults` to expect SIDEBAR in the seeded enabled set.

**Which AiUiSettings* files actually changed:** only the *tests* (`AiUiSettingsDetailViewTest`, `AiUiSettingsServiceSingletonTest`). `AiUiSettings.java`, `AiUiSettingsService.java`, `AiUiSettingsDetailView.java`, and `ai-ui-settings-detail-view.xml` were **not** modified — they were listed in `files_modified` only so the executor would confirm no change was needed (and it wasn't, beyond the new label key).

**New `msg://` key:** `com.vn.agent.entity/AiChatSurface.SIDEBAR` (en: `Right sidebar`, vi: `Thanh bên phải`).

## Decisions Made
- **Seed default for SIDEBAR (RESEARCH Open Q2):** keep `EnumSet.allOf(AiChatSurface.class)` — fresh installs ship `FULL_ROUTE`, `HEADER_BUTTON`, and `SIDEBAR` enabled, with `FULL_ROUTE` still the `defaultSurface`. Enabled-by-default for SIDEBAR only means the navbar toggle is present; the side panel itself starts closed (D-04). This is parity with `HEADER_BUTTON` shipping a navbar button by default.
- **Task 1 single combined commit:** the model test references `AiChatSurface.SIDEBAR`, so a standalone RED commit would not compile. The enum constant + extended test land together; GREEN verified via `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.entity.AiUiSettingsModelTest"`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Updated default-seed assertion in AiUiSettingsServiceSingletonTest**
- **Found during:** Task 2 (seed-default confirmation)
- **Issue:** `firstLoadCreatesSingletonWithDefaults` asserted the seeded enabled set was exactly `{FULL_ROUTE, HEADER_BUTTON}`; adding `SIDEBAR` to the enum (and keeping `EnumSet.allOf`) makes the seed `{FULL_ROUTE, HEADER_BUTTON, SIDEBAR}`, so the assertion would fail.
- **Fix:** Updated the `containsExactly(...)` to include `AiChatSurface.SIDEBAR`. This file was not in the plan's `files_modified` list but is the same in-scope regression as the plan-listed `AiUiSettingsDetailViewTest` default-seed assertions (lines 131/153), which were also updated.
- **Files modified:** `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/AiUiSettingsServiceSingletonTest.java`
- **Verification:** `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.*"` green.
- **Committed in:** `3b43a83` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 bug — in-scope regression from the new enum value).
**Impact on plan:** Necessary to keep the suite green after the planned enum addition. No scope creep — the file mirrors the plan-listed `AiUiSettingsDetailViewTest` change.

## Issues Encountered
None — both tasks executed as written.

## Verification Performed
- `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.entity.AiUiSettingsModelTest"` — green (GREEN gate).
- `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.uisettings.AiUiSettingsDetailViewTest"` — green.
- `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.*" --tests "com.vn.agent.entity.AiUiSettingsModelTest" --tests "com.vn.agent.view.uisettings.AiUiSettingsDetailViewTest"` — green (no regression in surface-related tests).
- `git diff --stat HEAD -- "*changelog*" "*liquibase*"` — empty (no DDL).
- `messages_en.properties` and `messages_vi.properties` both contain `com.vn.agent.entity/AiChatSurface.SIDEBAR=`.

## User Setup Required
None — no external service configuration required.

## Next Phase Readiness
- `AiChatSurface.SIDEBAR` exists and is admin-selectable — Plan 03 (`ChatSurfaceMounter`) can now gate the side-panel mount on `getEnabledSurfaceSet().contains(AiChatSurface.SIDEBAR)`.
- No blockers.

## Self-Check: PASSED

- `AiChatSurface.java` exists and contains `SIDEBAR` — FOUND
- `messages_en.properties` / `messages_vi.properties` exist — FOUND
- `15-01-SUMMARY.md` exists — FOUND
- Commit `efddda1` (Task 1) — FOUND
- Commit `3b43a83` (Task 2) — FOUND

---
*Phase: 15-right-sidebar-chat-surface-observability-ux*
*Completed: 2026-05-11*
