---
status: fixing
trigger: "LIVE UAT on :8088 (Claude Sonnet 4.6, iterations 1+2 loaded): no crash, model groups correctly, but reports 'công cụ tạo hàng loạt (propose_bulk_action_choices) không có trong bộ công cụ hiện tại của tôi' and falls back to per-row. Iteration 3 of bulk-save-tool-not-exposed."
created: 2026-06-02
updated: 2026-06-02
---

# Debug Session: bulk-create-allowlist-collision (iteration 3 of bulk-save-tool-not-exposed)

## Symptoms
<!-- IMMUTABLE after gathering. Provided by orchestrator (symptoms_prefilled). -->

expected: A request to create >=2 records of the same entity is grouped into one proposal, and the model renders a single bulk confirmation via propose_bulk_action_choices (no crash, no per-row fallback).
actual: No crash anymore (iteration-2 SingleRecordValues corrector works). The model groups the request correctly, but reports that propose_bulk_action_choices "không có trong bộ công cụ hiện tại của tôi" (is not in its current toolset) and falls back to per-row creation. The internal tool name is also leaked to the user.
errors: |
  No exception. The model states the bulk tool is absent. The enabledTools allowlist enumerated
  in the planning turn does not include propose_bulk_action_choices.
reproduction: Login admin/admin -> ai-agent/chat -> ask to create 3 Customers at once -> the model groups them but says the bulk tool is unavailable and creates per-row instead.
started: After Workstream B wired the enabledTools allowlist enforcement into AgentToolCallbacks (intersection with intent routing), on top of Workstream A which added propose_bulk_action_choices as a new tool. The seeded allowlist predates the new tool.

## Current Focus

reasoning_checkpoint:
  hypothesis: "Workstream A added propose_bulk_action_choices as a new proposal/orchestration @Tool. Workstream B then made AgentToolCallbacks.applyAllowlist intersect the per-turn toolset with the active AiParameters enabledTools allowlist. The seeded/active allowlist (created before the bulk tool existed) lists propose_action_choices but NOT propose_bulk_action_choices. On the planning turn (intentId == null, line 226) applyAllowlist intersects propose_bulk_action_choices OUT -> the model genuinely lacks it -> cannot render the bulk proposal -> falls back to per-row create."
  confirming_evidence:
    - "Live: model states 'propose_bulk_action_choices không có trong bộ công cụ hiện tại của tôi' and the planning-turn allowlist it enumerated does not contain that tool."
    - "AgentToolCallbacks.callbacksFor(intentId==null) at line 226 passes the full planning toolset through applyAllowlist(..., enabledTools) which filters by exact tool name."
    - "prepare_form_draft is already exempt: the PREFILL_FORM / named-intent paths return singlePrepareFormDraftCallback() directly, bypassing applyAllowlist. The proposal tools have no such exemption."
    - "Existing test enabledToolsAllowlistRestrictsPlanningTurnToIntersection codified the buggy behavior: it asserts an allowlist of {propose_action_choices} yields exactly that and excludes propose_bulk_action_choices."
  falsification_test: "If proposal/orchestration tools were exempt from applyAllowlist, then callbacksFor(planning, enabledTools without propose_bulk_action_choices) would STILL include propose_bulk_action_choices, while a non-exempt business tool absent from the allowlist would still be excluded. A regression test asserting this must pass."
  fix_rationale: "Proposal/orchestration tools (propose_action_choices, propose_bulk_action_choices, prepare_form_draft) are internal UX scaffolding, not business/data tools. The admin enabledTools allowlist is meant to gate business/data/mutation tools. Exempt the orchestration tool names from applyAllowlist filtering (same treatment prepare_form_draft already has via the named-intent bypass). Mutation/read/business tools remain gated -> Workstream B security intent preserved, no re-widening."
  blind_spots: "An admin could theoretically WANT to disable the proposal machinery via the allowlist; but that breaks the agent's own UX contract rather than enforcing a data policy, so exemption is correct. Also re-check no-leak (Workstream C): the model leaked the tool name while explaining absence; once the tool is reachable the trigger is gone, but add a focused rule that absent/unavailable tools are never named either."

next_action: Apply the orchestration-tool exemption in AgentToolCallbacks.applyAllowlist; update the buggy test; add regression test; reinforce no-leak; run targeted + mutation/perf (107) + full :ai-agent:test.

