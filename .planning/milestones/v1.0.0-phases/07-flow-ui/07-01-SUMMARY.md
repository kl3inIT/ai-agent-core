---
phase: 07-flow-ui
plan: 01
subsystem: flow-ui-foundation
tags: [jmix, flowui, vaadin, push, markdown, i18n, security-role, phase7-wave1]

requires:
  - phase: 02-foundations
    provides: AiAgentAdminRole interface (extended here), AiKnowledgeDocumentStatus enum (consumed by DocumentStatusChangedEvent)
  - phase: 03-metadata-first-runtime-six-tools
    provides: AiToolCallOutcome enum (consumed by StreamingEvent.ToolResult)
provides:
  - MarkdownRenderer bean (thread-safe Flexmark + OWASP sanitizer) for ChatView and ConversationDetailView
  - StreamingEvent sealed interface contract for 07-02 backend ↔ 07-03 ChatView bridge
  - AiAgentAppShell (@Push WEBSOCKET_XHR, opt-out property) enabling Vaadin Push for chat stream + KB status fan-out
  - DocumentStatusChangedEvent record (wired by 07-02, consumed by 07-06 KnowledgeBaseView)
  - AiAgentAdminRole.adminViews() with @MenuPolicy (3 menu ids) + @ViewPolicy (4 view ids) — admin gating for 07-04/07-05/07-06
  - Namespaced menu.xml (5 items: aiAgent.chat, aiAgent.conversations, aiAgent.parameters.list, aiAgent.knowledge.list, aiAgent.audit.list)
  - 114 new bilingual message keys covering every UI-SPEC §Copywriting Contract row (EN + VI parity)
affects: [07-02, 07-03, 07-04, 07-05, 07-06, 07-07b]

tech-stack:
  added:
    - "com.vladsch.flexmark:flexmark:0.64.8 (+ flexmark-ext-tables:0.64.8, flexmark-ext-autolink:0.64.8)"
    - "com.googlecode.owasp-java-html-sanitizer:20220608.1"
    - "io.jmix.gridexport:jmix-gridexport-flowui-starter (BOM-resolved 2.8.0)"
    - "io.jmix.security:jmix-security-flowui-starter (BOM-resolved 2.8.0) — required to resolve @MenuPolicy/@ViewPolicy"
  patterns:
    - "Spring @Component singleton for stateless Flexmark+OWASP pipeline (RESEARCH Standard Stack / Pitfall #5)"
    - "AppShellConfigurator + @ConditionalOnProperty opt-out (RESEARCH Pattern 2, Pitfall #9)"
    - "Jmix role interface extension — one policy-annotated method per concern (role stays a single file)"
    - "Pre-seeded bilingual message bundles to eliminate Wave-2 parallel-write conflicts on messages*.properties"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/MarkdownRenderer.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/StreamingEvent.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/push/AiAgentAppShell.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/push/DocumentStatusChangedEvent.java
  modified:
    - ai-agent/ai-agent/ai-agent.gradle
    - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties

key-decisions:
  - "Flexmark extension artifact is 'flexmark-ext-tables' (NOT 'flexmark-ext-gfm-tables') — the latter is not published to Maven Central. TablesExtension inside flexmark-ext-tables already produces GFM-compatible output."
  - "@MenuPolicy + @ViewPolicy live in package io.jmix.securityflowui.role.annotation (not io.jmix.security.role.annotation). The shipping artifact is io.jmix.security:jmix-security-flowui-starter — added as an explicit implementation dep because jmix-security-starter alone does not pull it."
  - "menu.addon display text switched from Phase-2 placeholder 'Ai-agent' to UI-SPEC 'AI Agent' / 'Trợ lý AI' — this is the first plan that surfaces the add-on to end-users, so brand copy is fixed now."
  - "AppShell opt-out property is matchIfMissing=true (Push ON by default) — the add-on's main value proposition depends on streaming chat and live KB status; turning Push off is the exceptional case."
  - "Verified @Push transport=WEBSOCKET_XHR against RESEARCH §Standard Stack; keeps the Phase 7 client compatible with HTTP load balancers that block persistent WebSockets by falling back to XHR."

metrics:
  duration: ~25m (wall clock across 3 tasks)
  completed: 2026-04-21

requirements-completed: [UI-08, UI-09, UI-10]
---

# Phase 07 Plan 01: Flow UI Foundation Summary

