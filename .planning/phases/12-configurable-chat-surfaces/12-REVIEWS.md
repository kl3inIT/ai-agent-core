---
phase: 12
reviewers: [codex]
reviewed_at: 2026-05-02T03:08:54.7674443+07:00
plans_reviewed:
  - .planning/phases/12-configurable-chat-surfaces/12-01-PLAN.md
  - .planning/phases/12-configurable-chat-surfaces/12-02-PLAN.md
  - .planning/phases/12-configurable-chat-surfaces/12-03-PLAN.md
  - .planning/phases/12-configurable-chat-surfaces/12-04-PLAN.md
  - .planning/phases/12-configurable-chat-surfaces/12-05-PLAN.md
  - .planning/phases/12-configurable-chat-surfaces/12-06-PLAN.md
---

# Cross-AI Plan Review - Phase 12

## Codex Review

### Summary

The replanned Phase 12 is much stronger than the stale three-surface version. The six-plan sequence is coherent, dependency-ordered, and mostly aligned with the locked `FULL_ROUTE` + `HEADER_BUTTON` scope. The plans show good discipline around Jmix-first UI, AccessManager gating, no raw Vaadin launcher, session/UI state separation, and TEST-14 coverage. I count **3 current-cycle HIGH concerns** that should be fixed before execution convergence.

### Strengths

- Plans correctly amend `ROADMAP.md` / `REQUIREMENTS.md` first in `12-01`, reducing risk of accidentally building `SIDEBAR`, `FLOATING`, P-21, or compact-mode work.
- The wave ordering is sensible: settings foundation -> admin UI/session state -> dialog/mounter/title work -> verification.
- `12-04` explicitly tests the riskiest runtime assumption: whether the dialog survives route navigation.
- Security posture is mostly sound: settings admin view is role-gated, header button uses `UiShowViewContext`, and `AiUiSettings` is excluded from LLM metadata.
- `12-05` handles auto-title as backend async work with no-clobber, bounded prompt context, audit, and fail-silent behavior.
- `12-06` is a real closeout gate, not just a checklist; it includes continuity, settings, title, i18n, and UAT coverage.

### Concerns

- **HIGH - `12-01` / `12-02`: `enabledSurfaces` text storage may still look like an unsupported enum collection.** The plan says persist a text column but expose `getEnabledSurfaces()` / `setEnabledSurfaces(Set<AiChatSurface>)` on the entity. If the field/property name is also `enabledSurfaces`, Jmix metadata or JavaBean inspection can treat it as a collection-valued entity property, which is exactly what the plan is trying to avoid. Use a persisted string like `enabledSurfaceIds` plus typed helpers such as `getEnabledSurfaceSet()` / `setEnabledSurfaceSet(...)`, and keep the checkbox field controller-managed.
- **HIGH - `12-05`: bounded async executor/autoconfig is not planned as an artifact.** Context D-15 requires `@EnableAsync` plus a sized `TaskExecutor`. `12-05` adds `@Async` title generation but does not list `AIConfiguration` or a new async configuration file, and the acceptance criteria do not enforce a bounded executor. Add an explicit `aiAgentTitleExecutor` configuration or prove an existing bounded executor is used via `@Async("...")`.
- **HIGH - `12-06`: TEST-14 has an escape hatch that may weaken the JDBC memory requirement.** The requirement says verify the same conversation id and JDBC memory rows. The plan allows falling back to `AiMessage + conversationId` as a proxy. That proves UI persistence but not necessarily Spring AI JDBC memory continuity. Prefer querying the Spring AI chat memory repository/table directly. If that is truly impossible, amend the requirement explicitly instead of letting the test silently weaken it.
- **MEDIUM - `12-01`: singleton creation race is not covered.** `AiUiSettingsService.loadCurrent()` can race on first concurrent UI init. Add a transaction/retry path: on duplicate key or optimistic insert conflict, reload the singleton.
- **MEDIUM - locale bundle names are inconsistent across artifacts.** Plans use `messages_en.properties` + `messages_vi.properties`, while some project context references `messages.properties` + `messages_vi.properties`. Verify actual bundle names and update every plan consistently.
- **MEDIUM - `AiUiSettings` audit fields are treated as optional in `12-01`.** Context says audit fields are part of the entity. Make them explicit in entity and Liquibase acceptance criteria.
- **MEDIUM - `12-03`: listener registry may retain UI references across tabs.** The design needs clear unregister-on-detach and UI-detach cleanup. Tests should cover detached UI callbacks being ignored, not just listener removal.
- **MEDIUM - `12-05`: manual title edit authorization is underspecified.** The plan says use secured `DataManager` "if" the user owns/can access the conversation. Add an explicit non-admin chat-user test proving title edit works only for the user's own conversation.
- **LOW - `12-05`: hidden `attachmentsPanel` split may affect current layout.** A 68/32 split with a hidden right panel could still alter sizing depending on component behavior. Add a focused UI/layout assertion or keep the split dormant until Phase 13 if it causes width regressions.

### Suggestions

- Rename the persisted settings field to `enabledSurfaceIds`; reserve `enabledSurfaces` for view-model/controller state only.
- Add `AiAgentTitleAsyncConfiguration` or update existing config with a bounded `ThreadPoolTaskExecutor`, then use `@Async("aiAgentTitleExecutor")`.
- Make TEST-14 directly assert Spring AI JDBC memory continuity, or formally narrow the requirement before implementation.
- Add singleton race handling to `AiUiSettingsService.loadCurrent()`.
- Add a non-admin title-edit test and a detached-UI listener cleanup test.
- Normalize message bundle filenames in all six plans before execution.

### Risk Assessment

**Overall risk: MEDIUM.** The architecture is sound and the plan now targets the correct two-surface scope. The remaining risks are concentrated in implementation details that can cause runtime breakage: Jmix metadata mapping for `enabledSurfaces`, async executor behavior, and whether TEST-14 truly proves JDBC memory continuity. Fixing the 3 HIGH concerns should make the phase execution plan low-to-medium risk.

---

## Consensus Summary

Only the Codex reviewer was invoked for this cycle because the requested workflow flags were `--phase 12 --codex`.

### Agreed Strengths

- The plan set now follows the locked two-surface scope and explicitly removes the stale sidebar/floating-launcher work.
- The wave dependency structure is coherent and places foundation, admin security, runtime surfaces, title behavior, and verification in a sensible order.
- Security-sensitive pieces are mostly identified: admin policies, view/menu gates, `UiShowViewContext`, LLM metadata exclusion, no-clobber title saves, and route disabling.

### Agreed Concerns

- The entity storage design for enabled surfaces must avoid exposing a Jmix collection-valued enum property by accident.
- Auto-title async execution needs an explicit bounded executor/configuration artifact.
- TEST-14 must directly prove Spring AI JDBC memory continuity, or the requirement must be deliberately narrowed.

### Divergent Views

- None. This cycle used one requested reviewer.
