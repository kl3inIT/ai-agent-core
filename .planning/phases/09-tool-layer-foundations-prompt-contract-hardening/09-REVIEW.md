---
phase: 09-tool-layer-foundations-prompt-contract-hardening
reviewed: 2026-04-27T00:00:00Z
depth: standard
files_reviewed: 20
files_reviewed_list:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditFieldHasher.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AiAgentAuditProperties.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolFetchPlanCustomizer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/FetchPlanContext.java
  - ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiAgentPromptProperties.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanResolver.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultPayloads.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AiAgentGuardProperties.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/HostPrefixPatternProvider.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/ToolNamePatternProvider.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties
findings:
  blocker: 2
  warning: 7
  info: 4
  total: 13
status: issues_found
---

# Phase 9: Code Review Report

**Reviewed:** 2026-04-27
**Depth:** standard
**Files Reviewed:** 20
**Status:** issues_found

## Summary

Phase 9 hardens the LLM tool/prompt boundary with new baseline context (`agent.entities`,
`agent.permissions`), richer `describe_entity`, fetch-plan permission intersection, and two new
output-scanner pattern packs (host-prefix leak, tool-name leak). The implementation is broadly
faithful to the spec — the projection-not-security comment is preserved, the `unknown_entity`
hint strings are reconciled byte-for-byte between `BuiltInDataTools.UNKNOWN_ENTITY_HINTS` and
`AgentSystemPromptRules.PROMPT_RULES`, the dynamic patterns use `Pattern.quote` for ReDoS
safety, and the streaming path of `OutputScannerAdvisor` is wired in addition to blocking
calls.

Adversarial review surfaced two blockers and several warnings:

- **BLOCKER-01:** `agent.roles` ordering is non-deterministic — `LinkedHashSet` over
  `getAuthorities()` does not guarantee a stable iteration order across Spring Security
  implementations, breaking the "byte-stable baseline" contract (E-01) the rest of the phase
  rests on.
- **BLOCKER-02:** `BuiltInDataTools.getRelatedRecords` builds a fetch plan that adds
  `FetchPlan.INSTANCE_NAME` on the relationship attribute and bypasses
  `FetchPlanIntersector`. The Javadoc claims this is safe because instance-name attributes are
  "by definition readable to anyone who can read the entity," but Jmix attribute permissions
  are independent of `@InstanceName` declaration, so a host that denies an `@InstanceName`-
  contributing attribute will see it loaded into `_instance_name` projections via this path.
  This is the exact PROMPT-04/TOOL-11 invariant the phase set out to enforce.

Warnings cluster around the `OutputScannerAdvisor` `@NonNull` contract violation, the `Boolean`
double-negation in `AiAgentAuditProperties`, dropped-attribute audit content sourced from
host customizers, missing `canReadEntity` check on nested fetch-plan walk, the
`extractUserKey` reflection silently catching `Throwable`-adjacent exceptions, and the
hint-string duplication between `BuiltInDataTools` and `AgentSystemPromptRules`.

---

## Blockers

