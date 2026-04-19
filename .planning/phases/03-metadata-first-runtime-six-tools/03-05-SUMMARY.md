---
phase: 03-metadata-first-runtime-six-tools
plan: 05
subsystem: host-integration
tags: [jmix-app, spi, tool-contributor, integration-test, end-to-end]
requirements: [SPI-01]
dependency_graph:
  requires:
    - com.vn.agent.spi.ToolContributor
    - com.vn.agent.tools.AgentToolCallbacks
    - com.vn.agent.tools.BuiltInDataTools
    - com.vn.agent.tools.ToolResultFormatter
    - com.vn.jmixapp.entity.Order
    - com.vn.jmixapp.entity.Customer
    - com.vn.jmixapp.test_support.AuthenticatedAsAdmin
    - io.jmix.core.DataManager
    - io.jmix.core.FetchPlans
    - io.jmix.core.EntityStates
    - io.jmix.core.security.SystemAuthenticator
    - org.springframework.ai:spring-ai-client-chat:1.1.4
  provides:
    - OrderSummaryToolContributor (SPI-01, D-15 host-side real sample)
    - ChatServiceToolIntegrationTest (Phase 3 success criterion #3 DataManager round-trip)
    - EntityStates fetch-plan guard in ToolResultFormatter (Rule 1 fix)
  affects:
    - Phase 4 ChatClientFactory (can consume AgentToolCallbacks.forCurrentUser with full confidence)
    - Any future host-side ToolContributor implementer (reference pattern)
tech_stack:
  added:
    - "jmix-app: org.springframework.ai:spring-ai-client-chat:1.1.4 (Rule 3 — @Tool/@ToolParam visible on host compile classpath)"
  patterns:
    - "Host ToolContributor returning List.of(this) per PATTERNS.md §OrderSummaryToolContributor"
    - "Named-parameter JPQL synthesized fully in host (no LLM input in string, :cid binding)"
    - "@SpringBootTest + AuthenticatedAsAdmin extension + SystemAuthenticator.runWithSystem(Runnable) for seed fixtures"
    - "EntityStates.isLoaded guard in ToolResultFormatter.buildEntityMap (Rule 1 fix)"
key_files:
  created:
    - jmix-app/src/main/java/com/vn/jmixapp/ai/OrderSummaryToolContributor.java
    - jmix-app/src/test/java/com/vn/jmixapp/ai/ChatServiceToolIntegrationTest.java
  modified:
    - jmix-app/build.gradle
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/ToolResultFormatterTest.java
key_decisions:
  - "Added spring-ai-client-chat as host dependency (Rule 3): add-on declares it as `implementation` only, so @Tool/@ToolParam annotations aren't transitively visible to hosts. Any jmix-app writing a ToolContributor needs this dep — documenting the pattern."
  - "EntityStates.isLoaded guard in ToolResultFormatter.buildEntityMap (Rule 1): FetchPlan.INSTANCE_NAME doesn't load all attributes; calling EntityValues.getValue on unfetched attributes of detached entities throws. Fix serializes unfetched attributes as null and lets callers drill via get_record / get_related_records (D-12)."
  - "Restricted-user denied-attribute absence deferred to Plan 04 EffectiveSchemaComputerTest (unit level) per 03-CONTEXT.md Deferred Ideas — do not duplicate with a second @SpringBootTest class."
  - "ChatService-routed mock-ChatModel variant deferred to Phase 4 (owns ChatClientFactory + advisor chain per CHAT-02/CHAT-03)."
metrics:
  duration_minutes: 35
  tasks_completed: 2
  files_created: 2
  files_modified: 3
  completed_date: 2026-04-19
---

# Phase 3 Plan 05: Host-Side Tool Contributor Sample + Integration Test Summary

Delivered SPI-01 (the host-side `ToolContributor` sample) and closed Phase 3's slice of success criterion #3 — `find_records` round-trip against real `jmixapp_Order` rows via the direct DataManager path + admin `describe_entity` surfaces structured JSON. Proves the per-request tool-assembly pipeline (built-ins + host contributor) works end-to-end in a real Jmix app.

## Outcome

- 2 files added under `jmix-app` (host-side sample + integration test).
- 3 files modified (jmix-app `build.gradle` adds Spring AI client dep; add-on `ToolResultFormatter` guards with `EntityStates.isLoaded`; its unit test updated for new ctor arg).
- `./gradlew :jmix-app:test` → exit 0 (5 tests pass: 3 new `ChatServiceToolIntegrationTest` + existing `UserTest` + `UserUiTest`).
- `./gradlew :ai-agent:ai-agent:test` → exit 0 (all pre-existing add-on tests still green).
- `./gradlew :jmix-app:build :ai-agent:ai-agent:build :ai-agent:ai-agent-starter:build -x test` → exit 0.
- Integration test observes **7 callbacks** via `AgentToolCallbacks.forCurrentUser()`: the six built-in tools (`list_entities`, `describe_entity`, `find_records`, `get_record`, `count_records`, `get_related_records`) **plus** the host contributor's `summarize_customer_orders` — per-request assembly confirmed in a real Spring context.

## Task-by-Task

### Task 1 — `OrderSummaryToolContributor` (commit `8198ab1`)

- New `@Component` at `jmix-app/src/main/java/com/vn/jmixapp/ai/OrderSummaryToolContributor.java` implements `com.vn.agent.spi.ToolContributor` and exposes one `@Tool(name = "summarize_customer_orders")` method that joins `Order` + `Customer` through `DataManager`.
- Uses `dataManager.load(Customer.class).id(cid).fetchPlan(FetchPlan.INSTANCE_NAME)` for the customer lookup and `dataManager.load(Order.class).query("select o from jmixapp_Order o where o.customer.id = :cid").parameter("cid", cid).fetchPlan(fp)` for the orders — the JPQL string is fully synthesized by the class; `customerId` flows as a named parameter (trust boundary T-03-24 accepted: host `@Tool` is trusted code).
- Constructor injection (`DataManager`, `FetchPlans`), per `CLAUDE.md`. No `@Autowired` / `@Inject` field-injection.
- `contribute()` returns `List.of(this)` per PATTERNS.md §"OrderSummaryToolContributor".
- `jmix-app/build.gradle` gained `implementation 'org.springframework.ai:spring-ai-client-chat:1.1.4'` (Rule 3 deviation — see below).

### Task 2 — `ChatServiceToolIntegrationTest` + add-on bug fix (commits `cdfd28d` + `fcac566`)

- New `@SpringBootTest` at `jmix-app/src/test/java/com/vn/jmixapp/ai/ChatServiceToolIntegrationTest.java` with three assertions:
  1. **`perRequestAssemblyIncludesBuiltInsAndHostContributor`** — `AgentToolCallbacks.forCurrentUser()` returns a callback array of length ≥ 7; the callback names contain all six built-in tool names **and** `summarize_customer_orders`. Per-request assembly (built-ins + host contributor) verified end-to-end.
  2. **`findRecordsOrderRoundTrip`** — seeds one `Customer` + one `Order` under `SystemAuthenticator.runWithSystem(Runnable)` with a time-stamped order number (T-03-26 mitigation against cross-run collisions), then calls `BuiltInDataTools.findRecords("jmixapp_Order", null, null)` directly and asserts the returned JSON contains the seeded order's number plus `"truncated":false`. **Phase 3 success criterion #3 DataManager-path coverage.**
  3. **`describeEntityAdminPathSurfacesStructuredJson`** — under `AuthenticatedAsAdmin`, calls `BuiltInDataTools.describeEntity("jmixapp_Order")` and asserts the returned JSON contains `jmixapp_Order` plus at least one expected attribute (`number`, `orderDate`, `customer`, `status`). Smoke assertion that `describe_entity` plumbing works end-to-end in `@SpringBootTest`.
- Restricted-user integration variant **NOT** added — covered authoritatively at unit level by Plan 04's `CurrentUserSchemaAccessTest` (previously `EffectiveSchemaComputerTest`; collapsed post-execute) per 03-CONTEXT.md `## Deferred Ideas`.
- ChatService-routed mock-`ChatModel` variant **NOT** added — deferred to Phase 4 alongside `ChatClientFactory` + advisor chain (CHAT-02/CHAT-03). The DataManager-path assertion is the authoritative Phase 3 coverage.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — Blocking] Spring AI `@Tool` annotations not transitively visible to host**
- **Found during:** Task 1 `./gradlew :jmix-app:compileJava`.
- **Issue:** `OrderSummaryToolContributor` failed to compile with `package org.springframework.ai.tool.annotation does not exist`. The add-on declares `spring-ai-client-chat:1.1.4` as `implementation` (not `api`), so `@Tool` / `@ToolParam` aren't on the host compile classpath.
- **Fix:** Added `implementation 'org.springframework.ai:spring-ai-client-chat:1.1.4'` to `jmix-app/build.gradle` with an explanatory comment. This is the correct host-side dependency pattern — any future host-side `ToolContributor` writer needs these annotations.
- **Files modified:** `jmix-app/build.gradle`
- **Commit:** `8198ab1`