**Wave 1 foundation for Phase 7 — dependency plumbing (Flexmark + OWASP sanitizer + gridexport + security-flowui), shared singletons (MarkdownRenderer, AiAgentAppShell, DocumentStatusChangedEvent, StreamingEvent sealed interface), admin gating (AiAgentAdminRole.adminViews), namespaced menu, and 114 bilingual message keys — unblocks all Wave 2 view plans (07-03..07-06) to run in parallel without sharing files.**

## Performance

- **Duration:** ~25 minutes
- **Tasks:** 3 (all `type="auto"`)
- **Files created:** 4
- **Files modified:** 5
- **Commits:** 3 atomic task commits

## Task Commits

1. **Task 1: Gradle deps + MarkdownRenderer + StreamingEvent DTO** — `500edb5`
2. **Task 2: Push AppShell + DocumentStatusChangedEvent + AiAgentAdminRole.adminViews** — `7823d1f`
3. **Task 3: menu.xml + bilingual messages bundles** — `5313b9a`

## Accomplishments

### Dependency resolution (evidence)

`./gradlew :ai-agent:ai-agent:dependencies --configuration compileClasspath` resolves:

| Dependency | Resolved version | Scope |
|---|---|---|
| `com.vladsch.flexmark:flexmark` | `0.64.8` | implementation |
| `com.vladsch.flexmark:flexmark-ext-tables` | `0.64.8` | implementation |
| `com.vladsch.flexmark:flexmark-ext-autolink` | `0.64.8` | implementation |
| `com.googlecode.owasp-java-html-sanitizer` | `20220608.1` | implementation |
| `io.jmix.gridexport:jmix-gridexport-flowui-starter` | `2.8.0` (BOM) | implementation |
| `io.jmix.security:jmix-security-flowui-starter` | `2.8.0` (BOM) | implementation |

### MarkdownRenderer

- Spring `@Component` singleton.
- Fields: `private final Parser parser`, `private final HtmlRenderer renderer`, `private final PolicyFactory sanitizer` — all initialized in the constructor from `MutableDataSet` with `TablesExtension.create()` + `AutolinkExtension.create()`.
- `sanitizer = Sanitizers.FORMATTING.and(BLOCKS).and(LINKS).and(TABLES)` (RESEARCH §Standard Stack line 448–451).
- `toSafeHtml(String)` returns `""` for null/empty input, otherwise parse → render → sanitize.

### StreamingEvent

- `public sealed interface StreamingEvent permits StreamingEvent.{Content, ToolCall, ToolResult, Citation, Final, Error}` — 6 nested records with signatures verbatim from RESEARCH §Example 1.

### AiAgentAppShell

- `@Push(transport = Transport.WEBSOCKET_XHR)` + `@ConditionalOnProperty(name = "jmix.ai-agent.flowui.push-autoconfigure", havingValue = "true", matchIfMissing = true)` + `implements AppShellConfigurator`.
- **Open question for bootRun verification (A2):** whether Vaadin's classpath scan for `AppShellConfigurator` honours Spring conditionality. If the host app declares its own shell AND leaves `push-autoconfigure=true`, a two-shell collision could still surface at `bootRun`. Documented in the class Javadoc and the 07-01 decisions; follow-up is manual human-verify in Phase 7 wrap-up.

### DocumentStatusChangedEvent

- `public record DocumentStatusChangedEvent(UUID documentId, AiKnowledgeDocumentStatus status, String errorMessage) {}` — `errorMessage` non-null only when `status == FAILED`.

### AiAgentAdminRole

- Existing `adminAccess()` + 5 `@EntityPolicy` annotations preserved verbatim.
- New `adminViews()` method with `@MenuPolicy(menuIds = {aiAgent.parameters.list, aiAgent.knowledge.list, aiAgent.audit.list})` + `@ViewPolicy(viewIds = {AiAgent_Parameters.list, AiAgent_Parameters.detail, AiAgent_KnowledgeBase.list, AiAgent_ToolCallAudit.list})`.

### menu.xml

- 5 items under `<menu id="AI" opened="true">`: `aiAgent.chat`, `aiAgent.conversations`, `aiAgent.parameters.list`, `aiAgent.knowledge.list`, `aiAgent.audit.list` — the three admin-gated ids match `AiAgentAdminRole.@MenuPolicy` byte-for-byte.

