# Phase 4: Orchestration Core - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in 04-CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-20
**Phase:** 04-orchestration-core
**Areas discussed:** Advisor chain + ChatClientFactory, Dual-layer persistence, Audit scope + REQUIRES_NEW, Listener fan-out + streaming + ownership

---

## Area Selection

| Option | Description | Selected |
|--------|-------------|----------|
| Advisor chain + ChatClientFactory | Ordering, per-request vs cached, ChatOptions overrides | ✓ |
| Dual-layer persistence | ConversationProjector vs JdbcChatMemoryRepository | ✓ |
| Audit scope & REQUIRES_NEW | Chat-level vs per-tool, REQUIRES_NEW mechanics | ✓ |
| Listener fan-out + streaming posture | AuditListener sync/async, streaming in Phase 4 or 7 | ✓ |

---

## Advisor chain + ChatClientFactory

### Q1: ChatClientFactory shape — built once at app start, or per request?

| Option | Description | Selected |
|--------|-------------|----------|
| Cached builder, per-request `.prompt()` | Stable advisors, tools + ChatOptions vary per request | ✓ |
| Fully per-request builder | Rebuild builder every ask() | |
| Builder per AiParameters profile, cached | Cache builder per profile id | |

**Rationale:** Tools vary per request (Phase 3 D-10), ChatOptions vary per active profile — advisors are stable. Cached builder is the Spring AI idiomatic shape.

### Q2: Advisor order

| Option | Description | Selected |
|--------|-------------|----------|
| Audit → Memory → Tool | Audit outermost; RAG slots between Memory and Tool in Phase 5 | ✓ |
| Memory → Audit → Tool | Audit misses memory-load errors | |
| Audit → Tool → Memory | Memory innermost — non-idiomatic | |

**Rationale:** AuditAdvisor as HIGHEST_PRECEDENCE sees final request+response and true latency. MessageChatMemoryAdvisor before ToolCallAdvisor so tool calls see prior context.

### Q3: ChatOptions override mechanics per request

| Option | Description | Selected |
|--------|-------------|----------|
| Resolve AiParameters → build ChatOptions → `.options(...)` | Per-request ChatOptions from active profile | ✓ |
| Bake everything into cached builder + rebuild on profile edit | Event-driven builder rebuild | |
| Hardcoded default profile, Phase 6 adds overrides | Defer profile system | |

### Q4: How does Phase 4 read the active profile (ParametersService is Phase 6)?

| Option | Description | Selected |
|--------|-------------|----------|
| DataManager lookup with `@ConfigurationProperties` fallback | Phase 6 only adds bootstrap row; lookup unchanged | ✓ |
| Phase 4 seeds a single default row at startup | Two bootstrap paths temporarily | |
| Skip AiParameters — application.yml only | Deliverable "model per AiParameters" slips | |

---

## Dual-layer persistence

### Q5: Where does ConversationProjector hook?

| Option | Description | Selected |
|--------|-------------|----------|
| Decorator around JdbcChatMemoryRepository | Single write path through the decorator | ✓ |
| Dedicated projection advisor | Two persist paths to sync | |
| ChatService-level explicit write | Bypasses advisor retries/error paths | |

### Q6: Content duplication — what goes in AiMessage?

| Option | Description | Selected |
|--------|-------------|----------|
| Full content in AiMessage too | Jmix-queryable, independent of Spring AI schema changes | ✓ |
| AiMessage metadata only, content by reference | Couples UI to Spring AI internal schema | |
| AiMessage summary only (role, counts, timestamp) | UI needs to read Spring AI table directly | |

### Q7: Transactional boundary

| Option | Description | Selected |
|--------|-------------|----------|
| Same transaction, REQUIRED propagation | Failed projection rolls back memory write | ✓ |
| REQUIRES_NEW for projection | Memory/domain rows can drift on failure | |

### Q8: AiConversation lifecycle — when is the row created?