### BL-01: `agent.roles` rendering order is not deterministic — breaks E-01 baseline byte-stability

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java:149-153`

**Issue:**
`rolesOf(user)` collects authorities into a `LinkedHashSet` whose iteration order matches the
order returned by `UserDetails.getAuthorities()`. Spring Security does NOT contract a stable
iteration order for `getAuthorities()` — `User` (in-memory) returns a `TreeSet` (sorted), but
Jmix's user implementation, JWT-derived users, and host-custom `UserDetails` vary. Two
equivalent users with the same role set can produce different `agent.roles=[...]` lines under
`renderAsText(...)`, which feeds directly into the per-request system prompt.

The Phase 9 spec is explicit that determinism is a CRITICAL eval dimension (E-01,
"byte-stable `agent.entities`, `agent.permissions`, truncation hint, and prompt rule
ordering"). `agent.roles` is rendered into the same baseline text block and the same
deterministic-prompt assertion fixtures.

When two equivalent runs produce different baseline text, prompt-hash stability fails, audit
replay diverges, and any future cache that keys off `(userId, roleSet, ...)` gets the wrong
hit/miss behavior. The provider's own Javadoc (line 47) says "identical requests produce
byte-identical baseline blocks (cache & audit prompt-hash stability)" — the current
`LinkedHashSet` strategy does not deliver that.

**Fix:**
Collect into a `TreeSet` (or sort the resulting `LinkedHashSet` before returning) so role
order is alphabetical regardless of `UserDetails` source:

```java
private static Set<String> rolesOf(UserDetails user) {
    return user.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toCollection(TreeSet::new));
}
```

Add a regression test that injects two `UserDetails` mocks returning the same authorities in
opposite orders and asserts identical `renderAsText(convId)` output.

---

### BL-02: `getRelatedRecords` bypasses `FetchPlanIntersector` for `INSTANCE_NAME` projection — TOOL-11 violation

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java:286-295`

**Issue:**
Line 291-295 builds the per-call fetch plan as:

```java
FetchPlan dataPlan = fetchPlanResolver.resolve("get_related_records", rootMetaClass);
FetchPlan fetchPlan = fetchPlans.builder(rootMetaClass.getJavaClass())
        .addFetchPlan(dataPlan)
        .add(relationship, fetchPlanBuilder -> fetchPlanBuilder.addFetchPlan(FetchPlan.INSTANCE_NAME))
        .build();
```

`dataPlan` was correctly intersected by `FetchPlanIntersector`. The `.add(relationship, ...
INSTANCE_NAME)` then re-attaches a NESTED plan on the relationship that loads whatever
attributes the target entity's `@InstanceName` declares — and that nested plan is never
intersected.

The inline comment (lines 286-290) asserts: "INSTANCE_NAME below bypasses intersection
because it only fetches what @InstanceName declared, which is by definition readable to anyone
who can read the entity." **This claim is false.** Jmix attribute-level permissions are
independent of `@InstanceName` declaration:

- A `Customer` entity with `@InstanceName("#getName")` may have an `EntityAttributePolicy`
  that DENIES `name` for some role.
- Jmix's `AccessManager` will gate the underlying `DataManager.load(...)` call but the
  fetch plan still requests the denied property — at minimum producing audit/log noise, and
  for partial-load scenarios potentially causing the value to materialise into the loaded
  entity graph for `MetadataTools.getInstanceName` to read.

The TOOL-11 invariant is explicit: "every host `ToolFetchPlanCustomizer` result is
intersected with current readable attributes BEFORE `DataManager.load(...)` ... denied
properties and nested denied properties are dropped, audited as `PLAN_NARROWED`, and never
enter the fetch plan passed to `DataManager`." The current code violates this for the
relationship's instance-name projection.

This is also a behavioural regression vector for Phase 11+ when mutation tools begin reading
relationship instance names through the same path.

**Fix:**
Either (a) intersect the composed plan before handing it to `DataManager.load(...)`:

```java
FetchPlan composed = fetchPlans.builder(rootMetaClass.getJavaClass())
        .addFetchPlan(dataPlan)
        .add(relationship, fpb -> fpb.addFetchPlan(FetchPlan.INSTANCE_NAME))
        .build();
FetchPlan narrowed = fetchPlanIntersector.intersectWithAcl(
        composed, rootMetaClass, "get_related_records");
Object rootEntity = dataManager.load(rootMetaClass.getJavaClass())
        .id(parseEntityId(id, rootMetaClass))
        .fetchPlan(narrowed)
        .optional()
        .orElse(null);
```

…or (b) resolve the nested plan separately and intersect against the target metaclass:

```java
FetchPlan instanceNameOnTarget = fetchPlans.builder(targetMetaClass.getJavaClass())
        .addFetchPlan(FetchPlan.INSTANCE_NAME)
        .build();
FetchPlan narrowedInstanceName = fetchPlanIntersector.intersectWithAcl(
        instanceNameOnTarget, targetMetaClass, "get_related_records");
// then merge narrowedInstanceName into the relationship sub-plan
```

