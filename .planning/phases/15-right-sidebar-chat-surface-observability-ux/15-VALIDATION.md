---
phase: 15
slug: right-sidebar-chat-surface-observability-ux
status: draft
nyquist_compliant: true
wave_0_complete: false
created: 2026-05-11
---

# Phase 15 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (Jupiter) + AssertJ + Mockito; Jmix `@UiTest` (`io.jmix.flowui.testassist`) for Flow-UI component tests; `@SpringBootTest(classes = {AITestConfiguration.class, FlowuiTestAssistConfiguration.class})` + `@ImportAutoConfiguration({AIAutoConfiguration.class, SpiDefaultsAutoConfiguration.class})` for chat-flow / leak-pattern tests |
| **Config file** | `ai-agent/ai-agent/build.gradle` (test source set) — no separate test config file |
| **Quick run command** | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.*" --tests "com.vn.agent.view.chat.fragment.*" --tests "com.vn.agent.view.uisettings.*" --tests "com.vn.agent.orchestration.*" --tests "com.vn.agent.guard.*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~3–8 min for the add-on module; ~10–15 min for the whole composite |

---

## Sampling Rate

- **After every task commit:** `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.*" --tests "com.vn.agent.view.chat.fragment.*" --tests "com.vn.agent.view.uisettings.*" --tests "com.vn.agent.orchestration.*" --tests "com.vn.agent.guard.*"`
- **After every plan wave:** `./gradlew :ai-agent:ai-agent:test` (full add-on module — catches the sealed-`switch` exhaustiveness fallout and any chat-flow regression)
- **Before `/gsd-verify-work`:** `./gradlew test` (whole composite) must be green
- **Max feedback latency:** ~8 min (quick run); ~15 min (full)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 15-01-01 | 01 | 1 | SURF-11, OBS-04 | T-15-A1 / — | Enum value rendered via msg:// label (not a tool/entity name); no DDL | unit | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.entity.AiUiSettingsModelTest"` | ✅ extend | ⬜ pending |
| 15-01-02 | 01 | 1 | SURF-11 | T-15-A3 / — | SIDEBAR selectable in admin controls; existing admin-view security unchanged | `@UiTest` | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.uisettings.AiUiSettingsDetailViewTest"` | ✅ extend | ⬜ pending |
| 15-02-01 | 02 | 1 | OBS-01, OBS-04 | T-15-B1 / — | Closed `ActivityKind` enum (structural no-leak); compiler-forced switch arm | unit | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.RenderStreamEventTest" --tests "com.vn.agent.view.chat.RenderStreamEventIntentPayloadTest"` | ✅ extend | ⬜ pending |
| 15-02-02 | 02 | 1 | OBS-01, OBS-04 | T-15-B2, T-15-B3, T-15-B4 | Best-effort emit (try/catch RuntimeException); correct `runId`; persisted audit shape unchanged | unit | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.orchestration.StreamingActivityEventTest" --tests "com.vn.agent.audit.*" --tests "com.vn.agent.rag.advisor.*"` | ❌ W0 (`StreamingActivityEventTest`) | ⬜ pending |
| 15-03-01 | 03 | 2 | SURF-11 | T-15-C1, T-15-C4, T-15-C5 | Mount gated by `AccessManager` + enabled-surface; panel on UI element (survives nav); state per-UI | `@UiTest` | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ChatSurfaceMounterTest" --tests "com.vn.agent.view.chat.ChatDialogViewTest" --tests "com.vn.agent.view.chat.ChatPanelFragmentSurfaceSwitchTest"` | ✅ extend (+ new cases) | ⬜ pending |
| 15-03-02 | 03 | 2 | SURF-11 | T-15-C3 / — | CSS-only shell docking (justified per D-01); no user free-text | build | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ChatSurfaceMounterTest"` | n/a (CSS) | ⬜ pending |
| 15-04-01 | 04 | 3 | OBS-01, OBS-02 | T-15-D1 / — | `TurnDetailRenderer` closed input alphabet (label-only; no tool/entity name param) | unit | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.fragment.TurnDetailRendererTest"` | ❌ W0 (`TurnDetailRendererTest`) | ⬜ pending |
| 15-04-02 | 04 | 3 | OBS-01 | T-15-D1, T-15-D2 | Status `<span>` sibling of message list (never in bubble); HTML-escaped `setText`; removed on terminal | `@UiTest` | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.fragment.ChatPanelFragmentStatusLineTest"` | ❌ W0 (`ChatPanelFragmentStatusLineTest`) | ⬜ pending |
| 15-04-03 | 04 | 3 | OBS-02, OBS-04 | T-15-D1, T-15-D3, T-15-D4, T-15-D5 | Label-only `Details`; bounded per-fragment step list; memoized row-level-access-respecting on-expand `AiAuditEvent` read; no new persisted state | `@UiTest` + audit fixture | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.fragment.ChatPanelFragmentTurnDetailTest" --tests "com.vn.agent.view.chat.fragment.ChatPanelFragmentTurnDetailHistoryTest"` | ❌ W0 (`ChatPanelFragmentTurnDetailTest`, `ChatPanelFragmentTurnDetailHistoryTest`) | ⬜ pending |
| 15-05-01 | 05 | 4 | TEST-19 | T-15-E1 / leak gate | Phase 9 leak regexes find no internal name in rendered status line + disclosure; negative control trips | `@UiTest` / `@SpringBootTest` | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ObservabilityLeakTest"` | ❌ W0 (`ObservabilityLeakTest`) | ⬜ pending |
| 15-05-02 | 05 | 4 | OBS-01, OBS-02, OBS-04 | T-15-E2, T-15-E3 | Locale completeness (en+vi); no new `@Entity`/changelog; `AiChatSessionState` unchanged | unit | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ObservabilityMessagesCompletenessTest" --tests "com.vn.agent.view.chat.NoNewPersistedStateTest" --tests "com.vn.agent.view.chat.AiChatSessionStateTest"` | ❌ W0 (`ObservabilityMessagesCompletenessTest`, `NoNewPersistedStateTest`) / ✅ extend (`AiChatSessionStateTest`) | ⬜ pending |
| 15-05-03 | 05 | 4 | OBS-04 (bookkeeping) | T-15-E4 / — | n/a — `.planning/` todo move | structural | `test -f .planning/todos/done/2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md && ! test -f .planning/todos/pending/2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md && echo MOVED` | n/a | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