| Option | Description | Selected |
|--------|-------------|----------|
| ChatService.ask() creates on first message if convId is new | Service-level entry point; first AiMessage in same tx | ✓ |
| Decorator auto-creates on first ChatMemory add() | Conversation metadata must come from memory context only | |
| Explicit createConversation() API before ask() | Two-step client API | |

---

## Audit scope + REQUIRES_NEW

### Q9: How many audit layers and where do they write?

| Option | Description | Selected |
|--------|-------------|----------|
| Two layers: chat-level AuditAdvisor + per-tool-call callback wrapper | Clean separation; kind=CHAT vs kind=TOOL rows | ✓ |
| Single AuditAdvisor walks response afterwards | Tighter coupling to Spring AI internals; data may be stale on rollback | |
| Two advisors (chat-level + tool-level) | Tool interception canonically at ToolCallback, not advisor | |

### Q10: REQUIRES_NEW placement

| Option | Description | Selected |
|--------|-------------|----------|
| On AuditWriter bean's methods only | Smallest blast radius; only audit-write tx is isolated | ✓ |
| On AuditAdvisor + tool-wrapper methods themselves | Business logic also runs in new tx — changes DataManager semantics downstream | |

### Q11: Chat-level audit content

| Option | Description | Selected |
|--------|-------------|----------|
| Pre: user/convId/promptHash/ts — Post: same + outcome/latency/errorClass | Two rows linked by runId; no content duplication with AiMessage | ✓ |
| Single post-only row with full round-trip | Loses "request received but never returned" auditability | |
| Full prompt + response snapshot in audit rows | Storage + redaction surface grows | |

---

## Listener fan-out + streaming + ownership

### Q12: AuditListener fan-out semantics

| Option | Description | Selected |
|--------|-------------|----------|
| Synchronous, same-thread, per-listener try/catch | Deterministic, no executor ownership, simple isolation | ✓ |
| ApplicationEventPublisher + @EventListener | Hides the async contract from hosts | |
| @Async on dedicated executor | Ordering nondeterminism, executor lifecycle | |

### Q13: Listener trigger timing

| Option | Description | Selected |
|--------|-------------|----------|
| afterCommit hook on the REQUIRES_NEW audit tx | Only fires on persisted rows | ✓ |
| Inside the audit-write method, before commit | Listener fires on rows that may roll back | |

### Q14: Streaming posture for Phase 4

| Option | Description | Selected |
|--------|-------------|----------|
| Defer streaming to Phase 7 | Blocking ask() only; UI lands streaming with Vaadin push | ✓ |
| Ship streaming primitive here | Larger Phase 4; ToolCallAdvisor + streaming not fully settled in M4 | |

### Q15: Ownership enforcement point

| Option | Description | Selected |
|--------|-------------|----------|
| Explicit pre-check in ChatService + DataManager row-level (defence in depth) | ConversationNotFoundException uniform for "not yours" and "doesn't exist" | ✓ |
| DataManager row-level only, no explicit check | Error type leaks through raw DataManager exception | |

---

## Claude's Discretion

- Internal decomposition of `ChatClientFactory` (private helpers, builder caching strategy beyond "built at startup")
- Bean names and exact package placement within `com.vn.agent.orchestration`
- Mechanism for registering the `MethodToolCallback` decorator (BeanPostProcessor vs `ToolCallbackProvider` decorator vs fluent `.tools(...)` assembly wrapper)
- Exact title-derivation for new conversations beyond "first user message, ~80 chars"
- promptHash algorithm (SHA-256 assumed; planner may adjust if inappropriate)

## Deferred Ideas

- Streaming (Flux / Vaadin push) — Phase 7
- Active-profile change event + builder rebuild — Phase 6
- Tool-result caching across turns — post-v1
- Listener execution-order guarantees — until a host surfaces a concrete need
- Async / queue-backed audit writes — revisit on load evidence
- Audit content redaction hooks — promptHash sidesteps for v1
