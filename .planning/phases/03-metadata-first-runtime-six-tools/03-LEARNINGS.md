---
phase: 3
phase_name: "metadata-first-runtime-six-tools"
project: "ai-agent-core"
generated: "2026-04-20T00:32:38.381+07:00"
counts:
  decisions: 4
  lessons: 5
  patterns: 5
  surprises: 4
missing_artifacts:
  - "03-VERIFICATION.md"
  - "03-UAT.md"
---

# Phase 3 Learnings: metadata-first-runtime-six-tools

## Decisions

### Collapse the metadata surface into `CurrentUserSchemaAccess`
Phase 3 ended with a single adapter replacing the earlier six-file parallel metadata layer, while keeping the TOOL-01/TOOL-02 behavior intact.

**Rationale:** Reuse Jmix `Metadata`, `AccessManager`, and `MessageTools` directly instead of maintaining a shadow metamodel.
**Source:** `03-01-SUMMARY.md`

---

### Keep `DOES_NOT_CONTAIN` stable on the LLM-facing DSL
The filter DSL kept the `DOES_NOT_CONTAIN` token for callers, even though Jmix 2.8 uses `NOT_CONTAINS` internally.

**Rationale:** Preserve a stable tool contract for the LLM while still emitting the correct Jmix operation constant at runtime.
**Source:** `03-02-SUMMARY.md`

---

### Use `MethodToolCallbackProvider` for Spring AI tool discovery
`AgentToolCallbacks` was implemented with `MethodToolCallbackProvider.builder().toolObjects(bean).build().getToolCallbacks()` instead of `ToolCallbacks.from(...)`.

**Rationale:** Spring AI 1.1.4 does not provide the helper assumed by the original plan, but the provider API performs the same reflection pass over `@Tool` methods.
**Source:** `03-03-SUMMARY.md`

---

### Defer restricted-user integration and ChatService-routed tool tests
Phase 3 kept admin-path integration coverage in `@SpringBootTest`, while leaving restricted-user integration coverage to the metadata unit test and the ChatService-routed path to Phase 4.

**Rationale:** Avoid duplicate coverage in Phase 3 and keep Phase 4 responsible for `ChatClientFactory` and advisor-chain end-to-end behavior.
**Source:** `03-05-SUMMARY.md`

---

## Lessons

### Exact framework APIs must be verified against the shipped version
Multiple plan assumptions were wrong at implementation time: the `AccessManager` package path, the Jmix `NOT_CONTAINS` constant name, the absence of `ToolCallbacks.from(...)`, and the non-static `LoadContext.Query` construction path.

**Context:** Phase 3 repeatedly hit compile-time mismatches between research/plan interfaces and the actual versions on the classpath.
**Source:** `03-01-SUMMARY.md`, `03-02-SUMMARY.md`, `03-03-SUMMARY.md`

---

### Narrow fetch plans require load-state-aware serialization
Serializing every `MetaProperty` after loading with `FetchPlan.INSTANCE_NAME` caused detached-entity failures once a real integration test ran against seeded data.

**Context:** `ToolResultFormatter` needed an `EntityStates.isLoaded(...)` guard before reading attributes, otherwise unfetched fields triggered runtime exceptions.
**Source:** `03-05-SUMMARY.md`

---

### Host-side tool samples need their own Spring AI compile dependency
The host `jmix-app` could not compile a real `ToolContributor` sample until it declared `spring-ai-client-chat` directly.

**Context:** The add-on exposed Spring AI as `implementation`, so `@Tool` and `@ToolParam` were not visible transitively to the host module.
**Source:** `03-05-SUMMARY.md`

---

### `Mockito.mockConstruction` is fragile when nested calls happen inside `when(...)`
The metadata access-context test hit `UnfinishedStubbingException` until argument values were extracted to locals before stubbing.

**Context:** Constructor-mocked Jmix access contexts work, but the stubbing style matters if the test reads mock arguments while Mockito is still building expectations.
**Source:** `03-04-SUMMARY.md`

---

### `runWithSystem` expects `Runnable`-style test lambdas
The prompt-injection harness and related tests had to drop `return null;` because `SystemAuthenticator.runWithSystem(...)` takes `Runnable`, not `Supplier`.

**Context:** This only surfaced while wiring real test fixtures through the Jmix system-authentication helper.
**Source:** `03-04-SUMMARY.md`

---

## Patterns

### Thin adapter over Jmix security and metadata
Use a single adapter that asks Jmix directly for schema, labels, and access decisions instead of inventing an add-on-owned shadow model.

**When to use:** When an AI-facing surface needs to stay close to the host framework's native authorization and metamodel semantics.
**Source:** `03-01-SUMMARY.md`

---

### Sealed DSL plus exhaustive switch for query mapping
Model the filter DSL as a closed hierarchy and map it with an exhaustive switch plus a recursive negation flag.

**When to use:** When a small JSON DSL must stay auditable, type-safe, and compiler-checked while still supporting nested boolean logic.
**Source:** `03-02-SUMMARY.md`

---

### Fresh per-request tool callback assembly
Build a new `ToolCallback[]` on each request by combining built-ins with host contributors instead of caching a global array.

**When to use:** When tool behavior or visibility depends on the current user, role set, or host-provided extension beans.
**Source:** `03-03-SUMMARY.md`

---

### Wrap untrusted persisted text in `<data>...</data>` and escape literal delimiters
Treat user-editable text as untrusted prompt input and harden it before it is returned to the model.

**When to use:** Whenever stored business data can flow back into an LLM response context and needs a lightweight, testable injection boundary.
**Source:** `03-03-SUMMARY.md`, `03-04-SUMMARY.md`

---

### Enforce architectural invariants with narrow ASM tests
Use bytecode-level tests to lock down one critical class rather than adding a broad architectural framework for a small invariant surface.

**When to use:** When one class has a high-value safety rule, such as "tool bodies must stay read-only" or "LLM input must not build JPQL strings."
**Source:** `03-04-SUMMARY.md`

---

## Surprises

### ASM 9.7 could not read the project's class files
The planned ASM version failed against JDK 25 class version 69 and had to be upgraded to 9.9.

**Impact:** The TOOL-08 enforcement test was blocked until the dependency changed, even though the enforcement logic itself was correct.
**Source:** `03-04-SUMMARY.md`

---

### Spring AI annotations were not visible to the host app by default
The first real `ToolContributor` sample failed to compile because the host module did not inherit Spring AI's tool annotations from the add-on.

**Impact:** The host extension pattern now includes an explicit Spring AI dependency, which is easy to miss if you only look at add-on code.
**Source:** `03-05-SUMMARY.md`

---

### The real `Customer` entity shape did not match the plan skeleton
The integration test fixture had to switch from `firstName`/`lastName` assumptions to the actual `name`/`email`/`phone` model.

**Impact:** Test scaffolding based only on plan text was wrong until it was grounded in the host entity definitions.
**Source:** `03-05-SUMMARY.md`

---

### Real seeded data exposed a formatter bug that unit tests had not reached
`find_records` worked at the query layer, but serializing the result surfaced unfetched detached attributes and broke the first true integration pass.

**Impact:** Phase 3 gained an `EntityStates.isLoaded(...)` guard in `ToolResultFormatter`, and the integration test became part of the phase's real confidence boundary.
**Source:** `03-05-SUMMARY.md`
