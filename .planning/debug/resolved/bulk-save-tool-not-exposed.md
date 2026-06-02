---
status: resolved
trigger: "bulk_save_records tool not reaching the LLM — agent (Claude Sonnet 4.6) says it lacks the tool and falls back to per-row create_record"
created: 2026-06-02
updated: 2026-06-02
---

# Debug Session: bulk-save-tool-not-exposed

## Symptoms

- **Expected:** When the user asks the agent to create/save ≥2 records of the same entity, the agent should call `bulk_save_records` (one batched call, one confirmation gate).
- **Actual:** The agent states "Tôi không có công cụ `bulk_save_records` trong bộ công cụ hiện tại" and falls back to calling `create_record` per row (N separate confirmation gates). Observed live on :8088 with model `anthropic/claude-sonnet-4-6` during Phase 17 UAT.
- **Error messages:** No stack trace. The model simply behaves as if the tool is absent from its toolset.
- **Timeline:** Surfaced during Phase 17 UAT (2026-06-02). Phase 17 was an internal refactor that did NOT touch the `@Tool`/`@ToolParam`/signature.
- **Reproduction:** Login admin/admin → ai-agent/chat → ask "tạo 5 đơn hàng cùng lúc cho khách hàng X bằng thao tác hàng loạt" → agent claims no bulk tool, proposes 5 per-row creates.

## Evidence (ruled IN / verified)

- timestamp: 2026-06-02 — `BuiltInMutationTools.bulkSaveRecords` carries `@Tool(name="bulk_save_records")` at line 276 (annotation intact).
- timestamp: 2026-06-02 — `bulk_save_records` IS present in the active `ai_agent_parameters.body_yaml` `enabledTools` allowlist. (Moot: see ELIMINATED — the allowlist is not wired into the runtime tool surface at all.)
- timestamp: 2026-06-02 — Active model is `anthropic/claude-sonnet-4-6` (reliable tool-caller), so not mere hallucination.
- timestamp: 2026-06-02 — **DECISIVE**: ran `JsonSchemaGenerator.generateForMethodInput` + `MethodToolCallbackProvider` against all 5 mutation @Tool methods. `bulk_save_records` inputSchema is WELL-FORMED Draft-2020-12: `records` => `{"type":"array","items":{"type":"object"},"description":"..."}`, top-level `required:[entityName,records,idempotencyKey]`, `additionalProperties:false`. NOT empty/invalid. Spring AI 1.1.4 OpenAI serialization (`OpenAiChatModel.getFunctionTools` → `FunctionTool.Function(desc,name,jsonSchema)` → `ModelOptionsUtils.jsonToMap`) passes this through verbatim. So the tool IS transmittable and is NOT dropped/mangled by Spring AI.
- timestamp: 2026-06-02 — `create_record.attributes` schematizes to a propertyless `{"type":"object"}` and works — proving the OpenRouter→Anthropic provider tolerates propertyless objects; the only structural delta for bulk (array-nesting) is standard JSON Schema. Schema-rejection hypothesis weakened to near-zero.
- timestamp: 2026-06-02 — `AgentToolCallbacksMutationEnabledTest` confirms `forCurrentUser()` returns 15 callbacks INCLUDING `bulk_save_records`. Assembly is correct.
- timestamp: 2026-06-02 — **DECISIVE root cause**: live chat NEVER uses `forCurrentUser()` (only `ParametersDetailView` diagnostic does). Both chat paths (`DefaultChatServiceImpl` streaming line 637 + blocking line 1097) use `AgentToolCallbacks.callbacksFor(userId, convId, intentId)`:
    - `intentId == null` (planning/auto turn): `auditedNonMutationCallbacks(false, true)` → ZERO mutation tools (bulk AND create absent).
    - `intentId == CREATE_NOW`: adds all 5 mutation callbacks (bulk present).
    - else: only `prepare_form_draft`.
- timestamp: 2026-06-02 — The create/update flow is funneled through the action-proposal mechanism, which is HARD-LIMITED to ONE record per proposal: `ActionProposalTool.proposeActionChoices` description line 30 "This tool validates ONE record per call. Do not pass an array as values"; `values` is `Map<String,Object>` (single row). `AgentSystemPromptRulesComposer.ACTION_PROPOSAL_RULES` line 40 explicitly forbids bulk in the planning turn: "Do not call create_record, update_record, or bulk_save_records during the planning turn." For multi-record it instructs "propose separate single-record choices; never pass an array as values."
- timestamp: 2026-06-02 — The CREATE_NOW turn's system prompt (`effectiveActionRules(CREATE_NOW)`, lines 99-107) names ONLY `create_record`: "Call create_record only for the selected target entity and only with those collected values." It never mentions `bulk_save_records`, and the proposal that triggered it carried a single row.

## Current Focus

hypothesis: CONFIRMED — `bulk_save_records` is unreachable through the live action-proposal create/update flow. The bug is a tool-surface + system-prompt ROUTING gap, NOT a JSON-schema defect. In a normal create turn the mutation tools are only attached during a CREATE_NOW action turn, and that turn's instructions (plus the one-record-per-proposal action mechanism) steer the model exclusively to `create_record`. When the user explicitly requests batch ("thao tác hàng loạt"), the model has no prompt-sanctioned path to bulk and correctly reports it as unavailable.
test: (done) generated schemas; traced both live `callbacksFor` call sites; read action-proposal tool + composer rules.
expecting: (met) bulk schema well-formed; bulk only in CREATE_NOW callback set; CREATE_NOW/planning prompts steer to create_record only; propose_action_choices is single-record-only.
next_action: RESOLVED — three workstreams implemented, tested, and committed. See Resolution.

## Eliminated

