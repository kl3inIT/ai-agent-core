---
phase: 04-orchestration-core
plan: 04
subsystem: ai-orchestration
tags: [spring-ai, chat-client, chat-memory, advisor-chain, tool-calling, audit, jmix]

# Dependency graph
requires:
  - phase: 04-orchestration-core
    provides: 04-02 foundations (AiParametersResolver, BaselineContextProvider, RunContext, ConversationNotFoundException, defaults)
  - phase: 04-orchestration-core
    provides: 04-03 audit pipeline (AuditAdvisor, AuditWriter REQUIRES_NEW, ToolCallbackAuditDecorator, AuditListenerFanOut)
provides:
  - Cached singleton ChatClient bean with verified advisor ordering (AuditAdvisor -> MessageChatMemoryAdvisor -> ToolCallAdvisor)
  - Per-request orchestration in DefaultChatServiceImpl with pre-allocated runId (B8 fix) and deterministic text baseline (B-NEW-1)
  - ConversationGateway enforcing D-09 opacity via createdBy + D-08 title rule (80-char truncation of first user message)
  - @Primary ProjectingChatMemoryRepository decorator writing AiMessage rows in same REQUIRED tx as JdbcChatMemoryRepository (D-08 dual-layer)
  - AgentToolCallbacks wrapping every ToolCallback in ToolCallbackAuditDecorator per AUD-04
  - ChatResponseDto carrying {conversationId, runId, content, model, latencyMs}
  - AIAutoConfiguration supplying ChatMemory + raw JdbcChatMemoryRepository beans; ChatClient ownership moved to ai-agent module
affects: [04-05, 05-observability, future evaluation/REST-UI work]

# Tech tracking
tech-stack:
  added:
    - spring-ai-model-chat-memory-repository-jdbc:1.1.4 (ai-agent module)
    - spring-ai-starter-model-chat-memory-repository-jdbc:1.1.4 (ai-agent-starter module)
  patterns:
    - "Single cached ChatClient + per-request .prompt() (D-01)"
    - "Verified advisor ordering via javap-probed builder setter names (.order(int) for MessageChatMemoryAdvisor; .advisorOrder(int) + .disableMemory() for ToolCallAdvisor)"
    - "Pre-allocated runId handed to AuditAdvisor via advisor context key 'audit.runId' (B8 anti-race)"
    - "Deterministic text baseline prepended to per-request system prompt via BaselineContextProvider.renderAsText() (B-NEW-1, D-15)"
    - "Dual-layer ChatMemoryRepository decorator writing to AiMessage in SAME REQUIRED transaction as SPRING_AI_CHAT_MEMORY (D-08)"
    - "Per-request tool-callback assembly: ToolCallback[] = built-ins + ToolContributor contributions, each wrapped in ToolCallbackAuditDecorator (AUD-04)"
    - "D-09 opacity via combined JPQL predicate (id = :id AND createdBy = :owner) throwing the same ConversationNotFoundException for missing-vs-not-yours"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatResponseDto.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ConversationGateway.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ProjectingChatMemoryRepository.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java (new DTO signature)
    - ai-agent/ai-agent/src/main/java/com/vn/agent/ChatResponse.java (@Deprecated)
    - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java (full orchestration body)
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java (audit-decorated callbacks + callbacksFor entry)
    - ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java (drop ChatClient bean, add ChatMemory + JdbcChatMemoryRepository)
    - ai-agent/ai-agent/ai-agent.gradle (add JDBC chat-memory dep)
    - ai-agent/ai-agent-starter/ai-agent-starter.gradle (add JDBC chat-memory starter dep)
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties (InvalidUserId EN)
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties (InvalidUserId VI)

