# Architecture

**Analysis Date:** 2026-04-24

## Pattern Overview

**Overall:** Composite Gradle workspace containing a reusable Jmix add-on, a Spring Boot auto-configuration starter, and a host Jmix sample application.

**Key Characteristics:**
- Use `settings.gradle` at the repository root to compose `ai-agent/` and `jmix-app/` through Gradle `includeBuild` boundaries.
- Keep reusable AI/Jmix behavior in `ai-agent/ai-agent/src/main/java/com/vn/agent`; keep auto-configuration glue in `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent`.
- Treat `jmix-app/src/main/java/com/vn/jmixapp` as a host application and integration sample that consumes `com.vn:ai-agent-starter`.
- Let Jmix security, metadata, `DataManager`, row-level roles, and FetchPlans remain the governing substrate for AI tool access.
- Place Flow UI layouts in XML resources and keep Java controllers focused on orchestration and event handling.

## Layers

**Composite Workspace Layer:**
- Purpose: Aggregates independently buildable Gradle projects without merging their module graphs.
- Location: `settings.gradle`
- Contains: Root composite wiring via `includeBuild 'jmix-app'` and `includeBuild 'ai-agent'`.
- Depends on: Gradle composite builds.
- Used by: IDE import, local development, and sample app dependency substitution.

**Add-On Core Module:**
- Purpose: Provides the reusable AI agent Jmix module: entities, services, tools, RAG, guardrails, audit, security roles, and Flow UI screens.
- Location: `ai-agent/ai-agent/src/main/java/com/vn/agent`
- Contains: `AIConfiguration`, `ChatService`, entities under `entity/`, built-in tools under `tools/`, orchestration under `orchestration/`, and views under `view/`.
- Depends on: Jmix core/data/security/Flow UI, Spring AI chat, Spring AI RAG, pgvector vector store, JDBC chat memory, and Spring Cache.
- Used by: `ai-agent/ai-agent-starter` via `api project(':ai-agent')` and by host applications via the starter artifact.

