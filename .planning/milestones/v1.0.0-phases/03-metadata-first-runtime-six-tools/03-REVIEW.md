---
phase: 03-metadata-first-runtime-six-tools
reviewed: 2026-04-19T00:00:00Z
depth: standard
files_reviewed: 29
files_reviewed_list:
  - ai-agent/ai-agent/ai-agent.gradle
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/AndNode.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/FilterDslMapper.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/FilterNode.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/LeafNode.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/LiteralCoercer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/NotNode.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/OrNode.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiAttributeInfo.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiEntityInfo.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiSchema.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/EffectiveSchemaComputer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/MetamodelScanner.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/UserEditableStringIndex.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolErrorDto.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolLimits.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolUserError.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties
  - ai-agent/ai-agent/src/test/java/com/vn/agent/filter/FilterDslMapperTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/filter/LiteralCoercerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/metadata/EffectiveSchemaComputerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/metadata/MetamodelScannerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/BuiltInDataToolsReadOnlyTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/PromptInjectionHarnessTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/ToolLimitsTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/ToolResultFormatterTest.java
  - ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiToolsAutoConfiguration.java
  - ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  - jmix-app/build.gradle
  - jmix-app/src/main/java/com/vn/jmixapp/ai/OrderSummaryToolContributor.java
  - jmix-app/src/test/java/com/vn/jmixapp/ai/ChatServiceToolIntegrationTest.java
findings:
  critical: 0
  warning: 2
  info: 6
  total: 8
status: issues_found
---

# Phase 03: Code Review Report

**Reviewed:** 2026-04-19
**Depth:** standard
**Files Reviewed:** 29 (12 main sources + 8 tests + 3 resources + 2 starter/autoconfig + 2 host-app + 2 build files)
**Status:** issues_found

## Summary

Phase 3 delivers the metadata-first runtime (scanner + effective-schema computer) and the six read-only built-in LLM tools, plus the filter DSL mapper, literal coercer, result formatter, and a per-request tool-callback assembler. Overall code quality is high: constructor injection throughout, immutable records, exhaustive sealed-hierarchy switch in the filter mapper, fail-closed error handling via `ToolUserError`, strict read-only enforcement by ASM bytecode test, and a dedicated prompt-injection harness against Pitfall 4.

Two Warnings relate to prompt-injection defense gaps: (1) referenced-entity `toString()` values are emitted without `<data>` wrapping in `ToolResultFormatter.buildEntityMap`, which can re-surface attacker-controlled strings from related entities' instance names; (2) `LiteralCoercer.coerceBoolean` uses default-locale `toLowerCase()`, which is a style/correctness consistency bug relative to `FilterDslMapper`'s use of `Locale.ROOT`. Info items are defensive-coding suggestions and do not affect correctness.

No Critical findings. Read-only contract is enforced at build time by the ASM scan; JPQL concatenation in `countRecords` uses only the whitelisted `mc.getName()` (no LLM input).

## Warnings

### WR-01: Referenced-entity values bypass `<data>` prompt-injection wrapper

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java:163-167`
**Issue:** When serializing an entity row, reference attributes (i.e. `mp.getRange().isClass()` and non-null value) are rendered as `String.valueOf(v)`, which invokes Jmix's `@InstanceName`-generated `toString()`. That string can contain user-editable String attribute values from the related entity (e.g. `Order.customer` toString includes `Customer.name`). Because this code path writes the result directly to the row map without passing through the `<data>...</data>` wrapper, a hostile string stored in a neighbor entity's instance-name attribute is emitted unescaped to the LLM, bypassing the D-13 defense that `PromptInjectionHarnessTest` pins only for the direct-attribute case.

The `PromptInjectionHarnessTest` confirms `AiMessage.content` is wrapped, but does not cover the "related entity instance-name contains an attack" vector. Row-level security does not help because the attacker may legitimately own the neighbor row.

**Fix:** Treat reference-attribute instance-name renderings as user-editable by default (since instance names usually derive from user-editable String fields):
```java
} else if (v != null && mp.getRange().isClass()) {
    String rendered = String.valueOf(v);
    row.put(mp.getName(), "<data>" + escapeDataDelimiters(rendered) + "</data>");
}
```
Alternatively, emit only the referenced entity's id (UUID) in rows and require the LLM to call `get_record` for the instance-name — that aligns with the D-12 "drill further" guidance and completely removes the leak vector. Add a harness test that seeds `AiMessage` with a reference to another entity whose instance-name contains the attack string and asserts wrapping.

### WR-02: `LiteralCoercer.coerceBoolean` uses default-locale `toLowerCase()`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/filter/LiteralCoercer.java:106`
**Issue:** `String lower = s.toLowerCase();` (no Locale argument) is locale-sensitive. For ASCII `"true"`/`"false"` the risk is small, but the project elsewhere (`FilterDslMapper.resolveOperation` line 119) correctly uses `toUpperCase(Locale.ROOT)`. Using default locale here is inconsistent and becomes a latent bug if the input vocabulary ever grows to include characters that fold differently (e.g. Turkish `I`/`İ`). Coverity and SonarQube flag this pattern (`java:S1449`).