The following new test files must be created as part of their owning plan task (each is a `tdd="true"` task — write the test first / alongside). They are the Nyquist scaffolds for this phase's new behaviors; no separate Wave-0-only plan is needed because every code-producing task here also creates its test in the same task.

- [ ] `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/StreamingActivityEventTest.java` — Plan 02 Task 2 (OBS-01: Activity emit sites are best-effort and push the right `ActivityKind` onto the sink)
- [ ] `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/TurnDetailRendererTest.java` — Plan 04 Task 1 (OBS-01/OBS-02/TEST-19 support: the `kind → msg://` mapper's label-only contract)
- [ ] `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentStatusLineTest.java` — Plan 04 Task 2 (OBS-01: status `<span>` lifecycle + never-in-bubble)
- [ ] `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentTurnDetailTest.java` — Plan 04 Task 3 (OBS-02/OBS-04: ≥1-step `Details`, 0-step omission, bounded/cleared step list)
- [ ] `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/ChatPanelFragmentTurnDetailHistoryTest.java` — Plan 04 Task 3 (OBS-02: lazy memoized `AiAuditEvent`-by-`runId` re-read respecting row-level access)
- [ ] `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ObservabilityLeakTest.java` — Plan 05 Task 1 (TEST-19)
- [ ] `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ObservabilityMessagesCompletenessTest.java` — Plan 05 Task 2 (OBS-01/OBS-02 locale coverage)
- [ ] `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/NoNewPersistedStateTest.java` — Plan 05 Task 2 (OBS-04)
- [ ] Existing-test extensions (tracked, not Wave-0 gaps): `AiUiSettingsModelTest`, `AiUiSettingsDetailViewTest`, `RenderStreamEventTest`, `ChatSurfaceMounterTest`, `ChatDialogViewTest`, `ChatPanelFragmentSurfaceSwitchTest`, `AiChatSessionStateTest`.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| The SIDEBAR panel is visually non-modal (the main view stays clickable, no modality curtain) and reflows the content via the push class on desktop | SURF-11 | Visual layout / interaction quality is not fully assertable in `@UiTest` (CSS rendering, push reflow, no-curtain) | App on http://localhost:8088 (do NOT auto-start `bootRun`): enable SIDEBAR in the admin UI-settings view; click the far-right PANEL navbar button; verify a right panel opens, the main view stays clickable, the content reflows (no overlap), the toggle shows an active state; navigate to another route — panel stays open, main view interactive; click the in-panel closer — panel collapses; shrink the window below ~768px — panel becomes a full-screen overlay; disable SIDEBAR — no panel, no toggle |
| The ephemeral status line animates subtly and the disclosure layout reads well | OBS-01, OBS-02 | Animation timing / visual polish (the design-conscious owner may revisit width/breakpoint/animation) | Same app: send a chat message that triggers a tool call + RAG; verify a centered "searching data…" / "retrieving documents…" line shows during streaming and disappears when the answer lands (and the answer bubble has no leftover status prose); verify a collapsed "what the agent did — N steps · total ms" disclosure appears, expands to readable label-only rows with per-step ms; verify a chat-only turn shows no disclosure |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies — every task has an `<automated>` command; the new test files are created within their owning `tdd="true"` task
- [x] Sampling continuity: no 3 consecutive tasks without automated verify — every task verifies
- [x] Wave 0 covers all MISSING references — the 8 new test files above; existing-test extensions tracked
- [x] No watch-mode flags — all `./gradlew test` invocations are one-shot
- [ ] Feedback latency < target — ~8 min quick / ~15 min full (acceptable for a Gradle/Vaadin module)
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
