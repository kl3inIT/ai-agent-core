---
status: investigating
trigger: "LIVE UAT on :8088 (Claude Sonnet 4.6): confirming a bulk create throws; chat shows generic 'Đã xảy ra lỗi'. Iteration 2 of bulk-save-tool-not-exposed."
created: 2026-06-02
updated: 2026-06-02
---

# Debug Session: bulk-create-confirm-throws (iteration 2 of bulk-save-tool-not-exposed)

## Symptoms
<!-- IMMUTABLE after gathering. Provided by orchestrator (symptoms_prefilled). -->

expected: A request to create ≥2 records of the same entity is grouped into one proposal, and confirming it creates all rows via a single bulk_save_records call with one confirmation gate.
actual: The model GROUPS correctly (good — Workstream A reachability fix works), but on the executing turn it calls propose_action_choices (SINGLE-record tool) and passes an ARRAY of rows to its `values` param. Chat shows generic "Đã xảy ra lỗi"; nothing is created.
errors: |
  org.springframework.ai.tool.execution.ToolExecutionException
  caused by com.fasterxml.jackson.databind.exc.MismatchedInputException:
    Cannot deserialize value of type java.util.LinkedHashMap<String,Object> from Array value (token JsonToken.START_ARRAY)
  at org.springframework.ai.tool.method.MethodToolCallback.buildTypedArgument(MethodToolCallback.java:172)
  audit event_name: propose_action_choices, outcome ERROR
  arguments_json: {"operation":"create","targetEntityName":"Customer","instanceName":"Bulk Test A, Bulk Test B, Bulk Test C","values":"[{\"name\":\"Bulk Test A\",...},{...B...},{...C...}]"}
reproduction: Login admin/admin → ai-agent/chat → ask to create 3 Customers at once (e.g. "tạo 3 khách hàng Bulk Test A/B/C") → confirm. Model calls propose_action_choices with values=array → ToolExecutionException before method body → generic chat error.
started: After iteration-1 fix (Workstream A) made bulk reachable. Surfaced in Phase 17 live UAT 2026-06-02.

## Current Focus

hypothesis: For a ≥2-record same-entity request, the model called propose_action_choices (values: Map<String,Object>, single row) and passed an ARRAY. Spring AI MethodToolCallback fails to deserialize array→Map and throws ToolExecutionException BEFORE the method body runs → generic chat error. The new propose_bulk_action_choices exists but was not chosen because legacy single-record guidance competes with the bulk path.
test: read tool signatures + composer rules (done); design (1) unambiguous prompt steering to bulk for ≥2 records and (2) a defensive boundary so an array payload into the single tool yields a structured corrective error, not a 500.
expecting: prompt makes bulk the mandatory path for ≥2 same-entity rows; defensive boundary returns a corrective ActionProposalResult; a reproducing test asserts no unhandled exception.
next_action: research Spring AI MethodToolCallback array→Map deserialization, then choose the cleanest boundary shape that avoids raw Object @ToolParam.

## Eliminated

## Evidence

- timestamp: 2026-06-02
  checked: ActionProposalTool.proposeActionChoices signature (line 40-52)
  found: `values` is Map<String,Object>. An array payload cannot deserialize into a Map; Spring AI throws in buildTypedArgument before the method runs. The corrective error CANNOT be produced from inside the method as-is.
  implication: Defensive boundary must change the parameter shape so deserialization succeeds, then detect the multi-record shape inside the method.

- timestamp: 2026-06-02
  checked: AgentSystemPromptRulesComposer.ACTION_PROPOSAL_RULES (lines 38-39)
  found: Already mentions both tools, but legacy single-record line is listed FIRST and the "never pass an array as values" caveat is attached to the single-record bullet. The model defaulted to the familiar single tool.
  implication: Reorder + strengthen so bulk is the unambiguous mandatory path for ≥2 rows; make the single tool's "strictly one record" constraint explicit and separate.

- timestamp: 2026-06-02
  checked: DefaultChatServiceImpl error mapping (lines 838-843)
  found: Any uncaught RuntimeException from the chat client maps to `chatView.error.generic` ("Đã xảy ra lỗi"). The ToolExecutionException/MismatchedInputException from buildTypedArgument propagates here.
  implication: The generic error is the catch-all. To avoid it we must prevent the exception from being thrown, not catch it lower.

