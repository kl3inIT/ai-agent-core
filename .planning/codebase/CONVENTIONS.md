# Coding Conventions

**Analysis Date:** 2026-06-04

Scope: the **Jmix AI Agent addon** at `ai-agent/ai-agent/` (Java 21, Jmix 2.8, Spring Boot 3, Vaadin Flow, Spring AI 1.1.4). Base package `com.vn.agent`. The sibling `jmix-app/` is a consumer harness and is secondary.

## Naming Patterns

**Files / Classes:**
- Full PascalCase nouns, no abbreviations. Spell identifiers out completely — `MutationCommitCoordinator`, `RelatedWriteMetadataResolver`, `AgentSystemPromptRulesComposer`. Abbreviated identifiers (`uei`, `mc`, `dt`) are forbidden across the codebase (enterprise convention).
- Entities prefixed `Ai` (e.g. `AiAuditEvent`, `AiConversation`, `AiKnowledgeDocument`, `AiUiSettings`). Jmix entity-name uses the `ai_` prefix: `@Entity(name = "ai_AiAuditEvent")`.
- Enums are first-class entity-package types: `AiToolCallOutcome`, `AiMessageRole`, `AiKnowledgeDocumentStatus`.
- Spring `@ConfigurationProperties` POJOs end in `Properties` (`AiAgentMutationProperties`, `AiAgentRagProperties`, `AiAgentTitleProperties`).

**Functions:**
- camelCase verbs. Gate/orchestration methods read as the security step they perform: `enforceRole`, `resolve`, `authorize`, `reserve`, `coerce`, `guard`, `save`, `finalize`.
- Resolver read-through methods spell out the target: `resolveTaskFileMaxFileSizeBytes`, `resolveRagUploadMaxFileSizeBytes` — distinct methods over a flags-style single method.

**Tools (LLM-facing):** `@Tool(name = ...)` uses snake_case (`create_record`, `update_record`, `add_related_record`, `bulk_save_records`, `list_entities`, `find_records`).

## Code Style

**Formatting:**
- 4-space indent, no Lombok anywhere on entities (forbidden). Javadoc is heavy and load-bearing — comments cite plan IDs (`Plan 11-07C`, `MUT-15`), decision IDs (`D-09`, `D-14`), and review tags (`R-03e`, `WR-001`). Keep this convention: comments explain *why* and which contract a line defends.
- Java 21 features in use: text blocks for `@Tool` descriptions, records for DTOs (`AiParametersBody`), pattern-matching `instanceof` (`bean instanceof DataSource delegate`).

**Compiler:** `-parameters` enabled and `options.release = 21` (build.gradle root `JavaCompile` config). Parameter names must survive compilation — Spring AI `@ToolParam` binding and Jmix rely on them.

**Linting:** No checkstyle / spotless / ArchUnit configured. ArchUnit was explicitly dropped in Phase 2 and is NOT a dependency. Structural rules are enforced by pure-JUnit source/reflection invariant tests instead (see TESTING.md). Use JetBrains MCP `get_file_problems` for per-file checks.

## Import Organization

1. Third-party (`io.jmix.*`, `org.springframework.*`, `org.springframework.ai.*`)
2. Jakarta (`jakarta.persistence.*`, `jakarta.validation.*`)
3. `java.*` / `java.util.*`
4. Static imports last (`static org.assertj.core.api.Assertions.assertThat`, `static org.mockito.Mockito.mock`)

No path aliases (Java). Wildcard imports appear for `jakarta.persistence.*` on entities.

## Jmix Conventions (MANDATORY)

These are the house Jmix rules and override generic Spring/JPA habits:

- **Entities:** `@JmixEntity` + `@Entity(name="ai_X")` + `@Table` + UUID `@Id @JmixGeneratedValue` + `Integer @Version` + `@InstanceName`. Agentstore-backed entities add `@Store(name = "agentstore")` (`AiAuditEvent`, `AiConversation`, `AiMessage`, `AiKnowledgeDocument`, `AiParameters`, `AiUiSettings`). No Lombok. Parent-child aggregates use `@Composition` + `@OneToMany(cascade=ALL, orphanRemoval=true)`; FK deletes use `@OnDelete(DeletePolicy.CASCADE)`.
- **Instantiation:** never call entity constructors directly. Use `Metadata.create(...)` or `DataManager.create(...)`.
- **Data access:** use `DataManager` / `UnconstrainedDataManager` fluent API, never `EntityManager`. Constrained loads (`dataManager.load(class).id(...)` / `.ids(...)` / `.all().list()`) for user-facing reads so row-level policies apply; `UnconstrainedDataManager` ONLY for system-internal writes (audit, seeding, ingestion) — the system user is itself policy-gated under `jmix-security-data`. For raw-JPQL `loadValue/loadValues` against agentstore entities, you MUST `.store("agentstore")` — the store is not inferred.
- **Injection:**
  - Services: constructor injection only.
  - Views (`@ViewController` + `@ViewDescriptor`): `@ViewComponent` for XML-declared components/containers/loaders; `@Autowired` for Spring beans (`DataManager`, `Messages`, `DialogWindows`). Example: `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java`.
  - Inject `io.jmix.core.Messages` (not Spring `MessageSource`) in views — `@NonNull`, auto-locale.
