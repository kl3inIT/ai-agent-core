---
phase: 15-right-sidebar-chat-surface-observability-ux
plan: 05
subsystem: testing
tags: [leak-test, pattern-pack-reuse, locale-completeness, structural-test, no-ddl, todo-bookkeeping, phase-15-close]

# Dependency graph
requires:
  - phase: 09-prompt-hardening-output-scanner (v1.1.0)
    provides: "ToolNamePatternProvider (TOOL_NAME_LEAK) + HostPrefixPatternProvider (HOST_PREFIX_LEAK) + AiAgentGuardProperties — the Phase 9 leak-guard pattern packs, reused VERBATIM (not forked)"
  - phase: 15-right-sidebar-chat-surface-observability-ux
    provides: "TurnDetailRenderer label-only mapper + ChatPanelFragment showStatus/appendTurnDetails (Plan 04); MountedChatSurfaceState sidebar fields (Plan 03); AiChatSurface.SIDEBAR label + the 15-01/15-03/15-04 msg:// keys"
provides:
  - "ObservabilityLeakTest (TEST-19, D-09) — reuses ToolNamePatternProvider/HostPrefixPatternProvider verbatim against (a) the TurnDetailRenderer mapper output across all ActivityKind values + neutral + {TOOL,RETRIEVAL} step rows incl. an ERROR/COMMIT_FAILED outcome + a null-latency row + the MessageFormat summary AND (b) a real rendered ChatPanelFragment's <span.ai-agent-status> text + an actual Details step-row inside the .ai-agent-turn-activity block; passing negative control routes find_records / jmixapp_Customer through the rendering path and asserts the regexes trip"
  - "ObservabilityMessagesCompletenessTest — every new Phase-15 msg:// key (15 keys) resolves non-blank in messages_en.properties AND messages_vi.properties; a one-bundle-only key fails"
  - "NoNewPersistedStateTest (OBS-04) — no Phase-15-named Liquibase changelog file; no Phase-15 <include file=> in agentstore-changelog.xml / changelog.xml; no @Table name with TURN/ACTIVITY/DISCLOSURE — NOT a hardcoded global @Entity count (15-REVIEWS #10)"
  - "AiChatSessionStateTest extended — AiChatSessionState still holds exactly {currentConversationId, listenerRegistrations} (no new field, no per-turn collection); MountedChatSurfaceState declares only {chatButton, missingAppLayoutWarned, sidebarToggleButton, sidebarPanelDiv, sidebarHostView, sidebarOpen} — no Collection/Map/array field"
  - "Phase 15 closed: the 2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui todo moved to .planning/todos/done/ with a Phase-15 resolution note; done/_INDEX.md updated"
affects: [phase-15 close, phase-20 (its STT error/retry-row leak coverage can mirror this UI-layer leak-test pattern)]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "UI-layer leak test by pattern-pack REUSE: construct ToolNamePatternProvider via `new ToolNamePatternProvider(List.of(), new AiAgentGuardProperties(null,null,null,null))` -> `.buildPattern()` -> `Pattern.compile(provider.asPattern().orElseThrow().regex())`; HostPrefixPatternProvider via a mocked Metadata with known metaclass names (mirrors HostPrefixLeakScannerTest.providerFor) — never re-define the regexes"
    - "Rendered-component leak assertion without @UiTest: drive the fragment's helper methods (showStatus / appendTurnDetails) against real Vaadin Element/Details/VerticalLayout components (no UI attach needed), then run the regexes over the ACTUAL rendered DOM text — the fragment package has no working @SpringBootTest boot harness, so this follows the established plain-JUnit+Mockito+reflective-injection precedent (ChatPanelFragmentStatusLineTest / ...TurnDetailTest)"
    - "Structural no-DDL assertion without a brittle count: walk **/liquibase/** for Phase-15-named .xml files + scan <include file=> entries in the master changelogs + scan entity sources for a forbidden @Table name token — three negatives, none pinning a global @Entity count (15-REVIEWS #10)"
    - "Reflective state-holder invariant: getDeclaredFields() filtered to non-static, compared to an expected name set, plus an assertion that the only collection-typed field is the listener registry (grows with subscribers, not chat turns)"