### Message bundles (parity evidence)

- **Total keys added per bundle:** 114 new Phase-7 keys in each of `messages.properties` and `messages_vi.properties`.
- **Coverage breakdown:**
  - Global menu: 5 (the `menu.addon` display value also updated to UI-SPEC brand copy).
  - ChatView: 22
  - ConversationList/Detail: 10
  - ParametersList/Detail: 23
  - KnowledgeBase: 23
  - AuditList: 27 (≥ 25 required by plan; captures every row of UI-SPEC table, including empty-state + detail subkeys).
  - Bundle total line count: 236 lines each.
- **Parity diff result:** `diff <(keys-of messages.properties | sort -u) <(keys-of messages_vi.properties | sort -u)` yields exactly one line of mismatch — the `localeDisplayName.{en,vi}` self-identifier — which is by design (each bundle marks its own locale). The LocaleParityTest in 07-07b MUST exclude `localeDisplayName\..*` from the parity comparison; this matches the pattern already in use for other Jmix add-ons.

## Verification

| Command | Result |
|---|---|
| `./gradlew :ai-agent:ai-agent:compileJava` | BUILD SUCCESSFUL |
| `./gradlew :ai-agent:ai-agent:processResources` | BUILD SUCCESSFUL |
| `./gradlew :ai-agent:ai-agent:compileTestJava` | BUILD SUCCESSFUL — Wave 0 RED skeletons still compile |
| `grep -c "toSafeHtml" .../MarkdownRenderer.java` | 1 |
| `grep -c "sealed interface StreamingEvent" .../StreamingEvent.java` | 1 |
| `grep -c "@Push(transport = Transport.WEBSOCKET_XHR)" .../AiAgentAppShell.java` | 1 |
| `grep -c "record DocumentStatusChangedEvent" .../DocumentStatusChangedEvent.java` | 1 |
| `grep -c "adminViews" .../AiAgentAdminRole.java` | 2 (annotation block + method decl) |
| `grep -c "adminAccess" .../AiAgentAdminRole.java` | 1 (preserved) |
| `grep -c "^aiAgent\\." menu.xml` | 5 (all 5 item ids present) |
| `grep -c "^chatView\\.title=" messages.properties` | 1 |
| `grep -c "^chatView\\.title=" messages_vi.properties` | 1 |
| `grep -c "^auditList\\." messages.properties` | 27 (≥ 25) |

## Decisions Made

1. **Flexmark artifact correction** — `flexmark-ext-tables` not `flexmark-ext-gfm-tables`. Verified via `search.maven.org` — the latter artifact is not published for any flexmark 0.64.x release. `TablesExtension` inside `flexmark-ext-tables` produces GFM-compatible pipe tables out of the box.
2. **@MenuPolicy / @ViewPolicy package** — Jmix 2.8 ships these annotations in `io.jmix.securityflowui.role.annotation` (inside `jmix-security-flowui` jar), NOT in `io.jmix.security.role.annotation` as the plan assumed. Added `io.jmix.security:jmix-security-flowui-starter` as an explicit implementation dep.
3. **AppShell opt-out default** — `matchIfMissing = true`. Push is the happy path for Phase 7; hosts opt OUT rather than opting in.
4. **menu.addon text** — aligned to UI-SPEC brand copy "AI Agent" / "Trợ lý AI"; previous Phase-2 placeholder "Ai-agent" replaced.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 — Bug] Flexmark tables extension artifact name**
- **Found during:** Task 1 `compileJava` (Could not find com.vladsch.flexmark:flexmark-ext-gfm-tables:0.64.8).
- **Issue:** Plan specified `com.vladsch.flexmark:flexmark-ext-gfm-tables:0.64.8`; that artifact does not exist in Maven Central.
- **Fix:** Changed to `com.vladsch.flexmark:flexmark-ext-tables:0.64.8` — the actual home of `TablesExtension`, which handles GFM tables by default.
- **Files modified:** `ai-agent/ai-agent/ai-agent.gradle`
- **Commit:** `500edb5`

