# Phase 7: Flow UI - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in `07-CONTEXT.md` — this log preserves the alternatives considered.

**Date:** 2026-04-21
**Phase:** 07-flow-ui
**Areas discussed:** Streaming transport + cancel; ChatView rendering & tool-call cards; Parameters view; KnowledgeBase view; Audit view; Conversation replay; Cross-cutting (package/gating/i18n/host)

---

## Streaming transport + cancel

| Option | Description | Selected |
|--------|-------------|----------|
| Flux + Vaadin Push | ChatService.stream → UI.access; requires @Push | ✓ |
| SSE endpoint + EventSource | Separate REST SSE transport | |
| Blocking only for v1 | No streaming | |

**User's choice:** Flux + Vaadin Push. **Note:** review `jmix-ai-backend` implementation and Vaadin Push / `UI.access()` docs before finalizing.

| Option | Description | Selected |
|--------|-------------|----------|
| Add-on enables @Push globally | AppShellConfigurator or auto-applied | ✓ |
| Host opts in via docs | Documented snippet | |
| Per-view manual UI.access | No @Push | |

| Option | Description | Selected |
|--------|-------------|----------|
| Reuse CancellationRegistry keyed by runId | Extend Phase 5 registry | ✓ |
| New per-conversation token | ChatCancellationRegistry parallel | |
| Dispose Flux locally in view | View-only Disposable | |

| Option | Description | Selected |
|--------|-------------|----------|
| Graceful fallback to blocking | Detect non-streaming, render single update | ✓ |
| Always route via stream | Accept single chunk | |
| Parameters profile flag | Explicit streaming boolean | |

---

## ChatView rendering & tool-call cards

| Option | Description | Selected |
|--------|-------------|----------|
| Plain VerticalLayout + Scroller | Bubble composition | ✓ |
| VirtualList for long histories | DataProvider over AiMessage | |
| Match jmix-ai-backend shape | Defer to reference | |

| Option | Description | Selected |
|--------|-------------|----------|
| Server-side Flexmark → HTML + Vaadin Html | Safe, streaming-friendly | ✓ |
| Client-side markdown Web Component | marked.js/markdown-it | |
| Plain text only | No markdown | |

| Option | Description | Selected |
|--------|-------------|----------|
| Collapsed badge → expand on click | Inline chip with details | ✓ |
| Always-expanded card inline | Full card per tool call | |
| Side panel tool trace | Right-side drawer | |

| Option | Description | Selected |
|--------|-------------|----------|
| Inline [n] markers → dialog | Numbered superscripts | ✓ |
| Footer list below answer | Plain source list | |
| Sidebar panel | Right-side citations | |

---

## Parameters view (YAML editor + profile switch)

**First pass rejected by user.** User clarification: hybrid Form (source of truth) + read-only YAML Preview tab. Regenerate YAML from form; no parallel editing in v1.

Re-asked with that anchor:

| Option | Description | Selected |
|--------|-------------|----------|
| Form covers ALL AiParametersBody fields | Full parity | ✓ |
| Common-only; advanced stays YAML-hidden | Reduced form | |
| Researcher decides field set | Defer | |

| Option | Description | Selected |
|--------|-------------|----------|
| Live on every form change | Instant preview | ✓ |
| On Save only | Stale while editing | |
| On tab switch | Lazy | |

| Option | Description | Selected |
|--------|-------------|----------|
| Per-field inline + block Save | Jmix validators | ✓ |
| On Save only, dialog summary | Batched errors | |
| Inline hard + soft warnings on Save | Hybrid | |

| Option | Description | Selected |
|--------|-------------|----------|
| List row action + detail button; immediate commit | Dual entry points | ✓ |
| List row only; immediate | Single entry | |
| Both + confirm dialog | Safer but friction | |

---

## KnowledgeBase view — upload + status refresh

| Option | Description | Selected |
|--------|-------------|----------|
| Multi-file Vaadin Upload, one ingest per file | Vaadin raw | ✓ (user clarification: use **Jmix Flow UI `<upload>`** not raw Vaadin Upload) |
| Single-file Upload | One at a time | |
| Drop-zone + button, multi-file | Styled variant | |

**User's choice:** Option 1 with **Jmix Flow UI `<upload>` component** (not raw Vaadin). Multi-file + drag-and-drop + progress come out of the box from Jmix. One async ingest per file; PENDING row per file immediately.

| Option | Description | Selected |
|--------|-------------|----------|
| Vaadin Broadcaster push from IngesterManager | True push | ✓ (user clarification: **Vaadin Push + UI.access driven by document-status-layer events**, implementation detail of event source deferred to researcher) |
| UI.setPollInterval every 3–5 sec | Polling | |
| Manual refresh button only | No auto | |

