---
phase: 04
fixed_at: 2026-04-20T14:00:00Z
review_path: .planning/phases/04-orchestration-core/04-REVIEW.md
iteration: 1
findings_in_scope: 8
fixed: 7
skipped: 1
status: partial
---

# Phase 04: Code Review Fix Report

**Fixed at:** 2026-04-20
**Source review:** `.planning/phases/04-orchestration-core/04-REVIEW.md`
**Iteration:** 1

**Summary:**
- Findings in scope: 8 (4 Medium + 4 Low)
- Fixed: 7
- Skipped: 1 (MD-01 — false premise; see below)
- Additional IntelliJ/inspection fixes: 0 (JetBrains MCP not available in this session; manual review of Phase 04 files surfaced no legitimate issues beyond the review findings)

All fixes compile cleanly (`./gradlew :ai-agent:ai-agent:compileJava` and `compileTestJava` both exit 0) and the full `:ai-agent:ai-agent:test` suite passes.

## Fixed Issues

### MD-02: `AiParametersResolver.buildFallback` builds YAML via unescaped string concatenation

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java`
**Commit:** `b8dce3e`
**Applied fix:** Dropped the synthetic YAML blob entirely. `buildFallback` now returns an `AiParameters` with `bodyYaml=null`. Each `effectiveXxx` accessor checks for null/blank `bodyYaml` first and falls through to `AiAgentDefaultsProperties` directly when absent. Eliminates the string-concatenation booby-trap for operator-supplied defaults containing YAML-significant characters (`:`, `#`, leading `-`, multiline).

### MD-03: `AuditWriter` silently drops conversation FK on stale ids

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java`
**Commit:** `b81c304`
**Applied fix:** Added WARN log in all three branches (`writeChatPre`, `writeChatPost`, `writeToolCall`) when `conversationId != null` but the `dataManager.load(...).optional()` lookup returns empty. Log message includes the conversationId and runId so operators can distinguish "conversation vanished mid-flight" from "chat never had a conversation".

### MD-04: `ToolCallbackAuditDecorator` writes uncapped `argumentsJson`

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java`
**Commit:** `09e66b1`
**Applied fix:** Introduced `ARGUMENTS_JSON_MAX_CHARS = 4096` (symmetric with `RESULT_SUMMARY_MAX_CHARS`) and a shared `cap(value, maxChars)` helper that appends `"…[truncated]"` when truncation occurs. Both PRE and POST `writeToolCall` sites now pass `cappedInput` instead of raw `toolInput`. The `errorMessage` branch of `resultSummary` is now also capped (previously uncapped).

### LO-01: `ChatClientFactory` hardcodes English fallback system prompt

**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiAgentDefaultsProperties.java`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java`

**Commit:** `0458be3`
**Applied fix:** Took reviewer option (b) — kept the literal non-i18n (it's a model-directed instruction, not UI text) but lifted it to a public constant `AiAgentDefaultsProperties.FALLBACK_SYSTEM_PROMPT`. `ChatClientFactory` references the constant. Rationale documented in Javadoc.

### LO-02: `ProjectingChatMemoryRepository.findByConversationId` not marked readOnly

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ProjectingChatMemoryRepository.java`
**Commit:** `f0ab708`
**Applied fix:** Changed `@Transactional` to `@Transactional(readOnly = true)` on `findByConversationId`. `findConversationIds` left untouched — not currently `@Transactional`, which is still correct (it doesn't start a new tx). `saveAll` and `deleteByConversationId` remain read-write.

### LO-03: `AuditAdvisor.hashPrompt` may emit inconsistent hashes

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditAdvisor.java`
**Commit:** `c4f2498`
**Applied fix:** Dropped both `request.prompt().toString()` branches (the cold fallback in the try block and the catch handler). When the USER-message scan and the last-instruction lookup both miss, `text` is now set to `""`. Preserves the "same user text ⇒ same promptHash" invariant even when framework metadata (options, tool-call results) would otherwise leak into the hash.
**Logic verification:** This change touches a derived hash value — behavior is now stable but the empty-string hash case is new. Flagged as `fixed: requires human verification` for the developer to confirm no test asserts the old behavior.

### LO-04: `BaselineContextProvider.extractUserKey` reflects on any method named `getKey`

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java`
**Commit:** `3224238`
**Applied fix:** Tightened the reflection to only accept `UUID` or `String` return values from the reflected `getKey()`. Anything else falls through to `user.getUsername()`. Prevents a host that swaps `UserDetails` for a class whose `getKey()` returns an AES key / API token / internal handle from leaking that value into the `agent.userId` context slot and the LLM-facing system prompt.

## Skipped Issues

### MD-01: `ConversationGateway` explicitly overwrites `createdDate`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ConversationGateway.java:64`
**Reason:** False premise. The reviewer assumed `AiConversation` uses Jmix's `@CreatedDate` / `@CreatedBy` auditing annotations, but the entity does NOT (confirmed by reading `AiConversation.java` — the `createdBy` and `createdDate` fields are plain `@Column` annotations with no Jmix/Spring audit annotation; `grep -r "@CreatedDate"` across `ai-agent/` returns zero matches across all entities in the codebase). Removing the explicit `fresh.setCreatedDate(OffsetDateTime.now())` would leave `createdDate` as NULL. The manual set is actually the correct approach given the entity's current shape.

**Alternative future path:** If the team wants to adopt Jmix auditing, the correct fix is to (1) add `@CreatedDate` / `@CreatedBy` annotations on the entity, (2) wire the audit listener, and (3) then remove the manual set. That's a multi-file refactor across the whole entity layer (other entities would be affected for consistency), which is out of scope for a code-review fix pass.

**Original issue:** On auto-create, the gateway sets `fresh.setCreatedDate(OffsetDateTime.now())` explicitly. Reviewer suggested dropping this and relying on Jmix `@CreatedDate` auditing.

## Additional IntelliJ / Inspection Fixes

**JetBrains MCP availability:** The `mcp__jetbrains__*` tool family was NOT exposed in this session's tool list (only standard Read/Edit/Write/Bash/Grep/Glob were available). Per the scope instructions, I performed a manual inspection pass over the Phase 04 production files (19 Java files across `orchestration/`, `audit/`, plus the spot-checked `ChatResponseDto`, `ConversationNotFoundException`, `RunContext`, `ToolCallAdvisorBuilderProbe`, `AuditListenerFanOut`) looking for:

- **Unused imports:** none found — every import statement is referenced.
- **Abbreviated identifiers (per user memory: no `uei`, `mc`, `mp`, `dt`, etc.):** none found. Locals use `user`, `method`, `result`, `instructions`, `context`, `runId`, `conversationId`, etc. — all fully spelled.
- **Obviously dead code:** none found. `ToolCallAdvisorBuilderProbe` is documentation-only constants (intentional — referenced symbolically to surface Spring AI API drift as compile errors) and not dead.
- **Style nits (`var`, wildcard imports, redundant modifiers):** none found. No wildcard imports. Effective use of `var` for local type inference.

Zero additional fixes applied. If the developer wants a proper SonarQube / IntelliJ inspection sweep, re-run this pass in a session where the JetBrains MCP is exposed.

## Test Status

`./gradlew :ai-agent:ai-agent:test` — all tests pass (exit 0). The MD-02 behavior change (null `bodyYaml` on fallback) does not break any existing test — the resolver's accessors still produce the same effective values, just via a different code path.

---

_Fixed: 2026-04-20_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