- timestamp: 2026-06-02
  checked: Spring AI 1.1.x behavior — GitHub issues #3924 + #4987 + Tool Calling reference
  found: Exceptions thrown during the ARGUMENT-BUILDING phase (MethodToolCallback.buildTypedArgument / JSON deserialization) occur BEFORE the tool method runs and are NOT wrapped in a processable ToolExecutionException. They escape ToolExecutionExceptionProcessor entirely. A ToolExecutionExceptionProcessor bean (alwaysThrow=false) would NOT convert this to a corrective string.
  implication: The ONLY robust boundary is to make deserialization SUCCEED for the bad shape, then detect "this is multiple records" inside the method body and return a structured corrective ActionProposalResult. Cannot rely on a processor bean.

- timestamp: 2026-06-02
  checked: Decisive evidence stack trace — `MismatchedInputException ... from Array value (token JsonToken.START_ARRAY)` binding to `java.util.LinkedHashMap<String,Object>`
  found: The model called propose_action_choices and put an ARRAY of row objects into `values` (typed Map<String,Object>). Jackson fails START_ARRAY → Map.
  implication: If `values` accepted a JSON STRING (always deserializes from any token via toString of raw, or declared String), the method could parse + detect array shape. But changing the proven single-record `values: Map` schema risks single-record regressions. Chosen approach: keep `values: Map` for the happy path AND add a defensive sibling string param so a stray array lands somewhere parseable — REJECTED as too clever. FINAL approach below.

- timestamp: 2026-06-02
  checked: Spring AI 1.1.4 sources — MethodToolCallback.buildTypedArgument (line 155-173), DefaultToolCallingManager.executeToolCall (line 240-244), DefaultToolExecutionExceptionProcessor.process, ToolCallbackAuditDecorator.callInternal
  found: buildTypedArgument(array, Map<String,Object>) → JsonParser.fromJson("[...]", Map) → MismatchedInputException → caught & rewrapped as ToolExecutionException(cause=MismatchedInputException). The audit decorator catches Throwable, writes ERROR audit (matches the live audit row), re-throws. DefaultToolCallingManager.executeToolCall catches ToolExecutionException and routes to DefaultToolExecutionExceptionProcessor (alwaysThrow=false, no rethrown list) → since cause is a RuntimeException, it returns the raw Jackson message STRING to the model rather than throwing.
  implication: The exception is technically handled by the framework, but the model receives a raw, unhelpful Jackson error ("Cannot deserialize ... from Array value") with NO instruction to switch tools. The model wastes the turn and surfaces a generic failure to the user. The robust fix is to (1) steer ≥2-record requests to propose_bulk_action_choices and (2) prevent the deserialization failure entirely so the single tool returns a STRUCTURED corrective ActionProposalResult that explicitly names the right path.

reasoning_checkpoint:
  hypothesis: "For ≥2 same-entity records the model called propose_action_choices with an ARRAY in `values` (Map<String,Object>); Jackson's array→Map failure in MethodToolCallback.buildTypedArgument produces a raw error returned to the model with no corrective guidance, wasting the turn and surfacing a generic failure. The bulk tool exists but legacy single-record prompt guidance competed and the model defaulted to the familiar single tool."
  confirming_evidence:
    - "Live audit row: event_name=propose_action_choices, outcome ERROR, error_class ToolExecutionException, values=array of 3 rows"
    - "Stack trace: MismatchedInputException from Array value (START_ARRAY) → LinkedHashMap<String,Object> at MethodToolCallback.buildTypedArgument:172"
    - "Spring AI source confirms array→Map throws before the method body; the corrective message cannot originate from inside the current method as-is"
  falsification_test: "If a deserialization-tolerant `values` param made the method body run on an array payload, the method would return a structured INVALID/corrective result instead of throwing — and a ≥2-record request steered by the strengthened prompt would call propose_bulk_action_choices, not propose_action_choices. A test passing an array shape into the single path must yield a corrective result, not an exception."
  fix_rationale: "Two layers: (1) PRIMARY prompt steering makes propose_bulk_action_choices the unambiguous mandatory path for ≥2 same-entity rows and explicitly forbids arrays in propose_action_choices.values; (2) DEFENSIVE — add an optional sibling rows param (List<Map>) to propose_action_choices so a model that intends a batch but reaches for the single tool lands in a parseable param; when multi-record input is detected, return a structured corrective ActionProposalResult (new INVALID message) telling the model to use the bulk tool. Avoids raw Object @ToolParam. Keeps the proven single-record values:Map schema intact for the happy path."
  blind_spots: "If the model literally puts the array under the `values` key (not the new rows param), Jackson still throws before the method. Mitigation: the prompt change makes that far less likely, and the framework already converts that specific failure into a model-visible message (no user-facing crash). The sibling-rows defensive param + a tolerant detection path covers the realistic 'reached for single tool but supplied multiple rows under a list-typed arg' case."