| Option | Description | Selected |
|--------|-------------|----------|
| Vaadin Badge variants per status | PENDING/READY/FAILED | ✓ |
| Plain text status | Minimal | |
| Icon + label | Visual | |

| Option | Description | Selected |
|--------|-------------|----------|
| Row action + confirm dialog | Row-level with confirm | ✓ |
| Row action, no confirm | Fast | |
| Bulk toolbar action only | Multi-row | |

---

## Audit view — filter + CSV export

| Option | Description | Selected |
|--------|-------------|----------|
| Typed filter bar + Jmix GenericFilter | Best of both | ✓ |
| Jmix GenericFilter only | Generic | |
| Typed filter bar only | Limited | |

| Option | Description | Selected |
|--------|-------------|----------|
| Jmix GridExportAction / gridexport add-on | Standard add-on | ✓ |
| Custom streaming CSV via Downloader | Manual | |
| Server-side scheduled export | Overkill | |

| Option | Description | Selected |
|--------|-------------|----------|
| Dialog with full details | Modal in list context | ✓ |
| Navigate to audit detail view | Standard Jmix | |
| Inline expand | Compact | |

| Option | Description | Selected |
|--------|-------------|----------|
| Badge with variant per outcome | Lumo variants | ✓ |
| Colored text / icon | Lighter | |
| Plain enum text | Minimum | |

---

## Conversation replay layout + admin-view-all

| Option | Description | Selected |
|--------|-------------|----------|
| Single scrollable transcript | ChatView components read-only | ✓ |
| Master-detail: list + pane | Scan long histories | |
| Timeline with collapsible turns | Custom UI | |

| Option | Description | Selected |
|--------|-------------|----------|
| Same ConversationListView, role-aware filter | One view class | ✓ |
| Separate admin menu entry | Two near-identical views | |
| Role-aware toggle in same view | Mine/All segmented | |

| Option | Description | Selected |
|--------|-------------|----------|
| Same collapsed-badge → expand | Reuse ChatView component | ✓ |
| Always-expanded cards in replay | Divergent | |
| Link to AuditListView filtered | Cross-link | |

| Option | Description | Selected |
|--------|-------------|----------|
| Read-only replay, no resume | Strictly historical | |
| 'Continue in chat' button | Opens ChatView at conversationId | ✓ |
| Decide in Phase 8 | Defer | |

---

## Cross-cutting

| Option | Description | Selected |
|--------|-------------|----------|
| com.vn.agent.view.{chat,conversation,parameters,knowledge,audit} | Subpackage per feature | ✓ |
| Flat com.vn.agent.view.* | Prefixed names | |
| Match jmix-ai-backend | Defer | |

| Option | Description | Selected |
|--------|-------------|----------|
| ResourceRole policies + menu visibility | AiAgentAdminRole @ViewPolicy + @MenuPolicy + @ViewAccessChecker belt-and-suspenders | ✓ |
| Menu-only gating | Fragile | |
| @ViewAccessChecker only | Bad UX | |

**User's choice:** Option 1 **plus architectural direction** — one shared chat backend + one reusable chat panel component; v1 ships full ChatView route, defer floating launcher + admin-configurable toggle to v2. Plan the chat bubble/transcript as a reusable Jmix Fragment.

| Option | Description | Selected |
|--------|-------------|----------|
| 100% en + vi parity, zero hardcoded | Build-time coverage check | ✓ |
| en complete, vi best-effort | Risks UI-09 | |
| en only in v1 | Conflicts ROADMAP | |

| Option | Description | Selected |
|--------|-------------|----------|
| Smoke-test only (bootRun + click through) | No host changes | ✓ |
| Add seeded Customer/Order Playwright scenario | Expands scope | |
| Defer Playwright, manual only | Weaker validation | |

---

## Claude's Discretion

See D-01..D-31 "Claude's Discretion" subsection in `07-CONTEXT.md`. Main areas where the planner has judgment:

- Fragment boundaries for reusable chat panel
- Flexmark config + sanitizer
- Exact Lumo variant mapping
- AppShellConfigurator contribution mechanism (class vs. docs)
- Package name for the chat-panel Fragment
- Ingestion event emission mechanism (ApplicationEventPublisher vs. Broadcaster)

## Deferred Ideas

- Floating / embeddable user chat launcher — v2
- Admin-configurable chat-surface toggle — v2
- Mutation tools UI — post-v1
- Scheduled / async CSV export — revisit on volume
- Cross-conversation search — post-v1
- VirtualList for very long histories — revisit if scroll perf degrades
