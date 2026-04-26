---
phase: 09-tool-layer-foundations-prompt-contract-hardening
verified: 2026-04-27T00:00:00Z
status: gaps_found
score: 28/30 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: null
  previous_score: null
  gaps_closed: []
  gaps_remaining: []
  regressions: []
gaps:
  - truth: "find_records, get_record, get_related_records consult FetchPlanResolver, which ALWAYS pipes through FetchPlanIntersector.intersectWithAcl before DataManager.load (Plan 09-04 must_have)"
    status: failed
    reason: |
      In BuiltInDataTools.getRelatedRecords (lines 286-300), the resolver returns an intersected
      dataPlan, but the code then composes a NEW outer plan that adds FetchPlan.INSTANCE_NAME on
      the relationship attribute. The composed plan is passed directly to dataManager.load(...)
      .fetchPlan(fetchPlan) without re-running it through fetchPlanIntersector.intersectWithAcl(...).
      The relationship's INSTANCE_NAME sub-plan therefore bypasses ACL narrowing.

      The inline comment in the code (lines 286-290) asserts: "INSTANCE_NAME below bypasses
      intersection because it only fetches what @InstanceName declared, which is by definition
      readable to anyone who can read the entity." Per code review BL-02, this claim is false —
      Jmix attribute-level permissions are independent of @InstanceName declaration. A host can
      DENY an attribute that contributes to @InstanceName, and the current code path will still
      load it via the un-intersected nested plan.

      This directly contradicts Plan 09-04's must_have truth that the resolver pipeline ALWAYS
      pipes through the intersector before DataManager.load, and violates the TOOL-11 requirement
      ("host plan cannot widen the projection beyond AccessManager-allowed attributes").

      No FetchPlanIntersectorTest case covers the INSTANCE_NAME-on-relationship scenario — grep
      for INSTANCE_NAME / instanceName / @InstanceName / get_related_records in the test file
      returned 0 matches.
    artifacts:
      - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java"
        issue: "Lines 286-300: getRelatedRecords composes FetchPlan.INSTANCE_NAME onto the relationship attribute AFTER the resolver/intersector tail has run, then loads with the un-intersected composed plan."
      - path: "ai-agent/ai-agent/src/test/java/com/vn/agent/tools/FetchPlanIntersectorTest.java"
        issue: "No test case covers the relationship INSTANCE_NAME bypass scenario; the regression bar for TOOL-11 has a hole."
    missing:
      - "Either intersect the composed plan before dataManager.load (run intersectWithAcl on the assembled FetchPlan in getRelatedRecords) OR resolve and intersect the nested INSTANCE_NAME plan against the target metaclass before composing it onto the relationship sub-plan."
      - "Add a FetchPlanIntersectorTest (or BuiltInDataToolsTest) case where the target entity's @InstanceName references a denied attribute and assert it does not appear in the loaded plan handed to DataManager."
      - "Update or remove the misleading inline comment claiming INSTANCE_NAME projections are 'by definition readable to anyone who can read the entity'."
  - truth: "Baseline rendering produces byte-identical output across equivalent runs (BaselineContextProvider Javadoc invariant E-01: 'identical requests produce byte-identical baseline blocks (cache & audit prompt-hash stability)')"
    status: failed
    reason: |
      In BaselineContextProvider.rolesOf (lines 149-153), authorities are collected into
      LinkedHashSet:

          return user.getAuthorities().stream()
                  .map(GrantedAuthority::getAuthority)
                  .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

      LinkedHashSet preserves insertion order, which is the iteration order of
      UserDetails.getAuthorities(). Spring Security does NOT contract a stable iteration order
      for getAuthorities() across implementations — Jmix users, JWT-derived users, and host-
      custom UserDetails all vary. Two equivalent users with the same role set can produce
      different agent.roles=[...] lines.

      This breaks the byte-stable-baseline contract Phase 9 explicitly relies on (Plan 09-03
      decision D-01/D-02 deterministic ordering claim, BaselineContextProvider class-level
      Javadoc, and the Phase 9 phase goal: "ship the deterministic baseline prompt contract").

      The agent.permissions byte-stability test passes because it asserts only the permissions
      JSON, not the full baseline. The agent.roles slot — which feeds the same renderAsText
      output — has no determinism test and no determinism guarantee.
    artifacts:
      - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java"
        issue: "Line 152: Collectors.toCollection(java.util.LinkedHashSet::new) is non-deterministic across UserDetails implementations; should be TreeSet or sorted before return."
      - path: "ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/BaselineContextProviderTest.java"
        issue: "No test asserts agent.roles ordering is deterministic across UserDetails implementations with reverse-order authorities."
    missing:
      - "Change rolesOf to collect into TreeSet (or sort the returned set) so role order is alphabetical regardless of UserDetails source."
      - "Add a BaselineContextProviderTest case that injects two UserDetails mocks returning the same authorities in opposite orders and asserts identical renderAsText(convId) output."
