# Agent Instructions

Instructions for AI coding agents working in the **ai-agent-core** repository.
This document pairs with the Jmix skills installed under `.claude/skills/` —
READ the matching skill (see **Skill routing**) before writing any Jmix artifact.

> Claude Code reads this as `CLAUDE.md`; Codex / OpenCode read it as `AGENTS.md`.
> Keep the two files in sync — they are the same canonical content.

## Project stack

- Java 21
- Jmix 2.8, Spring Boot 3, Vaadin Flow (Vaadin 24)
- Gradle composite build
- Relational database with Liquibase migrations. The add-on adds a second
  `agentstore` datasource (pgvector for knowledge-base embeddings).

## Repository layout

Composite Gradle build = one add-on + one demo host:

- `ai-agent/` — the Jmix **AI-agent add-on** (the product). Owns the agent
  harness, tools, knowledge base, audit, and chat UI. Frame it as a Jmix "AI
  agent" / "agent harness" — never a "copilot".
- `dth-crm/` — the **demo host** that consumes the add-on (sole demo host;
  `jmix-app` was dropped). Has its own jmix-crm-style AI surface.

The composite substitutes the add-on locally, so add-on changes are picked up
without publishing.

## Build & run

- Local dev app runs at **http://localhost:8088** (login `admin/admin`) and is
  usually already running — do NOT auto-start a server.
- Tests: `./gradlew test`, module-scoped `./gradlew :ai-agent:ai-agent:test`,
  or `./gradlew test --tests "com.vn.agent.SomeTest"`.
- NEVER use `bootRun` (or any non-terminating server start) as a verification
  gate — it does not exit and will hang your turn.

## Step 0 — map the task to artifacts, READ the matching skill BEFORE writing

The most common cause of defects is writing a Jmix artifact from memory instead
of from the rule that governs it. Your Jmix/Vaadin priors are the single biggest
source of wrong API names and broken descriptors.

Before writing a single file:

1. List every artifact the task implies — entities, enums, list views, detail
   views, composition children, services, event listeners, resource roles,
   changelogs, menu entries, message bundles.
2. For EACH artifact, READ the matching skill in **Skill routing** before you
   write it.
3. Only then start writing.

The verification skills (`jmix-ide-static-analysis`, `jmix-verify-bootrun`) are
gates, not how-to. They do not replace the artifact skill.

## Tooling — MCP first, universal floor always

This environment ships MCP servers — a Jmix-aware IDE inspection (JetBrains
`get_file_problems`), Context7 (`/jmix-framework/jmix-context7`), Playwright for
the browser, and `postgresmcp` for DB inspection/query. **When a server is
connected, it is your PRIMARY check — reach for it first.** For database
inspection/query prefer `postgresmcp` over JetBrains. ANY server may be absent;
when one is, do NOT skip the check — fall back to the universal floor:
`compileJava`, `./gradlew test`, and the mechanical-floor commands in
`jmix-ide-static-analysis`.

## Gates before declaring a task done

A task is NOT done after the code compiles. Three gates, in order; never assert
a gate passed without showing the evidence. At each gate use the MCP tool if it
is connected (primary); fall back to the universal check only when it is not.

| Gate | Primary — MCP, if connected | Fallback — always available |
|------|------|------|
| 1 API & static | verify EVERY Jmix/Vaadin symbol via **Context7** (`/jmix-framework/jmix-context7`) before you type it, AND run the IDE inspection (**`get_file_problems`**) on every file you wrote — for `*-view.xml` it is the only static catch for unresolved `msg://`, invalid property paths, and missing data containers | `compileJava` + the mechanical-floor commands in `jmix-ide-static-analysis` |
| 2 Context loads | *(no MCP substitute — always run the fallback)* | `./gradlew --no-daemon clean test` — boots the Spring/Jmix context, runs Liquibase + project tests, then EXITS |
| 3 Render | render-walk every view/button/field with the **browser tool** (Playwright) — confirm no error overlay, server exception, or raw `msg://` caption | no universal substitute — run the mechanical checks (the render-defect floor), then state `render not browser-verified` |

NEVER use `bootRun` (or any non-terminating server start) as the Gate-2 check —
it does not exit and will hang your turn. Gate 2 is `clean test`. The app is
usually already running at port 8088 for render-walks; if you DO start one
yourself, run it in the background and poll `/actuator/health` until UP before
driving the browser, then shut it down cleanly.

