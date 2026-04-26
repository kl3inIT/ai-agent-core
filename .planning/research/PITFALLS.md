# Pitfalls Research

**Domain:** Jmix AI Copilot add-on — adding v1.1.0 features (prompt-contract hardening, tool-layer refinements, mutation tools, AI exposure policy, speech/file task input, intent-driven extraction, configurable chat surfaces) ON TOP of the shipped v1.0.0 read-only Copilot.
**Researched:** 2026-04-26
**Confidence:** HIGH for security/transaction/Jmix-integration pitfalls (verified against shipped code in `ai-agent/ai-agent/src/main/java`, `MEMORY.md` rules, Jmix docs, Spring AI 1.1.4 docs); MEDIUM for STT/UX-specific pitfalls (less prior-art in this codebase).

The defining quality of this milestone is that **v1.0.0 already works**. Every pitfall below is framed as a regression risk: a way the new feature, plausibly implemented, breaks an invariant the shipped read-only flow already establishes. Generic LLM risks (jailbreaks, hallucination) are NOT included unless the v1.1 surface introduces a new attack path the v1.0 stack did not have.

The shipped invariants worth naming explicitly, because every pitfall is measured against them:

1. `DataManager` is the only data path; `EntityManager` is forbidden (`CLAUDE.md`).
2. `BuiltInDataTools` is read-only by ASM enforcement (Plan 04 ASM test); no `save`/`saveContext`/`remove`.
3. Audit is durable: `AuditWriter` runs in `REQUIRES_NEW` so PRE/POST rows survive even when the tool transaction rolls back (`ToolCallbackAuditDecorator` Javadoc).
4. `BaselineContextProvider.renderAsText` is byte-deterministic (alphabetical keys, `key=value` lines) so prompt-cache and audit-hash are stable.
5. `CurrentUserSchemaAccess` decides what entities/attributes the LLM can see, narrowing on top of `AccessManager`.
6. Authorization is Jmix's stack — `AccessManager` + `DataManager` row-/attribute-level policies. Per MEMORY "AI is just another Jmix client".
7. Tool errors return `ToolUserError` → `ToolResultFormatter.error`, never raw stack traces.
8. The `ChatPanelFragment` is the single chat-rendering primitive — Plan 07.1 explicitly factored it out for reuse.
9. RAG ingestion is opt-in by host configuration (`AiAgentRagProperties`) and KB lifecycle is a separate flow from chat.

A v1.1 pitfall is, in essence, a way to break one of those nine invariants while believing you haven't.

## Critical Pitfalls

### Pitfall 1: Mutation tools bypass `AccessManager` because `DataManager.save` doesn't go through the same filter path as `DataManager.load`

**What goes wrong:**
The natural way to add a `create_entity` / `update_entity` `@Tool` is to call `dataManager.save(entity)`. Read tools work because `DataManager.load(...).list()` runs queries through the constraint chain; mutation tools call `save`, and developers assume "same `DataManager`, same security." That assumption is wrong: row-level constraints from `jmix-security-data` apply in slightly different shapes for save vs. load, and **attribute-level policies are NOT applied automatically by `DataManager.save`** — a `MODIFY` policy on a single attribute is enforced when the user edits a Jmix view (the framework strips disallowed attributes from the form), but a tool that constructs an entity in code can set any field it wants and `save` will accept it. End-to-end result: the LLM gets to write attributes the user could not write through the UI.

**Why it happens:**
1. `DataManager` looks symmetric in the API but security symmetry is enforced by the *form layer* not the data layer for attribute writes.
2. The shipped v1.0 ASM test forbids `save` in `BuiltInDataTools` (TOOL-04), so developers building v1.1 mutation tools start from a clean file with no model.
3. `MEMORY.md` "UnconstrainedDataManager for system-internal writes" rule trains developers that audit/seed paths use `UnconstrainedDataManager` — but that's exactly the wrong reflex if used for mutation tools. The mutation tool must go through the policy-checked `DataManager`, never `UnconstrainedDataManager`.

**How to avoid:**
- Pre-flight every mutation tool with an explicit `accessManager.isPermitted(new CrudEntityContext(metaClass))` check for `CREATE`/`UPDATE` AND a per-attribute `EntityAttributeContext` check for every attribute the LLM is trying to set.
- Reject the tool call with `ToolUserError("permission_denied")` if any attribute fails — fail-closed, never partial-write.
- Add an ASM test mirroring the v1.0 read-only test: `BuiltInDataMutations` MUST NOT reference `UnconstrainedDataManager`.
- Integration test: a user with `READ` on `Customer` but no `MODIFY` on `Customer.creditLimit` calls `update_entity` setting `creditLimit=1000000` and the assertion is that the row is unchanged AND the audit row is `outcome=DENIED`.

**Warning signs:**
- Mutation tool unit test mocks `DataManager` only — no `AccessManager` interaction in the test.
- Code review shows `dataManager.save(...)` without a preceding `accessManager.isPermitted(...)` for the entity AND each touched attribute.
- Tool description string contains "the LLM can update any field" — phrasing implies symmetry with read, which is the bug.

**Phase to address:** Mutation-capable built-in tools (Phase: Mutation Tools).

---

### Pitfall 2: Mutation tool default-on instead of default-off, breaking the v1.0 safety contract

**What goes wrong:**
Spring Boot auto-configuration's natural default is "feature classes on the classpath get registered." A naive `MutationToolsAutoConfiguration` that creates `MethodToolCallbackProvider.builder().toolObjects(builtInMutations)` will add the tool callbacks to the `ChatClient`'s default tool set the moment the host upgrades to v1.1.0 — silently flipping a working read-only product into a system that can write to the host database. PROJECT.md and v1.0 D-10 state explicitly: "mutations require explicit host opt-in."

**Why it happens:**
1. Auto-config conventions favor "works out of the box."
2. The shipped `AiToolsAutoConfiguration` already auto-registers `BuiltInDataTools` — a copy-paste of that pattern for mutations registers them the same way, which is wrong.
3. Hosts upgrading from 1.0.0 → 1.1.0 read the changelog as "more features" and don't expect a security boundary change.

**How to avoid:**
- New property: `ai.agent.tools.mutations.enabled` defaulting to `false`. `MutationToolsAutoConfiguration` gates on `@ConditionalOnProperty(name = "ai.agent.tools.mutations.enabled", havingValue = "true")`.
- Per-tool opt-in: `ai.agent.tools.mutations.create.enabled`, `.update.enabled`, `.delete.enabled` — host can turn on `create` without `delete`.
- `CHANGELOG.md` for 1.1.0 has a SAFETY section at the top explicitly stating "mutations remain disabled by default."
- Boot-test that asserts: with default properties, no `@Tool`-annotated mutation method appears in the resolved `ToolCallbackProvider` bean.
- `ai-agent-starter` integration test: spin the starter with default config, assert that the published `ChatClient`'s tool callbacks list does NOT contain mutation tools.

**Warning signs:**
- The mutation auto-config has no `@ConditionalOnProperty` annotation.
- A unit test sets the enable property but no test pins down "with property absent, the bean is not created."
- Release notes do not call out the new property.

**Phase to address:** Mutation Tools (Phase) — must be the foundation plan in that phase, before any specific create/update tool is added.

---

### Pitfall 3: Mutation idempotency — the LLM retries the same tool call and produces duplicate rows

**What goes wrong:**
Spring AI's tool-calling loop will, under transient errors or model-side timeout, re-issue an identical tool call. For read tools this is harmless; for `create_order(customer=X, amount=100)` this creates two orders. The model sees the second response, says "done," and the user has a duplicate.

**Why it happens:**
1. The LLM does not know a tool call succeeded if the streaming connection dropped between the tool result emission and the next assistant chunk.
2. Spring AI 1.1.4's tool execution loop (`ToolCallingManager.executeToolCalls`) treats tool calls as side-effect-free for retry purposes.
3. The v1.0 `ToolCallbackAuditDecorator` audits each call, but two calls = two audit rows = two writes; auditing alone doesn't dedupe.

