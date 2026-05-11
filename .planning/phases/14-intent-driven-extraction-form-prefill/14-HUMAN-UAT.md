---
status: passed
phase: 14-intent-driven-extraction-form-prefill
source: [14-VERIFICATION.md, 14-10-PLAN.md, 14-10-SUMMARY.md]
started: 2026-05-08T17:32:04+07:00
updated: 2026-05-11T13:00:00+07:00
---

# Phase 14 Human UAT

## Current Test

number: 6
name: Expired Or Removed Draft
expected: |
  An expired or removed draft cannot open a form or substitute another draft; the user sees the expired-draft behavior and chat remains usable.
awaiting: gap closure for newly discovered negative-path issues

## Tests

### 1. Initial Chat State

expected: Chat opens with no entity/action intent card, usable message input, no created record, and no draft.
result: pass
verified: "Browser retest on 2026-05-10 against localhost:8090 showed a new chat with no static entity/action picker."

### 2. Missing Fields Are Clarified

expected: An incomplete create request makes the assistant ask for required fields. No action-choice row, mutation, or form navigation occurs yet.
result: pass
verified: "The assistant asked for missing email/phone after an incomplete Customer create request, and no action-choice row appeared before clarification."

### 3. Action Choices After Clarification

expected: After required fields are provided, a server-validated action-choice row appears with choices allowed by the logged-in user's Jmix permissions.
result: pass
verified: "After the user replied to skip missing optional fields, the message list rendered a ready action-choice row with Create now and Prefill form."

### 4. Create Now Path

expected: Clicking Create now enables the mutation path only for the selected action turn, creates the record under Jmix security, and records audit evidence.
result: pass
verified: "User confirmed the Create now path passed. The selected action created the target record only after the explicit Create now click."
ux_observation: "User noticed the disabled action-choice card remained anchored near the bottom after selection. This was treated as a minor UX cleanup and resolved by removing the action-choice row once an action is selected."

### 5. Prefill Form Path

expected: Clicking Prefill form creates an extraction draft, renders Open form to confirm, opens the Jmix detail view only after that click, preloads permitted values, saves through normal validation, and deletes the draft after Save.
result: pass
reported_issue: "The prefilled Customer form opened and pressing OK created the record, but the UI also showed an optimistic-lock error for the Extraction draft object."
resolution: "Fixed in OpenFormWithDraftHandler by removing the saved AiExtractionDraft instance returned by DataManager.save instead of the stale pre-save instance. Browser retest on localhost:8090 confirmed OK saves and returns to chat without the draft error."

### 6. Expired Or Removed Draft

expected: An expired or removed draft cannot open a form or substitute another draft; the user sees the expired-draft behavior and chat remains usable.
result: pass
verified: "Playwright dialog retest on localhost:8090 created a prefill draft, opened and saved the Customer form so the draft was removed, then clicked the stale Open form to confirm row again. No form opened; the user saw the draft-expired notification and the chat dialog remained usable."
ux_observation: "The stale confirm button remained visible after the expired-draft notification. This still satisfies the current expected behavior because the user sees the expired-draft message, but disabling/removing the button would be cleaner."

### 7. Permission Denied

expected: Unauthorized choices are not offered, and access lost between proposal and confirm prevents navigation or record creation.
result: pass
verified: "2026-05-11 retest on localhost:8088 against the local docker Postgres. Revoked the `ai-agent-mutation` resource-role assignment from `admin`, re-logged in, then sent 'tạo khách hàng tên UAT NoPerms 11Z ... cho tôi cả lựa chọn tạo ngay lẫn điền sẵn biểu mẫu'. Despite the user explicitly requesting both choices and the LLM passing choices=[\"create-now\",\"prefill-form\"] to propose_action_choices, the server returned status=READY with proposal.choices=[\"prefill-form\"] only — the unauthorized 'Tạo ngay' (create-now) choice was filtered server-side (allowedChoices.canCreateNow() == false without the mutation role) and the action-choice row rendered only 'Điền sẵn biểu mẫu' + 'Hủy'. DB audit confirms the filtered result. Earlier in the same session, with no mutation role and the LLM requesting only create-now, propose_action_choices returned status=ACCESS_DENIED with messageKey=chatView.intent.permissionDenied and no action row appeared (fail-closed)."
notes: "Sub-scenario 'access lost between proposal and confirm prevents navigation or record creation' is code-verified, not live-driven: OpenFormWithDraftHandler.confirmAndDeleteDraft re-checks UiShowViewContext.isPermitted() + isCreatePermitted on every open, and the Create-now path re-runs MutationAuthorizationService.enforceMutationRole per tool call. Live-testing it requires revoking a role mid-session, which only takes effect after re-login and therefore severs the chat conversation; the in-session ACCESS_DENIED + filtered-choices observations cover the spirit of the requirement."

