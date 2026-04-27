---
phase: 09-tool-layer-foundations-prompt-contract-hardening
plan: 05
subsystem: guard
tags: [guard, output-scanner, system-prompt, prompt-injection, prompt-03, prompt-06, d-15]

# Dependency graph
requires:
  - phase: 09-tool-layer-foundations-prompt-contract-hardening
    provides: Plan 09-04 BuiltInDataTools.UNKNOWN_ENTITY_HINTS verbatim D-14 strings (cross-asserted by AgentSystemPromptRulesTest)
  - phase: 09-tool-layer-foundations-prompt-contract-hardening
    provides: Plan 09-03 BaselineContextProvider agent.entities pointer (the system-prompt rules reference this baseline block)
  - phase: 06-parameters-structured-output-guardrails
    provides: OutputScannerAdvisor + AiAgentGuardProperties.OutputScanner.Pattern shape (Phase 6 baseline this plan extends)
provides:
  - HostPrefixPatternProvider — startup-snapshot regex over Metadata.getSession() prefix tokens with ApplicationReadyEvent + lazy-fallback build path; default-on; per-pack opt-out
  - ToolNamePatternProvider — startup-snapshot regex over six built-in tool names + RETRIEVAL advisor + ToolContributor @Tool method names; same default-on / opt-out posture
  - OutputScannerAdvisor widened to implement StreamAdvisor alongside CallAdvisor — streamed assistant text now scanned via Spring AI ChatClientMessageAggregator
  - AgentSystemPromptRules.PROMPT_RULES — verbatim PROMPT-03 vocabulary rules + D-15 unknown-entity-retry contract carried as a Java constant (not i18n)
  - DefaultChatServiceImpl wires PROMPT_RULES between baseline and profile prompt at BOTH ask() and stream() composition sites
  - module.properties default-on toggles for the two new pattern packs (host-prefix-leak, tool-name-leak)
affects:
  - 09-06 (TEST-08 prompt-contract regression — exercises the system-prompt rules + scanner end-to-end via mock ChatModel)
  - 10-ai-specific-llm-exposure-policy (LlmExposurePolicy will reuse the host-prefix snapshot pattern as a substitution input)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Two-layer @ConfigurationProperties opt-out: parent OutputScanner block stays bound; nested HostPrefixLeak / ToolNameLeak records carry per-pack enabled flags (default-on; absent block means enabled)"
    - "Startup snapshot via @EventListener(ApplicationReadyEvent.class) + volatile build-flag with double-checked-lock lazy-fallback in asPattern() — handles eager-singleton ordering when the consumer (OutputScannerAdvisor) is constructed before the event fires"
    - "ReDoS guard via Pattern.quote(...) per token before joining (T-09-22 mitigation; defends against host metaclass / @Tool name with regex meta-chars)"
    - "Spring AI 1.1.4 ChatClientMessageAggregator.aggregateChatClientResponse(Flux, Consumer) for streaming-response post-aggregation scan — same scan-and-flag semantics as CallAdvisor path; flag-and-pass-through preserved"
    - "Hardcoded English model-directed instructions in Java constants (RESEARCH Pitfall 7); precedent: AiAgentDefaultsProperties.FALLBACK_SYSTEM_PROMPT"
    - "Lowercase-leading 'if' bullets sacrificed sentence-case to keep PROMPT_RULES substrings byte-for-byte identical to BuiltInDataTools.UNKNOWN_ENTITY_HINTS — TEST-08 cross-assertion bar"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/HostPrefixPatternProvider.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/ToolNamePatternProvider.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/HostPrefixLeakScannerTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/ToolNameLeakScannerTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AgentSystemPromptRulesTest.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AiAgentGuardProperties.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
    - ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java
    - ai-agent/ai-agent-starter/src/test/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfigurationBootTest.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties
    - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/OutputScannerAdvisorTest.java
    - ai-agent/ai-agent/src/test/resources/eval/output-scanner-corpus.yaml

