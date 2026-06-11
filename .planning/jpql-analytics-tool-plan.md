# Plan: JPQL Analytics Tool + Batch Schema + Prompt Suggestions + UI Fixes

## Context & decisions

Researched jmix-crm's AI tool philosophy vs this addon's. Verdict: addon's tool
security is **more mature** (LLM-specific layers crm lacks: `<data>` prompt-injection
wrapping, uniform opacity, `LlmExposurePolicy` denylist, audit tree). crm's strength is a
single powerful **JPQL tool** for analytics (aggregation / GROUP BY / ORDER BY / projection)
— capabilities the addon's structured filter **cannot** express.

**Locked decisions:**
- Add a JPQL analytics tool, **re-skinned thin** to the addon's posture, NOT a verbatim port.
- Wire it as a **built-in default tool** of the addon (alongside `BuiltInDataTools` /
  `BuiltInLinkTools`), NOT via the host `ToolContributor` SPI. Enabled by default; the
  per-profile `enabledTools` allowlist remains the off-switch.
- **Raw error passthrough is OK** (user decision): JPQL errors return to the LLM mostly
  verbatim for self-correction; leaking entity names in JPQL errors is acceptable for this
  analytics power-tool.
- Security model = crm's thin model + addon overlay: `LoadValuesAccessContext` +
  `accessManager.applyRegisteredConstraints` (entity-read) → secured `dataManager.loadValues`
  (row-level auto) → **+ `LlmExposurePolicy.canReadEntity` overlay** on every entity the query
  references (blocks entities the admin hid from the LLM). Audit is free via
  `ToolCallbackAuditDecorator`.
- Add `describe_entities` (batch schema) so the LLM gets the relationship graph + enum ids
  needed to author correct JPQL.
- Add **prompt suggestions** (starter-prompt cards in empty chat).
- **UI audit + fix** broken sections.
- Do NOT tear down existing structured-filter / fetch-plan / exposure-perf code. The
  structured filter stays the safe default for simple reads; JPQL is the analytics escape hatch.
- Reports tool already exists (host `ToolContributor`) → out of scope.

## Read-only / test guardrails
- `BuiltInDataToolsReadOnlyTest` scans ONLY `BuiltInDataTools.class`. JPQL lives in a separate
  class `BuiltInJpqlTool` → not scanned. `describe_entities` added to `BuiltInDataTools` is
  pure metadata read (no save/remove/EntityManager, no param-into-JPQL concat) → stays compliant.
- `AgentToolCallbacksDefaultConfigTest` asserts an exact callback count (11) + tool names.
  Adding 2 tools → update to 13 + add the two names.

---

## Workstream A — JPQL analytics tool (`com.vn.agent.tools.jpql`)

New files (port + adapt; drop crm's `AiToolStatusPublisher`/`ToolContext` — addon uses
`StreamingSinkHolder` + audit decorator):

1. `JpqlParameter` (record) — `{parameterName, parameterValue}`.
2. `JpqlParameters` (record) — `{List<JpqlParameter> parameters}` + `empty()`/`fromMap()`.
3. `JpqlNamedParameterParser` (@Component) — extract `:name`, `contains`/`without`,
   `extractUnknownParameterName`.
4. `AiJpqlParameterConverter` (@Component) — Spring `ConversionService` heuristic coercion
   (bool → uuid → date → numeric → string), forgiving.
5. `JpqlResultConverter` (@Component) — `KeyValueEntity` → `List<Map<String,Object>>` via
   `EntitySerialization`; temporal → ISO string.
6. `JpqlQueryResult` (record) — `{success, data, rowCount, hasMore, offset, limit, errorMessage}`
   + `success(...)`/`failed(...)`.
7. `AiJpqlQueryService` (@Component) — execute with converted-then-original param fallback,
   `limit+1` hasMore probe, `firstResult(offset)`, default limit 50 / max 200.
   - `ensureQueryIsPermitted(jpql)`: build `LoadValuesAccessContext`,
     `accessManager.applyRegisteredConstraints`, throw `AccessDeniedException` if not permitted.
   - **Addon overlay (new):** iterate `queryContext.getEntityClasses()`; for each `MetaClass`,
     if `!llmExposurePolicy.canReadEntity(mc)` throw (entity name in message is fine).
8. `BuiltInJpqlTool` (@Component) — single `@Tool(name="run_jpql_query")` delegating to the
   service; description ported from crm (alias rules, reserved words, Jmix date macros,
   enum-id, aggregates, pagination). Catches `Exception` → `JpqlQueryResult.failed(message)`
   (raw passthrough, per decision).

## Workstream B — `describe_entities` batch
- Add `@Tool(name="describe_entities")` to `BuiltInDataTools`, param `List<String> entityNames`.
- Loop: `resolveReadableEntityOrThrow` → `getReadableSchema().get(mc)` →
  `llmReadableAttributes` → `objectMapper.readTree(toolResultFormatter.describe(mc, attrs))`,
  collect into a `List<JsonNode>` → `toolResultFormatter.toJson(list)`.
- Uniform opacity preserved (denied/unknown → `unknown_entity`).
- Description: "Describe multiple entities at once … call before run_jpql_query to get the
  relationship graph and enum ids."

## Workstream C — wiring + tests
- `AgentToolCallbacks`: add `BuiltInJpqlTool` field + ctor param +
  `Collections.addAll(all, fromBean(builtInJpqlTool))` in `auditedNonMutationCallbacks`. Update
  callback-count javadoc.
- `AgentToolCallbacksDefaultConfigTest`: 11 → 13; add `describe_entities`, `run_jpql_query`.
- New test `BuiltInJpqlToolSecurityTest` (or service-level): a denylisted entity in the JPQL
  is rejected by the exposure overlay; a permitted query returns rows.
- Verify `BaselineContextProvider` enumerates the new tools for the system prompt (it lists the
  exposed tool surface). Fix if the list is hardcoded.

## Workstream D — prompt suggestions
- Starter-prompt cards in the empty-chat state of `ChatPanelFragment`; clicking fills the
  composer input. Suggestions sourced from the message bundle (en + vi), not an entity.
- New small component `AiPromptSuggestionCard` (or inline `JmixCard` list) + CSS in
  `ai-agent-chat.css`. Render only when the message list is empty.
- Messages added to ALL locale files.

## Workstream E — UI audit + fix
- App is running locally (port 8080). Use Playwright: navigate chat / audit / knowledge /
  configuration views, screenshot, find broken layout/CSS ("nhiều đoạn vẫn bị vỡ"), fix.

## Validation sequence
1. JetBrains MCP `get_file_problems` on each new/edited Java file.
2. `./gradlew :ai-agent:ai-agent:compileJava` then targeted tests
   (`AgentToolCallbacksDefaultConfigTest`, new JPQL test, `BuiltInDataToolsReadOnlyTest`).
3. Full `./gradlew test`.
4. Restart app, Playwright end-to-end: ask an analytics question that forces
   `describe_entities` → `run_jpql_query` (e.g. "doanh thu theo tháng" / "top 5 khách theo số
   đơn"), confirm audit row outcome = success, prompt-suggestion click works, UI not broken.

## Out of scope
- Reports tool (already shipped via ToolContributor).
- Removing/refactoring structured-filter, fetch-plan, or exposure-perf code.