**Starter Auto-Configuration Module:**
- Purpose: Bridges the add-on into Spring Boot host apps through conditional auto-configuration.
- Location: `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent`
- Contains: `AIAutoConfiguration`, `AiToolsAutoConfiguration`, `AiAgentGuardAutoConfiguration`, and `SpiDefaultsAutoConfiguration`.
- Depends on: `ai-agent/ai-agent`, Spring Boot autoconfigure, Spring AI OpenAI starter, and Spring AI JDBC chat memory starter.
- Used by: Host apps through `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

**Host Application Layer:**
- Purpose: Demonstrates and exercises the add-on inside a standard Jmix application with domain entities, security roles, sample views, and host-contributed tools.
- Location: `jmix-app/src/main/java/com/vn/jmixapp`
- Contains: `JmixAppApplication`, domain entities in `entity/`, business services in `service/`, views in `view/`, security roles in `security/`, and host AI extensions in `ai/`.
- Depends on: `com.vn:ai-agent-starter:0.0.1-SNAPSHOT`, Jmix starters, Spring Boot, Vaadin, and Spring AI tool annotations.
- Used by: Local runtime verification and integration scenarios.

**Domain Entity Layer:**
- Purpose: Defines persistent data models for both the reusable add-on and sample host app.
- Location: `ai-agent/ai-agent/src/main/java/com/vn/agent/entity` and `jmix-app/src/main/java/com/vn/jmixapp/entity`
- Contains: AI entities such as `AiConversation`, `AiMessage`, `AiToolCallAudit`, `AiParameters`, `AiKnowledgeDocument`; host entities such as `Customer`, `Order`, `OrderLine`, `Product`, and `User`.
- Depends on: Jmix entity annotations, JPA, Liquibase changelogs, and message bundles.
- Used by: Jmix `DataManager`, Flow UI data containers/loaders, built-in tools, audit writers, RAG services, and sample app services.

**Orchestration Layer:**
- Purpose: Builds chat clients, resolves run context and parameters, persists conversations/messages, projects chat memory, and coordinates streaming events.
- Location: `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration`
- Contains: `ChatClientFactory`, `ConversationGateway`, `BaselineContextProvider`, `AiParametersResolver`, `ProjectingChatMemoryRepository`, `RunContext`, and streaming support classes.
- Depends on: Spring AI chat APIs, Jmix authentication/security context, `DataManager`, parameters service, RAG advisors, tools, and guard advisors.
- Used by: `DefaultChatServiceImpl`, `ChatPanelFragment`, and tests under `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration`.

**Tool Surface Layer:**
- Purpose: Exposes controlled LLM-facing operations against Jmix metadata and data while honoring current-user security.
- Location: `ai-agent/ai-agent/src/main/java/com/vn/agent/tools`
- Contains: `BuiltInDataTools`, `AgentToolCallbacks`, `ToolLimits`, `ToolResultFormatter`, `ToolErrorDto`, and payload DTO records.
- Depends on: `CurrentUserSchemaAccess`, Jmix `Metadata`, `AccessManager`, `DataManager`, structured filters, fetch plans, and Spring AI tool callback APIs.
- Used by: `AiToolsAutoConfiguration`, `ChatClientFactory`, host `ToolContributor` beans, and tool audit decorators.

**Structured Filter Layer:**
- Purpose: Converts LLM-provided filter ASTs into bounded Jmix conditions with strict literal coercion.
- Location: `ai-agent/ai-agent/src/main/java/com/vn/agent/filter`
- Contains: `FilterNode`, `LeafNode`, `AndNode`, `OrNode`, `NotNode`, `FilterLiteralValueConverter`, and `StructuredFilterConditionMapper`.
- Depends on: Jmix metadata/datatype services and tool depth limits.
- Used by: `BuiltInDataTools` for `find_records`, `count_records`, and related data access operations.

**RAG Layer:**
- Purpose: Handles knowledge document upload, ingestion, deletion, role-scoped retrieval filters, and retrieval augmentation advisor creation.
- Location: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag`
- Contains: `KnowledgeDocumentUploadService`, `KnowledgeDocumentService`, `AsyncIngestionWorker`, `IngesterManager`, `RetrievalFilterBuilder`, `ClasspathMarkdownIngester`, and `advisor/RetrievalAugmentationAdvisorFactory`.
- Depends on: Spring AI vector store/RAG APIs, Jmix `DataManager`, `AiKnowledgeDocument`, `AiAgentRagProperties`, task executor `aiAgentIngestExecutor`, and application events.
- Used by: `KnowledgeBaseView`, `ChatClientFactory`, asynchronous ingestion flows, and tests under `ai-agent/ai-agent/src/test/java/com/vn/agent/rag`.

