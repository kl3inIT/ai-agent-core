---
phase: 9
reviewers: [codex]
reviewed_at: 2026-04-26T19:38:57Z
plans_reviewed:
  - 09-01-PLAN.md
  - 09-02-PLAN.md
  - 09-03-PLAN.md
  - 09-04-PLAN.md
  - 09-05-PLAN.md
  - 09-06-PLAN.md
attempted_but_skipped:
  - claude (running inside Claude Code — skipped for independence)
  - opencode (CLI hung on stdin / `--file` arg parsing rejected the prompt; aborted)
unavailable:
  - gemini, qwen, cursor, coderabbit, ollama, lm_studio, llama_cpp
---

# Cross-AI Plan Review — Phase 9

## Codex Review

**Summary**
The plans are unusually thorough and trace well to Phase 9 decisions, but I would not approve them as-is. The biggest issue is not scope; it is correctness drift between the plans and the current repo: `default-params.yaml` is being treated as Spring config when it is actually strict `AiParameters` seed YAML, `RunContext` is static-only but used as an SPI value object, `MetadataTools.isPrimaryKey(...)` does not exist in Jmix 2.8.1, and the prompt-leak tests conflict with the current flag-and-pass-through scanner posture. Overall risk: **HIGH** until those blockers are fixed.

**Cross-Cutting Concerns**
- **HIGH:** Plans 09-01, 09-03, and 09-05 append `jmix.ai-agent.*` config to `ai-agent/ai-agent-starter/src/main/resources/default-params.yaml`. That file seeds `AiParameters.bodyYaml` and is parsed with `FAIL_ON_UNKNOWN_PROPERTIES`; adding `jmix:` will break first-boot seeding when the table is empty. Put defaults in `module.properties`, test properties, README/operator docs, or rely on resolved accessors.
- **HIGH:** The phase goal says user-facing replies must not contain internal entity/tool names, but `OutputScannerAdvisor` explicitly flags and passes through. TEST-08 cannot both script a leaky reply and assert final content is clean unless a sanitizer/blocking layer is added, which D-08 currently forbids.
- **HIGH:** Streaming path is missed. `DefaultChatServiceImpl` composes system prompts in both blocking `ask(...)` and `stream(...)`; Plan 09-05 only wires rules into the blocking path.
- **MEDIUM:** Several plan snippets use stale package/class names or accessors (`com.vn.agent.test_support.AITestConfiguration`, `assistantContent()`), while the repo has `com.vn.agent.AITestConfiguration` and `ChatResponseDto.content()`.

### 09-01 — AUD-07 Plumbing
Risk: **MEDIUM**

Strengths:
- Cleanly scoped plumbing-only plan; no premature mutation wiring.
- SHA-256 over explicit UTF-8 is appropriate for deterministic audit hashing.
- The property defaults mirror existing `resolved*` patterns.

Concerns:
- **HIGH:** `utility_cannotBeInstantiated()` is internally contradictory. After `ctor.setAccessible(true)`, the private constructor will instantiate successfully, so the `assertThatThrownBy(ctor::newInstance)` assertion will fail.
- **HIGH:** Writing audit properties into `default-params.yaml` is wrong for this repo.
- **LOW:** Plan says six tests, but the snippet creates seven.

Suggestions:
- Remove the reflective constructor invocation test; assert `Modifier.isFinal(...)` and `getConstructors().length == 0`.
- Do not modify `default-params.yaml`; document keys or add application defaults to `module.properties` if defaults must be visible.

### 09-02 — ToolFetchPlanCustomizer SPI
Risk: **HIGH**

Strengths:
- Good contract boundary: host projection override, then mandatory security intersection.
- First-non-empty customizer chain is the right extension model.
- Javadoc explicitly says fetch plans are projection, not security.

Concerns:
- **HIGH:** `FetchPlanContext(RunContext run, UserDetails user)` is not viable as described. `RunContext` (`ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/RunContext.java`) is static-only with a private constructor; Plan 09-04 later passes `null`, making `context.run()` misleading.
- **MEDIUM:** The plan claims hosts use `context.run()` as a handle, but the only usable API is `RunContext.get*()` static calls.
- **LOW:** Add a boot/default-bean test or extend the existing smoke test so the SPI default is covered.

Suggestions:
- Change `FetchPlanContext` to carry concrete values: `UUID runId`, `UUID conversationId`, retrieval params, locale if needed, and `UserDetails user`; or remove `RunContext run` entirely and document static `RunContext` access.
- Make Plan 09-04 depend on the corrected SPI shape.

### 09-03 — Baseline Context
Risk: **MEDIUM**

