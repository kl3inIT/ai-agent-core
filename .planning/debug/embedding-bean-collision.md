---
slug: embedding-bean-collision
status: resolved
trigger: |
  <!-- DATA_START -->
  The aiAgentEmbeddingModel passthrough bean uses @ConditionalOnMissingBean but the condition isn't
  skipping it, so both it and openAiEmbeddingModel end up in context — pgvector's
  vectorStore(EmbeddingModel) then can't disambiguate.
  <!-- DATA_END -->
created: 2026-04-21T00:00:00Z
updated: 2026-04-21T00:00:00Z
---

# Debug Session: embedding-bean-collision

## Symptoms

- **Expected:** `./gradlew :jmix-app:bootRun` (postgres profile, `OPENROUTER_API_KEY` set) starts the app; `PgVectorStoreAutoConfiguration.vectorStore(EmbeddingModel)` resolves a single `EmbeddingModel` bean and Spring boots Tomcat on 8080.
- **Actual:** `APPLICATION FAILED TO START`. Spring Boot prints:
  > Parameter 1 of method vectorStore in org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration required a single bean, but 2 were found:
  > - aiAgentEmbeddingModel: defined by method 'aiAgentEmbeddingModel' in class path resource [com/vn/autoconfigure/agent/AIAutoConfiguration.class]
  > - openAiEmbeddingModel: defined by method 'openAiEmbeddingModel' in class path resource [org/springframework/ai/model/openai/autoconfigure/OpenAiEmbeddingAutoConfiguration.class]
- **Error type:** `NoUniqueBeanDefinitionException` wrapped in `UnsatisfiedDependencyException` (failed bean: `vectorStore`).
- **Timeline:** Introduced by commit `cc3fd9e feat(05-01): add EmbeddingModel + VectorStore beans to AIAutoConfiguration`. Currently 100% reproducible on branch `gsd/phase-07-flow-ui` when bootRun executes with `OPENROUTER_API_KEY` present (without the key, it fails earlier at `openAiApi`).
- **Reproduction:** `set -a; source .env; set +a; ./gradlew :jmix-app:bootRun` → fails during context refresh before Tomcat starts.

## Current Focus

hypothesis: |
  `AIAutoConfiguration` is registered via `AutoConfiguration.imports` (correct mechanism) but
  lacks `@AutoConfigureAfter(OpenAiEmbeddingAutoConfiguration.class)`. Without ordering, Spring
  may evaluate the `@ConditionalOnMissingBean` on `aiAgentEmbeddingModel` before OpenAI's
  `openAiEmbeddingModel` is registered — the condition then holds and BOTH beans end up in the
  context. Fix: add `@AutoConfigureAfter` so the conditional fires after OpenAI's bean exists.
test: |
  1) Inspect `AIAutoConfiguration.java` — is it `@Configuration` or `@AutoConfiguration`?
  2) Check `AutoConfiguration.imports` for registration.
  3) Check for `@AutoConfigureAfter` / `@AutoConfigureBefore`.
  4) Confirm `EmbeddingModelBeanCollisionTest` exists and what it asserts.
  5) Apply fix and re-run bootRun.
expecting: |
  Ordering hypothesis confirmed; fix advances bootRun past the pgvector bean-resolution step.
next_action: CONFIRMED and RESOLVED — see Resolution.

## Evidence

- timestamp: 2026-04-21 — bootRun fails with NoUniqueBeanDefinitionException on `vectorStore` parameter, two `EmbeddingModel` candidates (ours + OpenAI's).
- timestamp: 2026-04-21 — `git log` shows file last touched in phase 5 commit `cc3fd9e`; phase 7 work did not modify `AIAutoConfiguration.java`.
- timestamp: 2026-04-21 — Finding (1): `AIAutoConfiguration` already uses `@AutoConfiguration` (good), not plain `@Configuration` as the trigger assumed.
- timestamp: 2026-04-21 — Finding (2): listed in `ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- timestamp: 2026-04-21 — Finding (3): NO `@AutoConfigureAfter`/`@AutoConfigureBefore` directive. This is the gap.
- timestamp: 2026-04-21 — Finding (4): `EmbeddingModelBeanCollisionTest` (RAG-02) at `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/config/EmbeddingModelBeanCollisionTest.java` **explicitly excludes** `OpenAiEmbeddingAutoConfiguration` via `spring.autoconfigure.exclude`, so it never exercised the real collision path — it only verified the stub scenario. That is why the bug shipped.
- timestamp: 2026-04-21 — `spring-ai-starter-model-openai:1.1.4` is an `implementation` dependency of `ai-agent-starter`, so `OpenAiEmbeddingAutoConfiguration` is guaranteed to be on the classpath. Safe to reference directly in `@AutoConfigureAfter`.
- timestamp: 2026-04-21 — Post-fix bootRun: the `NoUniqueBeanDefinitionException` on `vectorStore` is gone. Context refresh now proceeds past embedding-model resolution and fails on an unrelated Jmix metadata issue (`MetaClass not found for class com.vn.agent.entity.AiConversation` during `sec_AnnotatedResourceRoleProvider` instantiation) — NEW error, out of scope for this session.

## Eliminated

- `@Configuration` vs `@AutoConfiguration` mis-registration — the class already uses `@AutoConfiguration`.
- Missing `AutoConfiguration.imports` entry — entry is present.

## Resolution

- **Root cause:** `AIAutoConfiguration` (registered via `AutoConfiguration.imports`) lacked an `@AutoConfigureAfter(OpenAiEmbeddingAutoConfiguration.class)` ordering directive. Without it, Spring evaluated `@ConditionalOnMissingBean` on the `aiAgentEmbeddingModel` passthrough before OpenAI's `openAiEmbeddingModel` was registered — the condition wrongly held and both beans ended up in the context, which broke `PgVectorStoreAutoConfiguration.vectorStore(EmbeddingModel)`.
- **Why the existing test missed it:** `EmbeddingModelBeanCollisionTest` (RAG-02) sets `spring.autoconfigure.exclude=OpenAiEmbeddingAutoConfiguration,PgVectorStoreAutoConfiguration`, so it never observed the real auto-configuration ordering path. It proves the passthrough is idempotent in isolation; it does not prove ordering correctness in a real host.
- **Fix:** Changed `@AutoConfiguration` → `@AutoConfiguration(after = OpenAiEmbeddingAutoConfiguration.class)` in `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java` and added an import + javadoc explaining the ordering contract. Chose the `@AutoConfiguration(after = ...)` form over `@Primary` because `@Primary` would leave both beans registered (misleading) and silently defeat the documented "missing bean" override seam; the ordering fix makes `@ConditionalOnMissingBean` actually work as documented.
- **Verification:** `set -a; source .env; set +a; ./gradlew :jmix-app:bootRun` — the two-bean `NoUniqueBeanDefinitionException` on `vectorStore` no longer occurs. Context refresh now fails on an unrelated `MetaClass not found for class com.vn.agent.entity.AiConversation` during Jmix's `sec_AnnotatedResourceRoleProvider` — this is a distinct issue in a different module and outside this session's scope.
- **Follow-up (not in scope):**
  1. Strengthen `EmbeddingModelBeanCollisionTest` with a second scenario that keeps `OpenAiEmbeddingAutoConfiguration` enabled (no stub) and asserts the OpenAI bean is the unique survivor — this would catch future ordering regressions.
  2. Separately investigate the `AiConversation` `MetaClass not found` error exposed once the embedding fix cleared the way.