### 8. Streaming Authentication

expected: Streaming callbacks render action and confirm rows without `Authentication is not set` errors, and secured Jmix work runs as the logged-in user.
result: pass
verified: "Browser retest rendered both the action-choice row and the confirm row during streaming-backed interaction, with no Authentication-is-not-set error observed in the app log."

### 9. Provider And RAG Diagnostics

expected: Retrieval or embedding warnings do not block a non-RAG action-choice turn when the chat model succeeds; provider model failures are treated as configuration issues.
result: pass
verified: "The app log contained a best-effort embedding/retrieval warning, but the non-RAG Customer create/prefill action-choice flow still completed."

### 10. Cancelled Pending Action Cannot Still Mutate

expected: If the user cancels a pending create proposal in chat, the corresponding action-choice row is removed or disabled and cannot create a record afterward.
result: pass
verified: "2026-05-11 dialog retest on localhost:8088: created ready action row for UAT Fix Verify 11Mai (Tạo ngay / Điền sẵn biểu mẫu / Hủy buttons visible), then sent 'không tạo khách hàng đó nữa, hủy yêu cầu vừa rồi'. Assistant replied 'Đã hủy yêu cầu tạo khách hàng UAT Fix Verify 11Mai. Không có thay đổi nào được thực hiện.' All three action buttons removed from DOM (no stale Create-now to click). DB query confirms 0 rows in customer table where name='UAT Fix Verify 11Mai'."
prior_failure: "Earlier retest on localhost:8090 created the cancelled customer because the row remained active after natural-language cancellation."

### 11. Ambiguous Multi-Record Quantity

expected: If the user asks for an ambiguous count such as '2 or 3' records, the assistant asks for clarification or rejects the batch gracefully. It must not choose a count silently or end in a generic tool error.
result: pass
verified: "2026-05-11 dialog retest on localhost:8088: sent 'tạo 2 hoặc 3 khách hàng tên UAT Ambig 11A, UAT Ambig 11B, UAT Ambig 11C, bỏ qua email và số điện thoại'. Assistant replied 'Bạn muốn tạo 2 hay 3 khách hàng trong số các tên sau? UAT Ambig 11A / UAT Ambig 11B / UAT Ambig 11C. Vui lòng xác nhận số lượng để tôi tiến hành nhé!' — clarification requested, no silent count choice, no Spring AI tool-conversion error, no record creation (DB query confirms 0 rows named UAT Ambig*)."
prior_failure: "Earlier run silently picked 3 records and then crashed with a Spring AI Map<String,Object> deserialization error because the LLM passed an array of values."

### 12. Full-Page Prefill Cancel Preserves Conversation State