Strengths:
- Correctly routes through `CurrentUserSchemaAccess`, preserving Phase 10's substitution point.
- Deterministic ordering and compact permissions JSON are well designed.
- Locale-invariance test for `agent.permissions` is a good regression gate.

Concerns:
- **HIGH:** Again, `default-params.yaml` is the wrong place for `jmix.ai-agent.prompt.*`.
- **MEDIUM:** `agent.entities` truncates at 100, but the planned `agent.permissions` builder uses the full readable schema. That leaks entity names omitted from the inventory and can grow the prompt.
- **MEDIUM:** Existing `agent.roles` remains insertion-order dependent; if prompt hashing matters, sort roles too.

Suggestions:
- Drive both `agent.entities` and `agent.permissions` from the same sorted, capped entity list.
- Add an explicit test that no entity beyond the truncation limit appears in permissions.
- Put prompt properties in application property defaults/docs, not the seed YAML.

### 09-04 — Tool Layer / Fetch Plans / describe_entity
Risk: **HIGH**

Strengths:
- Addresses the right core files together, avoiding merge churn.
- The intersector/resolver split is a sensible internal architecture.
- Unknown-entity hints are placed at the tool-error boundary, which is the right place.

Concerns:
- **HIGH:** `metadataTools.isPrimaryKey(metaProperty)` does not exist in Jmix 2.8.1. Use `metadataTools.getPrimaryKeyProperty(metaClass)` and compare by name/property.
- **HIGH:** PROMPT-04 is not actually implemented as written. The requirement says `<data entity="<label>" type="<internalName>">...`; the plan only changes JSON fields to `entity` and `type`.
- **MEDIUM:** The intersector rebuild likely loses fetch modes, partial/system-property flags, and maybe nested semantics. It should preserve `FetchPlanProperty.getFetchMode()` and `loadPartialEntities()`.
- **MEDIUM:** The raw-reflection grep conflicts with the existing `java.lang.reflect.AnnotatedElement` column-length workaround.
- **MEDIUM:** D-14 hint wording is changed from the locked em dash version to ASCII hyphen. Pick one canonical literal before tests encode it.

Suggestions:
- Verify the exact output wrapper required for PROMPT-04 and implement it literally if the contract says XML-like `<data ...>`.
- Replace `isPrimaryKey` with `getPrimaryKeyProperty`.
- Preserve fetch modes and partial flags during intersection, or explicitly document why losing them is acceptable.
- Update all existing tests that assert `entityName` in records/count payloads.

### 09-05 — Scanner Patterns / System Prompt Rules
Risk: **HIGH**

Strengths:
- Dynamic host-prefix derivation is good and avoids hardcoded tenant prefixes.
- Pattern keys are stable and audit-safe.
- Keeping model-directed rules out of i18n bundles matches existing project precedent.

Concerns:
- **HIGH:** Rules are wired only into blocking chat, not streaming chat.
- **HIGH:** This plan depends semantically on 09-04 hint literals but only declares dependency on 09-03.
- **MEDIUM:** `ToolContributor.contribute()` at `ApplicationReadyEvent` can miss role-gated tools or run host code outside a request/auth context.
- **MEDIUM:** `AiAgentGuardAutoConfiguration.java` is modified in the task but missing from `files_modified`.
- **MEDIUM:** Existing tests constructing `new OutputScanner(...)` will break when the record gains components unless updated.
- **LOW:** Existing corpus path is `src/test/resources/eval/output-scanner-corpus.yaml`, not the root test resources path in the plan.

Suggestions:
- Add `depends_on: ["03", "04"]`.
- Wire `AgentSystemPromptRules` into both `ask` and `stream`.
- Include `AiAgentGuardAutoConfiguration.java` and affected existing tests in `files_modified`.
- Prefer extracting tool names from already-built callbacks in a controlled authenticated/system context, or document that dynamic/role-gated tools may be scanner-missed.

### 09-06 — TEST-08 Contract Suite
Risk: **HIGH**

Strengths:
- The intent is right: one deterministic mock suite plus one opt-in live suite.
- Locale parameterization and captured system-prompt checks are valuable.
- It cross-checks prompt rules and tool-error hints.

Concerns:
- **HIGH:** The mock test cannot assert leaky content is absent unless the product sanitizes or blocks. Current scanner returns content unchanged and only sets `flaggedPatternKey`.
- **HIGH:** Test skeleton imports `AITestConfiguration` from the wrong package and uses the wrong response accessor in places.
- **MEDIUM:** Overriding `CurrentAuthentication` with a primary mock in a full Jmix `@SpringBootTest` may interfere with `SystemAuthenticator` and security behavior. This needs a lighter seam or a proven test configuration.
- **MEDIUM:** Live test uses `OPENAI_API_KEY`, while existing live tests are OpenRouter-gated by `OPENROUTER_API_KEY`.