Add a `FetchPlanIntersectorTest` case where the target entity's `@InstanceName` includes a
denied attribute and assert it does not appear in the loaded plan.

---

## Warnings

### WR-01: `OutputScannerAdvisor.adviseCall` returns null while declaring `@NonNull` return

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/OutputScannerAdvisor.java:157-165`

**Issue:**
The method signature is `@NonNull ChatClientResponse adviseCall(...)`, but when
`chain.nextCall(request)` returns null the early return propagates that null:

```java
ChatClientResponse response = chain.nextCall(request);
if (!props.outputScannerEnabled() || response == null) {
    return response; // null leaks past @NonNull
}
```

Spring's `@NonNull` is contractual; downstream advisors expect non-null. While the canonical
`CallAdvisorChain` rarely returns null, the contract violation hides a defect class behind a
silent failure mode. (`adviseStream` in line 168 takes the safer route by returning the
upstream `Flux` directly.)

**Fix:**
Either drop the `@NonNull` annotation on the return type if Spring AI's `CallAdvisor`
contract permits null, or throw an `IllegalStateException` when the chain returns null.
Recommended:

```java
ChatClientResponse response = chain.nextCall(request);
if (response == null) {
    return response; // upstream contract violation; let Spring AI surface it downstream
}
if (!props.outputScannerEnabled()) {
    return response;
}
scanAndFlag(response);
return response;
```

…and remove the `@NonNull` from the return type if necessary, or alternatively throw on null.

---

### WR-02: `FetchPlanIntersector.walk` does not check `canReadEntity` on nested target metaclass

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java:107-111`

**Issue:**
When recursing into a nested plan for a class-typed property, `walk` checks
`schemaAccess.canReadAttribute(metaClass, propertyName)` on the OWNING side but never calls
`schemaAccess.canReadEntity(nestedMetaClass)` on the target. If a host customizer attaches a
sub-plan referencing an entity the user is denied at the entity level (but where the owning
attribute happens to be readable), the recursion proceeds and walks every property of the
denied target metaclass.

This works in practice when both checks return the same answer for the same role set, but
the spec lists `canReadEntity` and `canReadAttribute` as the two authoritative readers
(CONTEXT §"Reusable Assets"). Skipping `canReadEntity` on the nested boundary is an implicit
trust on the attribute check, fragile if Phase 10's `LlmExposurePolicy` decouples the two
permissions.

**Fix:**
Add the entity-level check before recursing:

```java
if (metaProperty != null && metaProperty.getRange().isClass()) {
    MetaClass nestedMetaClass = metaProperty.getRange().asClass();
    if (!schemaAccess.canReadEntity(nestedMetaClass)) {
        droppedAttributePaths.add(metaClass.getName() + "." + propertyName + " (target denied)");
        continue;
    }
    FetchPlan narrowedNestedPlan = walk(nestedPlan, nestedMetaClass, droppedAttributePaths);
    builder.mergeProperty(propertyName, narrowedNestedPlan, fetchMode);
}
```

---

### WR-03: PLAN_NARROWED audit row embeds host-supplied attribute names without sanitization

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java:123-139`

**Issue:**
`emitNarrowingAudit` constructs `denialReason` as
`"PLAN_NARROWED: entity=<name> dropped=<List.toString()>"`. The dropped attribute names come
from the `FetchPlanProperty.getName()` of a host-supplied `ToolFetchPlanCustomizer`. While
attribute names are normally Java identifiers, a buggy or malicious host customizer can
construct a `FetchPlan` with arbitrary property-name strings (Jmix's `FetchPlanBuilder` does
not validate names against the metamodel until `build()`). The resulting reason string flows
into the `denialReason` audit column.

This is not a critical vulnerability — the audit is internal and reviewer-only — but it can
produce huge or malformed `denialReason` values that break downstream audit UI parsing or
exceed column size in `AiToolCallAudit`.

**Fix:**
Cap the dropped list size and trim each attribute name to a sane length before formatting:

```java
private static final int MAX_DROPPED_ATTRS_IN_AUDIT = 20;
private static final int MAX_ATTR_NAME_LENGTH = 64;

