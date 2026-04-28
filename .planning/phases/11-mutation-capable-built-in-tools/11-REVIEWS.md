---
phase: 11
reviewers: [codex]
reviewed_at: 2026-04-28T17:12:47Z
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
  - 11-11-PLAN.md
---

# Cross-AI Plan Review - Phase 11

## Codex Review

## Summary

The revised plan set is materially better than the prior cycle: the major architectural fixes for default-off registration, request-hash idempotency, mutation audit ownership, create/update exposure separation, prompt wiring, and scanner coverage are now explicitly planned. I would still block execution. The remaining blockers are less about phase direction and more about whether the plans can compile and whether the tests actually prove the guarantees claimed.

Docs checked: Jmix authorization/DataManager patterns and Spring AI tool docs via Context7: https://docs.jmix.io and https://docs.spring.io/spring-ai/reference/api/tools.html.

## Strengths

- Prior HIGH resolved: concurrency and conflicting-call idempotency now have explicit `PENDING/COMMITTED/FAILED` status plus `REQUEST_HASH`.
- Prior HIGH resolved: duplicate audit is addressed by keeping mutation callbacks out of `ToolCallbackAuditDecorator`.
- Prior HIGH resolved: create vs update exposure is separated via `canCreate` / `canUpdate`.
- Prior HIGH resolved: prompt wiring now names `SystemPromptComposer` and both `DefaultChatServiceImpl` paths.
- Prior HIGH resolved: `ToolNamePatternProvider` is extended to link and mutation tool names.
- Tests now avoid the earlier `ToolUserError.getCode()` and outer-transaction replay mistakes.

## Concerns

- **HIGH: 11-07 still has likely compile blockers.** It calls `toolEntityResolver.parseEntityId(metaClass, id)` even though 11-04 defines `parseEntityId(String id, MetaClass metaClass)`. It also assumes enum-flavoured `AiMutationIntent.getStatus()` accessors that 11-01 does not require, and uses APIs/imports such as `metadataTools.getDefaultFetchPlan(metaClass)` / `EntityValues` in ways the plan does not verify.

- **HIGH: Stable mutation error-code mapping is not actually enforced at tool boundaries.** 11-06 remaps `invalid_literal` / `unsupported_type`, but 11-07 catches `ToolUserError` before calling `MutationErrorTranslator`, so converter failures can still leak as `invalid_literal` instead of `parameter_conversion_error`. Blank IDs can still surface as `invalid_id`.

- **HIGH: Idempotency finalization is still fragile.** Reservation now prevents concurrent duplicate host writes, which resolves the prior HIGH. But the success path writes audit before `markCommitted`, and catch blocks in the examples cannot reliably `markFailed` because the reservation is scoped inside the try body. A failed `markCommitted` can leave a successful host mutation stuck as `PENDING`.

- **HIGH: Related-write execution remains under-specified.** The plan states the right security matrix, but helpers like `wireInverseReference`, `clearInverseReference`, `childBelongsToParent`, and composition detection are left as design placeholders. Prior related-write HIGH is only partially resolved.

- **HIGH: TEST-12 does not prove the required rollback/audit guarantee.** Spying `MutationSaveExecutor.save()` to throw may bypass the real transactional method body rather than simulate a post-flush commit failure. That proves catch/audit behavior, not durable audit across a rolled-back mutation transaction.

- **HIGH: The mutation test fixture may not be registered in Jmix metadata.** Adding a `@JmixEntity` under `src/test/java` plus Liquibase is not enough unless the test Jmix module scans it. Existing test comments indicate `AITestConfiguration` currently only has the add-on entities registered.

- **MEDIUM: `llmReadableAttributes` extraction acceptance is too weak.** The note says to preserve relationship-target filtering, but acceptance criteria would pass an implementation that loses the current "hide relationships to hidden target entities" behavior.

- **MEDIUM: Unwrapped mutation callbacks lose decorator side effects.** Avoiding duplicate audit is correct, but those callbacks also bypass streaming tool-call/tool-result events currently emitted by `ToolCallbackAuditDecorator`.

- **MEDIUM: `COMMIT_FAILED` is too narrowly tied to optimistic locking.** The success criteria mention save/commit failures generally, but the planned outcome mapping uses `COMMIT_FAILED` mostly for optimistic-lock paths.

## Suggestions

- Make 11-01 require enum-flavoured `getStatus()` / `setStatus(AiMutationIntentStatus)` on `AiMutationIntent`.
- Fix all `parseEntityId` call sites and add a compile-focused acceptance grep for the exact signature.
- Route every `ToolUserError` through `MutationErrorTranslator` in mutation tools, except already-stable `unknown_entity`, `access_denied`, `not_found`, and `idempotency_violation`.
- Hoist `ReservationResult reservation` outside the try block and require `markFailed` for every post-reservation failure path.
- Move `markCommitted` before success audit/result return, or explicitly handle mark-commit failure without writing contradictory audit rows.
- Split related writes into a smaller design/test plan with concrete fixture parent/child entities and explicitly supported relationship types.
- Rework TEST-12 to exercise a real transactional rollback path, not only a spy-thrown exception.
- Add a test-specific Jmix module/configuration that explicitly registers `MutationTestFixture`.

## Risk Assessment

**Overall risk: HIGH.** The revised plans address several prior architectural blockers, but execution is still likely to fail on compile/test wiring and still leaves idempotency finalization, integrated error-code mapping, related-write semantics, and TEST-12 proof incomplete. The phase is achievable, but I would revise before autonomous execution.

---

## Consensus Summary

Only the Codex reviewer was invoked for this run because the command was `--codex`. A cross-reviewer consensus cannot be computed from a single reviewer. The current review confirms that several prior HIGHs are fully resolved, but six HIGH-severity blockers remain in this cycle.

### Agreed Strengths

- Single-reviewer run; no multi-reviewer agreement threshold is available.
- Codex found the revised plan set materially stronger than the prior cycle, especially around default-off registration, idempotency request hashing, mutation audit ownership, create/update exposure separation, prompt wiring, scanner coverage, and corrected test API assumptions.

### Agreed Concerns

- Single-reviewer run; no multi-reviewer agreement threshold is available.
- Current HIGH concerns:
  - 11-07 still has likely compile blockers.
  - Stable mutation error-code mapping is not enforced at mutation tool boundaries.
  - Idempotency finalization can leave successful mutations stuck in `PENDING`.
  - Related-write execution remains under-specified.
  - TEST-12 does not prove durable audit across a rolled-back mutation transaction.
  - The mutation test fixture may not be registered in Jmix metadata.

### Divergent Views

None. Only Codex was invoked in this cycle.