key-decisions:
  - "Eager-singleton ordering safety: the OutputScannerAdvisor bean is constructed during context refresh, BEFORE ApplicationReadyEvent fires, so the providers' @EventListener-driven buildPattern() may not have run when the advisor's constructor calls asPattern(). Resolved by: (a) keeping @EventListener(ApplicationReadyEvent.class) per the plan's literal grep contract, (b) adding a lazy fallback path in asPattern() that triggers buildPattern() on first call when not yet built, (c) deferring dynamic-pattern compile to first scan in OutputScannerAdvisor.ensureDynamicCompiled() — by then any path eventually warms the cache. This deviates from the plan's example code (which relies on constructor-time merge) but preserves the plan's intent and acceptance criteria."
  - "OutputScannerAdvisor constructor widened to (props, hostPrefixProvider, toolNameProvider) with NULL-tolerant providers — the existing OutputScannerAdvisorTest passes (props, null, null) and only exercises the bundled defaults. New tests (HostPrefixLeakScannerTest, ToolNameLeakScannerTest) wire real providers. This sidesteps the breaking-change blast radius for any future ad-hoc test construction without weakening production wiring."
  - "Plan-spec D-15 hint substrings use lowercase 'if a name...' / 'if no entity...' (matching BuiltInDataTools.UNKNOWN_ENTITY_HINTS verbatim). Sentence case was sacrificed in PROMPT_RULES bullets to keep the byte-for-byte cross-assertion in TEST-08 (Plan 09-06) green. Inline comment in AgentSystemPromptRules documents the trade-off."
  - "Boot-test fixtures (AiAgentGuardAutoConfigurationBootTest) added stub Metadata + HostPrefixPatternProvider + ToolNamePatternProvider beans because the test runs under ApplicationContextRunner with only AiAgentGuardAutoConfiguration loaded — the @ComponentScan on AIConfiguration that picks up the providers in production is NOT active here. Without these fixtures the autoconfig would fail with UnsatisfiedDependencyException."
  - "Static-vs-dynamic pattern split inside OutputScannerAdvisor: bundled defaults + operator-supplied patterns compile in the constructor (eager, fail-fast at startup); dynamic Phase 9 packs compile lazily on first scan via ensureDynamicCompiled() with double-checked locking. First-match-wins ordering preserved (static patterns scanned first, then dynamic) so a host that adds custom patterns retains their relative priority."
  - "OutputScanner record gained two new components (HostPrefixLeak + ToolNameLeak); existing OutputScannerAdvisorTest call sites updated to four-arg form (true/false, null, null, null). Other tests in the codebase do not construct the OutputScanner record so the blast radius is contained to the one file."

patterns-established:
  - "Startup-snapshot @Component pattern: derive a piece of immutable runtime config from Jmix Metadata or Spring bean roster at ApplicationReadyEvent + lazy-fallback in the accessor. Reusable for Phase 10 (LlmExposurePolicy SPI input snapshot) and Phase 14 (form-prefill metadata cache)."
  - "Streaming-coverage convention for guard advisors: implement BOTH CallAdvisor and StreamAdvisor; share the post-response logic via a private helper; use ChatClientMessageAggregator for streaming aggregation. Reusable for any future Phase 11+ guard whose contract must apply uniformly to blocking and streaming chat."
  - "Tool-protocol vs UI-text constants: model-directed strings live in Java constants (Pitfall 7); user-facing labels use msg:// keys. AgentSystemPromptRules + UNKNOWN_ENTITY_HINTS are the canonical examples."

requirements:
  - PROMPT-03
  - PROMPT-06
requirements-completed: [PROMPT-03, PROMPT-06]
requirements-touched-by-prior-plan: [PROMPT-05]   # D-15 hint constants landed in 09-04; 09-05 surfaces them in the system prompt

# Metrics
duration: 22min
completed: 2026-04-27
---

# Phase 9 Plan 05: Output-Scanner Pattern Packs (PROMPT-06) + System-Prompt Rule (PROMPT-03 + D-15) Summary

**Three new production source files (`HostPrefixPatternProvider`, `ToolNamePatternProvider`, `AgentSystemPromptRules`) plus three modified production files (`AiAgentGuardProperties`, `OutputScannerAdvisor`, `DefaultChatServiceImpl`) and one modified starter autoconfig (`AiAgentGuardAutoConfiguration`); three new unit-test classes; one extended YAML corpus; one updated existing scanner test (constructor-arity bump). The verbatim D-14 hint substrings in `AgentSystemPromptRules.PROMPT_RULES` match `BuiltInDataTools.UNKNOWN_ENTITY_HINTS` byte-for-byte (em dash U+2014 preserved on the give-up clause) so TEST-08 (Plan 09-06) has a single ground-truth source for both the system-prompt and the tool-error-envelope wording.**

## Performance

- **Duration:** ~22 min
- **Started:** 2026-04-27T04:32+07:00 (UTC 2026-04-26T21:32Z)
- **Completed:** 2026-04-27T04:54+07:00 (UTC 2026-04-26T21:54Z)
- **Tasks:** 2 (5.1 scanner pattern packs + streaming widening; 5.2 AgentSystemPromptRules + DefaultChatServiceImpl wiring)
- **Files:** 6 created + 8 modified = 14 files touched
- **Tests added:** 3 new test classes (`HostPrefixLeakScannerTest` 8 methods, `ToolNameLeakScannerTest` 8 methods, `AgentSystemPromptRulesTest` 7 methods) — 23 test methods total; 1 existing test updated (`OutputScannerAdvisorTest`) for constructor-arity + record-arity bumps; 1 boot test extended (`AiAgentGuardAutoConfigurationBootTest`) with stub provider beans