## Resolution

root_cause: |
  For a ≥2-record same-entity request the model GROUPED correctly (the iteration-1 reachability fix
  worked) but on the executing planning turn it called propose_action_choices (the SINGLE-record
  tool, values: Map<String,Object>) and passed an ARRAY of row objects to `values`. Spring AI 1.1.4's
  MethodToolCallback.buildTypedArgument runs JsonParser.fromJson("[...]", Map<String,Object>) →
  Jackson MismatchedInputException (START_ARRAY → Map) → ToolExecutionException, thrown during
  ARGUMENT BINDING, before the tool method body. On the streaming chat path this error reaches
  MessageAggregator as an error signal (confirmed in jmix-app/.bootrun3.out.log lines 188-244) and
  DefaultChatServiceImpl maps any uncaught RuntimeException to chatView.error.generic ("Đã xảy ra
  lỗi"); nothing is created. The new propose_bulk_action_choices existed but the legacy
  single-record planning guidance competed with it and the model defaulted to the familiar single
  tool. Spring AI's ToolExecutionExceptionProcessor cannot rescue this (the failure is in arg
  binding; GitHub spring-ai #3924/#4987), so the only robust boundary is to make the deserialization
  SUCCEED and detect the bad shape inside the method.

fix: |
  PRIMARY (prompt steering) — AgentSystemPromptRulesComposer.ACTION_PROPOSAL_RULES rewritten to
  "COUNT THE RECORDS FIRST, then pick the tool": EXACTLY ONE → propose_action_choices (values is a
  single object; NEVER an array); TWO OR MORE of the same entity → you MUST call
  propose_bulk_action_choices ONCE with valuesList ("the ONLY correct path for multiple records").
  Added explicit self-correction: if propose_action_choices returns WRONG_TOOL_FOR_BULK, re-issue via
  propose_bulk_action_choices. The competing legacy "single-record first" framing is removed.

  DEFENSIVE (no user-facing 500 on a model slip) — new array-tolerant value type
  action/SingleRecordValues with a custom @JsonDeserialize that accepts BOTH a JSON object (→ single
  record) and a JSON array (→ multiRecord=true, rows captured) WITHOUT throwing. propose_action_choices
  `values` param retyped from Map<String,Object> to SingleRecordValues. When multiRecord is detected
  the tool returns a STRUCTURED corrective ActionProposalResult.useBulkTool (action
  use_bulk_action_choices, status WRONG_TOOL_FOR_BULK, model-directed message naming
  propose_bulk_action_choices) — the model self-corrects next turn instead of getting a generic crash.
  @JsonDeserialize does NOT alter the advertised JSON Schema: a schema-stability test asserts `values`
  still serializes as an object (never an array), so the single-record happy path is unchanged. No raw
  Object @ToolParam used (project rule).

verification: |
  Targeted suites green:
  - action.ActionProposalToolTest: 7 tests, 0 failures (incl. 3 new: corrective-result-not-throw,
    array→multiRecord deserialize, object→single deserialize).
  - extraction.AgentToolCallbacksIntentGatingTest: 12 tests, 0 failures (incl. new schema-stability
    test proving `values` stays an object after the type change).
  - guard.AgentSystemPromptRulesComposerIntentTest: 6 tests, 0 failures (incl. new test asserting bulk
    is the mandatory path, arrays forbidden in single tool, WRONG_TOOL_FOR_BULK self-correction).
  - guard.AgentSystemPromptRulesComposerTest + AgentSystemPromptRulesTest + ToolNavigationLeakScannerTest
    + DefaultChatService* : all pass (no tool-name leak; prompt substrings intact).
  - Mutation + performance constraint: 107 tests, 2 skipped, 0 failures, 0 errors (baseline preserved;
    Phase 17 internals untouched).
  - Full :ai-agent:test : (see final report).

files_changed:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/action/SingleRecordValues.java (new)
  - ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionProposalTool.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionProposalResult.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRulesComposer.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/action/ActionProposalToolTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AgentSystemPromptRulesComposerIntentTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/AgentToolCallbacksIntentGatingTest.java

## Follow-up note (not in scope)
- create_record / update_record `attributes` (Map<String,Object>) in BuiltInMutationTools have the
  same theoretical array-misroute vulnerability, but they are attached only on the CREATE_NOW/
  BULK_CREATE_NOW action turn where the prompt names a single tool, and changing them touches Phase 17
  mutation internals (out of scope). If a future UAT shows the model arraying `attributes`, apply the
  same SingleRecordValues pattern there.