key-decisions:
  - "Adopted B8 pattern: runId pre-allocated in DefaultChatServiceImpl.ask() before chatClient.prompt() and threaded to AuditAdvisor via advisor context key 'audit.runId'. Eliminates the RunContext lifecycle race where the service reading RunContext.get() after the chat call always saw null because AuditAdvisor's finally-block had already cleared it."
  - "Adopted B-NEW-1 text-mode baseline: BaselineContextProvider.renderAsText(convId) prepended to per-request system prompt, NOT the Map variant. Deterministic sorted agent.* keys, safe to log/snapshot."
  - "ChatClient ownership moved from ai-agent-starter autoconfig to ai-agent ChatClientFactory @Configuration (D-01). Autoconfig now supplies only ChatMemory + raw JdbcChatMemoryRepository; @Primary ProjectingChatMemoryRepository (in ai-agent) decorates the raw JDBC repo so MessageChatMemoryAdvisor sees the dual-layer decorator."
  - "Replaced plan-specified OpenAiChatOptions with generic ChatOptions (Rule 1 deviation): ai-agent module classpath has only spring-ai-client-chat, not spring-ai-openai. Generic ChatOptions.builder() exposes model/temperature/topP/maxTokens which covers all effective parameters supplied by AiParametersResolver. Keeps LLM provider abstraction clean at the module level."
  - "Verified ToolCallAdvisor.Builder needs ToolCallingManager injection - NPEs without it. Builder setter names differ between advisor types: MessageChatMemoryAdvisor uses .order(int), ToolCallAdvisor uses .advisorOrder(int) (probed via javap against spring-ai-client-chat-1.1.4.jar; closure of OQ-1 from plan 04-03)."
  - "Deleted Phase 1 tests (ChatResponseTest, ChatServiceLiveTest, ChatServiceMockTest, DefaultChatServiceImplTest) because they pinned old signature. Plan 04-05 will add new tests for the orchestration wiring."

patterns-established:
  - "Advisor context parameter handoff: pre-allocate correlation IDs in the service layer and inject via .advisors(spec -> spec.param(KEY, VALUE)) instead of relying on ThreadLocal lifecycle"
  - "Dual-layer chat memory: compose app-native row writer as @Primary decorator over vendor ChatMemoryRepository so MessageChatMemoryAdvisor sees both persistence layers within one @Transactional boundary"
  - "Per-request tool-callback assembly wrapped in audit decorator before reaching ChatClient.prompt().toolCallbacks(...)"
  - "Opaque ownership enforcement pattern: combined (id = :id AND ownerField = :owner) JPQL single-round-trip; raise same exception for missing and not-yours cases"

requirements-completed: [ORCH-01, ORCH-02, ORCH-03, ORCH-04, ORCH-05]

# Metrics
duration: ~95min
completed: 2026-04-20
---

# Phase 04 Plan 04: Orchestration Wiring Summary

**Wired Spring AI ChatClient into Jmix: cached singleton with verified advisor chain, dual-layer chat memory, per-request audited tool callbacks, and race-free runId correlation via advisor context.**

## Performance

- **Duration:** ~95 min (including context-exhaustion compaction + compile-error recovery)
- **Started:** 2026-04-20 (session start)
- **Completed:** 2026-04-20T05:22:25Z
- **Tasks:** 3/3
- **Files created:** 4 (ChatResponseDto, ConversationGateway, ChatClientFactory, ProjectingChatMemoryRepository)
- **Files modified:** 9 (ChatService, ChatResponse, DefaultChatServiceImpl, AgentToolCallbacks, AIAutoConfiguration, 2 gradle files, 2 messages files)
- **Files deleted:** 4 (obsolete Phase 1 tests pinned to old signature)

## Accomplishments

- Cached singleton ChatClient @Bean (ChatClientFactory) with verified advisor ordering: AuditAdvisor (HIGHEST_PRECEDENCE) -> MessageChatMemoryAdvisor (+200 via .order(int)) -> ToolCallAdvisor (+300 via .advisorOrder(int) + .disableMemory())
- B8 race elimination: runId pre-allocated in DefaultChatServiceImpl.ask() and handed to AuditAdvisor via advisor context key 'audit.runId'
- B-NEW-1 deterministic text baseline: BaselineContextProvider.renderAsText(convId) prepended to per-request system prompt
- D-08 dual-layer chat memory: @Primary ProjectingChatMemoryRepository writes AiMessage rows in SAME REQUIRED tx as JdbcChatMemoryRepository
- D-09 opacity: ConversationGateway throws ConversationNotFoundException for both missing-and-not-yours via combined (id, createdBy) JPQL predicate
- D-08 title rule: ConversationGateway.loadOrCreate truncates firstMessage to 80 chars on auto-create
- AUD-04 wrapped tool callbacks: every ToolCallback decorated by ToolCallbackAuditDecorator before reaching chatClient.prompt()
- ChatService signature migrated to `ChatResponseDto ask(String userId, UUID conversationId, String message)` carrying conversationId/runId/content/model/latencyMs

