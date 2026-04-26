# Phase 1: Walking Skeleton & Packaging De-risk - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in 01-CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-18
**Phase:** 01-walking-skeleton
**Areas discussed:** Module split cleanup timing, Clean-consumer smoke format, ChatService stub API shape, Mock ChatModel strategy for CI

---

## Gray Area Selection

| Option | Description | Selected |
|--------|-------------|----------|
| Module split cleanup timing | When (if) to strip Vaadin deps from the functional module given the planned 4-module split | ✓ |
| Clean-consumer smoke format | Form of the publishToMavenLocal + fresh-Jmix-consumer verification | ✓ |
| ChatService stub API shape | Minimal vs future-shaped signature for Phase 1 | ✓ |
| Mock ChatModel strategy for CI | How to exercise the ChatClient path without live LLM calls | ✓ |
| Live-test credential handling | OpenRouter API-key env var + skip-if-missing policy | (not selected) |

---

## Module split cleanup timing

| Option | Description | Selected |
|--------|-------------|----------|
| Strip now, clean 4-module split | Remove jmix-flowui-starter + themes from ai-agent/ai-agent.gradle; headless functional module | |
| Defer — add modules first, clean later | Keep dirty functional module, just add two flowui modules alongside | |
| Strip now, keep only core + data | Most aggressive headless split; relocate all flow-ui code | |
| **(user-provided) Keep single 2-module shape for now, no flowui split** | Don't split Vaadin/FlowUI out; UI stays in starter. Add-on is expected to provide UI; Jmix add-on structure supports that. Revisit separation when a concrete REST-only consumer use case appears. | ✓ |

**User's choice:** User-provided — keep existing 2-module shape (`ai-agent` + `ai-agent-starter`). No new modules in Phase 1.
**Notes:** "Splitting it now adds module complexity without clear benefit at this stage. We can revisit separation later if we have a real use case for a headless, REST-only consumer." This deviates from ROADMAP.md Phase 1 deliverable (which specified adding the two flowui modules) and from PROJECT.md Key Decision #2. CONTEXT.md flags the ROADMAP/PROJECT updates as part of Phase 1 planning scope.

---

## Clean-consumer smoke format

| Option | Description | Selected |
|--------|-------------|----------|
| Use existing jmix-app as consumer (Recommended) | Toggle jmix-app from includeBuild to Maven Local dep; verify boot | ✓ |
| Committed consumer-smoke/ sub-project | Second includeBuild at repo root — true fresh consumer with ongoing maintenance | |
| Scripted ephemeral consumer test | Gradle/bash task that generates + tears down a throwaway Jmix project | |
| Documented manual checklist | RELEASE.md with manual verification steps | |

**User's choice:** Use existing jmix-app as consumer.
**Notes:** Cheapest path; reuses the demo app that already consumes the starter. Planner captures the toggle as a repeatable Gradle task / README script.

---

## ChatService stub API shape

| Option | Description | Selected |
|--------|-------------|----------|
| Minimal: String ask(String message) | True walking skeleton; API churn later | |
| Future-shaped with conversationId + user (Recommended) | Signature already carries conversationId + userKey; jmix-app integration written once | ✓ |
| Interface-only, no impl in Phase 1 | NoOp impl throws UnsupportedOperationException; live smoke calls ChatClient directly | |

**User's choice:** Future-shaped with conversationId + user.
**Notes:** Avoids signature churn when Phase 3 (orchestration) and Phase 4 (memory) land. Phase 1 stub accepts a null userKey placeholder and does not persist conversationId.

---

## Mock ChatModel strategy for CI

| Option | Description | Selected |
|--------|-------------|----------|
| Hand-rolled stub ChatModel @TestConfiguration | FakeChatModel as reusable @TestConfiguration bean | |
| spring-ai-test utilities (if exist in M4) | Use Spring AI's own test support | |
| @MockBean ChatModel per integration test | Mockito @MockBean per test | |
| Try spring-ai-test first, fallback to hand-rolled | Research-spike + fallback plan | ✓ |

**User's choice:** Try spring-ai-test first, fallback — with explicit instruction to align with `jmix-ai-backend` testing approach where possible.
**Notes:** Inspection of jmix-ai-backend (`RerankerTest.java`, `ExternalEvaluatorImplTest.java`) shows plain `Mockito.mock(ChatModel.class)` is the established pattern there — no spring-ai-test, no live tier. Decision captured as: Mockito mock as primary/fallback, spring-ai-test as a Phase-1 research spike, `@Tag("live")` separation added beyond the reference because the reference has no live tier and we explicitly need one for CI cost control.

---

## Claude's Discretion

- `ChatResponse` DTO shape and package.
- Exact Gradle wiring location (root subprojects block vs. per-module).
- Version matrix document format and location.
- `@JmixModule(dependsOn = …)` dependency list.
- Maven-Local consumer toggle task naming.
- Live-test skip-if-missing mechanism (JUnit idiom).

## Deferred Ideas

- 4-module split (`ai-agent-flowui` + `ai-agent-flowui-starter`) — revisit when a REST-only consumer use case justifies it.
- Stripping Vaadin deps from functional module — coupled to the 4-module split.
- Full OpenRouter / profile wiring — Phase 6.
- `spring-ai-test` evaluation harness usage (beyond presence check) — Phase 7+.
- `jmix-flowui-test-assist` adoption — no views yet, defer.
- Namespace rebrand from `com.vn` — outside Phase 1.