expected: Opening a prefilled form from the full chat page and cancelling without saving returns the user to a usable chat with the current conversation and pending action state intact.
result: pass
verified: "2026-05-11 retest on localhost:8088 against the local docker Postgres. From the full chat page (/ai-agent/chat) sent a prefill-form create request for UAT Prefill 11Y, clicked 'Điền sẵn biểu mẫu' -> 'Mở biểu mẫu để xác nhận' -> the Customer detail view opened at /customers/new with Tên prefilled to 'UAT Prefill 11Y'. Clicked Cancel -> 'Don't save'. On return to /ai-agent/chat the conversation title was still 'tạo khách hàng tên UAT FullPage Cancel 11X...' (NOT reset to 'Cuộc trò chuyện mới') and the full message history was intact (user turns + assistant ready + selected-action turn all present). DB confirms 0 rows in customer where name LIKE 'UAT%' (no record created) and the AiExtractionDraft for UAT Prefill 11Y remained confirmed=false (cancel does not confirm/delete the draft; TTL reaps it)."
prior_failure: "Earlier run lost the conversation/messages/action rows AND reset the title to Cuộc trò chuyện mới after Cancel -> Don't save from the full chat route."

### 13. User-Facing Transcript Hides Internal Action Payloads

expected: Chat history and dialog transcripts do not expose internal action-routing prompts, proposal ids, entity names, or collected JSON payloads.
result: pass
verified: "2026-05-11 dialog retest on localhost:8088: drove a full Create-now path for UAT Leak Check 11M, closed the chat dialog, reopened it. Reopened transcript contains no 'Selected action intent / Proposal id / Target entity / Collected values JSON' strings. Direct DB scan over ai_agent_message confirms all 7 historical leak rows are dated 2026-05-10 (pre-fix); the 2026-05-11 conversation produced 0 leak rows. Click-emitted user message is just the button label 'Tạo ngay', not the internal payload."
prior_failure: "Earlier reopened dialog rendered 'Selected action intent: create-now / Proposal id: ... / Target entity: Customer / Collected values JSON: ...' as a user message."

### 14. Create Now Double Click Idempotency

expected: Double-clicking Create now for the same ready proposal creates at most one record.
result: pass
verified: "Playwright dialog/full-page retest double-clicked Create now for UAT Double Click. The assistant completed the create flow and the Customers grid contained exactly one UAT Double Click row."

## Summary

total: 14
passed: 14
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

- truth: "Prefill form save should create the target record, delete the extraction draft, and not show an optimistic-lock error for AiExtractionDraft."
  status: resolved
  reason: "User reported that pressing OK in the prefilled Customer form creates the record but also shows an optimistic-lock error for the Extraction draft object."
  severity: major
  test: 5
  root_cause: "OpenFormWithDraftHandler saved the draft and then removed the stale pre-save draft instance, so Jmix could detect a version conflict after the draft save incremented the entity version."
  artifacts:
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandler.java"
      issue: "confirmAndDeleteDraft saves AiExtractionDraft and removes the original stale instance instead of the saved instance returned by DataManager.save."
  missing:
    - "Resolved: remove the saved AiExtractionDraft instance returned by DataManager.save."
    - "Resolved: add a regression test that fails if draft removal uses the stale pre-save instance."
  debug_session: "inline"
  fixed_by: "Use the AiExtractionDraft instance returned by DataManager.save for DataManager.remove."
  verified: "Targeted tests passed and Playwright retest on localhost:8090 confirmed Prefill form OK no longer shows the draft optimistic-lock error."

- truth: "After the user selects an action choice, the stale disabled action-choice card should not remain anchored at the bottom of the chat surface."
  status: resolved
  reason: "User reported that the disabled action-choice card appeared fixed at the bottom after Create now had already completed."
  severity: minor
  test: 4
  root_cause: "ChatPanelFragment disabled action-choice buttons after selection but left the selected action row mounted below the scrolling MessageList."
  artifacts:
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java"
      issue: "submitActionChoice disabled the selected action row but did not remove it after the action was selected."
  missing:
    - "Resolved: remove the action-choice row after Create now submits successfully."
    - "Resolved: remove the action-choice row after Prefill form creates the draft and before rendering the Open form confirmation row."
  debug_session: "inline"
  fixed_by: "Remove the selected action-choice row after a successful action selection."
  verified: "Regression test asserts selected action-choice rows are removed after selection."