**Fix:**
```java
String lower = s.toLowerCase(Locale.ROOT);
```
(add `import java.util.Locale;`).

## Info

### IN-01: `AiAttributeInfo` compact constructor NPEs on null `validationConstraints`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/AiAttributeInfo.java:28`
**Issue:** `validationConstraints = List.copyOf(validationConstraints);` throws NPE if the list is null, whereas `enumValues` at line 27 tolerates null. `MetamodelScanner` always passes a non-null list today, but the asymmetry is a footgun for external callers and future refactors.
**Fix:** `validationConstraints = validationConstraints == null ? List.of() : List.copyOf(validationConstraints);`

### IN-02: `LiteralCoercer.coerceDatatype` computes `raw.toString()` unconditionally

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/filter/LiteralCoercer.java:167`
**Issue:** `String asString = raw.toString();` runs before the type dispatch, even for UUID/Boolean branches that do not need it and for reference types that reject non-String inputs. Also, passing `raw` (not `asString`) to `coerceUuidString` at line 173 means a Jackson-deserialized UUID (non-String) is rejected even though its `toString()` would parse. Minor.
**Fix:** Move `String asString = raw.toString();` inside each branch that uses it, or accept `raw.toString()` for UUID parsing when `raw` is not a String.

### IN-03: `FilterDslMapper.validatePath` does not validate the root `MetaClass`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/filter/FilterDslMapper.java:172-200`
**Issue:** Intermediate hops call `schemaComputer.canReadEntity(currentMc)`, but the starting `mc` is not re-checked here. In practice `BuiltInDataTools.resolveOrError` validates `mc` before calling the mapper, so there is no production gap. Flagged as defense-in-depth: if any future caller invokes `FilterDslMapper.map(node, mc)` with an unvalidated `mc`, the first-segment check only validates the attribute, not the entity.
**Fix:** Add `if (!schemaComputer.canReadEntity(mc)) throw new ToolUserError("access_denied", ...);` at the top of `map(...)` or `mapInternal(...)`.

### IN-04: `ToolUserError.getMessage()` embeds user-supplied reason strings (log-injection)

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolUserError.java:22`
**Issue:** `super(errorCode + ": " + reason)` is seeded from reason strings that contain LLM-supplied values (`"no attribute " + segment + " on " + currentMc.getName()`, `"no entity named " + entityName`, etc.). If these exceptions are ever logged (even at DEBUG), newline/CRLF injection in an entity name or property name could forge log lines. Low severity because (a) the exception message never reaches the LLM (only `toDto()` does) and (b) Jmix/Spring loggers typically escape newlines. Worth knowing for Phase 4 audit-log wiring.
**Fix:** Sanitize or truncate `reason` when building the exception message, e.g. `reason.replaceAll("[\\r\\n\\t]", " ")`, or use parameterized logging (`log.warn("tool error: {}", dto)`) rather than `log.warn(e.getMessage())`.

### IN-05: `ToolResultFormatter.escapeDataDelimiters` is case-sensitive

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java:180-187`
**Issue:** `replace("<data>", "&lt;data&gt;")` is case-sensitive. The opening/closing wrapper is always lowercase, so an LLM-facing JSON reader interprets the delimiters literally; variants like `<DATA>` inside values are not a real bypass. Included as documentation: if the delimiter convention ever changes to be case-insensitive in an intermediate renderer, this escape function would need updating.
**Fix:** No change required. If future attack surface demands it: apply case-insensitive regex replacement.

### IN-06: `AgentToolCallbacks` allocates a fresh `MethodToolCallbackProvider` per bean per call

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java:56-61`
**Issue:** `fromBean(...)` builds a new `MethodToolCallbackProvider` (reflection scan of `@Tool` methods) every invocation. Each user request therefore re-reflects every bean's tool methods. This is not a correctness bug and matches the documented "fresh per call" contract, but the reflection is actually per-class (not per-user) so its results are cacheable by bean identity. Out-of-scope for v1 (performance), noted for Phase 4 chat-loop profiling. Do not change without a benchmark.
**Fix:** (deferred) Consider a `Map<Class<?>, ToolCallback[]>` memoization keyed on `bean.getClass()` once per-request allocation profiling in Phase 4 warrants it.

---

_Reviewed: 2026-04-19_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