human_verification: []
---

# Phase 9: Tool-Layer Foundations & Prompt-Contract Hardening Verification Report

**Phase Goal:** Tool-layer foundations + prompt contract hardening — ship the deterministic baseline prompt contract (entities/permissions), the FetchPlan resolver/intersector layer with TOOL-11 invariant enforcement, output-scanner pattern packs + system-prompt rules (PROMPT-03/06 + D-15), the AUD-07 hashing utility (Phase 11 consumer), the ToolFetchPlanCustomizer SPI surface, and the TEST-08 prompt-contract regression suite that locks the four runtime contracts.
**Verified:** 2026-04-27
**Status:** gaps_found
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| #   | Truth (from plan must_haves)                                                                                                                                  | Status     | Evidence                                                                                                                                                                                                       |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | AuditFieldHasher.sha256Hex pure static utility, 64-char lowercase hex, UTF-8 byte-stable, null-safe                                                           | VERIFIED   | `AuditFieldHasher.java` exists; `sha256Hex(null)→null`; `MessageDigest.getInstance("SHA-256")` + `StandardCharsets.UTF_8` + `HexFormat.of()`; `AuditFieldHasherTest` 7 tests pass.                              |
| 2   | AiAgentAuditProperties bound to `jmix.ai-agent.audit.*` with `hashSensitiveFields=true` default, `sensitiveFields=` empty default                              | VERIFIED   | `@ConfigurationProperties("jmix.ai-agent.audit")` record present; `module.properties` carries both keys; FoundationsBootSmokeTest binds without typo errors.                                                  |
| 3   | AUD-07 plumbing has zero callers in Phase 9                                                                                                                   | VERIFIED   | `grep -RF 'AuditFieldHasher.sha256Hex' ai-agent/ai-agent/src/main/java` returns 0.                                                                                                                              |
| 4   | ToolFetchPlanCustomizer SPI: `Optional<FetchPlan> overrideFor(String, MetaClass, FetchPlanContext)` (verbatim D-09 signature)                                  | VERIFIED   | Signature present byte-for-byte in `ToolFetchPlanCustomizer.java`.                                                                                                                                              |
| 5   | SPI Javadoc carries verbatim "fetch plan is projection, not security."                                                                                        | VERIFIED   | grep finds the verbatim phrase in `ToolFetchPlanCustomizer.java` Javadoc and as `PROJECTION_NOT_SECURITY_COMMENT` constant in `FetchPlanIntersector.java`.                                                      |
| 6   | FetchPlanContext is a concrete snapshot record (UUID runId, UUID conversationId, …, UserDetails user); does NOT carry RunContext as a component                | VERIFIED   | Record signature matches D-10 review correction; `grep -F 'com.vn.agent.orchestration.RunContext'` returns 0.                                                                                                   |
| 7   | SpiDefaultsAutoConfiguration registers `@ConditionalOnMissingBean` no-op default returning `Optional.empty()`                                                  | VERIFIED   | `defaultToolFetchPlanCustomizer` bean present.                                                                                                                                                                  |
| 8   | `agent.entities` renders alpha-sorted `name (label)` lines, truncated at limit (default 100) with verbatim hint "... (truncated, call list_entities for full list)"; empty schema → key omitted | VERIFIED   | Helpers `visibleEntities`, `renderEntitiesBlock` in `BaselineContextProvider.java`; verbatim hint string present once; `BaselineContextProviderTest` covers 9 new test methods including truncation case.       |
| 9   | `agent.permissions` is compact JSON, alpha-keyed, fixed key order r,u,c,d,modifiable, alpha-sorted modifiable[], all-zero-CRUD entries omitted                | VERIFIED   | `renderPermissionsJson` uses TreeMap + LinkedHashMap + TreeSet iteration; CRUD-zero skip implemented; tests assert all six behaviors.                                                                            |
| 10  | `agent.permissions` is byte-stable across `Locale.ENGLISH` vs `Locale.of("vi","VN")`                                                                            | VERIFIED   | `compose_permissionsJson_isLocaleInvariant_betweenEnglishAndVietnamese` test method exists; permissions JSON construction is locale-free by construction (no `messageTools` calls in `renderPermissionsJson`). |
| 11  | `agent.permissions` built from same sorted/capped list as `agent.entities` so entities beyond the cap cannot leak via permissions                              | VERIFIED   | `compose_permissionsDoesNotMentionEntitiesBeyondInventoryLimit` test present.                                                                                                                                   |
| 12  | Anonymous user / empty schema → both `agent.entities` and `agent.permissions` keys omitted entirely                                                            | VERIFIED   | Conditional `if (!visibleEntities.isEmpty())` guards both `ctx.put` calls in `compose(...)`.                                                                                                                    |
| 13  | AiAgentPromptProperties bound to `jmix.ai-agent.prompt.*` with `entityInventoryLimit` default 100                                                              | VERIFIED   | Record present; `AiAgentPromptPropertiesTest` covers all three default-resolution paths.                                                                                                                        |
| 14  | Baseline produces byte-identical output across equivalent runs (BaselineContextProvider class-Javadoc E-01 invariant)                                          | **FAILED** | `rolesOf` collects into `LinkedHashSet` (line 152) — ordering depends on `UserDetails.getAuthorities()` implementation; not deterministic across Spring Security user implementations. **See Gap #2.**          |
| 15  | `describe_entity` payload widened with MetadataTools-derived fields: comment, attributeType, cardinality, mandatory, readOnly, persistent, transient, primaryKey, [{name,label}] enumValues, {name,label} relationshipTarget, maxLength | VERIFIED   | `ToolResultPayloads.AttributeDescription` widened; `MetadataTools.getMetaAnnotationValue` invoked ≥2× in `ToolResultFormatter.java`; no raw reflection in production tool code; `DescribeEntityPayloadTest` has 7 tests asserting each field. |
| 16  | find_records / get_record / get_related_records consult FetchPlanResolver, which ALWAYS pipes through FetchPlanIntersector.intersectWithAcl before DataManager.load | **FAILED** | `fetchPlanResolver.resolve` invoked 3× in `BuiltInDataTools.java`. BUT `getRelatedRecords` (lines 286-300) composes `FetchPlan.INSTANCE_NAME` on the relationship AFTER the resolver tail and passes the un-intersected composed plan to `dataManager.load`. **See Gap #1 / BL-02.** |
| 17  | FetchPlanIntersector recursively walks host plan, drops denied properties, emits PLAN_NARROWED audit row                                                       | VERIFIED   | Recursive `walk()` in `FetchPlanIntersector.java`; `PLAN_NARROWED:` audit prefix present; `FetchPlanIntersectorTest` 9 tests cover pass-through, drop+audit, recursive nested drop, partial-flag preservation. |
| 18  | Verbatim TOOL-11 phrase declared as `FetchPlanIntersector.PROJECTION_NOT_SECURITY_COMMENT` and referenced from class Javadoc via `{@value}`                    | VERIFIED   | Constant + `{@value #PROJECTION_NOT_SECURITY_COMMENT}` Javadoc reference both present.                                                                                                                          |
| 19  | `ToolResultFormatter.records` emits literal `<data entity="<label>" type="<internalName>">…</data>` (label first)                                              | VERIFIED   | `return "<data entity=\"" + escapeAttribute(messageTools.getEntityCaption(metaClass)) + …` — label first via getEntityCaption, internal name second via getName.                                                |
| 20  | UNKNOWN_ENTITY_HINTS: three D-14 hints verbatim in locked order; em dash (U+2014) preserved on hint #3; passed via 3-arg ToolUserError on every unknown_entity throw | VERIFIED   | Constant present with em dash preserved; 3 throw sites in `resolveReadableEntityOrThrow` use 3-arg constructor; `UnknownEntityRetryHintTest` (6 tests) asserts.                                                |
| 21  | HostPrefixPatternProvider: dynamic regex from `metadata.getSession().getClasses()` at ApplicationReadyEvent; ReDoS guard via `Pattern.quote`; default-on with opt-out | VERIFIED   | `HostPrefixPatternProvider.java` present; HOST_PREFIX_LEAK constant; `@EventListener(ApplicationReadyEvent.class)` + `Pattern.quote` present; `HostPrefixLeakScannerTest` 8 tests pass.                       |
| 22  | ToolNamePatternProvider: snapshots six built-in tool names + RETRIEVAL + ToolContributor `@Tool` names; default-on with opt-out                                | VERIFIED   | `ToolNamePatternProvider.java` present; TOOL_NAME_LEAK constant; six built-in names enumerated; `ToolNameLeakScannerTest` 8 tests pass.                                                                         |
| 23  | OutputScannerAdvisor implements both CallAdvisor and StreamAdvisor; streaming uses ChatClientMessageAggregator                                                 | VERIFIED   | `implements CallAdvisor, StreamAdvisor`; `adviseStream` + `ChatClientMessageAggregator` present in source.                                                                                                      |
| 24  | AgentSystemPromptRules.PROMPT_RULES contains verbatim PROMPT-03 vocabulary rules + verbatim D-15 retry hints (matching UNKNOWN_ENTITY_HINTS byte-for-byte)     | VERIFIED   | All four required substrings present; em dash preserved on give-up clause; matches `BuiltInDataTools.UNKNOWN_ENTITY_HINTS` byte-for-byte.                                                                       |
| 25  | DefaultChatServiceImpl wires PROMPT_RULES between baseline and profile prompt at BOTH ask() and stream() composition sites                                     | VERIFIED   | `grep -F 'AgentSystemPromptRules.PROMPT_RULES' DefaultChatServiceImpl.java` returns exactly 2 matches.                                                                                                          |
| 26  | Audit row carries pattern KEY only — never matched leak text                                                                                                  | VERIFIED   | Existing OutputScannerAdvisor `writeFlag` contract preserved; both new pattern-pack tests assert `allSatisfy(v → doesNotContain(matchedText))`.                                                                 |
| 27  | TEST-08 mock variant runs in default CI; parameterized over Locale.ENGLISH and Locale.of("vi","VN")                                                            | VERIFIED   | `PromptContractMockTest` exists; `@ParameterizedTest` ≥3; both locales present; passes 10/10 in default CI.                                                                                                     |
| 28  | TEST-08 cross-locale lock: `agent.locale=en` and `agent.locale=vi_VN` tokens differ in captured system prompt per iteration (D-17)                              | VERIFIED   | `systemPromptCarriesDifferentAgentLocaleTokenPerIteration` test method present and passing; both literal tokens asserted.                                                                                       |
| 29  | TEST-08 unknown_entity hint substrings appear verbatim in BOTH ToolErrorDto.expected[] AND in composed system prompt                                            | VERIFIED   | `unknownEntityToolError_carriesThreeProceduralHintsVerbatim` + `systemPromptRulesAreCarriedThroughToLLM` test methods present.                                                                                  |
| 30  | TEST-08 live variant `@Tag("live")` excluded from default CI                                                                                                  | VERIFIED   | `PromptContractLiveTest.java` exists with `@Tag("live")` + `@EnabledIfEnvironmentVariable`; `excludeTags 'live'` filter in `ai-agent.gradle:135` excludes it from default suite.                                |