`compileJava` is BLIND to XML descriptors. Every `*-view.xml` defect — a
reference/enum field bound wrong, a broken `itemsQuery`, an action opening a
view id that does not exist (`NoSuchViewException`) — compiles perfectly clean.
A green `clean test` is necessary but NEVER sufficient: the context-load tests
boot the Spring/Jmix context but do NOT open your new views or exercise your
new roles.

Emit the evidence in your completion report. Per file you touched: its
static-check verdict. Per view/button/field you created: how you verified it
(inspection, mechanical check, or render walk). "BUILD SUCCESSFUL, all done"
with no per-file check and no render evidence is a non-answer.

## Anti-hallucination — verify a symbol before you type it

Inventing plausible-looking API names is a top failure mode: they survive
typing but blow up at compile or runtime. Before you type any Jmix/Vaadin
symbol not already used in this project's `src/`, verify it — Context7 is your
PRIMARY check when connected, else an IDE symbol search, else grep this project
for a working example. (If the exact symbol is already used in `src/`, copy
that call site.) High-frequency wrong→right traps are catalogued in
`jmix-verify-api-symbol`.

## Skill routing

READ the most specific skill for each artifact:

- Verify a Jmix/Vaadin API: `jmix-verify-api-symbol`
- Static checks / inspections / mechanical floor: `jmix-ide-static-analysis`
- Gate-2 context-load test (+ optional Gate-3 render walk): `jmix-verify-bootrun`
- Persistent entity: `jmix-create-entity`
- Enum used by an entity: `jmix-create-enum`
- List view: `jmix-create-list-view`
- Detail view: `jmix-create-detail-view`
- Parent-child composition editing (property-bound container, NO query loader): `jmix-create-composition-detail-view`
- Service-layer business logic: `jmix-create-service`
- Detail dialog from a button/action, OR master-row selection → filtered child grid: `jmix-add-dialog-detail-flow`
- Entity lifecycle/event business logic: `jmix-add-entity-event-listener`
- Database schema: `jmix-create-liquibase-changelog`
- Resource roles: `jmix-create-resource-role`
- User-visible text / entity-enum captions: `jmix-add-i18n-keys`
- Tests: `jmix-create-test`
- Fetch plans / unfetched-reference / N+1 tuning: `jmix-configure-fetch-plan`
- DTO / non-persistent UI-bound model: `jmix-create-dto-entity`
- Reusable Flow UI fragment: `jmix-create-fragment`

## Cross-cutting checklist for a new entity / view

For each new persistent entity, run through: `jmix-create-entity` +
`jmix-create-liquibase-changelog` + `jmix-create-resource-role` +
`jmix-add-i18n-keys`. For a user-facing entity, also add a list and/or detail
view (`jmix-create-list-view`, `jmix-create-detail-view`) and a view policy in
every role that can open them — **including dialog-only detail views opened
from a composition table**.

Service- or listener-level defaulting does NOT relieve the entity from
defaulting required fields on initial persist — defaults must work through
`DataManager.create()` + `DataManager.save()` directly (tests bypass the view
layer). See `jmix-create-entity`.

## When tests fail — it is almost never "pre-existing"

If the project ships a passing test suite and a test goes red after your change,
assume you broke it. A red `clean test` means the task is not done; investigate
before declaring a red gate "pre-existing." Common causes:

- **`NoSuchViewException` after you added views** → you broke the VIEW REGISTRY;
  it scans all `@ViewController` classes at startup and one broken view poisons
  navigation to EVERY view, including pre-existing ones. Check, in order: (1) every new
  view `.java` has a `package` line matching its directory — a class in the
  default package registers its `@Route`/`@ViewController` wrong; (2) no two
  `@ViewController(id=…)` share an id; (3) every `@ViewDescriptor` path resolves
  to a real XML next to the class; (4) no `*-view.xml` is empty/malformed — an
  empty descriptor throws `SAXParseException: Premature end of file` and poisons
  the registry.
- **`MetaClass not found for class X`** → the entity is missing `@JmixEntity`, or
  its package is outside the application scan root.
- **`ConstraintViolationException` on save** → a `@NotNull` persistent field has
  no value on the `DataManager` path (see `jmix-create-entity`).

