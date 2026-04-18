# Feature Research

**Domain:** Enterprise AI Copilot / "Chat-with-your-data" add-on for Jmix 2.8 apps
**Researched:** 2026-04-18
**Confidence:** MEDIUM-HIGH
  - HIGH on table-stakes and anti-feature classification (patterns well-established across Glean, ChatGPT Enterprise, Microsoft 365 Copilot, Perplexity Enterprise, Danswer/Onyx, OpenWebUI, LibreChat, Dify, and the reference `jmix-ai-backend`)
  - MEDIUM on Jmix-specific fit (exposure policy, DataManager-bound tools, Flow UI shape) — extrapolated from PROJECT.md and reference, not from shipped product validation

## Orientation

This product is **not a consumer chatbot** and **not a generic agent framework**. It's a governed, metadata-driven Q&A layer over a Jmix app's structured data and uploaded documents. That framing drives ruthless classification below: features that matter for ChatGPT.com are often anti-features here (personas, image generation, plugin marketplaces), while features enterprises treat as optional in consumer tools (audit, exposure policy, role-gated admin) are **table stakes**.

The reference project `jmix-ai-backend` already ships a credible admin UI shape (Chat / Parameters / VectorStore / Answer checks / Ingesters / Reranker / Post-retrieval filtering). That sets the floor — we must at least match it, generalized to arbitrary host entities.

---

## Feature Landscape

### 1. End-User Chat Experience

#### Table Stakes

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Multi-turn conversation with persistent history | Every LLM product since ChatGPT does this; single-turn feels broken | S | `MessageChatMemoryAdvisor` + JDBC-backed `ChatMemoryRepository`; Jmix JPA entity `AiConversation` + `AiMessage` |
| Streaming responses (token-by-token) | Sub-second first-token is the baseline UX; non-streaming feels frozen on 10+ second answers | M | Spring AI `ChatClient.stream()`; Vaadin Flow requires `UI.access()` + `@Push` for server-to-client streaming |
| Tool-call transparency ("searching records...", "reading document X") | Without it, users distrust the answer and can't spot when the agent guessed | M | Render tool-invocation events in-stream; intercept via `ToolCallback`/`ToolCallAdvisor` and emit UI events |
| Source citations for RAG answers (clickable, with snippet) | Enterprises will not trust un-cited answers on their own docs; regulatory/legal use demands provenance | M | Track retrieved `Document` metadata through advisor chain; render as numbered footnotes in Chat view |
| Stop generation button | Users kill runaway/wrong answers constantly; missing this is visibly bad | S | Cancel the reactive stream subscription |
| Copy answer to clipboard | Utility baseline; trivial to implement, jarring when absent | S | Native browser clipboard API |
| "New conversation" button | Context-bleed between unrelated questions is a known pain; fresh-start must be one click | S | Reset conversation ID and memory |
| Error surfacing (model timeout, tool failure, quota exceeded) | Silent failures are the #1 complaint in internal copilot rollouts | S | Distinct error bubbles with retry action; never swallow exceptions |

#### Differentiators

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Tool-result inspection (click a tool call → see JSON args + result rows) | Power users and admins can self-diagnose wrong answers; turns the chat into a debuggable artifact | M | The `AiToolCallAudit` entity is already planned — surface it inline in chat for admin users |
| Conversation replay from audit (admin opens a past conversation in read-only mode) | Support/ops workflow: "show me what the user saw when they complained" | M | Already in MVP scope per PROJECT.md — this is our table-stake-as-differentiator |
| Thumbs up/down with free-text reason, piped to audit | Feedback corpus for later answer-quality tuning and answer-checks seed data | S | Cheap to add, high optional-value payoff later |
| Inline entity previews (tool returns a `Customer` → render as Jmix entity link/card, not raw JSON) | Native Jmix-look feel; user can click through to the record's detail view | M | Hook into Jmix `MetadataTools.getInstanceName` and `ViewNavigators` |
| Suggested follow-up questions | Lowers cold-start friction; cheap via a second tiny LLM call | S | Common in Perplexity/Glean; nice-to-have |
| Regenerate with different model | Lets users compare provider quality without leaving chat | S | Already have per-conversation model override via `ChatOptions` |