key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ObservabilityLeakTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ObservabilityMessagesCompletenessTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/NoNewPersistedStateTest.java
    - .planning/todos/done/2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md
    - .planning/phases/15-right-sidebar-chat-surface-observability-ux/deferred-items.md
  modified:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/AiChatSessionStateTest.java
    - .planning/todos/done/_INDEX.md
  removed:
    - .planning/todos/pending/2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md

key-decisions:
  - "Followed the established fragment-test precedent (plain JUnit 5 + Mockito + reflective field injection) rather than the plan's literal `@UiTest` wording — the fragment package has no working @SpringBootTest/@UiTest boot harness (documented blocker in 15-04-SUMMARY / STATE 'Blockers'). The rendered-component leak assertion still scans the ACTUAL rendered DOM text (Element#getText on the real <span.ai-agent-status> + the real Details step rows) — it just doesn't attach to a live UI, which it doesn't need to. Same precedent used by ChatPanelFragmentStatusLineTest / ChatPanelFragmentTurnDetailTest / ChatSurfaceMounterTest."
  - "HostPrefixPatternProvider built from a mocked Metadata with a known host metamodel ({jmixapp_Customer, jmixapp_Order, acme_Product}) — mirrors HostPrefixLeakScannerTest.providerFor(...). This gives both a live HOST_PREFIX_LEAK regex AND a concrete host-prefixed entity name (jmixapp_Customer) for the negative control (no @SpringBootTest available to autowire the real provider, per the same blocker)."
  - "NoNewPersistedStateTest asserts the negative via (i) no Phase-15-named .xml under **/liquibase/** (the agentstore changelog uses <includeAll path=...> so any new .xml there would be picked up — this catches it), (ii) no Phase-15 <include file=> in agentstore-changelog.xml / changelog.xml, (iii) belt-and-braces: no @Table(name=...) under com/vn/agent/entity contains TURN/ACTIVITY/DISCLOSURE. No hardcoded global @Entity count (15-REVIEWS #10)."
  - "There is no .planning/todos/pending/_INDEX.md in this repo — the plan's files_modified listed it, but it does not exist; nothing to update there. The done/_INDEX.md 'still pending' line was updated to read 'none — the pending queue is empty'."

patterns-established:
  - "Phase-close test trio: a pattern-pack-reuse leak test (mapper output + a real rendered component + a passing negative control), a both-bundles locale-completeness test over the phase's new msg:// keys, and a structural no-DDL assertion (no phase-named changelog, no phase <include>, no forbidden @Table token) — none pinning a brittle global count."

requirements-completed: [TEST-19, OBS-01, OBS-02, OBS-04]

# Metrics
duration: ~40min
completed: 2026-05-12
---

# Phase 15 Plan 05: Cross-Cutting Tests + Folded-Todo Move Summary

**Closes Phase 15 with the cross-cutting safety/structural tests + the folded-todo bookkeeping: (1) `ObservabilityLeakTest` (TEST-19, D-09) reuses the Phase 9 `TOOL_NAME_LEAK` / `HOST_PREFIX_LEAK` pattern packs VERBATIM against BOTH the `TurnDetailRenderer` mapper output (every `ActivityKind` + neutral + {TOOL,RETRIEVAL} step rows incl. an `ERROR`/`COMMIT_FAILED` outcome + a null-latency row + the `MessageFormat` summary) AND a REAL rendered `ChatPanelFragment`'s `<span class="ai-agent-status">` text + an actual `Details` step-row inside the `.ai-agent-turn-activity` block, with a passing negative control that routes `find_records` / `jmixapp_Customer` through the rendering path and asserts the regexes trip; (2) `ObservabilityMessagesCompletenessTest` — all 15 new Phase-15 `msg://` keys resolve non-blank in `messages_en.properties` AND `messages_vi.properties`; (3) `NoNewPersistedStateTest` (OBS-04) — no Phase-15-named Liquibase changelog file, no Phase-15 `<include file=>` in the master changelogs, no `@Table` name with `TURN`/`ACTIVITY`/`DISCLOSURE` — NOT a hardcoded global `@Entity` count; (4) `AiChatSessionStateTest` extended — `AiChatSessionState` still holds exactly `{currentConversationId, listenerRegistrations}` and `MountedChatSurfaceState` gained only the fixed sidebar fields, no per-turn collection; (5) the `2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui` todo moved to `done/` with a Phase-15 resolution note and `done/_INDEX.md` updated.**