**Score:** 28/30 truths verified

### Required Artifacts

| Artifact                                                                                                              | Expected                                                            | Status     | Details                                                                              |
| --------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- | ---------- | ------------------------------------------------------------------------------------ |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditFieldHasher.java`                                            | SHA-256 hex utility                                                 | VERIFIED   | Static `sha256Hex` present.                                                          |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AiAgentAuditProperties.java`                                      | @ConfigurationProperties record                                     | VERIFIED   | Bound to `jmix.ai-agent.audit`.                                                      |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolFetchPlanCustomizer.java`                                       | Host SPI                                                            | VERIFIED   | D-09 signature; verbatim TOOL-11 phrase.                                             |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/FetchPlanContext.java`                                              | SPI parameter record                                                | VERIFIED   | Concrete snapshot, not RunContext.                                                   |
| `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java`                | No-op default bean                                                  | VERIFIED   | `defaultToolFetchPlanCustomizer` registered.                                         |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java`                             | agent.entities + agent.permissions emission                         | **STUB**   | Emits both keys but the `rolesOf` slot is non-deterministic (BL-01); see Gap #2.    |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiAgentPromptProperties.java`                             | @ConfigurationProperties record                                     | VERIFIED   | `entityInventory.limit` default 100.                                                 |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java`                              | ACL narrowing helper                                                | VERIFIED   | Recursive walk + PLAN_NARROWED audit + verbatim TOOL-11 constant.                    |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanResolver.java`                                 | First-non-empty wins customizer chain                               | VERIFIED   | Resolves + intersects.                                                               |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java`                                            | describe_entity widening + UNKNOWN_ENTITY_HINTS + resolver wiring   | **STUB**   | Two of three resolver-wired tools are correct; `getRelatedRecords` bypasses intersector for INSTANCE_NAME on relationship (BL-02). See Gap #1. |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java`                                         | Widened describe + PROMPT-04 envelope                               | VERIFIED   | Literal `<data entity="<label>" type="<internalName>">` envelope present.            |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/HostPrefixPatternProvider.java`                                   | HOST_PREFIX_LEAK regex                                              | VERIFIED   | ApplicationReadyEvent + Pattern.quote.                                               |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/ToolNamePatternProvider.java`                                     | TOOL_NAME_LEAK regex                                                | VERIFIED   | Six built-ins + RETRIEVAL + ToolContributor.                                         |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java`                                      | PROMPT_RULES constant                                               | VERIFIED   | Verbatim PROMPT-03 + D-15 substrings; matches UNKNOWN_ENTITY_HINTS byte-for-byte.    |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java`                                        | CallAdvisor + StreamAdvisor                                         | VERIFIED   | `implements CallAdvisor, StreamAdvisor`; ChatClientMessageAggregator wired.          |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`                                            | PROMPT_RULES wired in ask + stream                                  | VERIFIED   | Exactly 2 references.                                                                |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/PromptContractMockTest.java`                                            | TEST-08 default-CI regression                                       | VERIFIED   | 10 tests pass; D-17 cross-locale lock + verbatim hint cross-assertion.               |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/live/PromptContractLiveTest.java`                                       | TEST-08 opt-in live variant                                         | VERIFIED   | `@Tag("live")` + `@EnabledIfEnvironmentVariable`.                                    |

### Key Link Verification

| From                                              | To                                                       | Via                                              | Status     | Details                                                                                                  |
| ------------------------------------------------- | -------------------------------------------------------- | ------------------------------------------------ | ---------- | -------------------------------------------------------------------------------------------------------- |
| BaselineContextProvider.compose                   | CurrentUserSchemaAccess.getReadableSchema                | constructor injection + per-request call         | VERIFIED   | Single call site at line 103 (Phase 10 substitution seam preserved).                                     |
| BaselineContextProvider permissions               | AccessManager.applyRegisteredConstraints(CrudEntityContext) | per-entity CRUD probe                          | VERIFIED   | Lines 229-230.                                                                                            |
| BuiltInDataTools.findRecords / getRecord          | FetchPlanResolver.resolve                                | method call before DataManager.load              | VERIFIED   | `fetchPlanResolver.resolve` invoked at lines 188, 241.                                                    |
| BuiltInDataTools.getRelatedRecords                | FetchPlanResolver.resolve → FetchPlanIntersector.intersectWithAcl on the FINAL composed plan | method call before DataManager.load | **PARTIAL** | resolve() is called at line 291 (returns intersected dataPlan), but the composed plan with INSTANCE_NAME on the relationship is NOT re-intersected before line 296-300 `dataManager.load(...).fetchPlan(fetchPlan)`. **Gap #1.** |
| FetchPlanResolver                                 | FetchPlanIntersector.intersectWithAcl                    | always-applied pipeline tail                     | VERIFIED   | resolver tail invokes `intersectWithAcl` for every returned plan.                                        |
| BuiltInDataTools.resolveReadableEntityOrThrow     | ToolUserError("unknown_entity", ..., UNKNOWN_ENTITY_HINTS) | three-arg constructor                          | VERIFIED   | All three throw sites use 3-arg form.                                                                     |
| OutputScannerAdvisor                              | HostPrefixPatternProvider + ToolNamePatternProvider      | merged into compile-once pipeline                | VERIFIED   | Constructor consumes both providers.                                                                     |
| DefaultChatServiceImpl.composedSystemPrompt       | AgentSystemPromptRules.PROMPT_RULES                      | string concat at composition site                | VERIFIED   | 2 references — blocking ask + streaming stream.                                                          |

### Data-Flow Trace (Level 4)

| Artifact                                             | Data Variable                          | Source                                                  | Produces Real Data | Status      |
| ---------------------------------------------------- | -------------------------------------- | ------------------------------------------------------- | ------------------ | ----------- |
| BaselineContextProvider.compose                      | `readableSchema`                       | `currentUserSchemaAccess.getReadableSchema()`           | Yes                | FLOWING     |
| BaselineContextProvider.renderPermissionsJson        | `crud.is*Permitted()`                  | `accessManager.applyRegisteredConstraints(crud)`        | Yes                | FLOWING     |
| BuiltInDataTools.findRecords / getRecord             | `plan` from `fetchPlanResolver.resolve` | resolver chain → intersector → `DataManager.load`     | Yes                | FLOWING     |
| BuiltInDataTools.getRelatedRecords                   | `fetchPlan` (composed)                 | resolver returns dataPlan → composed with INSTANCE_NAME → load | Yes BUT INSTANCE_NAME branch is unintersected | **STATIC for INSTANCE_NAME projection** — Gap #1 |
| OutputScannerAdvisor (streaming)                     | `aggregatedResponse`                   | `ChatClientMessageAggregator.aggregateChatClientResponse` | Yes              | FLOWING     |

### Behavioral Spot-Checks

| Behavior                                                                          | Command                                                                                                                                | Result                  | Status |
| --------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- | ----------------------- | ------ |
| Phase 9 key tests pass in default CI                                               | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.PromptContractMockTest" --tests "com.vn.agent.tools.UnknownEntityRetryHintTest" --tests "com.vn.agent.guard.AgentSystemPromptRulesTest" --tests "com.vn.agent.orchestration.BaselineContextProviderTest" --tests "com.vn.agent.audit.AuditFieldHasherTest"` | BUILD SUCCESSFUL in 54s | PASS   |
| Both BLOCKER defects (BL-01 LinkedHashSet roles, BL-02 INSTANCE_NAME bypass) compile and ship in committed code | `grep -F 'LinkedHashSet::new' BaselineContextProvider.java` and `grep -A 4 'fetchPlanResolver.resolve("get_related_records"' BuiltInDataTools.java` | Defects confirmed in source | FAIL (defects present) |

