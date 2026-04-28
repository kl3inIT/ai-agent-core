---
phase: 11
reviewers: [codex]
reviewed_at: 2026-04-28T16:43:22Z
plans_reviewed:
  - 11-01-PLAN.md
  - 11-02-PLAN.md
  - 11-03-PLAN.md
  - 11-04-PLAN.md
  - 11-05-PLAN.md
  - 11-06-PLAN.md
  - 11-07-PLAN.md
  - 11-08-PLAN.md
  - 11-09-PLAN.md
  - 11-10-PLAN.md
---

# Cross-AI Plan Review - Phase 11

## Codex Review

## Summary

The plan set is directionally strong and covers the right phase surface: default-off mutation tools, layered Jmix security, idempotency, audit durability, prompt rules, link tools, and regression tests. However, several plans are not execution-ready. The largest risks are in idempotency correctness, duplicate audit rows, relationship mutation security, prompt wiring, and tests that likely will not compile or prove the intended guarantees. Overall, I would block execution until the high-severity items below are resolved.

Docs checked: Jmix `DataManager` / roles / tests and Spring AI tools via Context7 official docs: [Jmix docs](https://docs.jmix.io), [Spring AI tools docs](https://docs.spring.io/spring-ai/reference/api/tools.html).

## Strengths

- Good phase decomposition: foundation, SPI, resolver extraction, repository, translator, tools, wiring, tests.
- Default-off mutation surface is preserved with `@ConditionalOnProperty`.
- The plan correctly separates mutation tools from `BuiltInDataTools`, preserving the read-only ASM contract.
- Using regular `DataManager` for host mutations and `UnconstrainedDataManager` for internal dedup/audit is the right security posture.
- The plan recognizes Spring self-invocation risk and moves transactional save into `MutationSaveExecutor`.
- Locale parity and Jmix entity conventions are explicitly called out.
- The plan includes meaningful regression targets for default callback exposure, per-attribute denial, idempotency, and audit durability.

## Concerns

- **HIGH: Idempotency is not concurrency-safe.** `findExisting` followed by post-save dedup insert allows two concurrent calls with the same key to both mutate host data before the unique index rejects one dedup row. This violates "only one row is created/updated."

- **HIGH: Idempotency cannot detect conflicting call shape.** `AiMutationIntent` stores only result entity id/name, not an argument hash. `idempotency_violation` is planned but cannot be implemented reliably.

- **HIGH: Audit will likely be duplicated.** `AgentToolCallbacks` wraps every callback in `ToolCallbackAuditDecorator`, while `BuiltInMutationTools` also calls `AuditWriter.writeToolCall` directly. That produces two audit rows per mutation, with the decorator likely marking JSON error results as `SUCCESS`.

- **HIGH: Related-write security is incomplete.** `add_related_record` / `remove_related_record` check parent update and relationship attribute, but not necessarily child entity update/delete permission or inverse attribute modify permission. Composition removal can effectively delete a child despite `delete_record` being deferred.

- **HIGH: `remove_related_record` performs `dataManager.remove(child)` outside `MutationSaveExecutor`.** That bypasses the stated transactional proxy boundary and weakens rollback/audit semantics.

- **HIGH: `LlmExposurePolicy.canModify` currently appears update-oriented.** In the current source it checks `CrudEntityContext.isUpdatePermitted()`. Using it for `create_record` can block create-only users before the create-specific `AccessManager` check runs.

- **HIGH: Prompt-rule wiring is incomplete.** `SystemPromptComposer` is currently a static utility reading `AgentSystemPromptRules.PROMPT_RULES` directly. Plan 11-09 adds a component but does not list or clearly modify `SystemPromptComposer` / `DefaultChatServiceImpl`, so mutation rules may never be used.

- **HIGH: Tool-name leakage scanner misses new built-ins.** `ToolNamePatternProvider` hardcodes only the six read-only tools. New mutation and link tool names are not included unless explicitly added or sourced from `AgentToolCallbacks`.

- **HIGH: Tests likely do not compile as written.** `ToolUserError` has no `getCode()` method, `AiAuditEvent.getOutcome()` returns `AiToolCallOutcome` not a string id, and `RunContext` uses static accessors rather than injectable instance methods.

- **HIGH: TEST-11 does not prove replay.** Calling the tool twice inside one `@Transactional @Commit` method means the first after-commit dedup write has not run before the second call. It will not exercise committed replay correctly.

- **MEDIUM: `FilterLiteralValueConverter` currently emits `invalid_literal` / `unsupported_type`, not `parameter_conversion_error`.** Plan 11-06 assumes pass-through already produces the stable MUT-07 code, but current code does not.

- **MEDIUM: `ToolEntityResolver.llmReadableAttributes` skeleton changes behavior.** Existing `BuiltInDataTools` also hides relationship attributes whose target entity is not readable. The extraction acceptance criteria should lock that behavior.

- **MEDIUM: Callback count tests are brittle.** Exact `8` and `12` counts assume no `ToolContributor` beans. This may hold in the add-on context, but name-based assertions are safer and more diagnostic.

- **MEDIUM: Test authentication is underplanned.** Direct tool calls use `CurrentAuthentication`. The tests should run inside `SystemAuthenticator.withUser(...)` or the project's existing authenticated test pattern.

- **MEDIUM: Test fixture Liquibase wiring is uncertain.** Creating `src/test/resources/com/vn/agent/test_liquibase/...` is not enough unless the test changelog is actually wired into the test app without replacing the agentstore changelog.

- **LOW: `@EnableScheduling` on `AIConfiguration` is acceptable but broad.** A small scheduling config class would isolate the concern better.

## Suggestions

- Add a dedicated idempotency design fix before 11-05/11-07: store a sanitized `requestHash`, add status fields such as `PENDING`, `COMMITTED`, `FAILED`, and reserve the unique key before mutation, or use a transaction/lock strategy that prevents concurrent duplicate host writes.

- Decide one audit owner. Either make `ToolCallbackAuditDecorator` understand mutation outcomes, or skip decorator auditing for self-audited mutation callbacks. Do not keep both without an explicit dedup strategy.

- Split 11-07 into separate create/update and related-write plans. Related writes need their own security matrix for parent, child, inverse FK, collection attributes, composition, and orphan removal.

- Replace `LlmExposurePolicy.canModify(metaClass)` with operation-specific APIs: `canCreate`, `canUpdate`, and possibly `canModifyAttribute`, or document and fix `canModify` so create is not incorrectly update-gated.

- Normalize converter errors: either change `FilterLiteralValueConverter` for mutation use, or have `MutationErrorTranslator` remap `invalid_literal` / `unsupported_type` to `parameter_conversion_error`.

- Update prompt integration explicitly: modify `SystemPromptComposer` or inject `AgentSystemPromptRulesComposer` into `DefaultChatServiceImpl` for both blocking and streaming paths. Add tests for mutation-enabled and default prompts.

- Update `ToolNamePatternProvider` to include link and mutation tools, ideally from the same callback assembly path used by chat.

- Rework TEST-11 as two separate committed invocations, or avoid an outer test transaction so the first tool call's save and dedup write are visible before the second call.

- Use existing project test auth patterns with `SystemAuthenticator`, and avoid `@MockBean`/`@SpyBean` where the project has moved to `@MockitoBean` equivalents.

## Risk Assessment

**Overall risk: HIGH.** The phase goal is achievable, and the architecture is mostly sound, but the current plans contain several correctness and compilation blockers. The most serious are idempotency under concurrency, duplicate audit rows, incomplete related-write authorization, and tests that do not prove the claims they are meant to lock. I would revise those before autonomous execution.

---

## Consensus Summary

Only the Codex reviewer was invoked for this run because the command was `--codex`. A cross-reviewer consensus cannot be computed from a single reviewer, but the review produced a clear priority stack: fix idempotency semantics first, then audit ownership, related-write authorization, prompt/scanner wiring, and test compile/proof gaps.

### Agreed Strengths

- Single-reviewer run; no multi-reviewer agreement threshold is available.
- Codex found the phase decomposition, default-off guard, Jmix security direction, transactional boundary recognition, locale parity, and regression targets directionally strong.

### Agreed Concerns

- No multi-reviewer agreement threshold is available.
- The highest-priority Codex concerns are:
  - Idempotency reservation/replay is not concurrency-safe and lacks a request-shape hash.
  - Mutation audit ownership is ambiguous and may duplicate audit rows.
  - Related-write authorization and composition removal semantics are under-specified.
  - Prompt-rule and tool-name scanner integration may not wire into the current source.
  - Several planned tests likely do not compile or do not prove the intended behavior.

### Divergent Views

None. Only Codex was invoked in this cycle.