**2. [Rule 1 — Bug] @MenuPolicy / @ViewPolicy package path**
- **Found during:** Task 2 `compileJava` (package io.jmix.security.role.annotation does not exist for MenuPolicy/ViewPolicy).
- **Issue:** Plan specified `io.jmix.security.role.annotation.{MenuPolicy, ViewPolicy}`; those annotations live in `io.jmix.securityflowui.role.annotation` (Jmix 2.8).
- **Fix:** Updated imports in `AiAgentAdminRole.java`.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java`
- **Commit:** `7823d1f`

**3. [Rule 3 — Blocking missing dep] jmix-security-flowui-starter**
- **Found during:** Task 2 `compileJava` after fixing deviation #2 (package io.jmix.securityflowui.role.annotation does not exist).
- **Issue:** The `jmix-security-flowui` jar that ships `@MenuPolicy`/`@ViewPolicy` is NOT pulled transitively by `jmix-security-starter`; it is a separate add-on starter.
- **Fix:** Added `implementation 'io.jmix.security:jmix-security-flowui-starter'` under a Plan-07-01 comment in `ai-agent.gradle` (BOM resolves to 2.8.0).
- **Files modified:** `ai-agent/ai-agent/ai-agent.gradle`
- **Commit:** `7823d1f`

### Intentional scope additions

- **menu.addon copy update** — changed from Phase-2 placeholder "Ai-agent" to UI-SPEC "AI Agent" / "Trợ lý AI". This is the Phase that first surfaces the add-on to end users.

## Authentication Gates

None.

## Deferred / Follow-up

- **Human bootRun verification (A2):** confirm whether `@ConditionalOnProperty` actually prevents Vaadin from classpath-scanning `AiAgentAppShell` when `jmix.ai-agent.flowui.push-autoconfigure=false`. If not, the fallback is to delete `AiAgentAppShell` entirely and require host apps to carry the `@Push` annotation on their own shell (pattern documented in RESEARCH Open Q#3 and the class Javadoc).
- **LocaleParityTest implementation (07-07b):** must exclude `localeDisplayName\..*` from the key-parity comparison — this is a structural Jmix/Spring Boot bundle convention, not a bug.
- **07-07b Task 0 grep audit** — `grep -r "07-07b" ai-agent/ai-agent/src/test` must return zero after 07-07b rewrites the RED skeletons.

## Threat Flags

None — every new surface is covered by the plan's `<threat_model>` (T-07-01 through T-07-04). Mitigations applied:

- **T-07-01 (Markdown XSS)** — OWASP PolicyFactory composed exactly as spec'd (FORMATTING+BLOCKS+LINKS+TABLES) in `MarkdownRenderer`. Unit tests in 07-07b/`MarkdownRendererXssTest` will assert `<script>` / `javascript:` URIs / `data:` URIs / `on*` handlers all stripped.
- **T-07-02 (two-AppShell collision)** — `@ConditionalOnProperty` + `matchIfMissing=true` documented opt-out `jmix.ai-agent.flowui.push-autoconfigure=false`. Fallback documented in class Javadoc if Vaadin ignores Spring conditionality.
- **T-07-03 (bundle info disclosure)** — accepted; bundle content is user-facing copy only.
- **T-07-04 (admin gating elevation)** — `@MenuPolicy` menu ids + `@ViewPolicy` view ids match menu.xml entries byte-for-byte. 07-07b/`AdminViewAccessTest` will assert denials against non-admin users.

## Self-Check: PASSED

Artifacts verified (`[ -f ... ]` + `git log`):

- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/MarkdownRenderer.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/StreamingEvent.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/push/AiAgentAppShell.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/push/DocumentStatusChangedEvent.java
- FOUND (modified): ai-agent/ai-agent/ai-agent.gradle
- FOUND (modified): ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
- FOUND (modified): ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml
- FOUND (modified): ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
- FOUND (modified): ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
- FOUND commit: 500edb5 (Task 1 — deps + MarkdownRenderer + StreamingEvent)
- FOUND commit: 7823d1f (Task 2 — AppShell + DocumentStatusChangedEvent + AdminRole.adminViews)
- FOUND commit: 5313b9a (Task 3 — menu.xml + bilingual bundles)

Build gates:

- `./gradlew :ai-agent:ai-agent:compileJava` → BUILD SUCCESSFUL
- `./gradlew :ai-agent:ai-agent:processResources` → BUILD SUCCESSFUL
- `./gradlew :ai-agent:ai-agent:compileTestJava` → BUILD SUCCESSFUL (Wave 0 RED skeletons compile against updated bundles + new classes)

---
*Phase: 07-flow-ui*
*Completed: 2026-04-21*