**2. [Rule 1 — Bug] Customer entity field names differed from plan**
- **Found during:** Task 2 authoring.
- **Issue:** Plan's test skeleton used `c.setFirstName(...)`, `c.setLastName(...)`, `c.setEmail(...)`. Real `Customer` entity has `name`, `email`, `phone` (no first/last split).
- **Fix:** Adapted the seed code to use `setName(...)` + `setEmail(...)`. Documented inline.
- **Commit:** `fcac566`

**3. [Rule 1 — Bug] `ToolResultFormatter.buildEntityMap` threw on unfetched attributes**
- **Found during:** Task 2, running `findRecordsOrderRoundTrip`. Stack trace: `java.lang.IllegalStateException: Cannot get unfetched attribute [status] from detached object com.vn.jmixapp.entity.Order-... [detached]` at `EntityValues.getValue(Order, "status")` called from `ToolResultFormatter.buildEntityMap` (line 140).
- **Root cause:** `FetchPlan.INSTANCE_NAME` only loads attributes referenced by `@InstanceName` / `@DependsOnProperties`; for `Order` that is `number` + `orderDate`. `buildEntityMap` iterated **all** `MetaProperty` entries on the `MetaClass` and invoked `getValue` on unfetched attributes — which EclipseLink intercepts and throws on, for detached entities. This is a latent add-on bug exposed the first time `find_records` ran against a real seeded row (the Plan-04 unit tests mocked around serialization).
- **Fix:** Injected `io.jmix.core.EntityStates` into `ToolResultFormatter`; in `buildEntityMap`, check `entityStates.isLoaded(entity, mp.getName())` before calling `getValue` — unfetched attributes serialize as `null`. Callers drill further via `get_record` / `get_related_records` (per D-12). Preserves the read-only posture; does not weaken the `<data>` wrap or user-editable-string detection.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/ToolResultFormatterTest.java` (ctor signature).
- **Commits:** `cdfd28d` (fix) + `fcac566` (test ctor update bundled with Task 2).

### Notes (not deviations)

- Clean build required once to rebuild the Jmix test-classpath metamodel after adding the new main-source-set `@Component` (`OrderSummaryToolContributor`). Cached `:jmix-app:test` artifacts otherwise threw `MetaClass not found for class com.vn.jmixapp.entity.User` from `UserRepository.init`. This is a Jmix enhancement caching oddity, not a plan deviation — subsequent runs are fine.

## Restricted-User Coverage Choice

Chose **deferral to Plan 04 `CurrentUserSchemaAccessTest`** (previously `EffectiveSchemaComputerTest`; collapsed post-execute — the option explicitly recommended by 03-CONTEXT.md `## Deferred Ideas`). Rationale:

- Plan 04's unit-level `CurrentUserSchemaAccessTest` (previously `EffectiveSchemaComputerTest`) asserts a restricted user's readable schema (previously the `AiSchema` DTO; collapsed post-execute) omits `AccessManager`-denied attributes — the authoritative Phase 3 coverage.
- A `@SpringBootTest` duplicate would triple the CI runtime of this test class with no additional correctness signal (both paths go through the same `AccessManager` contract).
- `03-05-PLAN.md` explicitly instructs: *"Plan 05 does NOT add a second restricted-user integration class."* We followed that.

The ChatService-routed mock-`ChatModel` variant is deferred to Phase 4 (CHAT-02/CHAT-03 own the `ChatClientFactory` + advisor chain wiring).

## Deferred Issues

None — scope fully contained.

## Test Results

```
./gradlew :jmix-app:test  →  BUILD SUCCESSFUL (5 tests / 0 failures)
  com.vn.jmixapp.ai.ChatServiceToolIntegrationTest       (3/3 passed)
  com.vn.jmixapp.user.UserTest                           (1/1 passed)
  com.vn.jmixapp.user.UserUiTest                         (1/1 passed)

./gradlew :ai-agent:ai-agent:test  →  BUILD SUCCESSFUL (all add-on tests green)

./gradlew :jmix-app:build :ai-agent:ai-agent:build :ai-agent:ai-agent-starter:build -x test
  →  BUILD SUCCESSFUL
```

## Self-Check: PASSED

Files exist:
- FOUND: jmix-app/src/main/java/com/vn/jmixapp/ai/OrderSummaryToolContributor.java
- FOUND: jmix-app/src/test/java/com/vn/jmixapp/ai/ChatServiceToolIntegrationTest.java

Commits exist:
- FOUND: 8198ab1 (Task 1 — OrderSummaryToolContributor + spring-ai-client-chat dep)
- FOUND: cdfd28d (Rule 1 fix — EntityStates guard in ToolResultFormatter.buildEntityMap)
- FOUND: fcac566 (Task 2 — ChatServiceToolIntegrationTest + formatter test ctor update)