**How to avoid:**
- Mutation tool input contract: every mutation tool's `@ToolParam` schema includes a required `idempotencyKey` (UUID) field. The LLM is instructed to generate one per logical intent and reuse it on retry.
- Server-side: a Jmix entity `AiMutationIntent { idempotencyKey UUID PK, conversationId UUID, toolName, requestHash, responseSnapshot, createdAt }` in the agent store. Mutation tool first does `loadValue ... where idempotencyKey = :k .store("agentstore")`; if found, return the cached response (audited as `IDEMPOTENT_REPLAY`).
- The cached response is replayed as a `ToolUserError`-like result that the LLM can interpret as "this already happened" — NOT silently as success, otherwise the LLM cannot diagnose duplicate-intent attempts.
- Integration test: invoke same tool with same `idempotencyKey` twice; assert one row in the target entity, two audit rows, second outcome `IDEMPOTENT_REPLAY`.

**Warning signs:**
- Mutation `@Tool` signatures have no `idempotencyKey` parameter.
- Audit table shows duplicate `(conversationId, toolName, argumentsJson)` rows minutes apart — that's a retry loop with no idempotency guard.
- Integration tests run mutations once; no "called twice" test.

**Phase to address:** Mutation Tools.

---

### Pitfall 4: Mutation transaction rolls back but audit reports SUCCESS

**What goes wrong:**
The decorator pattern in v1.0 (`ToolCallbackAuditDecorator`) writes a PRE row, calls the delegate, then writes a POST row in `finally`. Both writes use `AuditWriter` in `REQUIRES_NEW` so they survive the delegate's transactional rollback. For read tools this is fine — there's nothing to roll back. For a mutation tool, the delegate runs in `REQUIRED`, the entity save fails on constraint violation, the outer transaction rolls back, but the POST row was written `REQUIRES_NEW` with `outcome=SUCCESS` because the decorator captured the return value before the rollback marker fired. Now the audit log says "wrote Customer X" but no Customer X exists.

**Why it happens:**
1. `ToolCallback.call` returns a String successfully, then JPA flush at transaction commit fails. The decorator already saw the return value as success.
2. Jmix entity validators (`@NotNull`, `@Size`, custom `@Validator`) fire at flush, not at `dataManager.save` invocation.
3. Spring AI does not surface "the transaction this tool ran in was rolled back" as a tool error — the framework only knows the return string.

**How to avoid:**
- Mutation tools call `dataManager.save(entity)` and explicitly trigger flush by reloading the saved entity in the same transaction. If reload fails, throw `ToolUserError("write_failed")`.
- Wrap the mutation method body in a programmatic transaction (`TransactionTemplate`) that returns the saved-entity snapshot on success and explicitly throws on rollback. The decorator captures the throw and writes `outcome=ERROR`.
- Add `outcome=COMMIT_FAILED` enum value in `AiToolCallOutcome` for the specific case "tool returned but transaction rolled back."
- Integration test: mutation that violates a `@NotNull` constraint at flush; assert audit row `outcome != SUCCESS`.

**Warning signs:**
- Mutation tool tests use `@DataJpaTest` rollback semantics and the test says "succeeded" without re-querying the DB through a fresh transaction.
- Audit timestamps show success rows immediately followed by `ERROR` rows from the orchestration layer for the same `runId` — a sign of "tool said yes, framework said no."

**Phase to address:** Mutation Tools (paired with audit extension).

---

### Pitfall 5: `@Composition` cascade writes silently exceed the LLM's intended scope

**What goes wrong:**
Jmix `@Composition` cascades persist of child entities along with parent. If a mutation tool `update_order(orderId, lineItems=[...])` accepts a list of line items in JSON, JPA cascade-persist will replace, add, and delete child rows according to the supplied list. The LLM, reading the prompt-contract description "update the order's lineItems," may submit only the new line items it wants to add, and the cascade will *delete* every line item not in the list. The user said "add a line item." The system silently deleted three.

**Why it happens:**
1. `@Composition` semantics treat the children collection as authoritative — pass `[A]` and the persisted state becomes `[A]`, regardless of prior `[B, C, D, A]`.
2. LLMs treat collection arguments as "the things I want to add," not "the new authoritative set" — natural-language ambiguity.
3. Tool description authors copy entity field names directly without warning the model about replace-semantics.

**How to avoid:**
- Mutation tools targeting `@Composition` parents must NOT accept the children collection as a tool param. Instead, expose separate tools: `add_order_line(orderId, line)`, `remove_order_line(orderId, lineId)`, `update_order_header(orderId, fields_excluding_lines)`.
- If a "replace" semantics tool is genuinely needed, name it `replace_order_lines` and have its `@Tool` description begin with "DESTRUCTIVE: this replaces all existing lines."
- Pre-write check: count children before write, log to audit; post-write reload and log delta (`children_added`, `children_removed`).
- Integration test: order with three lines, LLM "adds one" via a misuse path; assert preserved lines.

**Warning signs:**
- Mutation tool has a `List<LineItem> lines` parameter at all.
- Tool description contains "update the order including its lines" without clarifying replacement semantics.
- Audit shows large `children_removed` counts for tool calls described as "add."

**Phase to address:** Mutation Tools.

---

### Pitfall 6: Exposure policy widens instead of narrows because admin UI has no monotonicity guard