## Performance

- **Duration:** ~40 min
- **Completed:** 2026-05-12
- **Tasks:** 3
- **Files:** 3 created + 1 modified (tests); 1 todo moved + 1 INDEX modified; 1 deferred-items file created

## Final list of Phase-15 `msg://` keys asserted by `ObservabilityMessagesCompletenessTest`

| # | Key | Plan | en | vi |
|---|-----|------|-----|-----|
| 1 | `com.vn.agent.entity/AiChatSurface.SIDEBAR` | 15-01 | `Right sidebar` | `Thanh bên phải` |
| 2 | `chatSurfaceMounter.sidebarToggle.ariaLabel.open` | 15-03 | `Close AI chat sidebar` | `Đóng thanh bên trò chuyện AI` |
| 3 | `chatSurfaceMounter.sidebarToggle.ariaLabel.closed` | 15-03 | `Open AI chat sidebar` | `Mở thanh bên trò chuyện AI` |
| 4 | `chatSurfaceMounter.sidebarCloser.ariaLabel` | 15-03 | `Close AI chat sidebar` | `Đóng thanh bên trò chuyện AI` |
| 5 | `chatView.status.neutral` | 15-04 | `working` | `đang xử lý` |
| 6 | `chatView.status.chat` | 15-04 | `thinking` | `đang suy nghĩ` |
| 7 | `chatView.status.tool` | 15-04 | `searching data` | `đang tìm dữ liệu` |
| 8 | `chatView.status.retrieval` | 15-04 | `retrieving documents` | `đang truy xuất tài liệu` |
| 9 | `chatView.turnDetail.summary` | 15-04 | `what the agent did — {0} steps · {1} ms` | `agent đã làm gì — {0} bước · {1} ms` |
| 10 | `chatView.turnDetail.summaryPending` | 15-04 | `what the agent did` | `agent đã làm gì` |
| 11 | `chatView.turnDetail.step.tool` | 15-04 | `Searched data` | `Đã tìm dữ liệu` |
| 12 | `chatView.turnDetail.step.retrieval` | 15-04 | `Retrieved documents` | `Đã truy xuất tài liệu` |
| 13 | `chatView.turnDetail.step.chat` | 15-04 | `Generated reply` | `Đã tạo phản hồi` |
| 14 | `chatView.turnDetail.errorIndicator` | 15-04 | `(error — rolled back)` | `(lỗi — đã hoàn tác)` |
| 15 | `chatView.turnDetail.unknownDuration` | 15-04 | `—` | `—` |

(The `chatView.status.*` strings deliberately have no trailing `…` — the CSS `::after` animated ellipsis supplies it.)

## How `NoNewPersistedStateTest` asserts the negative (review point #10)

No hardcoded global `@Entity` count. Three independent negatives:

1. **`noPhase15NamedLiquibaseChangelogFileExists`** — walks the `com/vn/agent/liquibase` tree and asserts no `.xml` filename matches `(^|[^0-9])15-|phase-?15|sidebar|observability` (case-insensitive). The agentstore master changelog uses `<includeAll path="/com/vn/agent/liquibase/agentstore-changelog">`, so any new `.xml` dropped in that directory would be auto-included — this catches it.
2. **`rootChangelogsHaveNoPhase15Include`** — reads `agentstore-changelog.xml` (and, when run from the repo root, the `jmix-app` `agentstore-changelog.xml` / `changelog.xml`) and asserts no `<include file=...>` value matches the Phase-15 name pattern.
3. **`noEntityClassDeclaresAPerTurnTableName`** — walks `com/vn/agent/entity/*.java` and asserts no `@Table(name="...")` value contains `TURN` / `ACTIVITY` / `DISCLOSURE` (the belt-and-braces check).