- hypothesis: Excluded by enabledTools allowlist — ELIMINATED (the `enabledTools` field on `AiParametersBody` is referenced only by the parameters record + detail view; it is NOT wired into `ChatClientFactory`/`DefaultChatServiceImpl`/`AgentToolCallbacks` runtime tool assembly).
- hypothesis: @Tool annotation dropped by Phase 17 refactor — ELIMINATED (annotation + signature intact).
- hypothesis: Spring AI generates an invalid/empty input schema for `List<Map<String,Object>>` that is dropped at assembly or rejected by the provider — ELIMINATED (schema is well-formed Draft-2020-12 and transmitted verbatim; `create_record` proves propertyless objects are accepted; all 5 callbacks assemble; bulk present in the 15-tool `forCurrentUser()` set).
- hypothesis: GuardedToolCallingManager caps/filters the tool definitions — ELIMINATED (`resolveToolDefinitions` is pure delegation; no count cap on definitions).
- hypothesis: tool-description length/encoding causes provider to drop bulk — ELIMINATED as primary (4607 bytes; `add_related_record`/`create_record` are similarly long and work).

## Resolution

root_cause: `bulk_save_records` is never reachable through the live chat create/update flow. The live tool surface for mutations is attached ONLY during a `CREATE_NOW` action turn (`AgentToolCallbacks.callbacksFor(..., CREATE_NOW)`), and the create flow is funneled through the single-record `propose_action_choices` action-proposal mechanism. The active system prompts forbid bulk during planning (`ACTION_PROPOSAL_RULES`) and name only `create_record` during create-now (`effectiveActionRules(CREATE_NOW)`). There is no path that both (a) attaches `bulk_save_records` to the request AND (b) instructs / allows the model to call it with a multi-row payload. The model's "I don't have bulk_save_records" is faithful to the active routing, not a hallucination and not a Spring-AI schema defect.

fix: Three atomic workstreams (all green; mutation+performance suites unchanged at 107 tests / 0 failures; full ai-agent suite BUILD SUCCESSFUL).

  WORKSTREAM A — bulk_save_records reachable via a single batch confirmation gate (ONE gate for N rows):
  - ActionIntentId.BULK_CREATE_NOW added (sibling of CREATE_NOW).
  - ActionProposal gained an optional valuesList (rows) + isBulk(); single-record ctor preserved.
  - ActionProposalTool.proposeBulkActionChoices (>=2 rows of the same entity) — additive, single-record propose_action_choices untouched.
  - ActionProposalService.validateBulk: validates each row (attribute names + required fields), authorizes like create-now, emits ONE READY proposal carrying all rows with the BULK_CREATE_NOW choice.
  - AgentToolCallbacks.callbacksFor: BULK_CREATE_NOW attaches the same mutation tool surface as CREATE_NOW.
  - AgentSystemPromptRulesComposer: planning rules route multi-record requests to propose_bulk_action_choices; effectiveActionRules(BULK_CREATE_NOW) instructs the model to call bulk_save_records EXACTLY ONCE with the full rows array + a single fresh idempotencyKey.
  - ChatPanelFragment / StreamEventRenderer: render a single "Create all N" confirm button; the bulk turn carries the rows JSON. New i18n key chatView.actionChoice.bulkCreateNow in en + vi.

  WORKSTREAM B — enabledTools allowlist enforced at runtime (was a no-op security/exposure gap):
  - AiParametersResolver.effectiveEnabledTools(active): reads the allowlist from the active profile body YAML; null/absent/empty => null ("all allowed", preserves prior behavior); non-empty => selected tool names (blanks dropped).
  - AgentToolCallbacks.callbacksFor(..., enabledTools): new overload INTERSECTS the per-turn intent routing with the allowlist. A tool is exposed only if the intent routing includes it AND it is in the allowlist. Mutation tools respect it identically; the allowlist can only narrow, never widen. The structural prepare_form_draft fail-closed path is exempt.
  - DefaultChatServiceImpl: both chat call sites (streaming + blocking) pass parametersResolver.effectiveEnabledTools(active).

  WORKSTREAM C — no-leak system-prompt rule:
  - AgentSystemPromptRules.PROMPT_RULES gained an always-on "Do not leak internals" block: NEVER reveal internal tool names (read + mutation), NEVER show raw stable error codes (access_denied, validation_failed, parameter_conversion_error, idempotency_violation, concurrent_modification, unknown_entity, not_found), NEVER narrate step-by-step tool-call reasoning. prepare_form_draft excluded from examples to preserve the forward-reference-safety assertion.

verification: All targeted suites pass. Mutation + performance: 107 tests, 0 failures, 0 errors (matches pre-change baseline). Full ai-agent test suite: BUILD SUCCESSFUL. New/updated tests cover: bulk proposal validation (ActionProposalServiceTest +4), bulk tool delegation (ActionProposalToolTest +2), bulk callback routing + allowlist intersection/no-widen/null-preserves (AgentToolCallbacksIntentGatingTest +6), bulk/create-now/planning rules (AgentSystemPromptRulesComposerIntentTest +3), no-leak rule presence (AgentSystemPromptRulesComposerTest +2), enabledTools parsing (AiParametersResolverTest +4). Count-assertion + chat-mock test files updated for the new tool and 4-arg callbacksFor overload.

files_changed:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionIntentId.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionProposal.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionProposalService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionProposalTool.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRulesComposer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/StreamEventRenderer.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties

## Constraints

- Do NOT change Phase 17 mutation-internals BEHAVIOR. Mutation/performance suites must stay green.
- Build/test from `D:\DTH\ai-agent-core\ai-agent`. App may be running on :8088 — do not start a second instance.
- `gsd-sdk` shim broken — use `node "$HOME/.claude/get-shit-done/bin/gsd-tools.cjs"` or plain `git`.