**What goes wrong:**
SEED-007 activates a layer that narrows what the LLM sees BELOW the user's Jmix permissions. The admin UI naturally allows toggling per entity/attribute. A bug or a misunderstanding makes "allow this attribute for the LLM" actually grant the LLM access to an attribute the user doesn't have `READ` on through Jmix (because the rule is checked instead of the user's policy). Now the AI exposure policy *widens* the user's surface — the exact opposite of its purpose.

**Why it happens:**
1. The natural data model for the rule is `{entity, attribute, allow|deny}` — the same shape as a Jmix policy. Devs intuitively interpret "allow" as "grant," not "permit if otherwise allowed."
2. Confusion between two conceptual layers: Jmix says yes/no for the user; exposure policy *additionally* narrows for the LLM. The intersection must be enforced explicitly.
3. Admin UI label "Allow attribute X for LLM" reads to administrators as "grant access" — it actually means "do not additionally hide."

**How to avoid:**
- Implement exposure policy as `EXCLUDE only`. There is no "allow" rule type. Policy answers: "should this entity/attribute be hidden from the LLM EVEN IF the user has access?" Yes/no, never "grant."
- Final visibility = `userJmixVisibility AND (NOT exposureExcludes)`. Always boolean-AND, never OR.
- `CurrentUserSchemaAccess` already runs the user-side check; new `LlmExposureFilter` runs after it and can only remove items, never add.
- Test: user has `READ` on `Customer.creditLimit`; admin sets exposure rule `Customer.creditLimit -> excluded`; assert `getReadableSchema()` for that user does NOT include `creditLimit`. Inverse test: admin removes user's Jmix permission for `creditLimit`; admin tries to add an "allow" rule — UI must not have an allow option, and even if forced via REST, the visibility result must remain hidden.
- Admin UI label phrasing: "Hide from AI" / "Visible to AI" toggle, never "allow / deny."
- ArchUnit-or-targeted-unit-test: the class implementing the exposure filter must not call `getReadableSchema()` on `UnconstrainedDataManager`.

**Warning signs:**
- The exposure rule entity has an `EXPOSURE_TYPE` enum with both `ALLOW` and `DENY` values.
- The combination logic is `userVisible OR ruleAllows` anywhere in the code.
- Admin UI has a "force expose" button.

**Phase to address:** AI Exposure Policy (Phase).

---

### Pitfall 7: LLM permission inventory leaks denied entity names

**What goes wrong:**
The new `list_my_permissions` / "permission inventory" tool returns the user's effective entity-level permissions to help the LLM avoid `unknown_entity` retries. A naive implementation iterates all `MetaClass` instances and reports `{name, can_read, can_write}` — including names of entities the user is denied. This contradicts the v1.0 contract "if access is denied, the model should behave as if the entity does not exist" (see ownership-opacity test in `OwnershipOpacityTest.java`). Once the LLM has the entity name, it can include it in error messages, suggestions ("perhaps you meant entity X?"), or even argument values, leaking the name to a non-privileged user.

**Why it happens:**
1. "Permission inventory" naturally means "list of all permissions" in admin UI thinking; for the LLM, it must mean "list of entities the user CAN see."
2. The wide form is easier to test ("does the result match the policy table?") than the narrow form.

**How to avoid:**
- The permission-inventory tool returns ONLY entities for which the user has at least `READ`. Denied entities are never named in the output.
- The same contract applies to attributes: only readable attributes are listed; the existence of denied attributes is not signaled.
- The output schema must not have a `denied: true` field — there is no place to put a denied entity name.
- Unit test: user with `READ` only on `Customer`; tool result is `[{name: "Customer", ...}]` and a JSON-text scan asserts the string "Order" (denied) appears nowhere in the response.
- Integration test ports `OwnershipOpacityTest`'s contract to the new tool.

**Warning signs:**
- The inventory tool's `@Tool` description says "lists all permissions" instead of "lists entities the user can read."
- The tool implementation iterates `metadata.getSession().getClasses()` directly without filtering by `accessManager`.
- Test fixtures show denied-entity names in expected output JSON.

**Phase to address:** Tool-layer refinements (Phase).

---

### Pitfall 8: Prompt cache invalidated by every request because baseline context now includes inventory but key isn't stable

**What goes wrong:**
Prompt-contract hardening adds a readable entity inventory to the baseline context. The natural implementation iterates entities in whatever order `Metadata.getSession().getClasses()` returns. That order is JVM-instance-dependent (HashMap iteration). Two requests from the same user produce different `agent.entitiesInventory` strings, which means different prompt hashes, which means provider-side prompt-cache misses on every request and audit-hash instability. The latter breaks the v1.0 invariant that `BaselineContextProvider.renderAsText` is byte-deterministic.

**Why it happens:**
1. `Metadata.getSession().getClasses()` documentation does not promise order.
2. Devs reuse the existing `BaselineContextProvider.renderAsText` deterministic-key contract for the *outer* keys but forget to apply ordering to the *inner* inventory.
3. Tests run with a single fixture and happen to pass by coincidence.

**How to avoid:**
- The inventory rendering sorts by entity logical name (`metaClass.getName()`) — alphabetical, locale-independent, ASCII collation.
- Per-entity attribute lists also sort alphabetically.
- Locale-sensitive labels (`messageTools.getEntityCaption`) MUST be rendered as a separate line that is excluded from the prompt-cache key — or the cache key is computed against the locale-stripped form. Otherwise users in different locales get different cache keys for identical schemas.
- Unit test: render baseline twice on a freshly-restarted JVM; assert byte-equality. Run in two locales; assert the cache-key portion is identical.
- Add a `BaselineContextProviderTest#inventoryIsDeterministic` mirroring the existing determinism test.

**Warning signs:**
- Inventory rendering uses streams over `getClasses()` without `.sorted(...)`.
- Test asserts only that "Customer" appears, not the exact string of the rendered block.
- Cache hit rate drops sharply after v1.1 deploy.

**Phase to address:** Prompt-contract hardening (Phase).

---

### Pitfall 9: Token-budget blowup on hosts with many entities — the inventory grows linearly and breaks within-context tool loops

**What goes wrong:**
The injection of `agent.entitiesInventory` adds N×K tokens to every system prompt where N=entities, K=attributes/entity. For a host with 200 entities and 30 attributes each, that's ~6000 tokens prepended to every tool-loop iteration. v1.0's `TokenBudgetGuard` measures total context against the model's window; the new baseline might fit one round but blow up by iteration 3. Symptom: late-iteration `TokenBudgetExhaustedException` that wasn't possible in 1.0.

**Why it happens:**
1. Test data has ~5 entities; production hosts have 100+.
2. The inventory is rendered once but kept across all tool-loop iterations because it's part of the system prompt.

**How to avoid:**
- Inventory rendering is gated by a configurable budget: `ai.agent.context.inventoryMaxTokens` (default e.g. 1500). When the full inventory exceeds the budget, render only entity names + count of attributes; drop attribute lists. When still over, fall back to "see `describe_entity` to inspect any of N entities" and rely on the tool-call path.
- Telemetry: record the inventory's token count as an audit attribute on the run's first iteration, so operators see when it's close to budget.
- Test fixture with synthetic 100-entity model; assert prompt size stays within configured limit and that `describe_entity` is still callable for entities not detailed in the inventory.

**Warning signs:**
- Token budget exceptions cluster at iteration 2+, not iteration 1.
- Hosts with large schemas report regressions; small-schema demos work.
- The inventory string is a single field in `RunContext` with no truncation logic.

**Phase to address:** Prompt-contract hardening.

---

### Pitfall 10: `describe_entity` v1.1 leaks framework noise (raw `MetaClass.toString`, internal `@Comment` text, `@JmixProperty` flags)

**What goes wrong:**
The richer `describe_entity` wrapper's natural implementation is `return metaClass.toString() + attributes.map(MetaProperty::toString)`. That dumps internal Jmix model state (annotations, system flags like `_instanceName` placeholders, store names like `agentstore`, store-internal types). The LLM may echo that back verbatim to the user, exposing internal architecture. Worse: `@Comment` annotations on entity fields are intended for developer documentation but in many hosts contain phrases like "PII — encrypt at rest" or "internal-only, do not expose to UI." Now the LLM is reading those comments and might reason about them in user-facing replies.

**Why it happens:**
1. `MetaClass#toString` is convenient and looks structured.
2. `@Comment` is a common Jmix pattern and feels like documentation, not metadata. Developers don't think of it as a leak vector.
3. Test fixtures rarely include `@Comment` text.

**How to avoid:**
- `describe_entity` returns a hand-curated DTO: `name`, `caption` (locale-aware), `attributes: [{name, caption, type, multiplicity, required}]`. NO `@Comment`, NO store name, NO Jmix-system annotations, NO `MetaClass#toString`.
- Allowlist the fields included in the DTO; do not iterate `metaClass.getAnnotations()`.
- Test: fixture entity with `@Comment("INTERNAL: do not expose")` on a field; assert that string appears nowhere in the tool result.
- Test: assert tool result JSON does not contain "agentstore", "MetaClass", or "io.jmix".

**Warning signs:**
- The describe_entity result is much larger than v1.0's (>2x).
- Sample tool output in PR contains `_instanceName`, `JmixProperty`, or store-name strings.
- No allowlist or filter is visible in the implementation.

**Phase to address:** Tool-layer refinements.

---

### Pitfall 11: Fetch-plan host override widens projection beyond `AccessManager` allowed attributes

**What goes wrong:**
The new `FetchPlanOverrideSpi` lets hosts customize the fetch plan for built-in tools (e.g., to load `Customer.preferredContact` whenever a customer is loaded). A host implementation returns a fetch plan that includes attributes for which the current user does not have `READ`. `DataManager` will load those attributes (because the constraint is at the load-context level, not the fetch-plan level for all attributes — Jmix attribute policies are applied at projection but not always before the SQL fetch). The serialized JSON returned to the LLM contains denied attribute values.

**Why it happens:**
1. Fetch plan is conceptually "shape of the projection," not a security boundary.
2. SPI implementers reasonably assume "if I include it in the fetch plan, security still applies" — and for top-level entity attributes via Jmix data security it usually does, BUT for nested associations, joined columns, and computed `@JmixProperty` it's looser.
3. SPI is invoked AFTER `AccessManager` checks the entity, not after each attribute is filtered.

**How to avoid:**
- Wrap host-supplied fetch plans: after the host returns, the wrapper iterates the plan's properties and removes any whose `EntityAttributeContext` is denied for the current user.
- The plan is the *intersection* of host wish and user policy.
- Document SPI Javadoc: "your returned plan is filtered against user attribute policies; it cannot widen access."
- Test: host SPI requests `creditLimit`; user lacks `READ` on `creditLimit`; assert the loaded entity result has `creditLimit=null` (or omitted in JSON).

**Warning signs:**
- The SPI integration code passes the host's plan straight to `LoadContext.setFetchPlan` without inspection.
- SPI Javadoc mentions only "customize" without "subject to security."

**Phase to address:** Tool-layer refinements (fetch-plan override sub-feature).

---

### Pitfall 12: Fetch-plan cache key collides across users, causing one user's plan to be served to another

**What goes wrong:**
Caching the resolved fetch plan (host override + user-policy intersection) is necessary for performance — the policy lookup is expensive. The cache key is naturally the entity name. But the resolved plan depends on the current user's policies, so two users hitting the same entity must get different plans. A key of `entityName` alone returns user A's plan for user B's request, projection includes user A's allowed attributes, and user B sees data they shouldn't.

**Why it happens:**
1. Spring's default `@Cacheable` keying uses method args; if the override resolver method takes only `(MetaClass)`, the cache key is the MetaClass. User identity is implicit via `CurrentAuthentication`.
2. Performance optimization is added late, after the per-request path works.

**How to avoid:**
- Cache key includes a `userPolicySignature` derived from `CurrentUserSchemaAccess` — a stable hash of (userId, role-set, granular policy versions). Or scope the cache by user (per-session cache).
- The signature must include resource-role assignment timestamps so role changes invalidate the cache.
- Test: user A has `READ` on `Customer.creditLimit`, user B does not; both call `getRecord` for the same Customer; assert B's response omits `creditLimit`. Re-run after invalidating cache; same result.
- Prefer NOT caching across users until profiling proves it's necessary; per-request plan resolution is bounded.

**Warning signs:**
- Cache configuration uses just entity name as key.
- Cache hit rate is suspiciously high for a multi-tenant app.

**Phase to address:** Tool-layer refinements.

---

### Pitfall 13: Two-layer enforcement produces contradictory error messages — model gets `unknown_entity` from one layer and `access_denied` from another for the same entity

**What goes wrong:**
v1.1 now has two narrowing layers: Jmix `AccessManager` and the new exposure policy. When a user asks about `Customer`:
- If Jmix denies it, v1.0 contract says respond as if the entity doesn't exist (`unknown_entity`).
- If exposure policy denies it (Jmix would have allowed it), what's the response?

A naive implementation returns `access_denied` from the exposure layer, contradicting the `unknown_entity` contract. The LLM sees both signals across different tool calls and starts behaving inconsistently — probing for what's actually denied vs. nonexistent. Worse: the user sees "access denied" and learns the entity name exists, partially defeating the exposure policy.

**Why it happens:**
1. Different layers, different teams, different error-vocabulary defaults.
2. `access_denied` reads as "more correct" than `unknown_entity` because it's truthful about *why*.

**How to avoid:**
- The contract is collapsed: if the LLM cannot see an entity through the union of Jmix + exposure policy, the response is **always** `unknown_entity`. The reason is never communicated.
- The decision is centralized: `CurrentUserSchemaAccess` (existing) gains a wrapper that applies the exposure layer, and all six built-in tools call the wrapper. Mutation tools too.
- Test: user is Jmix-allowed on `Customer`, exposure-denied on `Customer`; tool returns `unknown_entity`. Audit row records the *real* reason (`exposure_denied`), but the LLM-facing response is opaque.
- The audit reason DOES distinguish — operators need it for support — but the LLM-facing JSON does not.

**Warning signs:**
- Tool error JSON has different `code` values for "policy denied" vs. "entity not in schema."
- A LLM-facing chat reply ever contains "you do not have permission to access X" naming an entity.

**Phase to address:** AI Exposure Policy.

---

### Pitfall 14: Admin changes exposure rule but cached baseline context / chat memory keeps stale schema

**What goes wrong:**
Admin marks `Customer.creditLimit` as exposure-denied at 12:00. User's open chat session was started at 11:30; its system prompt includes the entity inventory rendered at 11:30 — listing `creditLimit`. The model sees `creditLimit` in its context and continues to call `get_record` for it; the tool now correctly denies, but the user's experience is broken (the model "knows" about a field that no longer exists from its perspective). Chat memory from earlier in the session also references `creditLimit` in tool result snippets.

**Why it happens:**
1. The system prompt is built once per request, but the inventory was prepared from a cached `getReadableSchema()`.
2. JDBC chat memory persists past tool results verbatim.
3. Admin UI changes the rule, but no cache-busting event is fired.

**How to avoid:**
- Exposure-rule changes publish a Spring event `LlmExposureChangedEvent` that:
  - Evicts `getReadableSchema` per-user caches.
  - Marks all open `RunContext` runs as needing schema-reload on next iteration.
- Document trade-off: in-flight messages cannot be redacted retroactively; commit to "next user message after admin save uses the new policy." Communicate this in admin UI.
- Test: admin changes rule; user's existing session sends a new message; assert the inventory in the new system prompt does NOT include the now-denied attribute.
- Optional: a periodic "schema-fingerprint check" advisor that compares the rendered inventory's hash against the live one; if drifted, refuses the iteration with `unknown_entity` rather than answering from stale context.

**Warning signs:**
- Caches with no event-driven invalidation.
- Admin UI has no "in-flight sessions affected" warning.

**Phase to address:** AI Exposure Policy.

---

### Pitfall 15: Speech-to-text PII captured durably in audit logs

**What goes wrong:**
User speaks "Set up a meeting with Dr. Smith at 555-123-4567 about the Anderson divorce case." The STT transcription becomes a chat message. v1.0 audit captures every chat message via `AiMessage` rows. Now PII (phone numbers, names, contexts) sits in a durable Jmix entity, queryable via `DataManager`. Compliance/GDPR exposure that didn't exist with text input is now real because users speak more loosely than they type.

**Why it happens:**
1. STT input is treated as "just another text input" downstream.
2. Audit was scoped for tool calls and structured prompt content, not for raw user utterances of arbitrary sensitivity.
3. Speech invites natural-language phrasing that includes more incidental PII than typing.

**How to avoid:**
- New property `ai.agent.audio.audit.storeTranscript` defaulting to `false`. With the default, audit stores `transcript_length`, `language`, `confidence`, `provider` — not the transcript. The transcript flows into the LLM and into `AiMessage` (the conversation memory) as usual; the *audit* tree records a hash + length only.
- If the host needs full transcripts in audit, they opt in — and the operator README warns about retention/GDPR implications.
- Provide a host-configurable `TranscriptRedactor` SPI with a default no-op that hosts can swap for an enterprise PII detector.
- Test: opt-in disabled; audit row for STT input has `audit_payload` empty/hashed and original transcript still in `AiMessage`.

**Warning signs:**
- Audit detail dialog shows full transcript text by default.
- No mention of GDPR / retention in the STT feature README.
- The transcript is logged to `slf4j` at INFO level.

**Phase to address:** Speech-to-Text + File Task Input (Phase).

---

### Pitfall 16: Task-scoped file accidentally ingested into KB pgvector

**What goes wrong:**
The shipped KB ingestion path (`IngesterManager`, `ClasspathMarkdownIngester`, `KnowledgeDocumentService`) writes uploads to pgvector with role-scoped filters. The new "task-scoped file attachment" feature sends a file to *one chat task* — meant to be transient. A naive implementation reuses the KB ingestion entry point (it's already there, it works, it does role scoping). Result: every chat-uploaded image, screenshot, or temporary doc enters the persistent vector store, polluting RAG with one-off content and creating a privacy issue (one user's screenshot becomes retrievable for any user with overlapping role scope).

**Why it happens:**
1. Reuse is the natural reflex — the `IngesterManager` is right there.
2. Both flows accept `MultipartFile` / `InputStream`. The shape is identical.
3. The pending todo `2026-04-24-add-dedicated-chat-speech-and-file-task-input.md` exists *because* this conflation was identified.

**How to avoid:**
- Task-scoped files have a dedicated path that does NOT touch `VectorStore`. They are loaded into the per-request `RunContext` as a `List<TaskAttachment>`.
- Each attachment is converted to a Spring AI `Media` object (for vision-capable models) or a base64 string injected into the user message — never embedded, never persisted to vector DB.
- Lifecycle: attachment exists until the chat run completes; then dropped from memory. Optional: a separate `AiTaskAttachment` Jmix entity for retention with a TTL purge job, but explicitly NOT in pgvector.
- Test: upload a file via task path; assert pgvector row count is unchanged. Assert the file's content is in the LLM request payload but not in any `VECTOR_STORE` table.
- Operator README has a section "task files vs KB documents" with a clear diagram.

**Warning signs:**
- Task-file feature implementation injects `IngesterManager` or `VectorStore`.
- Code path shares the same DTO/entity as `AiKnowledgeDocument`.
- Test does not specifically assert vector store size unchanged.

**Phase to address:** Speech-to-Text + File Task Input.

---

### Pitfall 17: Intent-driven extraction lets the LLM call `ViewNavigators` directly

**What goes wrong:**
The intent-driven extraction → form prefill workflow's most natural Spring AI implementation is a `@Tool` named `open_form` that takes `(viewId, prefillData)` and calls `viewNavigators.view(...)`. Now the LLM "owns" UI navigation: any prompt-injection or model misfire opens an arbitrary view (potentially admin views) with arbitrary prefill, including admin-restricted views. This violates MEMORY rule "rely on Jmix AccessManager/DataManager for all security; no AI-specific exposure layer" and the controller-layer principle that only the controller layer should call `ViewNavigators`.

**Why it happens:**
1. Spring AI tool calling makes "give the LLM a button" trivially easy.
2. Reference architecture (`jmix-ai-backend`) does not include this pattern, so devs invent it freshly.

**How to avoid:**
- The LLM does NOT have a `navigate` tool. The extraction tool returns a *structured intent* DTO: `{viewType, suggestedPrefill, confidence}`. The DTO flows back to the chat UI controller (Vaadin server-side); the controller asks the user "Open the form to create X with these values?" with a confirm button. Only on user confirmation does the controller call `viewNavigators` from the controller layer.
- View resolution: the extraction service maintains an allowlist mapping intent types → view IDs. LLM cannot specify arbitrary view IDs.
- Authorization: even after user confirmation, the controller checks `accessManager.isPermitted(new ViewContext(viewId))` before navigating. Denied → notification, no navigation.
- Test: malicious prompt "open the user-management view"; LLM returns intent `{viewType: "USER_ADMIN"}`; assert intent is not in the allowlist and the chat reply explains the LLM cannot do that.

**Warning signs:**
- A `@Tool`-annotated method with `viewId` as a parameter.
- `ViewNavigators` injected into a `@Tool`-bearing class.
- The chat UI auto-navigates without a user-confirmation step.

**Phase to address:** Intent-driven Extraction (Phase).

---

### Pitfall 18: Prefilled draft bypasses Jmix entity validators and security

**What goes wrong:**
Extraction produces a `Customer` draft with `creditLimit=999999`. The chat UI prefills the form fields — *programmatically*, not through Jmix DataContext binding — and the user clicks Save. The form's binding validators may fire on user-edited fields, but if the prefilled field isn't touched, some validation paths are skipped (especially custom `@Validator` that runs on `dataContext.commit`). Worse: if the chat UI prefills via `setValue` on raw Vaadin components, attribute-level Jmix policies aren't checked at the form layer — they would have been checked if the user had typed into the field through the Jmix view.

**Why it happens:**
1. "Prefill" naturally means setting field values directly.
2. Vaadin component `setValue` bypasses Jmix's DataContext binding even in a Jmix view.
3. Validators on entity classes are rarely tested with programmatic prefill paths.

**How to avoid:**
- Prefill goes through `DataContext.create(Customer.class)` with `setValue` on the *entity instance*, not the UI component, so DataContext binding propagates the values through the standard Jmix change path. UI components reflect the entity's state.
- After prefill, run `dataContext.validate()` (or rely on save-time validation) BEFORE the user can click Save. Show validation errors as if the user had typed them.
- Attribute-level write policy: the controller iterates extracted attributes; for any attribute the user lacks `MODIFY` permission on, *do not prefill it* — leave it blank and surface a notification "I left fields blank that your role can't write."
- Test: extract customer with `creditLimit=999`; user has Jmix `READ` but not `MODIFY` on `creditLimit`; assert the form opens with `creditLimit` blank, not 999.

**Warning signs:**
- Prefill code calls `textField.setValue(...)` directly.
- No call to `dataContext` in the prefill path.
- Tests cover happy path only, not "user lacks write permission on field X."

**Phase to address:** Intent-driven Extraction.

---

### Pitfall 19: Two `ChatService` instances (one per chat surface) split conversation memory

**What goes wrong:**
Three configurable chat surfaces (full view, sidebar, floating launcher) each get their own `@ViewController` / fragment. A natural implementation gives each its own `ChatService` bean (or, less obvious, each surface starts a new `conversationId` because it doesn't see the existing one). User chats in floating launcher about an order, switches to the full view to keep talking — the full view has no memory of the previous turn. Conversation is split by surface, not by user.

**Why it happens:**
1. Each Vaadin view feels like a "new screen."
2. `conversationId` is a query parameter on the full view (`ChatView.onQueryParametersChange`); sidebar/floating have no URL to carry it.
3. Spring AI JDBC chat memory keys on `conversationId`; if the surfaces don't share one, memory is split correctly per the framework, but wrong per user mental model.

**How to avoid:**
- Single per-user "active conversation" tracker — a Vaadin session-scoped or Jmix `UserSettings` entry — that all three surfaces read/write. New surface attaches to the active conversation; user can explicitly start a new one from any surface.
- The `ChatPanelFragment` (already factored out per Plan 07.1) is the only chat-rendering primitive; surfaces are thin shells around it. Shell sets the `conversationId` from the shared tracker, not from query string only.
- Test: user opens floating launcher, sends message; user opens full view; assert the message appears in full view history.
- Cross-surface continuity: when surface A closes mid-stream, surface B opening must show the in-progress streaming OR a clear "continue in surface A" indicator. Decide one explicit behavior; don't both.

**Warning signs:**
- Three different `@Subscribe` handlers each calling `chatService.startConversation()`.
- `ChatPanelFragment` is *re-implemented* per surface instead of reused.
- No integration test that switches surfaces mid-conversation.

**Phase to address:** Configurable Chat Surfaces (Phase).

---

### Pitfall 20: Floating launcher z-index and keyboard-focus conflict with Jmix dialogs

**What goes wrong:**
The floating launcher uses `position: fixed` at z-index 9999. A Jmix `Dialogs.createOptionDialog(...)` opens at Vaadin's default dialog z-index. Either the launcher floats over the dialog (intercepting clicks) or the dialog hides the launcher. Keyboard focus is the worse problem: the launcher text input grabs focus when the dialog opens, breaking screen-reader flow and tab order. Floating UI in a Vaadin server-side app has no native browser-modal semantics.

**Why it happens:**
1. CSS z-index is ad-hoc; Vaadin's overlay system uses its own stacking context.
2. Vaadin Flow dialogs are full overlays; floating launchers are static positioned overlays. The two systems don't know about each other.
3. Accessibility testing is rarely done early.

**How to avoid:**
- Floating launcher uses a Vaadin overlay primitive (e.g., `Popup` or a custom component extending `Dialog`) so it participates in Vaadin's z-index management.
- A `UI.getCurrent().getInternals()` listener watches for dialog opens; launcher hides itself while any modal is open and restores after close.
- Focus management: launcher does NOT auto-focus its input on render; user clicks/tabs to focus. When focus is in launcher and Esc is pressed, launcher minimizes.
- Accessibility: launcher is keyboard-reachable via documented shortcut (e.g., `Ctrl+/`), but does not steal focus on page load.
- Manual UAT checklist: open launcher, open Jmix `Dialogs.createMessageDialog`, click in dialog — assert dialog receives clicks.

**Warning signs:**
- The launcher is a raw `Div` with `getStyle().set("position", "fixed")`.
- Keyboard testing is not in the test plan.
- z-index values appear in CSS as magic numbers without comments referencing Vaadin's stacking.

**Phase to address:** Configurable Chat Surfaces.

---

### Pitfall 21: Admin toggle for chat surfaces is compile-time only — host can't disable surfaces at runtime

**What goes wrong:**
"Admin toggle" gets implemented as a Spring property: `ai.agent.surfaces.floating.enabled=false`. To disable the floating launcher in production, the operator restarts. For an admin-governed feature on a multi-tenant or enterprise app, that's the wrong granularity — admins expect to toggle in a UI and have it take effect.

**Why it happens:**
1. `@ConditionalOnProperty` is the easiest way to gate beans.
2. Vaadin views are scanned at startup, not dynamically.

**How to avoid:**
- Property gating governs whether the surface bean is *available*; runtime toggle (a Jmix `AiAgentSettings` entity row) governs whether it *renders* for end users.
- The main layout queries the settings on each navigation; surfaces hide themselves if disabled at runtime.
- Per-role override: settings include "admin can always see floating launcher even when end-users can't" so admin can demo/test.
- Test: change settings via admin UI; assert end-user navigation no longer shows the disabled surface (no restart).

**Warning signs:**
- Surface enablement is solely `@ConditionalOnProperty`.
- Admin UI for surfaces does not exist or is read-only.

**Phase to address:** Configurable Chat Surfaces.

---

### Pitfall 22: Mutation tool errors leak structured-output diagnostic fields containing sensitive info

**What goes wrong:**
Mutation tools return structured errors via `ToolErrorDto` (existing) and possibly Spring AI's structured-output validation. A constraint-violation message like `"Customer with ssn=123-45-6789 already exists"` from a unique-constraint exception bubbles up into the tool result string. The model includes it in the user-facing reply.

**Why it happens:**
1. JPA exception messages quote the violating values for debugging.
2. Spring AI structured output errors echo the failed JSON back, which contains user-supplied data.
3. v1.0 read tools rarely had this risk because read-side errors are usually "not found"-shaped.

**How to avoid:**
- `ToolUserError` for mutations defines a strict allowlist of safe fields: `code`, `field` (attribute name only), `expected` (type/range, never user values). No `message` field that includes original values.
- A `MutationErrorTranslator` between `DataAccessException` and `ToolUserError` that maps known JPA exceptions to safe codes and discards exception messages.
- Test: trigger a unique-constraint violation with PII in the input; assert the tool result string does not contain the PII value.

**Warning signs:**
- Catch block: `catch (Exception ex) { return error(ex.getMessage()); }`.
- Audit shows tool results with literal SQL fragments.

**Phase to address:** Mutation Tools.

---

### Pitfall 23: STT/transcription provider tokens leak as a separate cost line item

**What goes wrong:**
Speech-to-text uses a provider (OpenAI Whisper, Azure Speech, or local). The provider has its own API key, separate billing, separate rate limit, separate auth. v1.0's `AiAuditEvent` tree captures LLM token counts but has no slot for STT. Audit underreports cost; finance sees a separate API bill no one tracks; operators can't debug "why is my chat slow today" because STT latency isn't audited.

**Why it happens:**
1. STT feels like an "input adapter" not part of the AI stack.
2. The provider is configured separately, often with a separate Spring AI bean (`AudioTranscriptionModel`).

**How to avoid:**
- Extend the audit tree with a sibling event type `STT_TRANSCRIPTION` parented to the user message: `provider`, `model`, `audioDurationSeconds`, `latencyMs`, `tokenCount` (some providers report it).
- Health check / metrics: STT provider availability is part of the operator dashboard.
- Property-driven STT enablement (`ai.agent.audio.stt.enabled`); default false; clearly documented in CHANGELOG.
- Test: invoke STT in audit-test fixture; assert audit row exists; assert latency captured.

**Warning signs:**
- STT integration commits add a new dependency without an audit-event addition.
- The operator README has no section on STT cost.

**Phase to address:** Speech-to-Text + File Task Input.

---

### Pitfall 24: Browser-side language detection misaligned with server-side audio processing

**What goes wrong:**
Browser captures audio with `lang="en-US"` from the page locale; server-side STT defaults to auto-detect or `"vi"` (host's primary locale). Result: English speech transcribed as Vietnamese phonetic gibberish, users blame the model. Or the inverse: Vietnamese speech sent without language hint to a provider that defaults to English.

**Why it happens:**
1. `getUserMedia` doesn't enforce language; it's an HTML attribute.
2. STT providers have different language conventions (`"en"` vs. `"en-US"` vs. `"eng"`).
3. The host's user locale (Jmix `CurrentAuthentication.getLocale()`) may differ from the user's spoken language.

**How to avoid:**
- The chat UI exposes a language selector for STT input that defaults to the user's Jmix locale but is user-overridable.
- The server-side STT call always passes the language explicitly — never relies on auto-detect for production. Auto-detect is opt-in via property.
- The full chain (browser audio capture, transport, server-side STT) carries one language code; map it once at the server boundary to provider-specific format.
- Test fixture: same audio, two language codes, assert different transcriptions.

**Warning signs:**
- STT integration call has no `language` parameter.
- UI has no language selector for voice input.

**Phase to address:** Speech-to-Text + File Task Input.

---

### Pitfall 25: Draft state for intent-driven extraction lives too long, leaks across users

**What goes wrong:**
Intent extraction returns a structured draft. The chat UI stores it for the confirm-then-prefill flow. If the draft is stored in a long-lived bean (`@Component` with a `Map<userId, Draft>`) without TTL, drafts accumulate. Worse: if stored in Vaadin session attribute and the session is ever shared (e.g., a misconfigured proxy), draft for user A leaks to user B on session collision.

**Why it happens:**
1. "Just put it in a map" is the easy first implementation.
2. Vaadin session lifecycle is well-understood by Vaadin devs but not always by AI-feature devs.
3. No automatic eviction in plain `ConcurrentHashMap`.

**How to avoid:**
- Drafts live in `RunContext` (per-conversation, per-request) only. They do NOT survive the chat turn.
- If the user delays confirmation, the draft is included in the LLM's next system prompt as "pending draft" — re-rendered each turn from server state, not held in client memory.
- After confirmation/cancellation, draft is explicitly cleared; a `@Scheduled` job purges any stragglers older than N minutes.
- Test: user A starts extraction, user B logs in, user A's draft is not visible to B in any context.

**Warning signs:**
- A `@Service` or `@Component` with a `Map<UUID, Object>` field.
- No expiration policy.
- Vaadin session attribute use without lock-down.

**Phase to address:** Intent-driven Extraction.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Reuse `IngesterManager` for task-scoped file input | Half the code | Vector store gets polluted with transient one-off files; privacy/role-leak risk | Never |
| Skip `idempotencyKey` on simple mutation tools because "the LLM rarely retries" | Smaller tool schema | Duplicate writes in production once retries happen, hard to retroactively dedupe | Never (idempotency key is mandatory) |
| Implement exposure rule with both `ALLOW` and `DENY` semantics for "flexibility" | Familiar policy shape | Layer can widen access; impossible to prove monotonicity in tests | Never |
| Use `UnconstrainedDataManager` in mutation tool to "skip duplicate AccessManager checks" | Faster path | Bypasses Jmix security entirely; v1's whole posture defeated | Never |
| Cache resolved fetch plan by entity name only | Big perf win | Cross-user data leak on hit | Never; only cache scoped per-user |
| Floating launcher styled with raw CSS `position: fixed` | Quick demo | Z-index collisions with Vaadin overlays; accessibility broken | Demo/prototype only; v1.1 ship requires Vaadin overlay primitive |
| Treat STT input as just-text downstream | One code path | PII durably audited; cost and latency invisible | Never; STT must have dedicated audit event and redaction toggle |
| Extract intent → directly navigate UI from `@Tool` | Single-click magic UX | LLM owns navigation; arbitrary view exposure on prompt injection | Never; controller layer is the only navigator |
| Auto-enable mutation tool auto-config | Out-of-box mutations | Silent safety regression for hosts upgrading; CHANGELOG can't undo it | Never; default OFF |

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| Spring AI 1.1.4 `ToolCallingManager` | Assume tool calls are retried only on explicit error; trust no-retry default | Add `idempotencyKey` to every mutation tool; treat all tool calls as at-least-once |
| Jmix `AccessManager` | Check entity policy only; assume `DataManager.save` enforces attribute policies | Explicitly check `EntityAttributeContext` for every attribute being written |
| Jmix `DataManager` | Use `UnconstrainedDataManager` for mutation tools "for system writes" | Mutation tools always use the policy-checked `DataManager`; `UnconstrainedDataManager` is for audit/seed only (per MEMORY rule) |
| Spring AI prompt cache | Render baseline context with default `Map` iteration order | Sort all keys + inventory contents alphabetically; render locale-sensitive strings outside the cache key |
| Spring AI `VectorStore` | Reuse for task-scoped attachments | Task attachments NEVER touch `VectorStore`; pass as `Media` to `ChatClient` only |
| Vaadin Flow `Dialogs` | Co-mount floating launcher with custom CSS z-index | Use Vaadin overlay primitive so launcher participates in stacking; hide on dialog open |
| Jmix `ViewNavigators` | Inject into a class with `@Tool` methods | `ViewNavigators` is controller-layer only; never reachable from LLM tool callbacks |
| Spring AI `AudioTranscriptionModel` (STT) | Audit only LLM tokens, ignore STT cost/latency | Add `STT_TRANSCRIPTION` audit event under the user message |
| Jmix attribute policies | Apply at entity-form binding only | Apply at every code path that constructs entities for save (mutation tools, intent extraction prefill) |
| Jmix `@Comment` annotation | Echo verbatim into `describe_entity` LLM result | Allowlist DTO fields; `@Comment` text never enters LLM context |

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Entity inventory in baseline context grows linearly with host schema | Token-budget exceptions late in tool loop; cache hit rate drops | Token-budgeted inventory rendering; truncate to names + count past threshold | Hosts with >100 entities or >30 attrs/entity |
| Per-request fetch plan resolution without cache | `getReadableSchema` called multiple times per tool call | Cache the resolved plan with user-policy-signature key | High-frequency tool loops (>10 calls/turn) |
| Mutation idempotency table grows unbounded | DB size grows; idempotency lookups slow | TTL-based purge job; index on (idempotencyKey, createdAt) | After ~30 days of production use |
| STT round-trip blocks the chat thread | Chat input feels frozen for seconds | Asynchronous STT with progress streaming; LLM call only after transcription done | Audio >10s or slow STT provider |
| Three chat surfaces each polling for streaming events | DB hit rate spikes; push channel saturated | Single `RunContext` shared across surfaces; surface bind is read-only | When >2 surfaces active simultaneously per user |
| Floating launcher re-renders on every navigation | Visible flicker; lost input state | Persist launcher state across navigation in session | Once SPA navigation is heavy |
| Exposure rule lookup on every `getReadableSchema` call | `O(rules × entities)` per call | Index rules by `entityName`; cache resolved rule-set per user | When admin adds many rules (>50) |

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Mutation tool reachable without `accessManager.isPermitted` pre-flight | LLM writes attributes the user couldn't write through UI | Mandatory `EntityAttributeContext` check per attribute |
| `UnconstrainedDataManager` used in mutation tool path | Total Jmix security bypass for AI writes | Architecture rule + ASM test; review-gate on any `UnconstrainedDataManager` import |
| Exposure rule with `ALLOW` semantics | LLM sees data the user is denied through Jmix | Only `EXCLUDE` rule type exists; AND-only combination logic |
| `describe_entity` echoes `@Comment` text | Internal-only PII annotations leak to LLM/user | Allowlist DTO fields; never iterate annotations |
| Permission-inventory tool names denied entities | Information disclosure of what exists vs. what's allowed | Output contains only entities the user can READ |
| Audit captures full STT transcripts by default | Durable PII storage, GDPR exposure | Default off; opt-in property; redactor SPI |
| Task-scoped file ingested into `VectorStore` | Cross-user document leakage via RAG retrieval | Task files use `Media` injection path only; ASM/runtime test asserts no `VectorStore.add` from task path |
| Intent-extraction tool calls `ViewNavigators` directly | Prompt injection navigates to admin views | LLM returns intent DTO only; controller asks user for confirmation; `accessManager.isPermitted(ViewContext)` before navigate |
| Prefill via Vaadin component `setValue` | Bypasses Jmix entity validators and attribute policies | Prefill via `DataContext` on entity instance; check `MODIFY` per attribute before setting |
| Mutation error message includes raw input values | PII echo via tool result string | Translator from JPA exceptions → safe `ToolErrorDto` codes; never include user values |
| Two-layer enforcement returns different error codes | Side-channel about why an entity is hidden | Single opaque `unknown_entity` reason in LLM-facing response; full reason in audit only |
| Cached fetch plan keyed by entity name only | Cross-user data leak | Cache key includes user-policy signature |
| Idempotency replay returns silent success | Loss of LLM signal that retry happened | Replay is audited and returned as `IDEMPOTENT_REPLAY` outcome distinct from fresh success |

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| Floating launcher steals focus on page load | Screen reader users start mid-chat; tab order broken | Launcher does not auto-focus; explicit shortcut + click to focus |
| Conversation memory split across surfaces | "I told you about the order!" — model has no record | Shared active-conversation tracker per user across all surfaces |
| Voice input button always visible regardless of mic permission | User clicks, gets confusing browser permission dialog mid-conversation | Detect mic capability on chat open; show button only when available; clear permission-prompt UX |
| Intent extraction auto-navigates without confirmation | User suddenly on an admin form with prefilled data they didn't ask for | Always confirm "Open form X with these values?" before navigating |
| Prefilled fields not visually marked as AI-suggested | User saves bad LLM data thinking they typed it | Distinct visual style ("AI-suggested" badge or blue border) on prefilled fields until edited |
| `unknown_entity` response leaks no help | User asks again with same wording; chat feels broken | Response includes "I can see these entities: …" referencing the inventory the LLM has |
| Admin disabling a chat surface doesn't update open browser tabs | Users continue clicking a surface that's "off" | Push-broadcast settings change to active sessions; surfaces re-render hidden |
| Exposure rule change doesn't notify affected open chats | User confused why model "forgot" a field mid-conversation | Soft notification: "Schema visibility was updated; some fields may no longer be available" |
| STT shows transcription only after entire audio captured | User feels frozen for seconds | Streaming partial transcripts during capture |
| Task file attached but not visually persisted in chat history | User doubts whether the file was used | Render attached file as a chat-message-adjacent chip with a "used in turn N" indicator |

## "Looks Done But Isn't" Checklist

- [ ] **Mutation tool implementation:** Often missing `idempotencyKey` parameter — verify `@Tool` schema includes it and integration test calls the tool twice with the same key.
- [ ] **Mutation tool implementation:** Often missing per-attribute `EntityAttributeContext` check — verify a "user has READ but not MODIFY on field X" test fails the write closed.
- [ ] **Mutation auto-config:** Often missing `@ConditionalOnProperty` — verify boot test asserts mutations are absent from default-property `ChatClient` tools list.
- [ ] **Exposure policy:** Often missing the `ALLOW`-rules-don't-exist invariant — verify entity has only `EXCLUDE` semantics and tests assert the layer cannot widen.
- [ ] **Exposure-rule cache:** Often missing event-driven invalidation — verify `LlmExposureChangedEvent` is fired on rule save and listened to by `CurrentUserSchemaAccess` cache.
- [ ] **`describe_entity` v1.1:** Often leaks `@Comment` or store-name strings — verify a fixture entity with `@Comment("INTERNAL")` and assert the string never appears in tool output.
- [ ] **Permission inventory tool:** Often lists denied entities — verify `OwnershipOpacityTest` analog asserts denied entity name absent from result text.
- [ ] **Baseline inventory rendering:** Often non-deterministic — verify byte-equal across two JVM cold-starts and across two locales (cache-key portion).
- [ ] **Token-budgeted inventory:** Often missing fallback — verify a 200-entity fixture renders within the configured budget and `describe_entity` still works for omitted entities.
- [ ] **STT integration:** Often missing audit event — verify `STT_TRANSCRIPTION` event written; verify default-off transcript storage.
- [ ] **Task-scoped file path:** Often shares ingestion code with KB — verify post-test `VectorStore` row count is unchanged.
- [ ] **Intent extraction:** Often has navigate-as-`@Tool` — grep `ai-agent` for `ViewNavigators` injected in any class with `@Tool` methods; must be zero.
- [ ] **Prefill:** Often uses `setValue` on UI components — verify prefill goes through `DataContext` and that "user lacks `MODIFY` on field X" leaves the field blank.
- [ ] **Three chat surfaces:** Often each instantiate their own `ChatService` — verify exactly one `ChatService` bean and surfaces share `conversationId` via session tracker.
- [ ] **Floating launcher:** Often uses raw CSS — verify Vaadin overlay primitive is used and accessibility test passes (keyboard reachable, no focus steal).
- [ ] **Admin runtime toggle:** Often property-only — verify changing `AiAgentSettings` row hides surface without restart.
- [ ] **Mutation transaction:** Often "tool returns success" before flush — verify a flush-failure integration test marks audit `outcome != SUCCESS`.
- [ ] **`@Composition` mutation:** Often passes children list as tool param — verify mutation tools targeting `@Composition` parents do not accept the children collection.
- [ ] **Fetch-plan cache:** Often keyed by entity name only — verify cross-user test gets correct projections with no cross-contamination.
- [ ] **Two-layer enforcement:** Often returns specific `access_denied` from exposure layer — verify LLM-facing response is uniformly `unknown_entity`; audit row carries the real reason.
- [ ] **Mutation error:** Often echoes JPA exception text — verify a unique-constraint violation with PII in the input does not leak the PII into the tool result string.
- [ ] **Draft state:** Often stored in long-lived `Map` — verify TTL/eviction and cross-user isolation test.

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Mutation tool default-on shipped | HIGH (security regression in released version) | Patch release: flip default to off; CHANGELOG security advisory; notify hosts; audit any host that upgraded between bad release and patch |
| Exposure rule has `ALLOW` semantics shipped | HIGH (data exposure baseline is wrong) | Patch release: deprecate `ALLOW` type; force-migrate existing rules to `EXCLUDE`-only; document migration; audit all existing rules for accidental widening |
| `describe_entity` leaks `@Comment` | MEDIUM (info disclosure) | Patch release adds allowlist; audit-grep production for `@Comment` text echo; notify hosts |
| Mutation idempotency missing | MEDIUM (duplicate rows) | Patch adds idempotency-key requirement; production cleanup script identifies same-conversation duplicate writes for review |
| Task files in pgvector | MEDIUM (cross-user retrieval) | Identify task-uploaded chunks by metadata flag; bulk-delete from vector store; document for hosts; patch path forward |
| Conversation split across surfaces | LOW (UX, not security) | Patch shares `conversationId` via session tracker; users notice quickly, support can guide manual conversation continuation |
| Floating launcher z-index conflict | LOW (UX) | Patch switches to Vaadin overlay primitive; hosts deploy patch |
| Baseline inventory non-deterministic | LOW (cost, not correctness) | Patch adds sort; observe cache-hit-rate recovery; no data correction needed |
| Audit captures STT PII | HIGH (compliance) | Operator opt-out; bulk-purge of past STT audit rows that were stored verbatim; retroactive disclosure may be required |
| LLM gets `ViewNavigators` | CRITICAL (arbitrary navigation, likely arbitrary writes via prefilled forms) | Hot patch: remove tool; force re-deploy; audit for any view opens during the regression window |
| Cross-user fetch-plan cache leak | HIGH (data exposure) | Hot patch: per-user cache or no cache; audit logs; rotate if other identifiers leaked |

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| 1 — Mutation bypasses `AccessManager` | Mutation Tools | "READ-but-not-MODIFY user blocked" integration test against `BuiltInDataMutations` |
| 2 — Mutation default-on | Mutation Tools (foundation plan) | Boot test: default config produces zero mutation tool callbacks |
| 3 — Idempotency duplicates | Mutation Tools | "Same idempotencyKey twice → one row" integration test |
| 4 — Audit/transaction divergence | Mutation Tools | "Constraint-violation tool call → audit `outcome != SUCCESS`" test |
| 5 — `@Composition` cascade | Mutation Tools | Tool schema review + test: order with three lines, "add one" path preserves prior |
| 6 — Exposure widens | AI Exposure Policy | Layer is `EXCLUDE`-only; user-visibility AND-only; integration test asserts no widening possible |
| 7 — Permission inventory leaks names | Tool-layer refinements | `OwnershipOpacityTest` analog for new inventory tool |
| 8 — Baseline non-determinism | Prompt-contract hardening | Byte-equality test across JVM restarts and locales |
| 9 — Token-budget blowup | Prompt-contract hardening | 200-entity synthetic fixture stays within budget |
| 10 — `describe_entity` leaks framework noise | Tool-layer refinements | Fixture entity with `@Comment("INTERNAL")` — string absent from tool output |
| 11 — Fetch-plan widens projection | Tool-layer refinements | Host SPI requests denied attribute; result excludes it |
| 12 — Fetch-plan cache cross-user leak | Tool-layer refinements | Two-user concurrent test asserts correct per-user projections |
| 13 — Two-layer error contradiction | AI Exposure Policy | LLM-facing response uniformly `unknown_entity`; audit carries true reason |
| 14 — Stale schema after exposure rule change | AI Exposure Policy | Admin saves rule mid-session; next user message uses new policy |
| 15 — STT PII in audit | Speech-to-Text + File Task Input | Default-off transcript storage; opt-in property; redactor SPI in place |
| 16 — Task file in pgvector | Speech-to-Text + File Task Input | Post-test `VectorStore` row count unchanged after task upload |
| 17 — LLM owns navigation | Intent-driven Extraction | No `@Tool` method has `viewId` parameter; `ViewNavigators` not in any tool class |
| 18 — Prefill bypasses validators | Intent-driven Extraction | "User lacks `MODIFY` on field X" test asserts blank field; `dataContext.validate()` called pre-Save |
| 19 — Surfaces split conversation | Configurable Chat Surfaces | Cross-surface continuation test: floating → full view shows shared history |
| 20 — Launcher z-index conflict | Configurable Chat Surfaces | Manual UAT + automated dialog-open-while-launcher-visible click test |
| 21 — Compile-time-only surface toggle | Configurable Chat Surfaces | Admin toggles surface; UI reflects change without restart |
| 22 — Mutation error message PII | Mutation Tools | Constraint-violation with PII input → tool result string excludes PII |
| 23 — STT cost untracked | Speech-to-Text + File Task Input | Audit row of type `STT_TRANSCRIPTION` exists for STT calls; latency captured |
| 24 — STT language mismatch | Speech-to-Text + File Task Input | UI language selector + explicit server-side language pass-through; integration test with two languages |
| 25 — Draft state leaks | Intent-driven Extraction | Cross-user isolation test; TTL purge test |

## Sources

- v1.0 shipped code (highest authority — these are the invariants v1.1 must preserve):
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` — read-only contract documented in Javadoc lines 32–40
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java` — deterministic prompt-text contract, lines 31–35
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java` — REQUIRES_NEW audit durability, lines 18–34
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java` — surface-reuse primitive (Plan 07.1)
  - `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/OwnershipOpacityTest.java` — opacity contract for denied entities
- `.planning/PROJECT.md` (this milestone scope, key decisions, deferred D-10/D-01 history)
- `.planning/STATE.md` (pending todos and seeds activated for v1.1)
- `CLAUDE.md` (project conventions: `DataManager`-only, no Lombok on entities, instantiate via `Metadata.create()`/`DataManager.create()`, no business logic in views)
- `MEMORY.md` rules (highest authority for project conventions):
  - "AI is just another Jmix client" — no AI-specific exposure layer beyond Jmix `AccessManager`
  - "UnconstrainedDataManager for system-internal writes under jmix-security-data" — explicit boundary for *audit/seed/ingestion*; mutation tools are NOT in that set
  - "Reuse Jmix built-ins over parallel layers" — exposure policy must extend Jmix patterns, not replace them
  - "Jmix-first UI over raw Vaadin" — floating launcher must use Jmix/Vaadin overlay primitives, not raw CSS
  - Project commitment that "mutations require explicit host opt-in" (PROJECT.md Constraints / Safety)
- Spring AI 1.1.4 docs (via Context7 `/spring-projects/spring-ai`):
  - Tool calling loop semantics — `ToolCallingManager.executeToolCalls` retries via the framework; tool calls are not idempotent by default
  - `ToolCallAdvisor` advisor-controlled tool execution — relevant for ordering audit/idempotency advisors around the new mutation path
- Jmix 2.8 docs (Context7 `jmix-framework/jmix-context7`):
  - `AccessManager` `EntityAttributeContext` and `CrudEntityContext` — the contexts mutation tools must check
  - `DataContext` — only safe path for prefill; bypassing leaves validators unfired
  - `ViewNavigators` — controller-layer only; not appropriate inside tool callbacks
- General LLM-tooling community wisdom (verified by alignment with above sources):
  - Idempotency keys for write-tool calls (Anthropic / OpenAI tool-use guides)
  - Two-layer authorization (app + AI exposure) — opacity contract pattern (informally documented across LangChain/LlamaIndex security threads)

---
*Pitfalls research for: Adding v1.1 features (mutation tools, AI exposure policy, prompt hardening, STT/file input, intent extraction, configurable chat surfaces) to v1.0.0 Jmix AI Copilot*
*Researched: 2026-04-26*