String dropped = droppedAttributePaths.stream()
        .limit(MAX_DROPPED_ATTRS_IN_AUDIT)
        .map(s -> s.length() > MAX_ATTR_NAME_LENGTH ? s.substring(0, MAX_ATTR_NAME_LENGTH) + "..." : s)
        .toList()
        .toString();
String denialReason = PLAN_NARROWED_PREFIX + " entity=" + rootMetaClass.getName()
        + " dropped=" + dropped
        + (droppedAttributePaths.size() > MAX_DROPPED_ATTRS_IN_AUDIT
                ? " (+" + (droppedAttributePaths.size() - MAX_DROPPED_ATTRS_IN_AUDIT) + " more)"
                : "");
```

---

### WR-04: `extractUserKey` swallows broad `Exception` from reflective invocation

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java:165-176`

**Issue:**
`extractUserKey` catches `Exception`, which masks `IllegalAccessException`,
`InvocationTargetException`, `SecurityException`, `NoSuchMethodException`, plus any
`RuntimeException` thrown inside the host's `getKey()` implementation. The caught exception
is silently ignored; the fallback returns `user.getUsername()`. This obscures broken host
integrations and means that an exception inside `getKey()` is indistinguishable from "no
`getKey()` method at all" — both fall through to the username.

**Fix:**
Narrow to `ReflectiveOperationException | RuntimeException` and log the swallowed exception
at debug:

```java
private static final Logger log = LoggerFactory.getLogger(BaselineContextProvider.class);

private static Object extractUserKey(UserDetails user) {
    try {
        var method = user.getClass().getMethod("getKey");
        Object result = method.invoke(user);
        if (result instanceof UUID || result instanceof String) {
            return result;
        }
        log.debug("getKey() returned non-UUID/non-String type {} on user class {} — falling back to username",
                result == null ? "null" : result.getClass().getName(), user.getClass().getName());
    } catch (NoSuchMethodException noKeyMethod) {
        // Expected for non-Jmix UserDetails — silent fallback.
    } catch (ReflectiveOperationException | RuntimeException reflectFailure) {
        log.debug("getKey() invocation failed on {} — falling back to username",
                user.getClass().getName(), reflectFailure);
    }
    return user.getUsername();
}
```

---

### WR-05: `AiAgentAuditProperties.resolvedHashSensitiveFields` uses convoluted Boolean logic

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AiAgentAuditProperties.java:34-36`

**Issue:**
```java
public boolean resolvedHashSensitiveFields() {
    return hashSensitiveFields == null || !Boolean.FALSE.equals(hashSensitiveFields);
}
```

The `||` short-circuit on null returns `true` (default-on), and the
`!Boolean.FALSE.equals(value)` is logically equivalent to `Boolean.TRUE.equals(value)` ONLY
when value is non-null Boolean — but here it returns `true` for `Boolean.TRUE` and `false`
for `Boolean.FALSE`. The double-negation hides intent and is harder to reason about than
the canonical form. It also opens the door to accidental drift when copy-pasted to other
properties. The same anti-pattern appears in `AiAgentGuardProperties.outputScannerEnabled()`,
`hostPrefixLeakEnabled()`, and `toolNameLeakEnabled()`.

**Fix:**
Use the standard "default-on Boolean" form:

```java
public boolean resolvedHashSensitiveFields() {
    return hashSensitiveFields == null ? true : hashSensitiveFields;
}
```

…or even simpler with the intent documented:

```java
public boolean resolvedHashSensitiveFields() {
    return !Boolean.FALSE.equals(hashSensitiveFields);
}
```

(`!Boolean.FALSE.equals(null)` is `true`; `!Boolean.FALSE.equals(Boolean.TRUE)` is `true`;
`!Boolean.FALSE.equals(Boolean.FALSE)` is `false` — the null check is redundant.)

---

### WR-06: `BuiltInDataTools.UNKNOWN_ENTITY_HINTS` and `AgentSystemPromptRules.PROMPT_RULES` duplicate the hint strings without a single source of truth

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java:58-62` and `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java:42-61`