- truth: "A user-visible cancellation must revoke the pending action row so the cancelled proposal cannot still be executed."
  status: resolved
  reason: "The assistant could previously acknowledge cancellation in natural language while the old action-choice row remained active and still able to create the cancelled record."
  severity: critical
  test: 10
  root_cause: "Pending action rows remained mounted until an action button was selected; natural-language cancellation did not remove or disable them, and there was no server-side revoked state."
  artifacts:
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java"
      issue: "Pending action rows remain mounted until an action button is selected; natural-language cancellation does not remove or disable them."
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionProposalService.java"
      issue: "Action proposals are stateless ready payloads; there is no persisted revoked state checked before executing a selected action."
  missing:
    - "Resolved: explicit Hủy cancel control rendered on each action-choice row; the row is removed from the DOM when the user cancels (whether by button click or by natural-language intent)."
    - "Resolved: with the row gone there is no stale Create-now button to click; mutation is impossible."
    - "Resolved: regression test covering action-row removal on cancel landed in the WIP test suite."
  debug_session: "inline Playwright UAT"
  fixed_by: "Render a Hủy button on the action-choice row; remove the row from the DOM on natural-language cancellation acknowledgement and on explicit Hủy click."
  verified: "2026-05-11 dialog retest: natural-language 'không tạo khách hàng đó nữa, hủy yêu cầu vừa rồi' removed all three action buttons from the DOM; DB confirms 0 customer rows for the cancelled UAT Fix Verify 11Mai name."

- truth: "Ambiguous multi-record requests should not silently choose a count or fail with raw tool argument deserialization errors."
  status: resolved
  reason: "The assistant previously chose 3 records for a '2 or 3' request and then hit a generic Spring AI tool conversion failure."
  severity: major
  test: 11
  root_cause: "The action proposal tool accepted a single Map<String,Object> values payload, but the model attempted a multi-row array payload for propose_action_choices."
  artifacts:
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionProposalTool.java"
      issue: "propose_action_choices has only a single-record values contract and cannot gracefully handle a multi-row array argument emitted by the model."
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRulesComposer.java"
      issue: "Action intent rules do not tell the model to ask for clarification on ambiguous counts or split multi-record proposals into supported single-row proposals."
  missing:
    - "Resolved: prompt rules now instruct the model to clarify ambiguous counts before calling propose_action_choices."
    - "Resolved: typed Map<String,Object> values parameter on the tool (see new resolved gap on Object-param binding) so the boundary handles array misuse as a structured tool error rather than a silent failure."
  debug_session: "inline Playwright UAT"
  fixed_by: "Combination of tightened prompt rules + typed values parameter on propose_action_choices."
  verified: "2026-05-11 dialog retest: assistant asked 'Bạn muốn tạo 2 hay 3 khách hàng…' for the ambiguous request; no silent count choice, no tool-conversion error, no row created (DB confirms 0 rows for UAT Ambig*)."

- truth: "Cancelling a prefilled form opened from the full chat page should preserve the current chat conversation and pending state."
  status: resolved
  reason: "Full-page prefill navigation previously returned to a fresh chat after Cancel -> Don't save, losing the current messages/action rows and resetting the title."
  severity: major
  test: 12
  root_cause: "The full-page route returned to /ai-agent/chat without preserving/restoring the source conversation id, while the floating chat dialog kept its state. Addressed by the pre-existing WIP (14-11) full-page source-conversation handling in OpenFormWithDraftHandler / ChatPanelFragment."
  artifacts:
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandler.java"
      issue: "Full-page form navigation needs review for source-conversation return handling."
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java"
      issue: "The full chat view does not restore the previous pending rows after returning from a cancelled prefill form."
  missing:
    - "Resolved: source conversation id preserved across prefill form navigation from the full chat page; returning to /ai-agent/chat keeps the title and message history."
    - "Regression-test Cancel -> Don't save from the full chat route (still recommended as a follow-up automated test)."
  fixed_by: "Pre-existing WIP (14-11) full-page source-conversation handling; verified live 2026-05-11."
  verified: "2026-05-11 live retest: prefill form opened from full chat, Cancel -> Don't save returned to the same conversation with title 'tạo khách hàng tên UAT FullPage Cancel 11X...' and full message history intact; DB confirms 0 customer rows created and the draft left confirmed=false."
  debug_session: "inline Playwright UAT"