### Requirements Coverage

| Requirement | Source Plan | Description                                                                                                          | Status        | Evidence                                                                                                                                                  |
| ----------- | ----------- | -------------------------------------------------------------------------------------------------------------------- | ------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| AUD-07      | 09-01       | Mutation audit row hashing utility + properties (Phase 11 consumer)                                                  | SATISFIED     | AuditFieldHasher + AiAgentAuditProperties shipped; zero callers per D-18 plumbing-only scope.                                                              |
| SPI-09      | 09-02       | ToolFetchPlanCustomizer SPI                                                                                          | SATISFIED     | SPI interface + FetchPlanContext + no-op default; verbatim TOOL-11 phrase.                                                                                 |
| TOOL-10     | 09-02, 09-04 | SPI signature + consumer wiring                                                                                     | SATISFIED     | SPI signature locked; FetchPlanResolver consumes the chain; default returns Optional.empty().                                                              |
| PROMPT-01   | 09-03       | agent.entities baseline emission                                                                                     | SATISFIED     | Implemented + tested; alpha-sorted, truncation hint, empty-schema omission.                                                                                |
| PROMPT-02   | 09-03       | agent.permissions baseline emission, locale-free cache key                                                           | SATISFIED     | Implemented; locale-invariance test passes.                                                                                                                |
| TOOL-12     | 09-03       | Per-entity permission inventory                                                                                      | SATISFIED     | Built into renderPermissionsJson; CRUD bits + modifiable[]; denied entities never appear (TOOL-12 opacity preserved).                                      |
| TOOL-09     | 09-04       | describe_entity widening via MetadataTools                                                                           | SATISFIED     | All listed fields present; no raw reflection in production tool code; DescribeEntityPayloadTest covers each.                                               |
| TOOL-11     | 09-04       | Override fetch plans pass attribute-policy intersection                                                              | **BLOCKED**   | FetchPlanIntersector exists and is reached for find_records / get_record, but get_related_records bypasses intersection on the relationship's INSTANCE_NAME sub-plan. **Gap #1.** |
| PROMPT-04   | 09-04       | `<data entity="<label>" type="<internalName>">…` envelope                                                            | SATISFIED     | Literal envelope present in `ToolResultFormatter.records`.                                                                                                  |
| PROMPT-05   | 09-04, 09-05 | unknown_entity retry contract in BOTH ToolErrorDto.expected[] AND system prompt                                     | SATISFIED     | UNKNOWN_ENTITY_HINTS at all 3 throw sites; AgentSystemPromptRules.PROMPT_RULES carries the same substrings; PromptContractMockTest cross-asserts.          |
| PROMPT-03   | 09-05       | System prompt forbids internal entity names + tool names in user-facing replies                                       | SATISFIED     | PROMPT_RULES contains both vocabulary rules; wired into ask() and stream().                                                                                |
| PROMPT-06   | 09-05       | OutputScannerAdvisor patterns for host-prefix and tool-name leakage; flag-and-audit posture                           | SATISFIED     | HostPrefixPatternProvider + ToolNamePatternProvider; default-on with opt-out; flag-only contract preserved.                                                |
| TEST-08     | 09-06       | Prompt-contract regression suite EN + VI; flag-promotion via ChatResponseDto                                          | SATISFIED     | PromptContractMockTest 10/10 pass; PromptContractLiveTest excluded from default suite via @Tag("live").                                                    |