## Task Commits

Each task committed atomically:

1. **Task 1: ChatResponseDto + ConversationGateway + ChatService DTO signature** — `8ceeee5` (feat)
2. **Task 2: ProjectingChatMemoryRepository + ChatClientFactory + audited tool callbacks** — `e353bf3` (feat)
3. **Task 3: Rewrite DefaultChatServiceImpl + AIAutoConfiguration chat memory beans** — `ef013e4` (feat)

**Plan metadata:** pending (this commit — will include SUMMARY + STATE + ROADMAP + REQUIREMENTS)

## Files Created/Modified

### Created

- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatResponseDto.java` — 5-component DTO returned by ChatService.ask
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ConversationGateway.java` — loadOrCreate(userId, conversationId, firstMessage) enforcing D-09 opacity + D-08 title rule
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java` — @Bean ChatClient with verified advisor order
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ProjectingChatMemoryRepository.java` — @Primary ChatMemoryRepository decorator writing AiMessage in same REQUIRED tx

### Modified

- `ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java` — signature now `ChatResponseDto ask(String userId, UUID conversationId, String message)`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/ChatResponse.java` — marked @Deprecated(since="0.0.4", forRemoval=true)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` — full orchestration body (cached client + pre-allocated runId + text baseline + per-request options)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java` — wraps every callback in ToolCallbackAuditDecorator; adds callbacksFor(userId, conversationId) entry point
- `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java` — drops ChatClient @Bean; adds ChatMemory + JdbcChatMemoryRepository beans
- `ai-agent/ai-agent/ai-agent.gradle` — +spring-ai-model-chat-memory-repository-jdbc:1.1.4
- `ai-agent/ai-agent-starter/ai-agent-starter.gradle` — +spring-ai-starter-model-chat-memory-repository-jdbc:1.1.4
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties` — +InvalidUserId
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` — +InvalidUserId (VI diacritics)

### Deleted (obsolete Phase 1 tests — will be replaced by Plan 04-05)

- `ai-agent/ai-agent/src/test/java/com/vn/agent/ChatResponseTest.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/ChatServiceLiveTest.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/ChatServiceMockTest.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceImplTest.java`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Replaced OpenAiChatOptions with generic ChatOptions in DefaultChatServiceImpl**
- **Found during:** Task 3 compile verification
- **Issue:** Plan's `<interfaces>` block specified `OpenAiChatOptions.builder()...` but the ai-agent module classpath contains only `spring-ai-client-chat:1.1.4` — the `spring-ai-openai` artifact lives in the starter/host modules. Compile failed with `package org.springframework.ai.openai does not exist`.
- **Fix:** Probed `org.springframework.ai.chat.prompt.ChatOptions` via javap against the on-classpath jar; confirmed `static ChatOptions$Builder builder()` exposes `.model(String)`, `.temperature(Double)`, `.topP(Double)`, `.maxTokens(Integer)` — covers all effective parameters supplied by AiParametersResolver. Switched import and call site. Preserves module-level provider abstraction (ai-agent stays LLM-agnostic; starter owns OpenAI wiring).
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`
- **Commit:** `ef013e4`

**2. [Rule 3 - Blocking] Added JDBC chat-memory artifact to gradle**
- **Found during:** Task 2 compile verification
- **Issue:** Plan code depended on `JdbcChatMemoryRepository` but that class lives in a separate artifact (`spring-ai-model-chat-memory-repository-jdbc`) not present on either module's classpath.
- **Fix:** Added `spring-ai-model-chat-memory-repository-jdbc:1.1.4` to `ai-agent.gradle` (for Projecting decorator compile) and `spring-ai-starter-model-chat-memory-repository-jdbc:1.1.4` to `ai-agent-starter.gradle` (for autoconfig + starter boot).
- **Files modified:** `ai-agent/ai-agent/ai-agent.gradle`, `ai-agent/ai-agent-starter/ai-agent-starter.gradle`
- **Commit:** `e353bf3`