Fix the cause, re-run `clean test` until green. A test that goes red and you
cannot explain is a blocker, never a footnote in your "done" summary.

## File-write trap

Always pass absolute paths to file-writing tools; in this nested composite
layout (`ai-agent/ai-agent/...`, `dth-crm/...`) the working directory may not be
what you assume. After a batch of writes, `ls` the path you intended AND confirm
each file is NON-EMPTY — a tool that silently writes a 0-byte file leaves a
defect that compile and `clean test` will NOT catch (an empty role class drops
all its policies; an empty `*-view.xml` poisons the view registry). If a file is
missing or empty, find and rewrite it; do NOT `rm -rf` to "clean up".

Never edit generated frontend files (`frontend/generated/`) — they are
regenerated on every build.

## Data access — `agentstore` specifics

The add-on's `Ai*` entities live in the `agentstore` datasource, which changes
two defaults:

- **`UnconstrainedDataManager` for system-internal writes under
  `jmix-security-data`.** Audit, seed, and ingestion writes use
  `UnconstrainedDataManager` — NOT `runWithSystem`. The "system" user is still
  policy-gated by Jmix security; only `UnconstrainedDataManager` truly bypasses
  entity policies for trusted system code.
- **Raw-JPQL `loadValue` / `loadValues` needs explicit `.store("agentstore")`.**
  Unlike fluent `dataManager.load(EntityClass)`, the raw-JPQL paths do NOT infer
  the store from the entity name. Required for `AiAuditEvent`, `AiConversation`,
  `AiMessage`, `AiKnowledgeDocument`, `AiParameters`, `AiExposureRule`. Forgetting
  `.store(...)` silently routes the query to `main` and returns empty results.
- **KB chunk metadata changes via reingest.** Post-ingest changes to
  `AiKnowledgeDocument` permission or `sourceEntityName` fields MUST trigger a
  reingest — never mutate pgvector chunk metadata directly. Use
  `KnowledgeDocumentService.updatePermissionsAndReingest` (or equivalent) so
  chunk metadata is rebuilt from the canonical document state.

## Project conventions (standing user feedback)

Distilled standing preferences for this project. Apply by default; deviate only
with explicit justification.

### Architecture & Security

- **AI is just another Jmix client.** Run under the current user's Jmix security
  context. Rely on `AccessManager` / `DataManager` for entity-, attribute-, and
  row-level control. Do NOT introduce an AI-specific exposure layer (e.g.
  `AiExposureRule`, `EntityExposurePolicy` SPI, `ExposureRuleListView`) unless a
  real use case surfaces that native Jmix security cannot express.
- **Reuse Jmix built-ins over parallel layers.** Before building any metadata,
  security, or query layer for the AI tool surface, check `Metadata`,
  `AccessManager`, `DataManager`, `FetchPlan`, `MetadataTools` first. The only
  pieces the add-on should own are the thin LLM-facing bits Jmix doesn't provide:
  1. Schema shaping for tools (what the LLM sees)
  2. Strict literal coercion (type safety at the prompt boundary)
  3. Path-depth governance (traversal limits)
  4. Result-size limits (context budget)
  5. Prompt-injection-safe result formatting
  "Ai*" DTOs are justified only for the LLM tool-surface shape.
- **Pragmatic module structure.** Keep module count minimal. Defer splits (e.g.
  headless/UI separation) until a named consumer or concrete requirement
  justifies it. "Future flexibility" is not sufficient justification.
- **SPI contributors only for app-specific behavior.** Baseline runtime context
  (current user, roles, locale, conversation id) is built into the add-on —
  contributors do NOT receive it as input. Reserve SPIs for domain-specific
  instructions, external-system context, or business rules the add-on cannot
  know in advance.
- **No ArchUnit (yet).** Enforce architectural constraints via code review and
  regular tests. Revisit only if the rule set grows or drift appears. The same
  bar applies to other static-enforcement tooling.
- **Curated multi-vendor model catalog.** The add-on mixes Qwen (offline) +
  Claude (online via OpenRouter); host-app deployment, not the add-on, gates
  reachability. Default model: `qwen/qwen3.6-35b-a3b`.

### Code Style

- **No abbreviated identifiers.** Spell names fully: `userEditableIndex`,
  `metaClass`, `metaProperty`, `datatype` — never `uei`, `mc`, `mp`, `dt`, `ctx`.
  Short loop vars (`i`, `e` in catch) remain fine.
