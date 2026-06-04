# Codebase Structure

**Analysis Date:** 2026-06-04

## Directory Layout

```
ai-agent-core/
├── ai-agent/                       # The Jmix AI Agent addon (PRIORITY)
│   ├── ai-agent/                   # Addon module (Gradle subproject)
│   │   └── src/main/java/com/vn/agent/
│   └── ai-agent-starter/           # Spring Boot auto-configuration starter
│       └── src/main/java/com/vn/autoconfigure/agent/
├── jmix-app/                       # Separate host application (consumes the addon)
├── docker/                         # postgres + nexus compose/config
├── docs/                           # Documentation
├── test-data/knowledge-base/       # RAG ingestion fixtures
└── .planning/                      # GSD planning artifacts (phases, codebase maps)
```

### Addon package layout (`ai-agent/ai-agent/src/main/java/com/vn/agent/`)

```
com/vn/agent/
├── AIConfiguration.java                  # @JmixModule + @ComponentScan entry config
├── AgentstoreStoreConfiguration.java     # Second datastore (agentstore) wiring
├── ChatService.java / DefaultChatServiceImpl.java   # Turn orchestration entry
├── ChatResponse.java
├── action/            # Side-effect-free proposal/draft orchestration tools
├── admin/             # Admin config DTOs + services (config/, config/dto/)
├── audit/             # AuditWriter, audit decorators, sanitizer
├── conversation/      # Title generation, session eligibility events
├── entity/            # Jmix entities (agentstore datastore)
├── exposure/          # LLM entity-exposure rules + policy
├── extraction/        # Intent extraction → form prefill (prepare_form_draft)
├── filter/            # Filtering support
├── guard/             # Rate/token/iteration guards, output scanner, tool-name gating
├── metadata/          # Metadata helpers for the LLM schema view
├── orchestration/     # RunContext, gateways, prompt composer, chat client factory
├── parameters/        # AiParameters resolution/overrides
├── push/              # Server push support
├── rag/               # pgvector retrieval (advisor/) + ingestion + config/
├── security/          # Resource roles (admin/user/mutation/row-level)
├── spi/               # Host extension points (MutationGuard, IntentExtractor, etc.)
├── taskfile/          # Task-file (attachment) handling
├── tools/             # @Tool callbacks (read) + fetchplan/ link/ mutation/
├── utils/
└── view/              # Vaadin Flow UI (chat/, audit/, knowledge/, exposure/, ...)
```

## Directory Purposes

**`orchestration/`:** Per-turn engine collaborators. Key files: `RunContext.java`, `ConversationGateway.java`, `SystemPromptComposer.java`, `ChatClientFactory.java`, `AiUiSettingsResolver.java`, `AiParametersResolver.java`, `StreamingSinkHolder.java`.

**`tools/`:** Model-facing `@Tool` callbacks. `BuiltInDataTools.java` (read), `tools/link/` (always-on link tools), `tools/mutation/` (5 mutation tools + `MutationGateChain`), `AgentToolCallbacks.java` (assembly/routing/allowlist), `tools/fetchplan/`.

**`tools/mutation/`:** The fail-closed mutation subsystem. `MutationGateChain.java` (spine), `BuiltInMutationTools.java` (@Tool adapters), `MutationRequest.java` (sealed variants), plus per-concern collaborators (`MutationSaveExecutor`, `MutationAuthorizationService`, `MutationAttributeBinder`, `MutationCommitCoordinator`, `MutationIntentRepository`, `MutationErrorTranslator`, `RelatedWriteMetadataResolver`, `DiffSerializer`).

**`guard/`:** Safety controls. `RateLimitGuard`, `TokenBudgetGuard`, `IterationCounter`, `OutputScannerAdvisor`, `GuardedToolCallingManager`, `ToolNamePatternProvider`/`HostPrefixPatternProvider`, `AgentSystemPromptRules(Composer)`.

**`security/`:** Resource roles as interfaces. `AiAgentMutationRole.java` (mutation marker gate), `AiAgentAdminRole`, `AiAgentUserRole`, `AiAgentUserRowLevelRole`.

**`entity/`:** agentstore-backed Jmix entities. `AiConversation`, `AiMessage`, `AiAuditEvent`, `AiParameters`, `AiKnowledgeDocument`, `AiExposureRule`, `AiUiSettings`, `AiTaskFile`, `AiExtractionDraft`, plus enums (`AiMessageRole`, `AiToolCallOutcome`, `AiKnowledgeDocumentStatus`).