#### Anti-Features

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Personas / character cards | Familiar from ChatGPT | Off-brand for enterprise data Q&A; invites jailbreak-style prompting; nothing to validate the persona's answers against | One system prompt per Parameters profile; let admins fork a profile instead |
| Image generation / DALL-E-style output | "Our AI should do everything" | Out of scope; no business data value; huge compliance surface (IP, NSFW) | Explicitly out of scope in docs |
| Voice input/output | Showy demo feature | Flow UI doesn't ship this natively; browser compatibility hell; no enterprise demand for copilot-over-CRM | Defer indefinitely |
| Agent-to-agent collaboration / autonomous multi-step loops | AI hype cycle | PROJECT.md explicitly says no — loops amplify hallucinations, tool-call blast radius, and cost; unsafe on mutation tools | Single-turn tool-call + RAG is MVP. Autonomous loops need separate safety story |
| Plugin marketplace | Copying ChatGPT | We are the plugin — the host app extends via SPI, not end-users via a store | SPI for hosts; no runtime plugin install |
| Prompt template library shared across all users | "Users want reusable prompts" | Becomes an ungoverned shadow knowledge base; one bad prompt poisons N users | If desired, ship as a Parameters-profile feature (admin-curated) |
| Conversation sharing via public link | Copying Perplexity / ChatGPT | Data exfiltration vector; the whole point is that conversations are over private host data | Export to PDF/audit trail only; never public URL |

---

### 2. Knowledge Base Management

#### Table Stakes

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Upload documents (PDF, MD, TXT, HTML) via admin UI | Baseline for any "chat with your docs" product; matches PROJECT.md MVP scope | M | Spring AI `TikaDocumentReader` covers all four formats; Vaadin `Upload` component |
| List / delete documents | Cannot manage a KB without these | S | Standard Jmix list view over an `AiDocument` entity |
| Re-ingest single document | Docs change; admins need to update without wiping the store | S | Delete-then-reingest by document ID in metadata |
| Ingestion status / progress (queued, processing, indexed, failed) | Async embedding runs take seconds-to-minutes; silent pipelines get reported as bugs | M | Status column on `AiDocument`; update via `@Async` ingester |
| Chunking with sensible defaults | Users should not have to tune this to get a working demo | S | Spring AI `TokenTextSplitter` with conservative defaults (512 tokens, 50 overlap) |
| Document source attribution in retrievals | Tied to citation table-stake in section 1 — metadata must survive chunking | S | Store `source`, `document_id`, `page/section` in chunk metadata |
| Search documents in admin UI (by filename, by metadata filter, by content similarity) | Reference project has this; ops teams use it to debug "why didn't retrieval find X" | M | Matches VectorStore admin view in `jmix-ai-backend` |

#### Differentiators

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Per-document metadata tags set at upload (department, tenant, sensitivity) used for retrieval filters | Lets one KB serve multiple audiences safely | M | Spring AI `VectorStore` supports metadata filter expressions; surface as a metadata editor in upload dialog |
| Chunking-strategy picker per document type | A 300-page policy PDF and a 1-page FAQ want different chunk sizes | M | Small SPI: `TextSplitterProvider` selects by MIME/extension |
| Post-retrieval filtering hook (Groovy or Java SPI) | Copied from `jmix-ai-backend`; lets ops drop stale/irrelevant docs without reingest | M | Already a known-working pattern in the reference |
| Reranker (second-pass relevance scoring before LLM sees chunks) | Noticeable quality lift on ambiguous queries; matches reference | M | OpenAI-model-based reranker in reference is cheap; consider a cross-encoder later |
| Document versioning (upload v2, keep v1 accessible, retrieval prefers latest by default) | Policy docs change; audits need "what was true on date X" | L | Nice-to-have; defer past MVP |