- **No `Object`-typed `@ToolParam`.** Spring AI 1.x mis-binds `@ToolParam Object`;
  declare the concrete type (`Map<String,Object>`, `List<...>`, `String`).
- **Rich tool descriptions for enterprise tools.** Mutation/data `@Tool`
  descriptions use the 5-section MANDATORY / INPUT / FORMATS / ERROR /
  STRICTNESS+EXAMPLES shape (~50–150 lines); correctness > token cost.
- **New built-in `@Tool` needs an allowlist entry.** A new `@Tool` not in the
  active `AiParameters.enabledTools` is filtered out; if the prompt names it the
  run crashes with "No ToolCallback found". Keep `enabledTools` null or add the
  tool (check the live DB profile, not yaml).

### Jmix UI — Single Review Gate

**1. Jmix Flow UI first, raw Vaadin last.**
- XML view descriptors are the default for layout, fields, actions, data
  containers/loaders, dialogs, filters, bindings.
- Prefer Jmix components: `dataGrid`, `formLayout`, `tabSheet`, `upload`,
  `genericFilter`, `DialogWindows`/`Dialogs`, `ViewNavigators`, standard
  list/detail actions, role-based view/menu policies.
- Java view controllers are for orchestration, event handling, validation, and
  small glue — not for layout trees expressible in XML.
- Raw Vaadin or programmatic Java UI only when Jmix has no equivalent; the
  deviation must be explicitly justified in the PR/commit message.

**2. Event wiring via Jmix's official event types — never invent event class names.**
- Buttons: `@Subscribe("buttonId")` with `ClickEvent<Button>`, or bind the
  button to a declared `<action>` + `@Subscribe("componentId.actionId")` /
  `@Install`.
- Data loaders/containers: `@Install(to = "loaderId", subject = "loadDelegate")`,
  `@Subscribe(id = "containerId", target = Target.DATA_CONTAINER)` for
  `ItemChangeEvent`, `ItemPropertyChangeEvent`, `CollectionChangeEvent`.
- View lifecycle: `@Subscribe` on official events listed by Jmix Studio's
  Handlers panel (e.g. `InitEvent`, `BeforeShowEvent`, `ReadyEvent`,
  `BeforeCloseEvent`, `AfterCloseEvent`, `AttachEvent`, `DetachEvent`,
  `QueryParametersChangeEvent`).
- Fields: `@Subscribe("fieldId")` with `ComponentValueChangeEvent` /
  `TypedValueChangeEvent`.
- Renderers/validators/delegates: `@Supply` / `@Install` — not programmatic
  registration.
- Do NOT use raw Vaadin `addClickListener(...)` / `addValueChangeListener(...)`
  in controllers when `@Subscribe` can express the same wiring — raw listeners
  bypass the view lifecycle and DI.

**3. Resolving uncertainty (component choice, XML syntax, event type, pattern) — lookup order:**
1. **Jmix Studio Handlers panel** for the target component/view (canonical
   source of truth for "what events exist").