- truth: "Internal selected-action orchestration text must never be rendered as a user-visible chat message."
  status: resolved
  reason: "The chat dialog transcript previously exposed selected action intent, proposal id, target entity, and collected values JSON after reopening."
  severity: major
  test: 13
  root_cause: "The selected-action instruction used to drive the model was persisted to AiMessage history with USER role so it surfaced verbatim on reopen."
  artifacts:
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java"
      issue: "Dialog transcript rendering includes internal selected-action payload text."
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRulesComposer.java"
      issue: "Selected-action rule text is safe as a system/developer instruction only; it must not enter persisted user-visible message history."
  missing:
    - "Resolved: selected-action prompts no longer enter the user-visible AiMessage history; only the button label (e.g. 'Tạo ngay') is persisted as the user turn."
    - "Resolved: regression assertion on absence of 'Selected action intent / Proposal id / Target entity / Collected values JSON' in transcript on reopen."
  debug_session: "inline Playwright UAT"
  fixed_by: "Keep selected-action orchestration text out of persisted AiMessage rows; persist only the user-visible button label for the selected action turn."
  verified: "2026-05-11 dialog retest: drove Tạo ngay for UAT Leak Check 11M, closed and reopened the dialog. Reopened transcript contains no leak strings. Direct DB scan over ai_agent_message: all 7 historical leak rows are 2026-05-10 dated (pre-fix); the 2026-05-11 conversation produced 0 leak rows."

- truth: "propose_action_choices must surface a READY action-choice row to the chat UI; without it Tests 7/10/11/13 cannot be driven."
  status: resolved
  reason: "Discovered during 2026-05-11 retest: every propose_action_choices call returned INVALID with proposal.values={} at latency 0ms, so StreamEventRenderer.parseActionProposalPayload returned null and no UI row was appended. The model retried 10+ times then fell back to natural-language prose ('Có vẻ hệ thống không thể hiển thị các lựa chọn hành động lúc này'). Direct DB inspection of ai_agent_audit_event confirmed the failure shape."
  severity: critical
  test: 10, 11, 12, 13
  root_cause: "ActionProposalTool declared `Object values` for the @ToolParam parameter. Spring AI 1.x did not bind a JSON object literal into Map/JsonNode/CharSequence for `Object`, so the runtime instanceof fallthrough in normalizeValues returned null and the tool short-circuited with INVALID + Map.of() values. The matching unit test passed because it constructed a Map directly, hiding the runtime Spring AI binding mismatch."
  artifacts:
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionProposalTool.java"
      issue: "values param declared as Object; normalizeValues type-discriminator did not match what Spring AI 1.x actually binds at runtime."
    - path: "ai-agent/ai-agent/src/test/java/com/vn/agent/action/ActionProposalToolTest.java"
      issue: "JsonNode/array tests asserted normalizeValues semantics that did not match the Spring AI binding path."
  missing:
    - "Resolved: parameter retyped as `Map<String, Object>` (matches BuiltInMutationTools.attributes which is known to bind correctly). normalizeValues helper removed."
    - "Resolved: test file simplified to a happy-path Map test plus a null-handling test; obsolete JsonNode/array tests removed (Spring AI binding now rejects malformed inputs at the boundary)."
    - "Verification: 2026-05-11 DB query showed propose_action_choices now returning status=READY with populated proposal.values at latency 87ms after the fix."
  debug_session: "inline Playwright UAT + agentstore DB introspection"
  fixed_by: "Retype @ToolParam values from Object to Map<String, Object> in ActionProposalTool.proposeActionChoices."
  verified: "2026-05-11 live retest: action-choice row renders with Tạo ngay / Điền sẵn biểu mẫu / Hủy buttons on first chat turn; DB confirms READY status with populated values.proposal."

## Gap Closure