**Guardrail Layer:**
- Purpose: Applies iteration limits, token budget checks, rate limits, output scanning, and tool veto logic.
- Location: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard`
- Contains: `GuardedToolCallingManager`, `RateLimitGuard`, `TokenBudgetGuard`, `OutputScannerAdvisor`, `IterationCounter`, `AiAgentGuardProperties`, and exception types.
- Depends on: Spring AI advisor/tool-calling APIs, Spring Cache, configured scanner patterns, and `ToolGuard` SPI beans.
- Used by: `AiAgentGuardAutoConfiguration`, `ChatClientFactory`, and `DefaultChatServiceImpl` request execution.

**Audit Layer:**
- Purpose: Records and dispatches tool-call and advisor audit events for observability and UI inspection.
- Location: `ai-agent/ai-agent/src/main/java/com/vn/agent/audit`
- Contains: `AuditAdvisor`, `ToolCallbackAuditDecorator`, `AuditWriter`, `AuditListenerDispatcher`, and `ToolCallAdvisorBuilderConstants`.
- Depends on: Spring AI advisor/tool callback APIs, `AiToolCallAudit`, `DataManager`, and `AuditListener` SPI.
- Used by: Chat execution, tool callback wrapping, and `ToolCallAuditListView` / `ToolCallAuditDetailDialog`.

**Parameters Layer:**
- Purpose: Stores, validates, seeds, and resolves YAML-backed AI runtime parameter profiles.
- Location: `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters`
- Contains: `AiParametersBody`, `AiParametersBodyYamlMapper`, `ParametersService`, `DefaultParamsSeeder`, `Overrides`, and `ParametersValidationException`.
- Depends on: Jackson YAML, Jakarta validation, `AiParameters`, and `DataManager`.
- Used by: `AiParametersResolver`, `ParametersListView`, `ParametersDetailView`, and chat client construction.

**Flow UI Layer:**
- Purpose: Provides user-facing add-on screens for chat, conversations, knowledge base, parameters, and audit.
- Location: `ai-agent/ai-agent/src/main/java/com/vn/agent/view` and `ai-agent/ai-agent/src/main/resources/com/vn/agent/view`
- Contains: Java controllers paired with XML descriptors including `ChatView`, `ChatPanelFragment`, `KnowledgeBaseView`, `ConversationListView`, `ConversationDetailView`, `ParametersListView`, and audit views.
- Depends on: Jmix Flow UI, XML descriptors, message bundles, view/menu policies, reusable UI fragments, and services.
- Used by: Jmix menu entries in `ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml` and host app navigation.

**SPI Extension Layer:**
- Purpose: Provides app-specific extension seams without duplicating Jmix-native security or metadata layers.
- Location: `ai-agent/ai-agent/src/main/java/com/vn/agent/spi`
- Contains: `ToolContributor`, `ToolGuard`, `PromptContextContributor`, `ContextContributor`, `CustomIngester`, and `AuditListener`.
- Depends on: Spring bean discovery and add-on orchestration contracts.
- Used by: Host extension `jmix-app/src/main/java/com/vn/jmixapp/ai/OrderSummaryToolContributor.java`, default auto-configuration, and chat/tool assembly.

## Data Flow

**Chat Request Flow:**

1. User navigates to `ChatView` at `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java`, which hosts `ChatPanelFragment` from `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`.
2. `ChatPanelFragment` delegates prompt execution to `ChatService` implemented by `DefaultChatServiceImpl` in `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`.
3. `DefaultChatServiceImpl` creates a `RunContext`, uses `ConversationGateway` to load/create conversation state, and asks `ChatClientFactory` for a configured Spring AI `ChatClient`.
4. `ChatClientFactory` composes baseline context, parameter profile, prompt contributors, RAG advisor, audit advisor, output scanner advisor, built-in and contributed tools, and guarded tool-calling support.
5. Spring AI invokes tools through decorated callbacks; built-in data tools use Jmix metadata/security/data APIs under the current user security context.
6. `ConversationGateway` persists user/assistant messages, `ProjectingChatMemoryRepository` projects memory to Spring AI JDBC memory, and streaming events flow back to the UI through `StreamingSinkHolder`.

**Built-In Data Tool Flow:**

1. `AgentToolCallbacks` in `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java` exposes methods from `BuiltInDataTools` as Spring AI tool callbacks.
2. `CurrentUserSchemaAccess` in `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java` filters visible entities and properties through Jmix metadata and security checks.
3. `StructuredFilterConditionMapper` maps filter nodes from `ai-agent/ai-agent/src/main/java/com/vn/agent/filter` into Jmix conditions while enforcing property-path depth and literal conversion.
4. `BuiltInDataTools` executes read-only loads/counts through `DataManager` and formats prompt-safe results through `ToolResultFormatter`.
5. `ToolCallbackAuditDecorator` and `AuditWriter` persist tool call metadata in `AiToolCallAudit` for `ToolCallAuditListView`.

**RAG Ingestion Flow:**

1. `KnowledgeBaseView` in `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java` accepts user document operations through the Flow UI XML view.
2. `KnowledgeDocumentUploadService` creates or updates `AiKnowledgeDocument` records and schedules asynchronous ingestion work.
3. `AsyncIngestionWorker` uses `IngesterManager`, configured splitters, and Spring AI `VectorStore` to chunk and store embeddings.
4. `DocumentStatusChangedEvent` in `ai-agent/ai-agent/src/main/java/com/vn/agent/push/DocumentStatusChangedEvent.java` communicates status changes back to UI/push consumers.
5. `RetrievalAugmentationAdvisorFactory` builds a retrieval advisor that `ChatClientFactory` includes in chat requests.
6. `RetrievalFilterBuilder` constrains retrieval by current user/role metadata stored with document chunks.

**Host Tool Contribution Flow:**

1. Host app adds a Spring bean implementing `ToolContributor`, such as `jmix-app/src/main/java/com/vn/jmixapp/ai/OrderSummaryToolContributor.java`.
2. `SpiDefaultsAutoConfiguration` and `AiToolsAutoConfiguration` collect available SPI beans and built-in tool callbacks.
3. `ChatClientFactory` includes host-contributed `@Tool` methods alongside built-in callbacks.
4. Host tools remain trusted host code and must use Jmix `DataManager`/FetchPlans for security-aware data access.

**State Management:**
- Persist conversations and messages through `AiConversation` and `AiMessage` in `ai-agent/ai-agent/src/main/java/com/vn/agent/entity`.
- Persist tool-call audit records through `AiToolCallAudit` and Liquibase changelogs under `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog`.
- Store parameter profiles in `AiParameters` and YAML bodies managed by `ParametersService`.
- Store knowledge document lifecycle in `AiKnowledgeDocument`; embeddings live in pgvector tables created by `070-ai-kb-vector-store.xml`.
- Use Spring AI JDBC chat memory through `ProjectingChatMemoryRepository` rather than treating memory as only in-process state.

## Key Abstractions

**Jmix Module Configuration:**
- Purpose: Registers the add-on with Jmix scanning, Flow UI view/action discovery, module properties, async ingestion, and vector-store defaults.
- Examples: `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties`
- Pattern: `@JmixModule`, `@ComponentScan`, `@ConfigurationPropertiesScan`, module-local `ViewControllersConfiguration`, and conditional beans.

**Spring Boot Starter Auto-Configuration:**
- Purpose: Enables host applications to consume the add-on by adding the starter dependency.
- Examples: `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java`, `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Pattern: Conditional auto-configuration classes imported through Spring Boot 3 `AutoConfiguration.imports`.