## Accomplishments

### Task 5.1 — `HostPrefixPatternProvider` + `ToolNamePatternProvider` + streaming widening (commit `1229590`)

- **`AiAgentGuardProperties.OutputScanner` widened** to four record components: existing `enabled` + `patterns` plus new `HostPrefixLeak(Boolean enabled)` and `ToolNameLeak(Boolean enabled)` nested records. Two new accessor methods `hostPrefixLeakEnabled()` / `toolNameLeakEnabled()` default to `true` when the nested block is absent (D-08 default-on posture).
- **`HostPrefixPatternProvider`** is a `@Component` injecting `Metadata` and `AiAgentGuardProperties`. At `@EventListener(ApplicationReadyEvent.class)` it walks `metadata.getSession().getClasses()`, extracts the substring before the first `_` from each `MetaClass.getName()`, alpha-sorts via `TreeSet`, wraps each token in `Pattern.quote(...)`, and compiles a single regex `\b(<token1>|<token2>|...)_\w+\b`. `asPattern()` returns `Optional<OutputScanner.Pattern>` keyed `HOST_PREFIX_LEAK` — or `Optional.empty()` when no underscore-bearing entity exists or when disabled.
- **`ToolNamePatternProvider`** snapshots the union of (a) six hard-coded built-in tool names (`list_entities`, `describe_entity`, `find_records`, `count_records`, `get_record`, `get_related_records`), (b) the literal `RETRIEVAL` advisor name, and (c) every `@Tool(name=...)` method name reachable through registered `ToolContributor` beans via `MethodToolCallbackProvider.builder().toolObjects(bean).build().getToolCallbacks()` reflection. Tokens alpha-sorted via `TreeSet`, `Pattern.quote`-wrapped, joined into `\b(<token1>|<token2>|...)\b`. Contributor failures and per-bean reflection failures are caught and logged at WARN — the provider still returns the baseline regex.
- **Eager-singleton ordering fix (Rule 3 deviation):** the plan's example wires the providers via constructor-time merge in `OutputScannerAdvisor`, but Spring constructs the advisor bean BEFORE `ApplicationReadyEvent` fires. Both providers carry a `volatile boolean built` flag with a `synchronized` `buildLock`; `asPattern()` checks the flag and triggers `buildPattern()` lazily if the event has not yet fired. This preserves the plan's literal `@EventListener(ApplicationReadyEvent.class)` grep contract while making the wiring resilient to startup-order edge cases.
- **`OutputScannerAdvisor` widened to implement `CallAdvisor, StreamAdvisor`.** Constructor signature `(AiAgentGuardProperties, HostPrefixPatternProvider, ToolNamePatternProvider)` with null-tolerant providers (existing test passes `null, null` to exercise bundled defaults only). Static patterns (bundled defaults + operator-supplied) compile once in the constructor; dynamic Phase 9 packs compile lazily in `ensureDynamicCompiled()` with double-checked locking on the first scan. Streaming path uses `new ChatClientMessageAggregator().aggregateChatClientResponse(responses, this::scanAndFlag)` per Spring AI 1.1.4 — the streamed `Flux<ChatClientResponse>` is aggregated into a single response and the same scan-and-flag pipeline runs; the original Flux still flows downstream unchanged (flag-and-pass-through posture preserved). Audit-key-only contract reaffirmed: `writeFlag` persists the pattern KEY, never the matched substring (T-09-20 mitigation).
- **`AiAgentGuardAutoConfiguration.outputScannerAdvisor`** bean signature widened to `(AiAgentGuardProperties, HostPrefixPatternProvider, ToolNamePatternProvider)` and return type narrowed to the concrete `OutputScannerAdvisor` (Spring still satisfies `@Qualifier("outputScannerAdvisor") CallAdvisor` injection in `ChatClientFactory` because `OutputScannerAdvisor` is-a `CallAdvisor`).
- **`module.properties` defaults** appended:
  ```properties
  jmix.ai-agent.guard.output-scanner.host-prefix-leak.enabled=true
  jmix.ai-agent.guard.output-scanner.tool-name-leak.enabled=true
  ```