## How the rendered-component leak assertion was wired (review point #9)

`renderedFragmentNeverEmitsInternalNames` builds a `ChatPanelFragment` with a real `VerticalLayout` `messageListSlot` + `MessageList` (mirroring `onReady`), a passthrough `Messages` mock (returns the key), and a `CurrentAuthentication` mock; then it drives the fragment's own helper methods — `showStatus(neutralStatusKey())` → `showStatus(statusKeyFor(TOOL))`, then `appendTurnDetails(runId, [TOOL 42ms, TOOL 7ms errored, RETRIEVAL null-ms])` — the same methods the streaming `doOnNext`/`doOnComplete` wiring calls, which by construction never receive a `@Tool` name or an entity name. It then reads the ACTUAL rendered DOM:

- the real `statusRow` `Element` (asserts `class` contains `ai-agent-status`, then runs both regexes over `Element#getText()`);
- the real `turnActivityBlock` `Div` (asserts `class` contains `ai-agent-turn-activity`), its single child `Details` (regexes over `getSummaryText()`), and every rendered step-row text inside the `Details`' `VerticalLayout` content (regexes over the concatenated child-span text).

This is a real rendered component scan — it does not attach to a live UI (the fragment package has no working `@SpringBootTest`/`@UiTest` boot harness — see 15-04-SUMMARY / STATE "Blockers" — so the test follows the established `ChatPanelFragmentStatusLineTest` / `ChatPanelFragmentTurnDetailTest` plain-JUnit+Mockito precedent; the `accessUi`-wrapped UI mutations are not needed because the helper methods append elements synchronously to the real `Element` tree). The mapper-output scans (`statusLineMapperNeverEmitsInternalNames`, `turnDetailMapperNeverEmitsInternalNames`) keep the cheap exhaustive coverage. The negative control (`negativeControl_routingAToolNameTrips`) proves the regexes are live: `Pattern toolNameRegex()` matches `"I will call find_records next"` (`.find()` true) and `Pattern hostPrefixRegex()` matches `"Searched jmixapp_Customer — 42 ms"` (`.find()` true), and a sanity check confirms the benign labels (`chatView.turnDetail.step.tool`, `…step.retrieval`) trip neither.

## Todo move + INDEX updates — confirmation

- `.planning/todos/pending/2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md` → `.planning/todos/done/2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md`, with a trailing/leading resolution note: *"Resolved by Phase 15 — Right-Sidebar Chat Surface & Observability UX (OBS-01 + OBS-02, decisions D-05..D-08, refined per 15-REVIEWS); shipped 2026-05-12."* plus a one-paragraph summary of how the two surfaces were implemented (grouped `.ai-agent-turn-activity` block, sibling `<span class="ai-agent-status">`, `UnconstrainedDataManager` + mandatory filter, `StreamingEvent.Activity`, no new persistence, `ObservabilityLeakTest` gate).
- `.planning/todos/done/_INDEX.md` — added a row for the todo (`Phase 15 — Right-Sidebar Chat Surface & Observability UX`, OBS-01/OBS-02, decisions D-05..D-08, leak-gated by `ObservabilityLeakTest`, no new persistence — OBS-04); the "still pending" line now reads *"none — the pending queue is empty."*
- There is **no** `.planning/todos/pending/_INDEX.md` in this repo — the plan's `files_modified` listed it, but it does not exist; nothing to update there. (Recorded in the move commit message.)

## Task Commits