## Eliminated

## Evidence

- timestamp: 2026-06-02
  checked: AgentToolCallbacks.callbacksFor / applyAllowlist (lines 223-263) and the prepare_form_draft exemption
  found: The planning turn (intentId null) at line 226 calls applyAllowlist(auditedNonMutationCallbacks(false, true), enabledTools). applyAllowlist (251-263) keeps ONLY callbacks whose name is in enabledTools. prepare_form_draft is exempt only because PREFILL_FORM / named intents return singlePrepareFormDraftCallback() directly (lines 241-243), never touching applyAllowlist. The proposal tools (propose_action_choices, propose_bulk_action_choices) go through applyAllowlist with no exemption.
  implication: Any enabledTools allowlist that omits propose_bulk_action_choices removes it from the planning turn. The seeded allowlist predates the tool, so it is omitted -> the collision.

- timestamp: 2026-06-02
  checked: ActionProposalTool tool-name constants (lines 16-17) and AgentToolCallbacks.PREPARE_FORM_DRAFT_TOOL_NAME (line 67)
  found: TOOL_NAME=propose_action_choices, BULK_TOOL_NAME=propose_bulk_action_choices, PREPARE_FORM_DRAFT_TOOL_NAME=prepare_form_draft. These three are the orchestration/proposal/draft infrastructure tools.
  implication: The exemption set is exactly these three names. Mutation tool names (MUTATION_TOOL_NAMES) and read/business tools must stay subject to the allowlist.

- timestamp: 2026-06-02
  checked: AgentSystemPromptRules.PROMPT_RULES no-leak block (lines 81-92)
  found: The no-leak rule already lists propose_action_choices and propose_bulk_action_choices as names NEVER to reveal. The live leak is the model EXPLAINING the tool's absence, a different failure mode (not narrating tool use). Once the tool is reachable the trigger disappears.
  implication: Primary fix removes the leak trigger. Secondary: add a focused rule that unavailable/absent tools are never named either, to harden against the same class.

- timestamp: 2026-06-02
  checked: default-params.yaml (enabledTools: null) and AiParametersBody javadoc (enabledTools null = all allowed)
  found: The code default is null (all tools allowed) so a fresh deployment does NOT hit the bug. The live env has a seeded/admin non-null allowlist that omits the new tool.
  implication: The durable fix is the code exemption (covers any admin allowlist). No seed allowlist exists in code to amend; the YAML default stays null.

## Resolution

root_cause: |
  Collision between two prior workstreams. Workstream A added propose_bulk_action_choices as a new
  proposal/orchestration @Tool. Workstream B then wired AgentToolCallbacks.applyAllowlist to enforce
  the active AiParameters enabledTools allowlist as an INTERSECTION with the per-turn intent routing.
  The seeded/active allowlist was created BEFORE propose_bulk_action_choices existed and lists
  propose_action_choices but NOT propose_bulk_action_choices. On the planning turn (intentId == null)
  applyAllowlist intersects propose_bulk_action_choices OUT of the toolset -> the model genuinely does
  not have it -> it cannot render the bulk proposal -> it explains the absence (leaking the internal
  tool name) and falls back to per-row create. prepare_form_draft escaped this only because its
  named-intent path bypasses applyAllowlist entirely; the proposal tools had no such exemption.

fix: |
  PRIMARY (orchestration-tool allowlist exemption) — AgentToolCallbacks.applyAllowlist now exempts the
  proposal/draft orchestration tool names (propose_action_choices, propose_bulk_action_choices,
  prepare_form_draft) from enabledTools filtering: a callback survives if it is exempt OR its name is in
  the allowlist. These are internal UX scaffolding, not business/data tools, so the content allowlist
  does not gate them (same spirit as prepare_form_draft's existing named-intent bypass). Mutation/read/
  business tools remain fully gated by the allowlist -> Workstream B security intent preserved; the
  exemption only adds the agent's own proposal machinery, never widens business/mutation tools.

  SECONDARY (no-leak reinforcement) — AgentSystemPromptRules.PROMPT_RULES no-leak block extended with a
  rule that the assistant must NEVER name an internal/unavailable tool when a capability is missing;
  instead it explains the limitation in plain business language. Hardens against the model surfacing a
  tool name while describing absence.

verification: |
  (pending test run)

files_changed:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/AgentToolCallbacksIntentGatingTest.java