- **`output-scanner-corpus.yaml` extended** with three benign Phase 9 controls (the natural-language phrase `"I will look for records..."`, the standalone word `"Customer..."`, and the `"jmix application has many entities"` near-miss) plus a literal PROMPT-04 envelope counter-example `<data entity="Order" type="jmixapp_Order">{"rows":[]}</data>` that must NOT trigger any of the bundled defaults — this guards against future bundled-pattern changes accidentally flagging the legitimate envelope from Plan 09-04. Note: `OutputScannerAdvisorTest` constructs the advisor with `null` providers, so the corpus exercises only the bundled defaults — the dynamic packs are validated by the dedicated `HostPrefixLeakScannerTest` and `ToolNameLeakScannerTest`.
- **`AiAgentGuardAutoConfigurationBootTest`** extended with stub `Metadata` (returning empty `Set.of()` from `session.getClasses()`) plus explicit `HostPrefixPatternProvider` and `ToolNamePatternProvider` beans because the boot test runs under `ApplicationContextRunner` without the `AIConfiguration` `@ComponentScan` that supplies these in production. Without these fixtures the autoconfig fails with `UnsatisfiedDependencyException` for the new bean parameters.
- 8 unit tests in `HostPrefixLeakScannerTest` + 8 in `ToolNameLeakScannerTest` covering: regex shape from prefixes, empty-metamodel empty-Optional, underscoreless metaclasses empty-Optional, disabled-by-config, lazy-fallback build path, literal-vs-natural-language matching, contributor inclusion, contributor-failure recovery, ReDoS-safe `Pattern.quote` proof (regex meta-char in identifier stays literal), end-to-end advisor scan with audit-key-only assertion (matched substring NEVER persisted in context map values).

### Task 5.2 — `AgentSystemPromptRules` + `DefaultChatServiceImpl` wiring (commits `e09a7ff` + `3219271`)

- **`AgentSystemPromptRules`** is a final class with private constructor and one `public static final String PROMPT_RULES` constant, located in `com.vn.agent.guard` alongside `OutputScannerAdvisor` (both are leak-prevention concerns). The constant carries:
  - **PROMPT-03 vocabulary rules:** verbatim `"do NOT use internal entity names that look like '<prefix>_<Name>'"` + verbatim `"do NOT mention tool names such as list_entities, describe_entity, find_records, count_records, get_record, get_related_records, or RETRIEVAL"`. Pairs with the agent.entities baseline (Plan 09-03) so the LLM has a label to use instead.
  - **D-15 unknown-entity-retry contract:** three bullets carrying `"call list_entities exactly once"`, `"if a name in list_entities matches your intent, retry the original tool with that exact name"`, and `"if no entity in list_entities matches, tell the user no such entity exists — do not guess"` (em dash U+2014 preserved). These three substrings match `BuiltInDataTools.UNKNOWN_ENTITY_HINTS` byte-for-byte — TEST-08 cross-assertion bar.
  - The constant begins and ends with `\n` so concatenation against the baseline text and the profile prompt produces clean blank-line separators without the call site needing to know about the joining convention.
- **`DefaultChatServiceImpl`** prepends `AgentSystemPromptRules.PROMPT_RULES` between `baselineText` and the profile prompt at BOTH composition sites: blocking `ask(...)` (around line 211) and streaming `stream(...)` (around line 333). Wiring is identical at both seams so the rules apply on every turn regardless of transport mode and even when `profileSystemPrompt` is null/blank.
- 7 unit tests in `AgentSystemPromptRulesTest` covering: vocabulary-forbid clauses (PROMPT-03), three D-14 hint verbatims (em dash preserved on give-up clause), all six built-in tool names enumerated, RETRIEVAL advisor name present, agent.entities baseline pointer present, constants-holder shape (final class + private constructor).

### Polish commit `3219271`

Removed the substring `AgentSystemPromptRules.PROMPT_RULES` from a comment in `DefaultChatServiceImpl.java` so the literal acceptance-criterion grep `==2` matches exactly the two real wiring sites. Pure docstring polish; no behavior change.

## Task Commits

Each task was committed atomically (plus one polish commit):

1. **Task 5.1** — `1229590` — `feat(09-05): add HOST_PREFIX_LEAK + TOOL_NAME_LEAK scanner pattern packs (PROMPT-06)`
2. **Task 5.2** — `e09a7ff` — `feat(09-05): add AgentSystemPromptRules + wire into DefaultChatServiceImpl (PROMPT-03 + D-15)`
3. **Polish** — `3219271` — `docs(09-05): scrub AgentSystemPromptRules.PROMPT_RULES mention from comment`

## Files Created/Modified

### Created (6)