Suggestions:
- Decide the product contract first: either "flag leaks" or "withhold/sanitize leaks." Then make TEST-08 assert that exact behavior.
- Use `ChatResponseDto.flaggedPatternKey()` and `content()` directly.
- Mirror existing live test env gates and wiring.
- If locale must be controlled, prefer an existing authenticated test-user mechanism or a small locale-scoped collaborator rather than replacing `CurrentAuthentication` globally.

**Overall Risk Assessment**
Overall risk: **HIGH**. The plans cover the right Phase 9 scope and are strong on traceability, but several will fail compile or boot as written, and the prompt-leak success criterion is not aligned with the implemented scanner posture. Fix the `default-params.yaml` misuse, SPI context shape, Jmix API mismatch, streaming prompt wiring, and TEST-08 contract semantics before execution.

References checked: Jmix fetch plans and security docs from `/jmix-framework/jmix-context7`, Spring AI tools docs from `/spring-projects/spring-ai`, plus local repo files.

---

## Consensus Summary

Only one external reviewer (codex) produced output for this run, so this section reflects that single review rather than multi-reviewer consensus. Other CLIs were either unavailable on this machine (gemini, qwen, cursor, coderabbit, ollama, lm_studio, llama_cpp), self-skipped (claude — running inside Claude Code), or failed to produce output (opencode hung / rejected the input shape).

### Top Concerns to Address Before Execution

1. **`default-params.yaml` misuse (HIGH, plans 09-01, 09-03, 09-05).** That file seeds `AiParameters.bodyYaml` with `FAIL_ON_UNKNOWN_PROPERTIES`; appending `jmix.ai-agent.*` keys will break first-boot seeding. Move defaults to `module.properties` or operator docs.
2. **`FetchPlanContext` SPI shape (HIGH, 09-02 + 09-04).** `RunContext` is static-only — it cannot be carried as a value object. Re-shape `FetchPlanContext` to concrete fields (`runId`, `conversationId`, `UserDetails`, locale) before 09-04 consumes it.
3. **Jmix API mismatch (HIGH, 09-04).** `MetadataTools.isPrimaryKey(metaProperty)` does not exist in 2.8.1. Use `getPrimaryKeyProperty(metaClass)` instead.
4. **PROMPT-04 implementation gap (HIGH, 09-04).** Requirement says `<data entity="<label>" type="<internalName>">…` wrapper, but the plan only renames JSON fields. Re-confirm the contract literal and implement it as specified, or relax the requirement.
5. **Streaming path not wired (HIGH, 09-05).** `AgentSystemPromptRules` is added only to `ask(...)`; `stream(...)` composes its own system prompt and would silently bypass the rules. Wire both paths.
6. **TEST-08 contract drift (HIGH, 09-06).** Scanner currently flags-and-passes; tests as planned assume sanitization/blocking. Pick one product contract (flag-only audit, or sanitize/withhold) and align both the scanner posture and the test assertions before writing TEST-08.
7. **Plan dependency graph (HIGH, 09-05).** 09-05 depends on 09-04's hint literals but declares only 09-03. Add `depends_on: ["03", "04"]`.
8. **Stale test wiring (MEDIUM, 09-06).** Plan imports `com.vn.agent.test_support.AITestConfiguration` (wrong package) and `ChatResponseDto.assistantContent()` (wrong accessor); current code is `com.vn.agent.AITestConfiguration` / `.content()`. Mirror existing live tests' env gating (`OPENROUTER_API_KEY`, not `OPENAI_API_KEY`).
9. **Permissions/inventory consistency (MEDIUM, 09-03).** `agent.entities` truncates at 100 while `agent.permissions` uses the full readable schema — drive both from the same sorted, capped list.
10. **Test correctness bugs (MEDIUM/LOW, 09-01, 09-05).** Reflective `ctor.newInstance()` after `setAccessible(true)` will succeed (test will fail); existing `new OutputScanner(...)` callers will break when the record grows; corpus path is `src/test/resources/eval/output-scanner-corpus.yaml`.

### Strengths Worth Preserving

- Plans are well traced to REQ-IDs and decisions.
- Phase scope is conservative: plumbing only for AUD-07, no mutation wiring this phase.
- Architecture choices (intersector/resolver split, host-projection-then-security-intersection, scanner-pattern providers, dynamic host-prefix derivation, locale-free permission cache key) are sound.
- TEST-08 split (deterministic mock suite + opt-in live suite) is the right shape.

### Divergent Views

N/A (single reviewer).