**Chat Service Boundary:**
- Purpose: Public application-facing boundary for prompt execution.
- Examples: `ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`
- Pattern: Interface plus Spring-managed implementation that delegates context assembly to orchestration collaborators.

**Run Context:**
- Purpose: Captures per-request user, roles, locale, conversation, and runtime parameter context.
- Examples: `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/RunContext.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java`
- Pattern: Immutable context DTO assembled from Jmix current authentication and request inputs.

**Tool Callback Assembly:**
- Purpose: Converts built-in and host-contributed tool methods into Spring AI callbacks under audit and guard wrappers.
- Examples: `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/GuardedToolCallingManager.java`
- Pattern: Decorator and collector composition around Spring AI tool abstractions.

**Security Roles:**
- Purpose: Grants add-on menus/views/entities and row-level access according to Jmix role policy mechanisms.
- Examples: `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRowLevelRole.java`
- Pattern: Jmix `@ResourceRole` and row-level role interfaces instead of AI-specific exposure rules.

**Flow UI View Pair:**
- Purpose: Keeps UI layout declarative and controller code behavior-focused.
- Examples: `ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationListView.java` with `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/conversation/conversation-list-view.xml`
- Pattern: `@Route`, `@ViewController`, `@ViewDescriptor` Java controller paired with XML descriptor and message bundle keys.