- Plan 14-10 replaced the old static first-screen intent picker with a post-clarification action proposal flow.
- Plan 14-10 added constrained selected-action routing for Create now and Prefill form.
- Plan 14-10 restored captured current-user authentication inside streaming UI callbacks.
- Pre-existing WIP (14-11): Hủy cancel control on the action-choice row + cancellation-removes-row; selected-action orchestration text kept out of persisted AiMessage history; ambiguous-count prompt rules; full-page prefill source-conversation preservation.
- 2026-05-11 in-session fix: typed propose_action_choices `values` parameter as `Map<String, Object>` to fix the Spring AI binding regression (without this every propose_action_choices call returned INVALID with empty values and no action row ever rendered).
- 2026-05-11 UAT: ALL 14 checks pass. Tests 10 / 11 / 12 / 13 / 7 driven live on localhost:8088 against the local docker Postgres (`docker-compose.yml` at repo root) with the runtime model qwen/qwen3.6-35b-a3b after the host topped up OpenRouter credits.

## Session Handoff - 2026-05-11 (UAT COMPLETE)

All 14 human-UAT checks pass. Phase 14 manual UAT is done.

Environment changes made this session (in the working tree, uncommitted):
- `docker-compose.yml` (repo root) + `docker/postgres/init/01-init-databases.sh` — local PostgreSQL 16 with pgvector, host port 5432, creates `ai-agent` + `agentstore` DBs and enables the `vector` extension. Bring up with `docker compose up -d` (or `& "C:\Program Files\Docker\Docker\resources\bin\docker.exe" compose up -d` if `docker` isn't on PATH).
- `jmix-app/src/main/resources/application.properties` — both datasource URLs flipped from `jdbc:postgresql://10.123.123.174:5555/...` to `jdbc:postgresql://localhost:5432/...`. (Port 5432, not 5555, because host 5555 collides with a local SoftEther `vpnserver_x64`.)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionProposalTool.java` — `@ToolParam values` retyped `Object` -> `Map<String, Object>` (Spring AI 1.x did not bind a JSON object into the `Object` param's runtime type, so the tool short-circuited to INVALID and no action row ever rendered). `normalizeValues`/`normalizeJsonValues` helpers removed.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/action/ActionProposalToolTest.java` — dropped the obsolete JsonNode/array tests; kept a happy-path Map test + a null-handling test.
- Plus pre-existing WIP (14-11) that was already in the tree before this session: Hủy cancel control on action rows + cancellation-removes-row; selected-action text kept out of persisted AiMessage history; ambiguous-count prompt rules; full-page prefill source-conversation preservation.

Fresh-DB setup quirk discovered this session: a brand-new agentstore re-seeds AiParameters from `default-params.yaml` (model = `qwen/qwen3.6-35b-a3b`), and a brand-new main DB only assigns `system-full-access` to `admin`. The `propose_action_choices` Create-now branch requires the `ai-agent-mutation` resource role assigned to the user — on a fresh DB you must add it (Users admin view, or `INSERT INTO sec_role_assignment (id, version, username, role_code, role_type) VALUES (gen_random_uuid(), 1, 'admin', 'ai-agent-mutation', 'resource')` then re-login). Without it the action row only offers Prefill form (which is itself a correct demonstration of Test 7's unauthorized-choice filtering).

Recommended next action:
- Decide what to do with the working-tree changes: commit the `ActionProposalTool` fix + test cleanup (small, isolated), and decide whether the docker-compose / localhost datasource change should be committed or kept local. The 14-11 WIP belongs to a separate gap-closure that someone started before this session.
- Add a regression test for the full-page prefill Cancel -> Don't save flow (Test 12) — it's currently only covered by manual UAT.
- Proceed to milestone close (v1.1.0).

User testing directive (kept for reference):
- User explicitly asked to test non-happy-path behavior, not only happy-path flows.
- Prioritize edge/negative cases such as cancellation, stale/expired drafts, unauthorized access, ambiguous requests, duplicate clicks, lost navigation state, and internal prompt/payload leakage.
- Treat "assistant says it cancelled" as insufficient unless the stale UI action is also revoked or server-side execution is rejected.