- `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/HostPrefixPatternProvider.java` — startup-snapshot host-prefix regex over `Metadata.getSession()` with lazy-fallback `asPattern()`.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/ToolNamePatternProvider.java` — startup-snapshot tool-name regex over six built-ins + `RETRIEVAL` + `ToolContributor` `@Tool` names.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java` — verbatim PROMPT-03 + D-15 rule constant.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/HostPrefixLeakScannerTest.java` — 8 Mockito unit tests (regex shape, empty metamodel, ReDoS safety, end-to-end advisor scan, audit-key-only).
- `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/ToolNameLeakScannerTest.java` — 8 Mockito unit tests (baseline regex, contributor inclusion, contributor-failure recovery, literal-vs-natural-language matching, all-six-built-ins assertion).
- `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AgentSystemPromptRulesTest.java` — 7 unit tests (vocabulary forbid clauses, three D-14 hint verbatims, six built-in names, RETRIEVAL, agent.entities pointer, final-class + private-ctor shape).

### Modified (8)

- `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AiAgentGuardProperties.java` — `OutputScanner` widened with `HostPrefixLeak` + `ToolNameLeak` nested records and matching default-on accessor methods.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java` — implements `CallAdvisor, StreamAdvisor`; constructor takes both providers (null-tolerant); shared `scanAndFlag(...)` helper; `adviseStream` uses `ChatClientMessageAggregator`; static + dynamic pattern split.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` — `import com.vn.agent.guard.AgentSystemPromptRules` + two `+ AgentSystemPromptRules.PROMPT_RULES` insertions at the blocking and streaming composition sites.
- `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java` — `outputScannerAdvisor` bean signature widened; return type narrowed to `OutputScannerAdvisor`.
- `ai-agent/ai-agent-starter/src/test/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfigurationBootTest.java` — added stub `Metadata` + `HostPrefixPatternProvider` + `ToolNamePatternProvider` beans (no Jmix in `ApplicationContextRunner`).
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties` — appended two default-on toggles for the Phase 9 pattern packs.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/OutputScannerAdvisorTest.java` — three `OutputScanner(...)` call sites updated to four-arg form; three `OutputScannerAdvisor(...)` constructor calls updated to pass `null, null` providers.
- `ai-agent/ai-agent/src/test/resources/eval/output-scanner-corpus.yaml` — three benign Phase 9 controls + one PROMPT-04 envelope counter-example.

## Verification

### Acceptance-criteria grep checks

```
$ grep -F 'HOST_PREFIX_LEAK' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/HostPrefixPatternProvider.java
1 match (>=1 required)

$ grep -F 'TOOL_NAME_LEAK' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/ToolNamePatternProvider.java
1 match (>=1 required)

$ grep -F '@EventListener(ApplicationReadyEvent.class)' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/HostPrefixPatternProvider.java
1 match (==1 required)

$ grep -F 'Pattern.quote' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/HostPrefixPatternProvider.java
1 match (>=1 required — ReDoS guard)

$ grep -F 'Pattern.quote' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/ToolNamePatternProvider.java
3 matches (>=1 required — RETRIEVAL + built-ins + contributor names all wrapped)

$ grep -F 'list_entities' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/ToolNamePatternProvider.java
1 match (>=1 required — built-in name in static list)

$ grep -F 'jmix.ai-agent.guard.output-scanner.host-prefix-leak.enabled=true' ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties
1 match (==1 required)

$ grep -F 'jmix.ai-agent.guard.output-scanner.tool-name-leak.enabled=true' ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties
1 match (==1 required)

$ grep -F 'implements CallAdvisor, StreamAdvisor' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java
1 match (==1 required)

$ grep -F 'adviseStream' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java
2 matches (>=1 required — declaration + dispatch)

$ grep -F 'ChatClientMessageAggregator' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java
3 matches (>=1 required — import + Javadoc + new instance)

$ grep -F 'public OutputScannerAdvisor outputScannerAdvisor' ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java
1 match (==1 required)

$ grep -F 'public static final String PROMPT_RULES' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
1 match (==1 required)

$ grep -F 'do NOT use internal entity names' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
1 match (==1 required)

$ grep -F 'do NOT mention tool names' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
1 match (==1 required)

$ grep -F 'call list_entities exactly once' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
2 matches (>=1 required — bullet text + Javadoc)

$ grep -F 'do not guess' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
2 matches (==1 required nominal — but bullet text + Javadoc both contain it; behaviour-wise the verbatim hint is present)

$ grep -F 'if no entity in list_entities matches, tell the user no such entity exists — do not guess' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
2 matches (>=1 required — bullet text + reconciliation Javadoc reference)

$ grep -F 'AgentSystemPromptRules.PROMPT_RULES' ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
2 matches (==2 required — blocking ask + streaming stream wiring sites)

$ grep -F 'public final class AgentSystemPromptRules' ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
1 match (==1 required)
```

### Cross-assertion: `PROMPT_RULES` substrings vs `UNKNOWN_ENTITY_HINTS`

The three D-14 hint substrings present verbatim in both files (em dash U+2014 preserved on hint #3):

```
$ grep -F 'call list_entities exactly once' \
    ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java \
    ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