**Issue:**
The three `unknown_entity` procedural hints are duplicated as separate string literals in
two files. The `AgentSystemPromptRules` Javadoc admits this and pins the contract to TEST-08
cross-assertion. This works until someone edits one constant and not the other — the test
assertion will fail, but the only place that records WHICH copy is "right" is the test
expectation, which itself needs to be edited in lockstep.

The `// MUST start with lowercase 'if' so they match` comment in `AgentSystemPromptRules`
is a maintenance smell.

**Fix:**
Lift the three hint strings to a shared constant in a neutral location (e.g.
`com.vn.agent.tools.UnknownEntityHints` or directly in `BuiltInDataTools` exposed as
`public static final List<String>`), and have `AgentSystemPromptRules` build its prompt rule
by interpolating those constants:

```java
public final class UnknownEntityHints {
    public static final String CALL_ONCE =
            "call list_entities exactly once";
    public static final String RETRY_ON_MATCH =
            "if a name in list_entities matches your intent, retry the original tool with that exact name";
    public static final String GIVE_UP_ON_NO_MATCH =
            "if no entity in list_entities matches, tell the user no such entity exists — do not guess";

    public static final List<String> AS_LIST = List.of(CALL_ONCE, RETRY_ON_MATCH, GIVE_UP_ON_NO_MATCH);

    private UnknownEntityHints() {}
}
```

Then `AgentSystemPromptRules.PROMPT_RULES` builds its bullet lines via
`"- " + UnknownEntityHints.CALL_ONCE + "."` etc., and `BuiltInDataTools.UNKNOWN_ENTITY_HINTS`
is just `UnknownEntityHints.AS_LIST`. The byte-for-byte match becomes a compile-time
property, not a test assertion.

---

### WR-07: `FetchPlanContext` exposes raw `UserDetails` to host SPI, leaking authentication internals

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/FetchPlanContext.java:30-37`

**Issue:**
`FetchPlanContext` carries `UserDetails user` as a record component. Host customizer code can
invoke `user.getPassword()` (which Spring Security may return as the BCrypt hash for some
implementations until cleared) or other lifecycle methods. This is a wide SPI surface for the
documented use case ("host can vary fetch plans by ... role"), where only the role set and
username are actually needed.

This also makes `FetchPlanContext` non-equals-friendly (`UserDetails` rarely implements
`equals`), so the record's auto-generated `equals/hashCode` will be identity-based for the
user component — surprising for a `record` type that callers may otherwise treat as a value.

**Fix:**
Replace `UserDetails user` with the minimum projection the SPI needs — e.g. a separate
`record UserSnapshot(String username, Set<String> roles)`:

```java
public record FetchPlanContext(UUID runId,
                               UUID conversationId,
                               Integer retrievalTopK,
                               Double retrievalSimilarityThreshold,
                               String retrievalFiltersJson,
                               Locale locale,
                               UserSnapshot user) {

    public record UserSnapshot(String username, Set<String> roles) {}
}
```

`FetchPlanResolver.resolve` constructs the snapshot at the tool boundary by reading
`currentAuthentication.getUser()` and projecting authorities into a sorted role set
(addressing the same determinism concern from BL-01).

---

## Info

### IN-01: `RecordsPayload.hint` is a hardcoded English string — should reuse a constant or i18n key

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java:140-142`