**Orphaned requirements:** None. REQUIREMENTS.md Phase 9 cross-reference table (lines 178-200) maps PROMPT-01..06, TOOL-09..12, SPI-09, AUD-07, TEST-08 to Phase 9 — all 13 are claimed by at least one plan in this phase.

### Anti-Patterns Found

| File                                                                            | Line     | Pattern                                                                  | Severity   | Impact                                                                                                                                 |
| ------------------------------------------------------------------------------- | -------- | ------------------------------------------------------------------------ | ---------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java` | 149-153  | `Collectors.toCollection(LinkedHashSet::new)` over `getAuthorities()`     | Blocker    | Non-deterministic role ordering breaks the byte-stable baseline contract that the entire phase rests on (E-01 invariant). **BL-01.**     |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` | 286-300  | Composed `fetchPlan` with `INSTANCE_NAME` on relationship is NOT re-intersected before `DataManager.load`; misleading inline comment claims this is safe | Blocker    | Direct violation of TOOL-11 invariant. A host that DENIES an attribute that contributes to the target's @InstanceName will have it loaded into the LLM-facing payload via the un-intersected nested plan. **BL-02.** |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java` | 157-165  | `@NonNull ChatClientResponse adviseCall(...)` returns null when chain returns null | Warning    | Contract violation hidden behind silent failure. WR-01 in code review.                                                                |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java` | 107-111  | nested-plan walk does not check `canReadEntity` on target metaclass     | Warning    | Implicit trust on attribute check; fragile if Phase 10 LlmExposurePolicy decouples entity- and attribute-level permissions. WR-02.    |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java` | 123-139  | `denialReason` embeds host-supplied attribute names without size cap     | Warning    | Could overflow audit column or break parsing if a buggy host customizer supplies pathological names. WR-03.                            |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java` | 165-176  | `extractUserKey` swallows broad `Exception` from reflective invocation  | Warning    | Masks broken host integrations. WR-04.                                                                                                  |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AiAgentAuditProperties.java` | 34-36    | `!Boolean.FALSE.equals` double-negation idiom (also in AiAgentGuardProperties accessors) | Warning    | Convoluted; same anti-pattern across multiple property records. WR-05.                                                                  |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/FetchPlanContext.java` | 30-37    | `UserDetails user` exposed raw to host SPI                                | Warning    | Leaks authentication internals (e.g. password/hash for some impls); record auto-equals/hashCode is identity-based for this component. WR-07. |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` + `AgentSystemPromptRules.java` | n/a      | Three D-14 hint strings duplicated as separate string literals in two files | Warning    | TEST-08 cross-assertion is the only thing keeping them in sync. WR-06.                                                                  |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiAgentPromptProperties.java` | 30-32    | No validation on non-positive `entityInventoryLimit`                     | Info       | Operator misuse silently disables the inventory or throws elsewhere. IN-03.                                                            |