2. **Context7** — query `jmix-framework/jmix-context7` with a specific question
   (e.g. "Jmix Button handlers", "Jmix dataGrid selection event", "Jmix
   CollectionLoader events").
3. **Official Jmix docs** at `docs.jmix.io`.
4. **GitHub**: `jmix-framework/jmix`, `jmix-framework/jmix-samples`, and
   `jmix-ai-backend` for real usages.
5. `jmix-ai-backend` for analogous screens (chat, parameters, knowledge-base,
   audit, dialogs, navigation, data loading) — generalize patterns, don't copy
   domain details blindly.

Never guess annotation parameters, event class names, or XML element names from
memory. If you can't cite where the event type is documented, you haven't
verified it yet.

### Jmix UI — Concrete patterns (must-follow)

These are the specific patterns that have repeatedly tripped up implementations.
Apply by default; deviation requires explicit justification.

**DataGrid column renderers.** Use `@Supply(to = "grid.col", subject = "renderer")`
paired with `UiComponents.create(...)` — NOT programmatic
`getColumnByKey().setRenderer()` in `onInit`. `@Supply` integrates with the view
lifecycle; programmatic registration in `onInit` runs before the metamodel binds
and silently no-ops on some columns.

**Per-row action columns.** XML `<column key="actions">` + `@Supply` renderer →
`DataGridRenderers.buildActionsColumn(...)` with `EnumSet<ActionColumnType>`.
Reuse built-in list actions by calling `grid.select(row)` then `.execute()` on
the action — do NOT duplicate action logic into ad-hoc click listeners.

**Row-action buttons (toolbar buttons enabled when a row is selected).** Declare
a `list_itemTracking` action inside `<dataGrid>` and bind the button via
`action="grid.actionId"`. Never `addSelectionListener` + `setEnabled` by hand —
that bypasses the action lifecycle and breaks keyboard/screen-reader contracts.

**No `property=` on dual-typed grid columns.** When a column is backed by a
String field but exposed via an enum getter (or vice versa), use
`<column key="foo">` + `@Supply` renderer ONLY. Adding `property=` triggers a
metamodel resolution crash that silently empties the grid body — no exception,
no log line, just a blank grid.

**Filter UI.** Use `<genericFilter>` + `<propertyFilter>` children for list-view
filtering — NOT a hand-built `<hbox>` with manual JPQL composition.
`genericFilter` provides type-aware widgets, locale-aware labels, timezone
handling, and parameterized JPQL automatically.

**Data-loader events.** Use `@Subscribe(id = "loaderId", target = Target.DATA_LOADER)`
with the typed event (`PostLoadEvent`, `PreLoadEvent`) — NOT
`loader.addPostLoadListener(...)` in `onInit`. The annotated form participates in
the view lifecycle and survives view re-attachment.

**Query-param reading.** Use `@Subscribe View.QueryParametersChangeEvent` +
`event.getQueryParameters().getSingleParameter(...)` — NOT Vaadin's
`BeforeEnterObserver`. Vaadin's observer fires before Jmix's view binding
completes, so injected components are still null.

**`CollectionContainer` lookup.** Use `container.getItemOrNull(id)` /
`container.getItem(id)` — NOT `container.getItems().stream().filter(...).findFirst()`.
The container indexes by id; the stream form is O(n) and breaks if the
collection is later swapped.

**Upload component — `receiverType` is deprecated under the hood.** Keep
`UploadHandler.toFile(...)` as the receiver. Vaadin 24.8 marks
`Upload.getReceiver` / `setReceiver` `forRemoval` — only `FileRejectedEvent` is
safe to migrate to `@Subscribe`. Do NOT eagerly switch to programmatic
`setReceiver` without a Jmix-side replacement landing first.

**i18n in views — use `io.jmix.core.Messages`, not Spring `MessageSource`.**
Inject `Messages` (`@NonNull`, locale-aware) for any code that feeds
`Notifications`, `Dialogs`, or rendered captions. Spring's `MessageSource`
returns nullable Strings, which trips IntelliJ nullability warnings throughout
the call chain. Keep keys in the **root** message bundle (per-view bundles trip
the IntelliJ Jmix-plugin's stale-index bug); add to BOTH
`messages_en.properties` and `messages_vi.properties`.

### Workflow — JetBrains MCP after Java work

When wrapping up a meaningful chunk of Java work (refactor, feature, multi-file
change, anything you'd consider "done and ready to commit"), run
`mcp__jetbrains__get_file_problems(filePath, onlyErrors=false)` on touched files
in parallel and triage before reporting completion. Not required for trivial
edits (typo, single-line rename, comment).

Triage rules:
- **Fix:** diamond operators, missing `@NonNull` on overrides in `@NonNullApi`
  packages, javadoc errors (e.g. `@link` to private member), `Objects::nonNull`
  over lambda, `List.getLast()` over `list.get(size-1)`, real bugs.
- **Skip:** defensive null checks in belt-and-suspenders guards, `@Tool`/SPI
  methods flagged "never used" (Spring AI reflection), stylistic `if → switch`
  rewrites, condition-always-true/false on intentional contract guards.

If JetBrains MCP is not connected, say so and wait — don't silently skip. Pair
with module-scoped tests (`./gradlew :module:test`) when behavior could plausibly
change.

## Local references

- Additional reference implementation:
  `D:\Study materials spring 2026\EXE101\ai\jmix-ai-backend`.
- OpenRouter access: read `OPENROUTER_API_KEY` from `.env` — do not hardcode
  secrets.
