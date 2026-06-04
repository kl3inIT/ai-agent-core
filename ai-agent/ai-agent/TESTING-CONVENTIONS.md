# Test conventions — `:ai-agent:ai-agent`

> Goal: keep the suite **fast** and **honest**. The dominant cost is **how many distinct
> Spring contexts boot**, not how many `@Test` methods run. Each unique
> `@SpringBootTest` configuration boots Jmix + the `agentstore` datastore + Liquibase once
> and is then cached by Spring's test-context cache. Tests that declare an **identical**
> configuration **share one cached context**; tests that differ by even one property or one
> `@Import` get their **own** boot.

State at last audit (2026-06-04): **847 tests, 0 failures**, full suite ~12 min, 86
`@SpringBootTest` files across ~24 distinct contexts (15 of them used by a single test).

## 1. Use a shared composed annotation — don't copy-paste the boot recipe

Integration tests must NOT repeat `@SpringBootTest(...) + @ImportAutoConfiguration(...) +
@Import(...)`. Use (or add) a composed annotation so identical-config tests collapse onto one
cached context.

- **Mutation-tool tests** → `@MutationIntegrationTest`
  (`tools/mutation/MutationIntegrationTest.java`): mutation ON + fixture persistence + test
  users + stub chat/vector. Example: `BuiltInMutationToolsIdempotencyReplayTest`,
  `BuiltInMutationToolsIdempotencyViolationTest` share one context via it.
- Need one extra property? Keep the annotation and add `@TestPropertySource(properties = "…")`
  on top (this gives that class its own context but still removes the boilerplate). Example:
  `BuiltInMutationToolsBulkSaveTest` (+`bulk-max-rows=100`).

When a cluster outside mutation needs the same treatment, add a sibling annotation (e.g. a
plain `@AiIntegrationTest` for mutation-OFF tool tests) rather than re-pasting the recipe.

## 2. Do NOT merge / collapse tests that intentionally alter the context

These legitimately need their own context — merging them silently DELETES coverage:

- **`@MockitoBean` / `@MockBean` overrides** — replace a real bean (e.g. `MutationGuard`,
  `AccessManager`) with a mock. If merged into a real-bean suite, the real gating path stops
  being exercised. (`BuiltInMutationToolsPartialFailureTest`, `…AccessGatingTest`, etc.)
- **Fault-injection `@Import`** — e.g. a throwing entity listener
  (`BuiltInMutationToolsBulkSaveListenerRollbackTest`).
- **Behaviour-defining properties** — e.g. `…DefaultConfigTest` asserts the mutation-OFF
  default; never add `mutation.enabled=true` to it.

## 3. Prefer MERGING over-fragmented same-context tests; never DELETE for coverage

The suite has little true assertion duplication — its bloat is **fragmentation** (one `@Test`
per file from plan-by-plan execution). When several files share an identical real-bean context
and the same setup, merge them into one class with multiple `@Test` methods (dedupe the
`@AfterEach`/helpers). This cuts files AND contexts with **zero coverage loss**. Example:
`BulkSave` + `BulkSaveIdempotency` → one `BuiltInMutationToolsBulkSaveTest` (6 tests).

Deletion is only for a **verified** duplicate assertion — not for "looks redundant."

## 4. Keep invariant / scanner tests as pure-JUnit (no Spring)

Source/reflection guards stay pure-JUnit by design (no context, fastest tier) and must not be
"upgraded" to `@SpringBootTest`: `MutationToolInvariantsTest`, `SecretRedactionInvariantsTest`
(SEC-08), `ToolNavigationLeakScannerTest`, `ToolDescriptionInvariantsTest`,
`MutationErrorTranslatorTest`, `RelatedWriteMetadataMemoTest`. ArchUnit is intentionally NOT a
dependency.

## 5. UI tests

`@UiTest @SpringBootTest(classes = {AITestConfiguration.class, FlowuiTestAssistConfiguration.class})`
is the canonical Jmix Flow UI recipe. Keep UI tests grouped so they share their (heavier)
context.

## 6. Before adding a new `@SpringBootTest` config, ask:

1. Does an existing composed annotation already describe this context? Use it.
2. Do I really need a different property/import, or can I join an existing context?
3. If different, is the difference essential (mock / fault / behaviour) or accidental? Make
   accidental differences identical so the context is shared.

A new one-off context is a ~15–25s tax on every CI run — spend it deliberately.