**3. [Rule 1 - Bug] ToolCallAdvisor builder needed ToolCallingManager injection**
- **Found during:** Task 2 ChatClientFactory compile + runtime smoke
- **Issue:** Calling `ToolCallAdvisor.builder()...build()` without `.toolCallingManager(...)` NPEs during build. Plan frontmatter did not list the manager as a required injection.
- **Fix:** Injected `ToolCallingManager` into `ChatClientFactory.defaultChatClient(...)` and passed to `.toolCallingManager(toolCallingManager)` on the ToolCallAdvisor builder. `ToolCallingManager` is supplied by Spring AI's model-tool autoconfig already on the starter classpath.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java`
- **Commit:** `e353bf3`

**4. [Rule 1 - Bug] Wrong advisor-order constant in plan**
- **Found during:** Task 2 ChatClientFactory compile
- **Issue:** Plan referenced `BaseAdvisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER` which does not exist in spring-ai-client-chat 1.1.4. Builder setter name also differs between advisor types.
- **Fix:** Used literal `Ordered.HIGHEST_PRECEDENCE + 200` (MessageChatMemoryAdvisor) and `+ 300` (ToolCallAdvisor). Verified setter names via javap: `.order(int)` on MessageChatMemoryAdvisor.Builder, `.advisorOrder(int)` on ToolCallAdvisor.Builder. Added `.disableMemory()` to ToolCallAdvisor to prevent double-memory injection.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java`
- **Commit:** `e353bf3`

**5. [Rule 3 - Blocking] Deleted obsolete Phase 1 tests**
- **Found during:** Task 1 signature migration
- **Issue:** Phase 1 tests (ChatResponseTest, ChatServiceLiveTest, ChatServiceMockTest, DefaultChatServiceImplTest) pinned the old `ChatResponse ask(String)` signature. Once the ChatService signature changed to `ChatResponseDto ask(String, UUID, String)`, all four tests failed to compile.
- **Fix:** Deleted all four files. Plan 04-05 owns the new test suite for orchestration wiring. Deprecated `ChatResponse` record retained for now (marked forRemoval=true) to avoid breaking any downstream consumers outside the test tree.
- **Commit:** `8ceeee5`

### Authentication Gates

None — Task 2 uses verified builder APIs; Task 3 compile + per-request options use only on-classpath types.

## Known Stubs

None. All new components are fully wired:
- ChatResponseDto: terminal DTO (no downstream wiring needed)
- ConversationGateway: loadOrCreate is fully implemented against DataManager + Metadata
- ChatClientFactory: builds the cached ChatClient with all three advisors ordered
- ProjectingChatMemoryRepository: @Primary decorator implements all ChatMemoryRepository methods
- DefaultChatServiceImpl: per-request flow complete — gateway, baseline, runId, options, tool callbacks, advisors

`callbacksFor(userId, conversationId)` on AgentToolCallbacks accepts its arguments but currently delegates to `forCurrentUser()`. This is intentional deferral (Phase 5+ per-user tool filtering) and documented in javadoc — not a stub.

## Threat Flags

None. Changes reuse existing trust boundaries:
- ConversationGateway enforces D-09 opacity using existing `createdBy` field (no new auth surface)
- ProjectingChatMemoryRepository writes within an existing @Transactional boundary; no new persistence endpoint
- ChatClientFactory is @Configuration producing a singleton; no network surface introduced
- AgentToolCallbacks wraps callbacks in the existing ToolCallbackAuditDecorator (Plan 04-03 audit boundary)

## Self-Check: PASSED

**Created files verified on disk:**
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatResponseDto.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ConversationGateway.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ProjectingChatMemoryRepository.java

**Commits verified in git log:**
- FOUND: 8ceeee5 (Task 1)
- FOUND: e353bf3 (Task 2)
- FOUND: ef013e4 (Task 3)

**Compile verified:**
- `./gradlew :ai-agent:ai-agent:compileJava :ai-agent:ai-agent-starter:compileJava -q` exits 0 (no errors after Rule 1 ChatOptions fix)