### Human Verification Required

None. Phase 9 is a backend-only phase; all must_haves are testable from code + tests. The two blockers are observable in code and contradict explicit Plan 09-04 / 09-03 truths and the BaselineContextProvider Javadoc invariant.

### Gaps Summary

Phase 9 ships 28 of 30 must_have truths verified, but two BLOCKER-class defects identified by the standalone code review (`09-REVIEW.md`) reflect real, currently-committed code that contradicts Phase 9's explicit must_have contracts:

**Gap #1 — TOOL-11 INSTANCE_NAME bypass (BL-02):** `BuiltInDataTools.getRelatedRecords` lines 286-300 construct a fetch plan that adds `FetchPlan.INSTANCE_NAME` on the relationship attribute AFTER `fetchPlanResolver.resolve(...)` returns, then passes the composed plan to `DataManager.load(...)` without re-running it through `FetchPlanIntersector.intersectWithAcl(...)`. Plan 09-04's must_have explicitly requires "ALWAYS pipes through `FetchPlanIntersector.intersectWithAcl(...)` before `DataManager.load(...)`." The inline code comment claiming INSTANCE_NAME projections are "by definition readable to anyone who can read the entity" is **factually wrong** per Jmix — `EntityAttributePolicy` can deny attributes regardless of whether they contribute to `@InstanceName`. This is exactly the TOOL-11 invariant Phase 9 set out to enforce.

**Gap #2 — Non-deterministic agent.roles ordering (BL-01):** `BaselineContextProvider.rolesOf` collects authorities into `LinkedHashSet`, preserving the iteration order of `UserDetails.getAuthorities()`. Spring Security does not contract a stable order across user implementations (Jmix users, JWT users, host-custom users). This breaks the byte-stable baseline invariant the provider's Javadoc explicitly claims and that the entire Phase 9 deterministic-prompt-contract rests on. The `agent.permissions` byte-stability test passes only because it asserts permissions JSON in isolation; agent.roles flows into the same `renderAsText` system-prompt block with no determinism test or guarantee.

Both defects are small to fix (one ~3-line change in each file plus regression tests), but they directly contradict explicit Phase 9 must_have truths. Closing them is required before Phase 9 can be considered goal-achieved per the goal-backward verification standard ("task completion ≠ goal achievement").

Counter-evidence in favor of partial close: tests for unrelated code paths pass; all artifacts exist; verbatim phrases / fixed property keys / SPI signatures are exactly as planned. Auto-fix risk is low because the two-line code changes are mechanical and the regression-test patterns already exist in the same test classes.

---

_Verified: 2026-04-27_
_Verifier: Claude (gsd-verifier)_