**`spi/`:** Host extension contracts only — `MutationGuard`, `IntentExtractor`, `ToolContributor`, `ToolGuard`, `ContextContributor`, `PromptContextContributor`, `CustomIngester`, `ToolFetchPlanCustomizer`.

**`ai-agent-starter/`:** Auto-configuration for host apps — `AIAutoConfiguration`, `AiToolsAutoConfiguration`, `AiAgentGuardAutoConfiguration`, `SpiDefaultsAutoConfiguration`, `KnobInventoryAutoConfiguration` + scanner.

## Key File Locations

**Entry Points:**
- `AIConfiguration.java`: Jmix module config (component scan, async, scheduling, SPI defaults).
- `DefaultChatServiceImpl.java`: chat turn orchestration.
- `view/chat/ChatView.java`: primary UI entry.

**Configuration:**
- `AgentstoreStoreConfiguration.java`: agentstore datasource/EMF/tx/Liquibase/pgvector.
- `resources/com/vn/agent/module.properties`: module defaults.
- `*Properties.java` (e.g. `guard/AiAgentGuardProperties.java`, `tools/mutation/AiAgentMutationProperties.java`, `rag/config/AiAgentRagProperties.java`): `@ConfigurationProperties` knobs.

**Core Logic:**
- `tools/mutation/MutationGateChain.java`: mutation gate spine.
- `tools/AgentToolCallbacks.java`: tool surface assembly.
- `audit/AuditWriter.java`: audit boundary.

**Resources:**
- `resources/com/vn/agent/liquibase/agentstore-changelog/`: numbered changelogs (`010-...` → `120-...`).
- `resources/com/vn/agent/view/**`: XML view descriptors.
- `resources/com/vn/agent/messages_en.properties`, `messages_vi.properties`: locale bundles.
- `resources/com/vn/agent/prompts/*.st`: prompt templates.
- `resources/com/vn/agent/menu.xml`: menu entries.

**Testing:**
- `ai-agent/ai-agent/src/test/java/com/vn/agent/`: tests organized by feature package (e.g. `extraction/`, `tools/mutation/`).

## Naming Conventions

**Files:**
- Entities: `Ai*` prefix (`AiConversation`, `AiAuditEvent`).
- Properties beans: `*Properties` with `@ConfigurationProperties`.
- Mutation collaborators: `Mutation*`.
- View XML descriptors: kebab-case matching controller (`chat-view.xml` ↔ `ChatView.java`).
- Liquibase changelogs: `NNN-kebab-description.xml`, included in `agentstore-changelog.xml`.

**Directories:**
- Feature/concern packages, lowercase (`orchestration`, `tools/mutation`, `guard`).

## Where to Add New Code

**New read tool:**
- Add `@Tool` method to `tools/BuiltInDataTools.java` (or a new bean registered as a `ToolContributor`).

**New mutation operation:**
- Add a `MutationRequest` variant + `@Tool` adapter in `tools/mutation/BuiltInMutationTools.java`; extend each gate `switch` in `MutationGateChain.java`. Never bypass the spine.

**New agentstore entity:**
- `entity/Ai*.java` (UUID + Version + InstanceName) + new numbered changelog under `resources/com/vn/agent/liquibase/agentstore-changelog/` included in `agentstore-changelog.xml` + messages in BOTH `messages_en.properties` and `messages_vi.properties`. Ensure `.store("agentstore")` for raw JPQL value loads.

**New view:**
- Controller in `view/<feature>/`, XML descriptor in `resources/com/vn/agent/view/<feature>/`, menu entry in `menu.xml`, messages in all locale files.

**New host extension point usage:**
- Implement an interface in `spi/`; defaults wired by `ai-agent-starter/.../SpiDefaultsAutoConfiguration.java`.

**New config knob:**
- Add to the relevant `*Properties` bean; it is auto-discovered by `KnobInventoryScanner` in the starter.

## Special Directories

**`resources/com/vn/agent/liquibase/`:**
- Purpose: agentstore schema migrations.
- Generated: No. Committed: Yes. Run automatically on startup.

**`jmix-app/`:**
- Purpose: Separate host application that consumes the addon (NOT part of the addon).
- Committed: Yes (bootrun log files are untracked noise).

**`test-data/knowledge-base/`:**
- Purpose: RAG ingestion fixtures.
- Committed: Yes.

---

*Structure analysis: 2026-06-04*