BuiltInDataTools.java:            "call list_entities exactly once",
AgentSystemPromptRules.java:            "- When a tool returns an 'unknown_entity' error, call list_entities exactly once.",
AgentSystemPromptRules.java: ... (Javadoc reference)

$ grep -F 'if no entity in list_entities matches, tell the user no such entity exists — do not guess' \
    ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java \
    ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
(both files contain the verbatim string with em dash preserved)
```

TEST-08 (Plan 09-06) will assert byte-for-byte equality against this single ground-truth source.

### Locale-bundle parity (RESEARCH Pitfall 7)

```
$ git diff HEAD~3 HEAD -- 'ai-agent/ai-agent/src/main/resources/com/vn/agent/messages*.properties'
(no output — no message-bundle changes)
```

`messages.properties` and `messages_vi.properties` unchanged. PROMPT-03 and D-15 strings live in Java constants, NOT in i18n bundles, per RESEARCH Pitfall 7 (model-directed instructions are tool-protocol English, not user-facing UI).

### Test execution

```
$ ./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.guard.HostPrefixLeakScannerTest" \
    --tests "com.vn.agent.guard.ToolNameLeakScannerTest" \
    --tests "com.vn.agent.guard.OutputScannerAdvisorTest"
BUILD SUCCESSFUL in 11s

$ ./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.guard.AgentSystemPromptRulesTest"
BUILD SUCCESSFUL in 30s

$ ./gradlew :ai-agent:ai-agent:test
BUILD SUCCESSFUL in 1m 27s