**Host Extension SPI:**
- Purpose: Lets applications add domain-specific tools, context, guards, audit listeners, or custom ingesters.
- Examples: `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolContributor.java`, `jmix-app/src/main/java/com/vn/jmixapp/ai/OrderSummaryToolContributor.java`
- Pattern: Spring beans implementing narrow interfaces; baseline runtime context remains internal to the add-on.

## Entry Points

**Composite Build:**
- Location: `settings.gradle`
- Triggers: Gradle/IDE import at the repository root.
- Responsibilities: Includes `ai-agent` and `jmix-app` as composite builds.

**Add-On Module Configuration:**
- Location: `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java`
- Triggers: Jmix/Spring context startup when the module is on the classpath.
- Responsibilities: Registers the Jmix module, scans add-on beans/properties, configures view/action packages, async ingestion executor, and default vector store support.

**Starter Auto-Configuration Imports:**
- Location: `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Triggers: Spring Boot auto-configuration discovery.
- Responsibilities: Imports add-on auto-configurations into host applications.

**Host Spring Boot Application:**
- Location: `jmix-app/src/main/java/com/vn/jmixapp/JmixAppApplication.java`
- Triggers: `bootRun` or packaged application startup.
- Responsibilities: Starts the sample Jmix app, configures datasource beans, and logs the runtime URL.

**Chat UI Route:**
- Location: `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java`
- Triggers: Navigation to the add-on chat route from menu or URL.
- Responsibilities: Hosts the reusable chat panel and initializes conversation-aware query parameters.

**Knowledge Base UI Route:**
- Location: `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java`
- Triggers: Navigation to `ai-agent/knowledge`.
- Responsibilities: Manages document upload, status display, delete/cancel actions, and ingestion UI feedback.

**Conversation Admin/User Routes:**
- Location: `ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationListView.java` and `ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationDetailView.java`
- Triggers: Navigation to `ai-agent/conversations` or `ai-agent/conversations/:id`.
- Responsibilities: Lists accessible conversations, displays transcripts, and redirects continuation into chat.

**Host Tool Bean:**
- Location: `jmix-app/src/main/java/com/vn/jmixapp/ai/OrderSummaryToolContributor.java`
- Triggers: Spring component scanning in the host application.
- Responsibilities: Contributes a domain-specific `summarize_customer_orders` tool.

## Error Handling

**Strategy:** Fail closed for security-sensitive and budget-sensitive operations; return prompt-safe structured tool errors for user-correctable input; persist operational failures in audit/status entities where useful.

**Patterns:**
- Throw typed runtime exceptions for guard failures from `ai-agent/ai-agent/src/main/java/com/vn/agent/guard`, such as `RateLimitExceededException`, `TokenBudgetExhaustedException`, and `IterationCapExceededException`.
- Use `ToolUserError` and `ToolErrorDto` in `ai-agent/ai-agent/src/main/java/com/vn/agent/tools` for LLM-facing validation errors instead of leaking stack traces.
- Use `ParametersValidationException` in `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/ParametersValidationException.java` for invalid YAML/parameter bodies.
- Use `ConversationNotFoundException` in `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ConversationNotFoundException.java` for invalid conversation references.
- Mark knowledge documents failed through `KnowledgeDocumentService` / `AsyncIngestionWorker` rather than leaving ambiguous in-progress records.
- Persist tool call outcomes in `AiToolCallAudit` through `AuditWriter` for post-run diagnosis.

## Cross-Cutting Concerns

**Logging:** Use SLF4J in application and service classes; preserve correlation context across async RAG work through `MdcPropagatingTaskDecorator` in `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/MdcPropagatingTaskDecorator.java`.
**Validation:** Validate structured filters through metadata-aware converters in `ai-agent/ai-agent/src/main/java/com/vn/agent/filter`; validate parameter YAML through `AiParametersBodyYamlMapper` and Jakarta validation in `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters`.
**Authentication:** Use the current Jmix security context through `CurrentAuthentication`, `AccessManager`, Jmix resource roles, and row-level roles; do not add a parallel AI exposure policy layer.

---

*Architecture analysis: 2026-04-24*