- **i18n:** all UI text via `msg://` keys. Every key MUST exist in ALL locale files: `messages_en.properties` AND `messages_vi.properties` under `src/main/resources/com/vn/agent/`. Parity is test-enforced (`i18n/I18nParityTest`, `i18n/LocaleParityTest`).
- **UI:** Jmix XML view descriptors + Jmix components first; raw/programmatic Vaadin only with explicit justification. Wire events via `@Subscribe` / `@Install`, not manual listeners in `onInit`.

## Mutation & Security Conventions (CRITICAL)

The mutation tool surface (`com.vn.agent.tools.mutation`) encodes the strictest house rules:

- **Single transactional boundary:** `MutationSaveExecutor.save` is the ONLY `@Transactional` method. Orchestrators (`BuiltInMutationTools`, `MutationGateChain`, `MutationCommitCoordinator`) are NEVER `@Transactional` — a self-invoked `@Transactional` method on the same bean is silently bypassed by Spring proxying. This is asserted by reflection in `MutationToolInvariantsTest`.
- **Fail-closed gate order:** canonical sequence is `enforceRole → resolve → authorize → reserve → coerce → guard → save → finalize`. Every authorization gate must throw BEFORE the save delegate (`mutationSaveExecutor.`) crosses the transaction boundary. Source-order is structurally enforced in `MutationToolInvariantsTest`.
- **Audit isolation:** `auditWriter.writeToolCall(` may appear in exactly one place — `MutationCommitCoordinator.safeWriteAudit`. `BuiltInMutationTools` must hold no `AuditWriter` reference (thin orchestration bean). Enforced by source-scan test.
- **Rich `@Tool` descriptions:** mutation/data tools use the 5-section text-block format — `MANDATORY WORKFLOW` / `INPUT CONTRACT` / `FORMATS` / `ERROR` / `STRICTNESS + EXAMPLES` (~50–150 lines). Correctness over token cost. See `BuiltInMutationTools.java`.
- **`@ToolParam` typing:** declare concrete types — `Map<String,Object>`, `List<...>`, `String`. NEVER `@ToolParam Object` (Spring AI 1.x mis-binds it).
- **KB chunk metadata:** post-ingest permission/source-entity changes require reingest; never mutate pgvector chunk metadata directly.

## Memoization House Pattern

Plain `java.util.concurrent.ConcurrentHashMap` for in-process memoization/state. NO Caffeine, NO `@Cacheable` in `main`. Confirmed: zero `Caffeine`/`@Cacheable` references in `src/main`. Examples:
- `orchestration/StreamingSinkHolder.java`
- `rag/CancellationRegistry.java`
- `tools/mutation/RelatedWriteMetadataResolver.java`

(Spring Cache starter is on the classpath, but only for guard rate-limit / token-budget caches via `ConcurrentMapCacheManager` — not for general memoization.)

## Error Handling

- Mutation errors funnel through `MutationErrorTranslator` (host exceptions → LLM-safe, redacted messages). Outcomes are typed (`AiToolCallOutcome`: `SUCCESS`, `COMMIT_FAILED`, ...).
- Captions must be truthful: `COMMIT_FAILED` is worded "Commit outcome unknown" / "Chưa rõ kết quả commit" — never "database commit failed" (the host save returned; only finalization is unknown). Wording is locale-parity test-enforced.
- Guard SPIs throw to abort (fail-closed); leak scanners (`ToolNameLeakScanner`, `HostPrefixLeakScanner`, `ToolNavigationLeakScanner`) strip internal identifiers from output.

## Logging

SLF4J via Spring Boot. No `System.out`. Secrets are redacted before logging/persisting (`SecretRedactionInvariantsTest` enforces this).

## Function & Module Design

- Business logic in services, never in views.
- Thin orchestration beans delegate to single-responsibility collaborators (gate chain, save executor, commit coordinator, error translator) rather than monolithic methods.
- `@ConfigurationProperties` records expose tunable knobs; `AiUiSettingsResolver` provides DB-column-wins read-through over property defaults (null column falls through to the property value).

---

*Convention analysis: 2026-06-04*