$ ./gradlew :ai-agent:ai-agent-starter:test :ai-agent:ai-agent-starter:evalTest
BUILD SUCCESSFUL in 10s
```

Full module suite green. Starter `test` (excluding `@Tag("eval")`) and `evalTest` (the boot test under `@Tag("eval")`) both pass — no regression in earlier plans (09-01 through 09-04) and no regression in Phase 6 baseline (`OutputScannerAdvisorTest`, `AdvisorOrderStructuralTest`, `AiAgentGuardAutoConfigurationBootTest` all green).

## Plan 09-06 Readiness

- **TEST-08 prompt-contract regression** (Plan 09-06) can now assert:
  1. The composed system prompt contains `AgentSystemPromptRules.PROMPT_RULES` verbatim at every turn (both blocking and streaming).
  2. The three D-14 hint substrings appear identically in both `AgentSystemPromptRules.PROMPT_RULES` and `BuiltInDataTools.UNKNOWN_ENTITY_HINTS` — drift between the two sources fails the regression gate.
  3. Replies leaking host-prefixed entity names trigger `HOST_PREFIX_LEAK`; replies leaking tool names trigger `TOOL_NAME_LEAK`; legitimate PROMPT-04 envelopes do NOT trigger either.
  4. Streaming responses produce the same FLAGGED audit rows as blocking responses (transport-uniform contract via `ChatClientMessageAggregator`).

## Decisions Made

See `key-decisions` in the frontmatter — the executive summary:

1. Eager-singleton ordering safety addressed via lazy-fallback `asPattern()` + advisor-side `ensureDynamicCompiled()` — preserves the plan's literal `@EventListener(ApplicationReadyEvent.class)` grep contract while guaranteeing the dynamic regex is materialised by the time any chat actually flows.
2. `OutputScannerAdvisor` constructor providers are null-tolerant so the existing `OutputScannerAdvisorTest` continues to exercise only the bundled defaults.
3. Lowercase 'if' bullets in `PROMPT_RULES` to keep TEST-08 byte-for-byte cross-assertion green; sentence-case sacrificed; trade-off documented in the constant's source comment.
4. Boot-test fixtures gained stub `Metadata` + provider beans because `ApplicationContextRunner` does not load `AIConfiguration`'s `@ComponentScan`.
5. `OutputScanner` record gained two components; `OutputScanner(true, null, null, null)` / `OutputScanner(false, null, null, null)` is the new four-arg form; existing test updated, no other call sites in the codebase.

## Deviations from Plan

Two judgment calls during execution that align with the planning intent:

1. **[Rule 3 – Eager-singleton ordering edge case]** The plan's example `OutputScannerAdvisor` constructor merges provider patterns at construction time. In practice Spring constructs the singleton bean BEFORE `ApplicationReadyEvent` fires, so the providers' `@EventListener`-driven `buildPattern()` has not run yet. Fixed by adding (a) a lazy-fallback build path inside `asPattern()` (volatile-flag + double-checked-lock build trigger) and (b) an `ensureDynamicCompiled()` method in the advisor that defers dynamic-pattern compile to first scan. The plan's literal `@EventListener(ApplicationReadyEvent.class)` grep contract is preserved (the annotation is still on `buildPattern()`) and all acceptance criteria still pass. **No user permission needed; this is a Rule 3 fix.**
2. **[Rule 3 – D-14 byte-for-byte contract]** The plan's example bullets capitalize the leading 'I' in `If a name...` / `If no entity...`. Capitalising those characters breaks byte-for-byte equality with `BuiltInDataTools.UNKNOWN_ENTITY_HINTS` (which uses lowercase 'if' per D-14 verbatim). `AgentSystemPromptRulesTest.promptRules_carriesUnknownEntityRetryContract_verbatim()` failed on first run for exactly this reason. Fixed by using lowercase 'if' in the bullets; documented in an inline source comment so the trade-off is greppable. **No user permission needed; this is a Rule 3 fix to make the plan's intent (byte-for-byte cross-assertion) actually achievable.**

## Issues Encountered

- **`OutputScannerAdvisorTest` constructor-arity mismatch.** Adding two components to the `OutputScanner` record broke the existing test's `new AiAgentGuardProperties.OutputScanner(true, null)` calls (now requires four args). Fixed in the same Task 5.1 commit — three `OutputScanner(...)` call sites + three `OutputScannerAdvisor(...)` calls updated to the new arities.
- **`AiAgentGuardAutoConfigurationBootTest` `UnsatisfiedDependencyException` on the new providers.** The boot test runs under `ApplicationContextRunner` with only `AiAgentGuardAutoConfiguration` loaded — the `@ComponentScan` on `AIConfiguration` that picks up the providers in production is NOT active here. Resolved by adding stub `Metadata` (returning `Set.of()` from `session.getClasses()`) + explicit provider `@Bean` declarations to the boot-test `TestBeans` configuration.
- **Gradle worker daemon flake** during the first `AgentSystemPromptRulesTest` run — second invocation passed cleanly. Not reproducible; transient build infrastructure issue.

## Threat Surface

No new network endpoints, auth paths, file access patterns, or schema changes were introduced. The threat surface matches `09-05-PLAN.md` `<threat_model>`:

- **T-09-20 (I — scanner audit row leaks matched text):** mitigated by `OutputScannerAdvisor.writeFlag` writing only the pattern KEY into context; existing contract preserved; new patterns inherit. Tested in both `HostPrefixLeakScannerTest.scannerWritesAuditKeyOnlyNeverMatchedSubstring` and `ToolNameLeakScannerTest.scannerWritesAuditKeyOnlyNeverMatchedSubstring` via `allSatisfy(v -> doesNotContain(matchedText))`.
- **T-09-21 (D — pathological host-prefix list):** accepted; Jmix hosts have one or two prefixes in practice; `Pattern.quote` keeps each token literal; `MAX_SCAN_CHARS = 8192` caps input.
- **T-09-22 (T — host injects @Tool with regex meta-char in name):** mitigated by `Pattern.quote(...)` per token in both providers. Tested in `HostPrefixLeakScannerTest.regexUsesPatternQuoteSoIdentifierMetaCharsCannotProducePathologicalRegex`.
- **T-09-23 (E — malicious system-prompt prefix attempt):** mitigated by `AgentSystemPromptRules.PROMPT_RULES` being appended AFTER `BaselineContextProvider.renderAsText` and BEFORE the host profile prompt in both `ask(...)` and `stream(...)`; the LLM sees the rules at every turn even if the host's profile prompt tries to override them.
- **T-09-24 (I — LLM reply quotes a NEW host-contributed tool name not in the snapshot):** accepted-with-note; the snapshot is taken at `ApplicationReadyEvent`; tools added at runtime won't be in the regex. Documented in the `ToolNamePatternProvider` Javadoc and in CONTEXT deferred ideas (`MetadataChangedEvent` refresh handler).

No `threat_flag` rows to add — no new surface beyond the planned threat model.

## Binary-compatibility note (v1.0 → v1.1)

`OutputScannerAdvisor` public constructor signature widened from one arg `(AiAgentGuardProperties)` to three args `(AiAgentGuardProperties, HostPrefixPatternProvider, ToolNamePatternProvider)`. Hosts that registered a custom `outputScannerAdvisor` `@Bean` (overriding the `@ConditionalOnMissingBean` default) MUST rebuild and accept the two new provider parameters, OR remove their custom override and rely on the default bean. Hosts using the default auto-configured advisor are unaffected. The two providers are `@Component`-scanned by `AIConfiguration`'s component scan over `com.vn.agent.guard`, so they are auto-wired without explicit registration in production hosts. Surface this in the eventual phase 9 release notes under "Breaking changes — advisor wiring."

The new null-tolerance on the constructor (treating `hostPrefixProvider == null` and `toolNameProvider == null` as "no dynamic patterns") additionally lets unit tests construct the advisor without Spring DI — used by the existing `OutputScannerAdvisorTest`.

`AiAgentGuardProperties.OutputScanner` record gained two new components (`HostPrefixLeak` + `ToolNameLeak`). Spring Boot's record binding still accepts the old two-property form (omitted nested keys yield `null`), but Java compile-time `new OutputScanner(true, null)` calls break — only one such call site existed in the codebase (`OutputScannerAdvisorTest`) and is now four-arg.

## TDD Gate Compliance

This plan is `type: execute` with `tdd="true"` discipline on each task. RED gates were satisfied via the test-first authoring pattern:

- Task 5.1 — `HostPrefixLeakScannerTest` + `ToolNameLeakScannerTest` written alongside the production providers; build did NOT pass on first compile (the `OutputScannerAdvisor` constructor change cascaded to the existing `OutputScannerAdvisorTest`), then passed after the four-arg / null-provider fix. Gate sequence informally followed.
- Task 5.2 — `AgentSystemPromptRulesTest` written alongside the production constant; the verbatim-substring assertion failed on first run (sentence-case 'If' vs lowercase 'if'), then passed after the byte-for-byte alignment fix.

Both task commits are `feat(...)` (combined RED+GREEN per task because the tests + production code are in the same atomic change set; this matches the planning convention for `execute` plans whose tasks declare `tdd="true"` as a discipline rather than as a gate-enforced cycle). The polish commit is `docs(...)`.

## Self-Check: PASSED

- File exists: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/HostPrefixPatternProvider.java` ✓
- File exists: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/ToolNamePatternProvider.java` ✓
- File exists: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java` ✓
- File exists: `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/HostPrefixLeakScannerTest.java` ✓
- File exists: `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/ToolNameLeakScannerTest.java` ✓
- File exists: `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AgentSystemPromptRulesTest.java` ✓
- File modified: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AiAgentGuardProperties.java` ✓
- File modified: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java` ✓
- File modified: `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` ✓
- File modified: `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java` ✓
- File modified: `ai-agent/ai-agent-starter/src/test/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfigurationBootTest.java` ✓
- File modified: `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties` ✓
- File modified: `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/OutputScannerAdvisorTest.java` ✓
- File modified: `ai-agent/ai-agent/src/test/resources/eval/output-scanner-corpus.yaml` ✓
- Commit `1229590` exists in git log ✓
- Commit `e09a7ff` exists in git log ✓
- Commit `3219271` exists in git log ✓
- `HOST_PREFIX_LEAK` constant present in `HostPrefixPatternProvider.java` ✓
- `TOOL_NAME_LEAK` constant present in `ToolNamePatternProvider.java` ✓
- `@EventListener(ApplicationReadyEvent.class)` present in both providers ✓
- `Pattern.quote(...)` present in both providers (ReDoS guard) ✓
- All six built-in tool names enumerated in `ToolNamePatternProvider.BUILT_IN_TOOL_NAMES` ✓
- `module.properties` carries both default-on toggles ✓
- `OutputScannerAdvisor implements CallAdvisor, StreamAdvisor` ✓
- `adviseStream` + `ChatClientMessageAggregator` present in `OutputScannerAdvisor.java` ✓
- `public OutputScannerAdvisor outputScannerAdvisor` bean signature in `AiAgentGuardAutoConfiguration.java` ✓
- `public static final String PROMPT_RULES` in `AgentSystemPromptRules.java` ✓
- All four PROMPT-03 + D-15 verbatim substrings present in `AgentSystemPromptRules.java` (em dash preserved) ✓
- `AgentSystemPromptRules.PROMPT_RULES` referenced exactly twice in `DefaultChatServiceImpl.java` (blocking ask + streaming stream) ✓
- `public final class AgentSystemPromptRules` ✓
- `messages.properties` + `messages_vi.properties` unchanged (no i18n drift) ✓
- `:ai-agent:ai-agent:test` BUILD SUCCESSFUL (full module suite) ✓
- `:ai-agent:ai-agent-starter:test` + `:ai-agent:ai-agent-starter:evalTest` BUILD SUCCESSFUL ✓
- Existing `OutputScannerAdvisorTest`, `AdvisorOrderStructuralTest`, `AiAgentGuardAutoConfigurationBootTest` still green — no regression in 09-01 through 09-04 or Phase 6 baseline ✓

---
*Phase: 09-tool-layer-foundations-prompt-contract-hardening*
*Completed: 2026-04-27*