#### Anti-Features

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Auto-ingesting host entity records into the vector store | "The agent should search everything" | PROJECT.md explicitly out of scope — freshness race vs. DB, security bypass risk (chunks in vector store won't respect row-level Jmix security), duplication | Structured data via `DataManager` tools. Vector store = unstructured docs only |
| Web/URL crawling in v1 | Obvious next step | Auth on gated pages, robots/ToS, crawl scheduling, dead-link cleanup — full subsystem; PROJECT.md defers | File upload covers enterprise document flows; SPI `Ingester` allows hosts to add URL ingesters if needed |
| Auto-OCR of scanned PDFs | "Some of our PDFs are images" | Tika + Tesseract adds a native dep, 10x processing time, and error modes we can't QA | Require text-extractable PDFs in v1; document the limit; add as opt-in ingester later |
| Cross-language translation at ingestion | "Index Spanish docs as English" | Introduces translation quality as a second failure mode; embedding models are multilingual already | Use multilingual embedding model; let the LLM handle cross-language Q&A |
| In-place document editing via UI | "Make it a CMS" | Scope explosion; contradicts source-of-truth principle | Edit in source system, re-upload |

---

### 3. Agent Configuration

#### Table Stakes

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| System prompt editable by admin | Every deployment needs tone/guardrail tuning; hard-coding system prompt is a non-starter for a reusable add-on | S | `Parameters.systemPrompt` field |
| Model selection (provider + model name) | Enterprises negotiate LLM contracts; must be able to point at OpenAI, Azure, OpenRouter, on-prem | S | `ChatOptions` per request; provider via bean swap |
| Temperature / top-p / max tokens | Universally expected knobs | S | Standard `ChatOptions` fields |
| Enabled-tool toggles | Host may want to disable `get_related_records` for deployment X | S | Boolean columns on Parameters; filter tool list at ChatClient build time |
| Multiple Parameters profiles, one active | Reference project shows this pattern; A/B testing profiles is how admins validate changes safely | M | Matches `jmix-ai-backend`; Jmix entity + "active" flag with single-active constraint |

#### Differentiators

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Per-conversation model override (admin/power user) | Cost vs. quality tradeoff on the fly; debug "does GPT-4 get this right?" | S | `ChatOptions` per request |
| YAML-based Parameters configuration (like reference project) | Version-controllable; copy-paste between envs; admins can import/export | M | Reference has this exact pattern; adopt it wholesale |
| Prompt/instruction contributors SPI (host augments system prompt with tenant/user context) | Per-PROJECT.md SPI list; lets host inject "current user is a sales manager at Tenant X" | M | Called during ChatClient build; composes additively |
| Context-contributor SPI (inject current user, locale, timezone) | Removes a whole class of "the AI doesn't know who I am" complaints | S | PROJECT.md SPI; low-cost high-value |
| Guardrails / forbidden-topic config | "Never answer questions about employee salaries" | M | Input advisor that short-circuits on keyword/classifier hit; avoid rolling our own — use Spring AI `SafeGuardAdvisor` if present in 2.0.0-M4 |

#### Anti-Features

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| End-user access to temperature/model sliders | "Give users control" | 99% of users will misconfigure and complain; support nightmare; breaks reproducibility of audit | Admin-owned Parameters profiles; users pick a named profile at most |
| GUI prompt-chain builder (LangFlow-style) | "Let admins design agents" | We are not an agent-building platform; contradicts PROJECT.md positioning; huge UX surface | Java/Kotlin SPI for hosts that need custom flows |
| Fine-tuning management UI | "Our data is special" | Orders of magnitude more complex than stated; cost/quality rarely beats RAG; ongoing MLOps burden | RAG + better prompts first; fine-tuning is a separate product |
| Memory/"remember this about me" per-user long-term memory | ChatGPT Memory feature | Privacy minefield in enterprise, state drift, hard to invalidate, no clean GDPR story for v1 | Conversation-scoped memory only; context contributors for per-user facts sourced from host DB |

---

### 4. Observability & Audit

#### Table Stakes

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Persistent tool-call audit (who, when, tool, args, result, duration) | Compliance requirement in any serious enterprise rollout; also the primary debug tool | M | Already MVP scope: `AiToolCallAudit` Jmix JPA entity via DataManager |
| Conversation log (messages, citations, latency) | Ops need "show me what actually happened in conversation X" | M | Tied to memory persistence; reuse storage |
| Searchable/filterable audit view | Unsearchable logs are functionally useless at scale | S | Standard Jmix list view with filter |
| Token counting per message and per conversation | Cost attribution and abuse detection; enterprises demand this | S | `Generation.metadata` from Spring AI carries token usage; persist on each turn |
| Error logging with enough context to reproduce | Standard observability | S | Use Spring Boot logging + store exception class + request ID in audit |

#### Differentiators

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Cost accounting (tokens × published $/1k → $ per user per day) | CFO-facing visibility that justifies or kills the rollout | M | Pricing table per model; compute on audit write |
| OpenTelemetry / Micrometer export for traces and metrics | Integrates with existing enterprise observability stacks (Grafana, Datadog) | M | Spring AI 2.0 ships with Micrometer observability — verify exact advisor wiring in Context7 before implementation |
| Answer-quality checks (reference-answer regression suite) | Copied from reference project; lets admins run "did our last Parameters change regress anything?" before deploying | L | Reference uses a second LLM as judge; well-scoped feature |
| Audit-listener SPI (push events to Slack/SIEM/Splunk) | PROJECT.md SPI; table stakes for regulated industries without being table stakes for the MVP itself | S | Simple `ApplicationEventPublisher` + documented event schema |
| Per-user / per-role usage dashboard | Shows adoption; surfaces who's hammering the system | M | Chart/KPI component in admin view; derive from audit table |

#### Anti-Features

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Storing raw LLM request/response blobs forever | "We might need them for audit" | Bloats the host DB fast; PII retention issues; most fields are reconstructable from structured audit | Structured `AiToolCallAudit` + conversation log; raw blobs are opt-in and retention-capped |
| Real-time "spy on user" admin view | "Security wants it" | Crosses ethical lines in most jurisdictions; easier to justify post-hoc audit | Audit replay is sufficient; no live mirroring |
| Custom DSL query language over audit | "We need power queries" | Jmix generic filter + CSV export covers 95% of cases; inventing a DSL is ceremony | Jmix filter component; document SQL-view-of-audit for advanced ops |

---

### 5. Safety & Governance

#### Table Stakes

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Jmix row/attribute security end-to-end on tool calls | **The single load-bearing safety claim of the product.** If a user without access to `Customer.email` can get it via chat, the product is dead in enterprise | M | Guaranteed by routing through `DataManager` with the authenticated user's `SystemAuthenticator`; must be integration-tested with negative cases |
| Entity exposure policy (allowlist/denylist layer on top of Jmix security) | Jmix security says "this user can read X"; exposure policy says "but the AI should not see X even for authorized users" (e.g., internal codes, audit tables) | M | PROJECT.md SPI; enforced at schema-generation time |
| Read-only default | PROJECT.md mandate; also the sane enterprise default | S | Ship no mutation tools enabled |
| Rate limits per user / per conversation | LLM cost abuse is real; even internal users accidentally loop | S | Token-bucket filter at `ChatController` level; configurable |
| Auditability of every tool invocation | Already listed as table stake in section 4; reiterating as a safety property — cannot be silently disabled | S | Wired at advisor level, not at tool level |

#### Differentiators

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Guard/policy-hook SPI (veto tool calls before execution) | PROJECT.md SPI; lets host enforce domain rules ("no queries on Q4 data before earnings") | M | Advisor that fires before `ToolCallAdvisor` |
| PII redaction in prompts and/or in audit | Required for HIPAA/PCI/GDPR conversations; even optional redaction is a selling point | L | Regex-based for v1 (emails, SSN patterns) is good enough for demo; real PII detection is its own research topic |
| Prompt-injection mitigation for RAG (mark retrieved content as untrusted, use structured delimiters, system-prompt hardening) | Jailbreak via uploaded doc is a known attack; enterprises now ask about it | M | Spring AI `PromptTemplate` with explicit untrusted-content delimiters; document mitigation pattern |
| Dry-run / confirmation for mutation tools (later phase) | When mutations are eventually enabled, this is table stakes at that point | L | Scaffolded in MVP per PROJECT.md; activated in a later phase |
| Output content safety classifier | Block obvious unsafe output | M | Provider-side moderation (OpenAI moderation API) — cheap if provider supports |

#### Anti-Features

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Custom "AI permissions" model parallel to Jmix roles | "We need special AI permissions" | Two parallel authz systems always drift apart; users end up with surprising access | Exposure policy *composes* with Jmix security; never replaces it |
| Self-hosted PII model in v1 | "Compliance needs it" | Model hosting, maintenance, false-positive tuning — whole subsystem | Regex + documented limits in v1; integrate Presidio/etc. as a post-v1 SPI |
| Jailbreak-proof guarantees | Marketing pressure | No LLM is jailbreak-proof. Promising this is a lawsuit | Document best-effort mitigations; make audit the ultimate safety net |

---

### 6. Extensibility (SPI)

This is the product's architectural moat — extensibility is the Jmix-add-on story. All listed in PROJECT.md.

#### Table Stakes

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Tool contributors (host adds `@Tool`-annotated beans) | Without this, add-on is just read-only — useless for host-specific domain logic | S | Spring AI `@Tool` + component scan; discover and register in ChatClient build |
| Context contributors (inject per-user/tenant/env context into prompts) | Universal need; without it the agent is blind to the caller | S | PROJECT.md SPI |
| Prompt/instruction contributors (augment system prompt per-deployment) | Hosts need domain voice without forking | S | PROJECT.md SPI; ordered list of instruction segments |
| Entity exposure policy (programmatic control of `MetaClass`/`MetaProperty` visibility) | Already covered in safety; also the extensibility story for "hide my audit tables from the AI" | M | PROJECT.md SPI; consulted during schema build |
| Audit/run listeners | Side-channel observability without coupling | S | PROJECT.md SPI; plain Spring events |

#### Differentiators

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Guard/policy hooks (veto tool calls) | Already in safety; from SPI angle, it's the enforcement point hosts use to express business rules | M | PROJECT.md SPI |
| Custom ingester SPI (host defines new document source types) | Lets hosts add URL crawlers, S3 ingesters, SharePoint connectors without forking | M | Interface matching reference `Ingester` pattern |
| Custom retriever / tool-result transformer SPI | Power hosts may want to pre-process retrieved docs or augment tool results | M | Nice-to-have; skip if Spring AI advisors cover the case |
| Custom post-retrieval filter SPI | Matches reference; lets hosts drop irrelevant chunks with their own logic | S | Reference uses Groovy; we can use plain Java SPI |

#### Anti-Features

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Runtime SPI hot-reload / plugin JARs dropped into a folder | "Make it dynamic" | Classloader hell; security review surface; no Spring-native story | Static compile-time SPI via Spring beans; redeploy to change behavior |
| Wrapping Spring AI `VectorStore` with our own interface | "More control" | PROJECT.md explicitly forbids custom vector-store abstractions; delays upstream upgrades | Use Spring AI `VectorStore` directly everywhere |
| Exposing Jmix internal APIs to SPIs | Convenience | PROJECT.md forbids; locks us out of upgrades | Public Jmix APIs only |

---

### 7. Admin UX

#### Table Stakes

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Role-gated admin views (Parameters / KB / Audit / Exposure) | MVP requires `AiAgentAdminRole`; unprotected admin views are a compliance fail | S | Already planned |
| Standard Jmix list+detail views with filter + paging for every entity | Users expect Jmix conventions in a Jmix app | S | Standard `StandardListView` / `StandardDetailView` |
| CSV/Excel export on audit and conversations | Compliance hands over audit data; without export, admins dump manually | S | Jmix `DataGridExporter` add-on or similar |
| `messages*.properties` for every label in every shipped locale | PROJECT.md + CLAUDE.md mandate; hardcoded UI text is forbidden | S | Ship `en`, `ru` (Jmix-native locales) minimum; document how to add more |
| Menu integration via `menu.xml` with sensible defaults | Plug-and-play claim fails if admin has to manually wire menu items | S | Ship menu.xml snippets in the flowui-starter |

#### Differentiators

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Exposure policy admin view (see the schema the AI currently sees, with a visual allow/deny editor) | Turns an abstract SPI into something ops can verify | M | High value for trust-building with security teams |
| "Test prompt" button on Parameters detail (runs a sample question with this profile, shows answer + latency + tokens) | Shortens the edit-test loop from minutes to seconds | M | Embed a mini-chat widget on the Parameters detail view |
| Health/diagnostic view (provider reachable? vector store reachable? last successful embedding? token quota?) | First question during any incident — "is it our side or theirs?" | M | Spring Boot Actuator + custom indicators; surface in a KPI screen |
| Per-user conversation browser (admin picks a user, sees all their conversations) | Support workflow; compliance investigation | S | Filter on conversations view |

#### Anti-Features

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Custom-themed "AI branded" chat widget different from host Jmix look | "Make it feel like a product" | Breaks the "plug into any Jmix app" promise; hosts want *their* brand | Use host theme; provide only minimal CSS hooks |
| Mobile-first responsive chat | "Our execs use phones" | Vaadin Flow is server-side; full mobile polish is a project, not a feature; rare for internal admin tools | Responsive-enough is fine; mobile app is a separate product |
| Embedded LLM-playground page (raw prompt/response, no RAG, no tools) | "For testing" | Tempts users to bypass all governance; creates an audit blind spot | "Test prompt" on Parameters with full pipeline only |

---

### 8. Enterprise Concerns

#### Table Stakes

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| SSO pass-through (respects host Jmix authentication) | Enterprises will not approve a product with a separate login; Jmix handles this but we must not break it | S | Don't introduce our own session; propagate `SecurityContext` to ChatController |
| Works on-prem with OpenAI-compatible endpoint (OpenRouter, vLLM, Ollama, Azure OpenAI) | Per PROJECT.md; matches the top-3 enterprise deployment asks | S | `base-url` + api-key config; already the reference pattern |
| Configurable via standard Spring Boot properties / Jmix app properties | Baseline for any Spring/Jmix add-on | S | `@ConfigurationProperties` classes |
| Data residency: all chat history, audit, and vectors stored in the host DB / pgvector instance | Enterprise data-residency compliance; shipping data to a third-party backend is a dealbreaker | S | Architecturally true if we use host DataSource for memory + audit, host's pgvector for vectors |

#### Differentiators

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Multi-tenancy readiness (per-tenant Parameters profile, per-tenant KB filter, per-tenant audit scope) | Jmix supports multi-tenancy; add-on should compose with it | M | Consume Jmix `TenantProvider`; inject tenant filter into exposure policy and vector-store filter |
| Full air-gap support documented and tested (no outbound calls except the configured LLM endpoint) | Regulated industries demand it | M | Audit the dep graph; document every outbound call; CI test with network blocked |
| Secrets management integration (Vault/AWS Secrets Manager for API keys) | Storing an API key in a Jmix `Parameters` row is not acceptable for regulated customers | S | `@Value` with Spring Cloud Config / Vault support documented; avoid storing secret in DB |
| Configurable data retention / audit pruning job | GDPR right-to-erasure; storage cost control | S | Jmix scheduler + configurable retention window |

#### Anti-Features

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Shipping an operated SaaS backend alongside the add-on | "Managed service" | We're an add-on, not a SaaS; operating shared infra contradicts data-residency selling point | Add-on only; host operates their own infra |
| Bundled provider API keys ("just works out of the box") | Onboarding friction reduction | Legal and cost nightmare; customers would assume it's free | Require key configuration; provide clear docs + free-tier provider suggestion |
| Telemetry / usage phone-home to the add-on vendor | "Understand adoption" | Enterprise security will block it by default; erodes trust | Opt-in only, off by default, fully documented; better: don't ship it |
| Claims of SOC2/ISO certification | Sales pressure | These certify organizations, not libraries | Document what the add-on does/doesn't do; let the customer certify their own deployment |

---

## Feature Dependencies

```
Metadata-first runtime (schema gen)
    └──required-by──> 6 generic tools
                          └──required-by──> Tool-call audit
                          └──required-by──> Chat view (tool transparency)

Exposure policy SPI
    └──filters──> Metadata-first runtime
    └──surfaced-by──> Exposure admin view (differentiator)

DataManager-bound tool execution
    └──required-by──> Jmix row/attr security on tool calls
    └──required-by──> Jmix-native entity previews in chat

ChatMemory (JDBC)
    └──required-by──> Multi-turn conversation
    └──required-by──> Conversation list + replay
    └──enables──────> Per-user conversation browser

Advisor-chain wiring (ChatClient + ToolCallAdvisor + RAG advisor + MessageChatMemoryAdvisor)
    └──required-by──> Streaming, tool transparency, citations, audit hooks
    └──enables──────> Context/Prompt/Guard SPIs (they attach as advisors or composers)

VectorStore (pgvector) + Ingester pipeline
    └──required-by──> KB upload/list/delete
    └──required-by──> Citations
    └──enhanced-by──> Reranker
    └──enhanced-by──> Post-retrieval filter
    └──enhanced-by──> Per-doc metadata tags

Parameters entity + "active" flag
    └──required-by──> Model/temperature/prompt config
    └──enables──────> YAML import/export (differentiator)
    └──enables──────> "Test prompt" button
    └──enables──────> Answer checks (differentiator)

Tool-call audit entity
    └──required-by──> Audit view
    └──required-by──> Conversation replay
    └──enables──────> Cost accounting
    └──enables──────> Audit listener SPI
    └──enables──────> Usage dashboard

Role `AiAgentAdminRole`
    └──gates──> Parameters / KB / Audit / Exposure views

Mutation-tool scaffolding (disabled)
    └──later-requires──> Dry-run/confirmation UX
    └──later-requires──> Guard SPI enforcement
    └──conflicts-with──> "Ship enabled by default" (explicit anti-feature)
```

### Dependency Notes

- **Schema generation is the critical path.** Tools, exposure policy, and audit entity fields all depend on a stable internal schema representation. Land this in the first phase or everything downstream rebases.
- **Audit must be wired at the advisor layer, not per tool.** If each tool calls `auditService.log(...)` itself, custom host tools can forget to — violates the "cannot be silently disabled" MVP requirement.
- **Citations depend on metadata propagation through the full RAG advisor chain.** Spring AI 2.0.0-M4 advisor ordering must be verified via Context7 before locking in the design — retrieval-metadata survival through subsequent advisors has bitten teams on earlier Spring AI versions.
- **"Test prompt" differentiator requires the full advisor chain in place**, so it's naturally a later-phase feature after Chat + Parameters + KB exist.
- **Exposure admin view (differentiator) requires the exposure policy SPI to be introspectable** — design the SPI with "list current effective allow/deny set" in mind from day one.

---

## MVP Definition

### Launch With (v1) — Table Stakes Only

Aligns with PROJECT.md's Active requirements. Nothing added, nothing removed.

- [ ] Metadata-first runtime + 6 read-only tools — **core value proposition; without it the add-on is generic Spring AI**
- [ ] DataManager-bound tool execution inheriting Jmix security — **the load-bearing safety claim**
- [ ] Entity exposure policy SPI (default: expose-all-readable) — **required before any enterprise trusts the rollout**
- [ ] Hybrid orchestration: ChatClient + tool calling + pgvector RAG + JDBC chat memory — **the product loop**
- [ ] Chat view with streaming, tool-call transparency, citations, stop/new-chat, error surfacing — **table stakes per section 1**
- [ ] Conversations list + replay — **already MVP scope; also the primary debug artifact**
- [ ] Parameters admin (multi-profile, one-active, model/temp/prompt/enabled-tools, YAML import/export) — **matches reference; required for any non-trivial deployment**
- [ ] KB admin (upload PDF/MD/TXT/HTML, list, delete, reingest single, ingestion status) — **matches reference MVP**
- [ ] Tool-call audit entity + searchable audit view + token counting — **non-negotiable for enterprise**
- [ ] `AiAgentAdminRole` gating Parameters/KB/Audit/Exposure — **safe defaults**
- [ ] Core SPIs: Tool, Context, Prompt, Exposure Policy, Guard, Audit Listener — **the extensibility story**
- [ ] `messages*.properties` for all labels, `menu.xml` snippet for auto-integration — **Jmix hygiene**
- [ ] Demo host (`jmix-app/`) as integration-test harness with Jmix security negative-case tests

### Add After Validation (v1.x)

Triggered by: real host adoption, specific customer asks, observed usage patterns.

- [ ] Exposure admin view (visual allow/deny editor) — **trigger: a security-team-led adoption stalls on "show me what the AI can see"**
- [ ] "Test prompt" button on Parameters — **trigger: admins complain about edit-test cycle time**
- [ ] Per-document metadata tags + retrieval filters — **trigger: first multi-audience KB request**
- [ ] Reranker + post-retrieval filtering SPI — **trigger: answer-quality complaints on ambiguous queries**
- [ ] Answer-quality checks (reference-answer regression suite) — **trigger: admins want to change Parameters safely**
- [ ] Cost accounting (token × $/1k → $/user/day) — **trigger: finance asks**
- [ ] Thumbs up/down feedback + audit — **trigger: need training data for answer-checks or model tuning**
- [ ] Health/diagnostic view — **trigger: first production incident**
- [ ] Custom ingester SPI + first adapter (e.g., simple URL ingester) — **trigger: host wants web sources**
- [ ] OTel/Micrometer export wiring — **trigger: customer with existing observability stack**

### Future Consideration (v2+)

Deferred until product-market fit is established or a specific, funded customer need emerges.

- [ ] Mutation tools enabled with dry-run + confirmation — **PROJECT.md-scaffolded; needs separate safety story**
- [ ] Multi-tenancy (tenant-scoped Parameters, KB, audit) — **compose with Jmix multi-tenancy when a real multi-tenant host adopts**
- [ ] Document versioning — **niche until a regulated customer asks**
- [ ] PII redaction beyond regex — **when a HIPAA/PCI customer signs on**
- [ ] Prompt-injection mitigation SOTA (content delimiters, classifier-based) — **when a known-exploit is reported**
- [ ] Non-OpenAI-compatible native providers (Anthropic/Gemini/Bedrock starters) — **when OpenRouter proves insufficient for a deal**
- [ ] Chunking-strategy picker per document type — **when the default splitter is the documented cause of a quality complaint**
- [ ] Usage dashboard — **when adoption metrics become a stakeholder ask**
- [ ] Secrets-manager integration docs/patterns — **when a customer requires Vault**
- [ ] Configurable audit-retention pruning job — **when DB-size is first complained about**

---

## Feature Prioritization Matrix

Only features the roadmap will touch in v1 or v1.x. Ruthlessly pruned.

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Metadata-first schema + 6 tools | HIGH | MEDIUM | P1 |
| DataManager-bound tool execution with Jmix security | HIGH | LOW | P1 |
| Exposure policy SPI (programmatic) | HIGH | MEDIUM | P1 |
| Chat view: streaming + tool transparency + citations | HIGH | MEDIUM | P1 |
| Conversations list + replay | MEDIUM | LOW | P1 |
| Parameters admin (multi-profile, YAML) | HIGH | MEDIUM | P1 |
| KB admin (upload/list/delete/reingest/status) | HIGH | MEDIUM | P1 |
| Tool-call audit entity + view + token counting | HIGH | MEDIUM | P1 |
| `AiAgentAdminRole` + role gating | HIGH | LOW | P1 |
| Tool / Context / Prompt / Guard / Audit-Listener SPIs | HIGH | MEDIUM | P1 |
| Rate limiting per user | MEDIUM | LOW | P1 |
| `messages*.properties` + menu.xml integration | MEDIUM | LOW | P1 |
| Demo host integration-test harness with security negative tests | HIGH | MEDIUM | P1 |
| Exposure admin view | MEDIUM | MEDIUM | P2 |
| "Test prompt" on Parameters | MEDIUM | MEDIUM | P2 |
| Per-doc metadata tags + retrieval filters | MEDIUM | MEDIUM | P2 |
| Reranker + post-retrieval filter SPI | MEDIUM | MEDIUM | P2 |
| Answer-quality checks | MEDIUM | HIGH | P2 |
| Cost accounting | MEDIUM | MEDIUM | P2 |
| Thumbs up/down feedback | LOW | LOW | P2 |
| Custom ingester SPI | MEDIUM | MEDIUM | P2 |
| Health / diagnostics view | MEDIUM | MEDIUM | P2 |
| Regenerate with different model | LOW | LOW | P2 |
| Inline entity previews in chat | MEDIUM | MEDIUM | P2 |
| Mutation tools (dry-run + confirm) | HIGH | HIGH | P3 |
| Multi-tenancy | HIGH | HIGH | P3 |
| Document versioning | LOW | HIGH | P3 |
| PII redaction (beyond regex) | MEDIUM | HIGH | P3 |
| OTel/Micrometer export | MEDIUM | MEDIUM | P3 |
| Usage dashboard | LOW | MEDIUM | P3 |

**Priority key:**
- P1: MVP launch (maps to Phase 1-3 of roadmap)
- P2: Add after MVP validation (maps to v1.x)
- P3: Future consideration (v2+)

---

## Competitor / Reference Feature Analysis

| Feature | `jmix-ai-backend` (reference) | Danswer/Onyx (OSS enterprise Q&A) | Dify (agent platform) | Glean / M365 Copilot (commercial) | Our Approach |
|---------|------------------------------|-----------------------------------|----------------------|-----------------------------------|--------------|
| Metadata-first tools over host entities | No (hardcoded domain) | No (doc-centric only) | No (user builds tools) | Limited (connector-per-source) | **Yes — core differentiator; auto-generated from Jmix metamodel** |
| Entity/attribute security on tool calls | N/A | Partial (doc ACLs) | No | Yes (connector-enforced) | **Yes — inherited from DataManager/Jmix** |
| Multi-profile parameters with YAML | Yes | No | Yes (app versions) | No | **Yes — port reference pattern** |
| Vector store admin UI | Yes | Yes | Yes | Hidden | **Yes — match reference** |
| Answer-quality checks (LLM-as-judge) | Yes | No | Evals (separate) | Internal only | **v1.x — port reference feature** |
| Reranker | Yes (OpenAI model) | Yes (cross-encoder) | Plugin | Yes | **v1.x** |
| Post-retrieval filtering | Yes (Groovy) | Limited | Plugin | Yes | **v1.x — SPI instead of Groovy** |
| Conversation replay / audit | Limited | Yes | Yes | Yes | **v1 — first-class** |
| Tool-call audit (structured) | No | Limited | No | Yes | **v1 — first-class, Jmix JPA entity** |
| Exposure policy (beyond native ACLs) | No | No | No | Partial (connector config) | **v1 — SPI as key differentiator** |
| SPI for host extension (tools, context, guards) | No | No (monolithic) | Yes (apps/workflows) | No | **v1 — the whole product story** |
| Mutation tools | No | No | Yes | Limited | **v2+ with dry-run** |
| Streaming + tool transparency in chat | Yes | Yes | Yes | Yes | **v1** |
| On-prem / air-gap | Yes | Yes | Yes | No (SaaS) | **v1** |
| Multi-tenancy | No | Partial | Yes | Yes (tenant-per-org) | **v2+** |

Key takeaway: our **unique positioning** is the intersection of *metadata-first tool generation over a real enterprise business-object framework* + *Jmix-native security inheritance* + *SPI-driven host extension*. No reference competitor occupies this intersection. Everything else is parity features we must not get wrong.

---

## Sources

- `.planning/PROJECT.md` — scope, constraints, SPI list, safety posture (authoritative)
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend/README.md` — reference feature surface (Chat, Parameters, VectorStore, Answer checks, Ingesters, Reranker, Post-retrieval filtering)
- `D:/DTH/ai-agent-core/CLAUDE.md` — Jmix conventions, forbidden patterns (EntityManager, Lombok on entities, hardcoded UI text)
- Cross-product pattern synthesis: Glean, ChatGPT Enterprise, Microsoft 365 Copilot, Perplexity Enterprise, Danswer/Onyx, OpenWebUI, LibreChat, Dify (training-data + ecosystem-level confidence; no version-specific API claims made from these)
- Spring AI 2.0.0-M4 primitives referenced by name (`ChatClient`, `MessageChatMemoryAdvisor`, `ToolCallAdvisor`, `VectorStore`, `@Tool`); **actual API shapes for roadmap must be verified via Context7** (`jmix-framework/jmix-context7` and `spring-projects/spring-ai`) before implementation — milestone release, known API drift

### Confidence Notes

- **HIGH confidence** on table-stakes vs differentiator vs anti-feature classification — these patterns are stable across the enterprise AI copilot category and directly reinforced by PROJECT.md's explicit scope decisions.
- **HIGH confidence** on Jmix-specific table stakes (DataManager security, `messages*.properties`, menu.xml, role gating) — backed by CLAUDE.md and Jmix convention.
- **MEDIUM confidence** on Spring AI 2.0.0-M4 specific wiring details (advisor ordering, streaming tool-call transparency) — Context7 verification required at implementation time per PROJECT.md's own "milestone release" warning.
- **MEDIUM confidence** on complexity estimates — based on reference project's scope and general Jmix/Spring AI effort patterns; should be re-estimated during phase planning.

---

*Feature research for: Jmix AI Copilot add-on (ai-agent-core)*
*Researched: 2026-04-18*