**Issue:**
The truncation hint string `"result was truncated to the limit; call count_records for the
exact total or narrow the filter"` is inline. It mentions the tool name `count_records`,
which `ToolNamePatternProvider` will subsequently flag if the LLM echoes it into a reply.
The hint is intentionally English (LLM-protocol per RESEARCH Pitfall 7), but the literal
deserves a named constant alongside `BuiltInDataTools.UNKNOWN_ENTITY_HINTS` so the contract
location is consistent.

**Fix:**
Lift to `BuiltInDataTools` (or a new `ToolHints` holder) as
`public static final String FIND_RECORDS_TRUNCATED_HINT = "..."` and reference from
`ToolResultFormatter.records`.

---

### IN-02: Stale Javadoc reference in `AiAgentGuardProperties.resolvedPatterns()` lists 3 defaults but description mentions "three bundled regex defaults"

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AiAgentGuardProperties.java:18-21,105-114`

**Issue:**
The class-level Javadoc says "D-18: `resolvedPatterns()` — three bundled regex defaults
covering prompt-injection canaries, system-tag leakage, and role-break attempts." Phase 9
adds the host-prefix-leak and tool-name-leak packs as DYNAMIC patterns (compiled in
`OutputScannerAdvisor.ensureDynamicCompiled`), so the count is still three for static
defaults — the doc is correct. However, a reader scanning the file may miss that two more
packs are wired separately and assume the scanner only ships three patterns total. A
forward-reference helps.

**Fix:**
Append to the Javadoc bullet:

```
 * Phase 9 adds two DYNAMIC pattern packs (host-prefix-leak, tool-name-leak) compiled
 * lazily by OutputScannerAdvisor on first scan; see resolvedPatterns() Javadoc.
```

---

### IN-03: `AiAgentPromptProperties.resolvedEntityInventoryLimit()` does not validate non-positive limits

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiAgentPromptProperties.java:30-32`

**Issue:**
If a host sets `jmix.ai-agent.prompt.entity-inventory.limit=0` or a negative number, the
limit silently becomes that value. `BaselineContextProvider.visibleEntities` then calls
`sorted.subList(0, limit)` which throws `IllegalArgumentException` on negative limit and
returns an empty list on `0`, which would suppress `agent.entities` and `agent.permissions`
entirely. The truncation-hint code path also uses the same value.

The phase spec defaults this to 100 and aligns with `find_records max=100`. Operator misuse
should fail fast with a meaningful error, not silently disable the inventory or throw
from an unrelated call site.

**Fix:**
Clamp or validate:

```java
public int resolvedEntityInventoryLimit() {
    int v = entityInventory == null || entityInventory.limit() == null
            ? 100 : entityInventory.limit();
    if (v <= 0) {
        throw new IllegalStateException(
                "jmix.ai-agent.prompt.entity-inventory.limit must be > 0, got " + v);
    }
    return v;
}
```

---

### IN-04: `module.properties` declares the audit hashing keys but the code reads from `jmix.ai-agent.audit.*` — verify the prefix matches

**File:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties:42-43` and `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AiAgentAuditProperties.java:28`

**Issue:**
`module.properties` ships `jmix.ai-agent.audit.hash-sensitive-fields=true` and
`jmix.ai-agent.audit.sensitive-fields=` (empty). `AiAgentAuditProperties` is annotated
`@ConfigurationProperties("jmix.ai-agent.audit")`, so the prefix matches and the keys bind
correctly. Verified during review — no defect, but the empty `sensitive-fields=` value
binds to an empty Set rather than null, which means `resolvedSensitiveFields()` returns the
explicit empty set even when the operator did not configure anything. This is the desired
default per D-18 ("empty default") so the implementation is correct, but reviewers tracing
the path should know the empty key is significant rather than a typo.

**Fix:**
No code change needed. Add a comment to `module.properties`:

```properties
# Empty value is intentional — D-18 ships zero default sensitive fields; hosts add
# attribute names per deployment after Phase 11 wiring lands.
jmix.ai-agent.audit.sensitive-fields=
```

---

_Reviewed: 2026-04-27_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