1. **Task 1: TEST-19 ObservabilityLeakTest — Phase 9 pattern packs over mapper + rendered fragment** — `0fd8ad8` (test).
2. **Task 2: locale-completeness + no-Phase-15-changelog + state-holders-unchanged tests** — `b4fc205` (test).
3. **Task 3: move folded collapsible-tool-detail/ephemeral-status todo to done/** — `2db9134` (docs).

**Plan metadata:** _(this commit)_ `docs(15-05): complete cross-cutting tests + folded-todo move plan` — this SUMMARY + STATE.md + ROADMAP.md + REQUIREMENTS.md + deferred-items.md.

## Files Created/Modified

- `view/chat/ObservabilityLeakTest.java` (new) — TEST-19; references `com.vn.agent.guard.ToolNamePatternProvider` + `com.vn.agent.guard.HostPrefixPatternProvider` (reused, not forked); mapper-output scans + a real-rendered-fragment scan + a negative control.
- `view/chat/ObservabilityMessagesCompletenessTest.java` (new) — both-bundles non-blank check over the 15 new Phase-15 keys; loads each `Properties` from the classpath with a source-tree fallback.
- `view/chat/NoNewPersistedStateTest.java` (new) — OBS-04 structural negatives (changelog filename scan + `<include file=>` scan + `@Table` token scan).
- `view/chat/AiChatSessionStateTest.java` (modified) — added `sessionStateHoldsExactlyConversationIdAndListeners_noPerTurnAccumulation` and `sidebarOpenStateHolderHasOnlyFixedFields_noPerTurnCollection` (reflective field-set + collection-field checks).
- `.planning/todos/done/2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md` (new — moved from `pending/`) + `.planning/todos/done/_INDEX.md` (modified).
- `.planning/phases/15-right-sidebar-chat-surface-observability-ux/deferred-items.md` (new) — records the pre-existing `:jmix-app:test` PostgreSQL-dependency failures (see "Deviations").

## Decisions Made

See the `key-decisions` frontmatter — the plain-JUnit precedent over `@UiTest` (documented fragment-boot blocker), the mocked-`Metadata` `HostPrefixPatternProvider` construction (mirrors `HostPrefixLeakScannerTest.providerFor`), the three-negatives `NoNewPersistedStateTest` shape (no global `@Entity` count — 15-REVIEWS #10), and the absence of `.planning/todos/pending/_INDEX.md`.

## Deviations from Plan

### Test-shape adjustment (within plan intent, not a Rule deviation)

The plan calls `ObservabilityLeakTest` an `@UiTest` + `@SpringBootTest` test. As in Plan 15-04 (and 15-03), the fragment package has no working `@UiTest`/`@SpringBootTest` boot harness (a documented pre-existing Spring-context boot regression — see 15-04-SUMMARY and STATE "Blockers"; v1.2 plans are explicitly told to "prefer XML/source-scan or pure-Mockito tests for UI/contract coverage where the boot context is implicated"). `ObservabilityLeakTest` follows that established precedent: plain JUnit 5 + Mockito + reflective field injection, exercising the fragment's `showStatus` / `appendTurnDetails` against real Vaadin `Element`/`Details`/`VerticalLayout` components and scanning the ACTUAL rendered DOM text. All the plan's acceptance criteria are met: the mapper-output status scan over all `ActivityKind` values + neutral; the mapper-output disclosure scan over {TOOL, RETRIEVAL} step rows incl. an `ERROR`/`COMMIT_FAILED` outcome + a null-latency row + the summary; the RENDERED-COMPONENT scan over the actual `<span.ai-agent-status>` text + an actual `Details` step-row inside the `.ai-agent-turn-activity` block; and the negative control asserting the regexes DO match a deliberately-routed `find_records` / `jmixapp_Customer`. The `HostPrefixPatternProvider` is constructed via a mocked `Metadata` (mirrors `HostPrefixLeakScannerTest.providerFor`) since no `@SpringBootTest` is available to autowire the real bean — this is one of the two construction options the plan's `<interfaces>` block explicitly allows.

### Out-of-scope, environment-only: `:jmix-app:test` requires a running PostgreSQL

`./gradlew :jmix-app:test` fails in this environment with `org.postgresql.util.PSQLException: The connection attempt failed` (creating `agentstoreLiquibase` / `agentstoreEntityManagerFactory`), and the dependent `@SpringBootTest`s then trip the `ApplicationContext failure threshold (1) exceeded` short-circuit — 24 tests, 13 failed, all the same root cause. This is **not caused by Phase 15** (Phase 15's changes are test-only and live entirely in the `:ai-agent:ai-agent` module, which runs on HSQLDB / no DB — `./gradlew :ai-agent:ai-agent:test` is fully green, including the four plan test classes). It is the project's standing DB-dependent-integration-test situation: `jmix-app`'s integration tests need a live PostgreSQL `agentstore` datasource, which is not provisioned here. Logged to `.planning/phases/15-.../deferred-items.md`; no code change required from Phase 15. (SCOPE BOUNDARY — pre-existing failure in an unrelated module.)

No other deviations — no scope creep, no architectural change, no Rule 4 checkpoint. `git status` is clean after all three commits; no change under `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/`; no new file under `**/liquibase/**`.

## Issues Encountered

- The `:jmix-app:test` PostgreSQL dependency (above) — pre-existing, environment-only, logged to `deferred-items.md`.

## Verification Performed

- `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ObservabilityLeakTest"` — green.
- `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ObservabilityMessagesCompletenessTest" --tests "com.vn.agent.view.chat.NoNewPersistedStateTest" --tests "com.vn.agent.view.chat.AiChatSessionStateTest"` — green.
- `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ObservabilityLeakTest" --tests "com.vn.agent.view.chat.ObservabilityMessagesCompletenessTest" --tests "com.vn.agent.view.chat.NoNewPersistedStateTest" --tests "com.vn.agent.view.chat.AiChatSessionStateTest"` — green (all four plan test classes together).
- `./gradlew :ai-agent:ai-agent:test` — green (full add-on module — no regression in the existing chat / guard / surface / fragment tests).
- `./gradlew :jmix-app:test` — FAILED with the pre-existing PostgreSQL-connection root cause (above); unrelated to Phase 15's test-only changes; logged to `deferred-items.md`.
- `test -f .planning/todos/done/2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md && ! test -f .planning/todos/pending/2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md` — prints `MOVED`.
- `git status --short` — clean after all three task commits + the docs/deferred files staged for the metadata commit.

## User Setup Required

None — no external service configuration; no new dependency. (To confirm `:jmix-app:test` green, run it against the project's documented PostgreSQL dev DB.)

## Next Phase Readiness

- Phase 15 is complete: `SIDEBAR` chat surface (15-01/15-03), `StreamingEvent.Activity` edge emit (15-02), in-fragment status line + per-turn tool-detail disclosure (15-04), and the cross-cutting leak/locale/no-DDL tests + the folded-todo move (15-05). TEST-19 is met as a genuine UI-layer leak gate.
- The in-fragment status-row pattern + this UI-layer pattern-pack-reuse leak-test pattern are established for Phase 20's STT error/retry row.
- No blockers introduced. (Standing blocker: the `@UiTest`/`@SpringBootTest` fragment boot harness — and the `:jmix-app:test` PostgreSQL dependency — remain pre-existing environmental items, unchanged by this plan.)

## Threat Flags

None — no new security surface. This plan adds tests + `.planning/` bookkeeping only. The leak test IS a threat mitigation (T-15-E1: reuses the Phase 9 `TOOL_NAME_LEAK` / `HOST_PREFIX_LEAK` regexes against the rendering path's mapper output AND a real rendered component, with a passing negative control); `NoNewPersistedStateTest` + the extended `AiChatSessionStateTest` lock T-15-E2 (no new persisted state, no per-turn accumulation); `ObservabilityMessagesCompletenessTest` locks T-15-E3 (no single-locale label leak). No production code touched.

## Self-Check: PASSED

- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ObservabilityLeakTest.java` — FOUND (references `ToolNamePatternProvider` + `HostPrefixPatternProvider`)
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ObservabilityMessagesCompletenessTest.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/NoNewPersistedStateTest.java` — FOUND (contains `NoNewPersistedStateTest`)
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/AiChatSessionStateTest.java` — FOUND (extended)
- `.planning/todos/done/2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md` — FOUND; `pending/` copy — GONE
- `.planning/todos/done/_INDEX.md` — FOUND (updated)
- `.planning/phases/15-right-sidebar-chat-surface-observability-ux/15-05-SUMMARY.md` — FOUND (this file)
- Commit `0fd8ad8` (Task 1) — FOUND
- Commit `b4fc205` (Task 2) — FOUND
- Commit `2db9134` (Task 3) — FOUND

---
*Phase: 15-right-sidebar-chat-surface-observability-ux*
*Completed: 2026-05-12*
