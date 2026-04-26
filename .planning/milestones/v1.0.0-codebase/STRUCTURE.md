# Codebase Structure

**Analysis Date:** 2026-04-24

## Directory Layout

```
ai-agent-core/
├── settings.gradle                         # Composite build root including ai-agent and jmix-app
├── build.gradle                            # Root marker for Jmix composite project
├── gradle/                                 # Root Gradle wrapper support
├── docs/                                   # Project reference documentation
├── .planning/                              # GSD planning, state, phase, and codebase map artifacts
├── ai-agent/                               # Reusable add-on multi-module Gradle build
│   ├── settings.gradle                     # ai-agent-addon module declarations
│   ├── build.gradle                        # Shared add-on build configuration
│   ├── ai-agent/                           # Main Jmix add-on module
│   │   ├── ai-agent.gradle                 # Main add-on dependencies, tests, and tasks
│   │   └── src/
│   │       ├── main/java/com/vn/agent/     # Add-on Java source
│   │       ├── main/resources/com/vn/agent/# Add-on Jmix resources, views, i18n, Liquibase
│   │       └── test/                       # Add-on tests and fixtures
│   └── ai-agent-starter/                   # Spring Boot starter module
│       ├── ai-agent-starter.gradle         # Starter dependencies and test tasks
│       └── src/main/java/com/vn/autoconfigure/agent/ # Auto-configuration classes
└── jmix-app/                               # Host Jmix sample app consuming the add-on starter
    ├── build.gradle                        # Host application build
    └── src/
        ├── main/java/com/vn/jmixapp/       # Host app Java source
        ├── main/resources/com/vn/jmixapp/  # Host app Jmix resources, views, i18n, Liquibase
        └── test/                           # Host app tests and test properties
```

## Directory Purposes

**Repository Root:**
- Purpose: Composite workspace wrapper for local development across add-on and host app.
- Contains: `settings.gradle`, `build.gradle`, `gradlew`, `gradle.properties`, `.planning/`, `ai-agent/`, and `jmix-app/`.
- Key files: `settings.gradle`, `build.gradle`, `README.md`, `AGENTS.md`.

**`.planning/`:**
- Purpose: GSD planning state, roadmap artifacts, phase plans, todos, research, and codebase intelligence.
- Contains: `STATE.md`, `codebase/`, `phases/`, `todos/`, `research/`, `debug/`, and `quick/`.
- Key files: `.planning/STATE.md`, `.planning/codebase/ARCHITECTURE.md`, `.planning/codebase/STRUCTURE.md`.

**`ai-agent/`:**
- Purpose: Standalone Gradle build for the reusable add-on and starter artifacts.
- Contains: Build scripts, Gradle wrapper, `ai-agent/` main module, and `ai-agent-starter/` module.
- Key files: `ai-agent/settings.gradle`, `ai-agent/build.gradle`, `ai-agent/ai-agent/ai-agent.gradle`, `ai-agent/ai-agent-starter/ai-agent-starter.gradle`.

**`ai-agent/ai-agent/`:**
- Purpose: Main Jmix add-on module containing runtime logic, UI, entities, migrations, and tests.
- Contains: `src/main/java/com/vn/agent`, `src/main/resources/com/vn/agent`, `src/test/java/com/vn/agent`, and `src/test/resources`.
- Key files: `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`.

**`ai-agent/ai-agent-starter/`:**
- Purpose: Spring Boot starter and auto-configuration module for host consumption.
- Contains: Auto-configuration Java classes and Spring Boot auto-configuration imports.
- Key files: `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java`, `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

**`jmix-app/`:**
- Purpose: Host Jmix application and executable sample that consumes `com.vn:ai-agent-starter`.
- Contains: Host domain entities, services, security roles, Flow UI views, Liquibase changelogs, application properties, and tests.
- Key files: `jmix-app/src/main/java/com/vn/jmixapp/JmixAppApplication.java`, `jmix-app/build.gradle`, `jmix-app/src/main/resources/application.properties`.

**`docs/`:**
- Purpose: Project documentation and reference material.
- Contains: Markdown documentation files.
- Key files: Add project-facing documentation here when it is not phase-specific GSD planning content.

## Key File Locations

**Entry Points:**
- `settings.gradle`: Root composite build entry point.
- `ai-agent/settings.gradle`: Add-on multi-module build entry point.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java`: Jmix add-on module entry point.
- `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`: Spring Boot starter discovery entry point.
- `jmix-app/src/main/java/com/vn/jmixapp/JmixAppApplication.java`: Host app Spring Boot runtime entry point.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java`: Add-on chat route entry point.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java`: Add-on knowledge-base route entry point.

**Configuration:**
- `ai-agent/build.gradle`: Shared add-on subproject configuration, Jmix BOM, Java toolchain, repositories, publication setup, and Spring AI BOM.
- `ai-agent/ai-agent/ai-agent.gradle`: Main add-on dependencies, JUnit tags, `evalTest`, `liveTest`, and `integrationTest` tasks.
- `ai-agent/ai-agent-starter/ai-agent-starter.gradle`: Starter dependencies and starter-specific `evalTest` task.
- `jmix-app/build.gradle`: Host app Jmix/Spring Boot/Vaadin build, add-on starter dependency, and test dependencies.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties`: Add-on module properties and default AI/RAG/guard settings.
- `jmix-app/src/main/resources/application.properties`: Host app runtime configuration.
- `jmix-app/src/test/resources/application-test.properties`: Host app test configuration.

**Core Logic:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`: Main chat service implementation.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java`: Spring AI chat client assembly.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ConversationGateway.java`: Conversation/message persistence gateway.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java`: Current-user runtime context extraction.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java`: Built-in Jmix data tool implementation.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java`: Security-filtered schema access.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/filter/StructuredFilterConditionMapper.java`: Structured filter conversion.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java`: Knowledge document lifecycle operations.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java`: Async document ingestion worker.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/GuardedToolCallingManager.java`: Guarded Spring AI tool calling manager.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java`: Tool-call audit persistence.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/parameters/ParametersService.java`: AI parameter profile persistence/validation.
- `jmix-app/src/main/java/com/vn/jmixapp/ai/OrderSummaryToolContributor.java`: Host-side tool contribution example.

**Persistent Models:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiConversation.java`: AI conversation entity.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiMessage.java`: AI message entity.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallAudit.java`: Tool-call audit entity.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiParameters.java`: Runtime parameter profile entity.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocument.java`: Knowledge document entity.
- `jmix-app/src/main/java/com/vn/jmixapp/entity/Customer.java`: Host sample customer entity.
- `jmix-app/src/main/java/com/vn/jmixapp/entity/Order.java`: Host sample order entity.
- `jmix-app/src/main/java/com/vn/jmixapp/entity/OrderLine.java`: Host sample order line entity.
- `jmix-app/src/main/java/com/vn/jmixapp/entity/Product.java`: Host sample product entity.

**Jmix Flow UI:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java`: Chat screen controller.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/chat-view.xml`: Chat screen layout.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java`: Reusable chat panel controller.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml`: Reusable chat panel layout.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationListView.java`: Conversation list controller.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/conversation/conversation-list-view.xml`: Conversation list layout.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationDetailView.java`: Conversation transcript controller.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/conversation/conversation-detail-view.xml`: Conversation transcript layout.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java`: Knowledge base controller.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/knowledge/knowledge-base-view.xml`: Knowledge base layout.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersListView.java`: Parameters list controller.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/parameters/parameters-list-view.xml`: Parameters list layout.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/ToolCallAuditListView.java`: Tool-call audit list controller.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/audit/tool-call-audit-list-view.xml`: Tool-call audit list layout.
- `jmix-app/src/main/java/com/vn/jmixapp/view/main/MainView.java`: Host main layout controller.
- `jmix-app/src/main/resources/com/vn/jmixapp/view/main/main-view.xml`: Host main layout descriptor.

**Security:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java`: Add-on user resource role.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java`: Add-on admin resource role.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRowLevelRole.java`: Add-on row-level role.
- `jmix-app/src/main/java/com/vn/jmixapp/security/JmixAppSecurityConfiguration.java`: Host security configuration.
- `jmix-app/src/main/java/com/vn/jmixapp/security/FullAccessRole.java`: Host full-access role.
- `jmix-app/src/main/java/com/vn/jmixapp/security/SampleDataRole.java`: Host sample data role.
- `jmix-app/src/main/java/com/vn/jmixapp/security/UiMinimalRole.java`: Host UI role.

**Database Migrations:**
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog.xml`: Add-on aggregate Liquibase changelog.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/010-ai-conversation.xml`: Conversation table migration.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/020-ai-message.xml`: Message table migration.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/030-ai-tool-call-audit.xml`: Tool-call audit table migration.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/040-ai-parameters.xml`: Parameters table migration.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/050-ai-knowledge-document.xml`: Knowledge document table migration.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/060-ai-chat-memory.xml`: Spring AI JDBC chat memory migration.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/070-ai-kb-vector-store.xml`: Knowledge-base vector store migration.
- `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml`: Host app aggregate changelog.
- `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/agentstore-changelog.xml`: Host-side add-on changelog inclusion.

**Internationalization and Menus:**
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties`: Add-on English messages.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties`: Add-on Vietnamese messages.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml`: Add-on menu entries.
- `jmix-app/src/main/resources/com/vn/jmixapp/messages_en.properties`: Host English messages.
- `jmix-app/src/main/resources/com/vn/jmixapp/messages_vi.properties`: Host Vietnamese messages.
- `jmix-app/src/main/resources/com/vn/jmixapp/menu.xml`: Host menu entries.

**Testing:**
- `ai-agent/ai-agent/src/test/java/com/vn/agent`: Main add-on test packages.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/test_support`: Add-on test support configuration and stubs.
- `ai-agent/ai-agent/src/test/resources/eval`: Evaluation fixtures for tagged eval tests.
- `ai-agent/ai-agent-starter/src/test/java`: Starter auto-configuration tests.
- `jmix-app/src/test/java/com/vn/jmixapp`: Host app tests.
- `jmix-app/src/test/resources/application-test.properties`: Host app test runtime configuration.

## Naming Conventions

**Files:**
- Java classes use PascalCase matching class names: `ChatClientFactory.java`, `BuiltInDataTools.java`, `KnowledgeDocumentService.java`.
- Java records and DTO-like types use descriptive PascalCase: `RunContext.java`, `ChatResponseDto.java`, `ToolErrorDto.java`.
- Jmix view XML descriptors use kebab-case: `chat-view.xml`, `conversation-list-view.xml`, `tool-call-audit-list-view.xml`.
- Liquibase changelogs use ordered numeric prefixes: `010-ai-conversation.xml`, `070-ai-kb-vector-store.xml`.
- Gradle subproject build files match module names: `ai-agent.gradle`, `ai-agent-starter.gradle`.

**Directories:**
- Add-on Java packages use capability-oriented names under `com.vn.agent`: `orchestration`, `tools`, `rag`, `guard`, `audit`, `parameters`, `view`, `security`, `spi`.
- Jmix Flow UI views are grouped by screen/entity under `view/<feature>`: `view/chat`, `view/conversation`, `view/knowledge`, `view/parameters`, `view/audit`.
- Jmix XML descriptors mirror Java view package grouping under `src/main/resources/com/vn/agent/view/<feature>`.
- Host app packages follow standard Jmix app boundaries under `com.vn.jmixapp`: `entity`, `service`, `view`, `security`, and `ai`.

## Where to Add New Code

**New Add-On Runtime Feature:**
- Primary code: `ai-agent/ai-agent/src/main/java/com/vn/agent/<capability>`.
- Tests: `ai-agent/ai-agent/src/test/java/com/vn/agent/<capability>`.
- Configuration properties: Add records/classes under the owning package, then rely on `@ConfigurationPropertiesScan` from `AIConfiguration`.
- Resources: Add messages to `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` and `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties`.

**New Add-On Entity:**
- Primary code: `ai-agent/ai-agent/src/main/java/com/vn/agent/entity`.
- Changelog: `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/<next-number>-<description>.xml`.
- Changelog include: `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog.xml`.
- Messages: `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` and `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties`.

**New Add-On Flow UI Screen:**
- Controller: `ai-agent/ai-agent/src/main/java/com/vn/agent/view/<feature>/<Feature>View.java`.
- XML descriptor: `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/<feature>/<feature>-view.xml`.
- Menu entry: `ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml`.
- Messages: `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` and `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties`.
- Security policies: `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java` or `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java`.

**New Built-In LLM Tool:**
- Implementation: `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java` or a new class in `ai-agent/ai-agent/src/main/java/com/vn/agent/tools` when the tool is not a data-tool concern.
- Callback assembly: `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java`.
- Guard/audit integration: Preserve wrapping through `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java` and `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/GuardedToolCallingManager.java`.
- Tests: `ai-agent/ai-agent/src/test/java/com/vn/agent/tools`.

**New Host-Specific Tool:**
- Implementation: `jmix-app/src/main/java/com/vn/jmixapp/ai/<Name>ToolContributor.java`.
- Pattern: Implement `ToolContributor`, return `List.of(this)`, and annotate tool methods with Spring AI `@Tool` / `@ToolParam`.
- Data access: Use `DataManager` and explicit FetchPlans in host code, as in `jmix-app/src/main/java/com/vn/jmixapp/ai/OrderSummaryToolContributor.java`.

**New RAG Ingestion Behavior:**
- Built-in service code: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag`.
- Custom host extension: Implement `CustomIngester` from `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/CustomIngester.java` in the host app.
- Tests: `ai-agent/ai-agent/src/test/java/com/vn/agent/rag`.

**New Guardrail:**
- Core guard code: `ai-agent/ai-agent/src/main/java/com/vn/agent/guard`.
- Auto-configuration: `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AiAgentGuardAutoConfiguration.java` if host-level beans/properties are needed.
- Tests: `ai-agent/ai-agent/src/test/java/com/vn/agent/guard` and starter tests when auto-configured.

**New Host Domain Feature:**
- Entity: `jmix-app/src/main/java/com/vn/jmixapp/entity`.
- Service: `jmix-app/src/main/java/com/vn/jmixapp/service`.
- View controller: `jmix-app/src/main/java/com/vn/jmixapp/view/<entity>`.
- View XML: `jmix-app/src/main/resources/com/vn/jmixapp/view/<entity>`.
- Changelog: `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog/<next-number>-<description>.xml`.
- Messages: `jmix-app/src/main/resources/com/vn/jmixapp/messages_en.properties` and `jmix-app/src/main/resources/com/vn/jmixapp/messages_vi.properties`.
- Security roles: `jmix-app/src/main/java/com/vn/jmixapp/security`.

**Utilities:**
- Add-on shared UI/data-grid helpers: `ai-agent/ai-agent/src/main/java/com/vn/agent/utils`.
- Host app UI helpers: `jmix-app/src/main/java/com/vn/jmixapp/view/util`.
- Prefer package-local helper classes when a utility is not reused across packages.

## Special Directories

**`ai-agent/ai-agent/src/main/resources/META-INF/resources/frontend/styles`:**
- Purpose: Add-on frontend CSS resources served by Vaadin/Jmix.
- Generated: No.
- Committed: Yes.

**`ai-agent/ai-agent-starter/src/main/resources/META-INF/spring`:**
- Purpose: Spring Boot 3 auto-configuration discovery metadata.
- Generated: No.
- Committed: Yes.

**`ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog`:**
- Purpose: Add-on database schema migrations for AI conversations, audit, parameters, knowledge, memory, and vector storage.
- Generated: No.
- Committed: Yes.

**`jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog`:**
- Purpose: Host sample app domain schema migrations.
- Generated: No.
- Committed: Yes.

**`ai-agent/ai-agent/src/test/resources/eval`:**
- Purpose: Evaluation rubric fixtures used by tagged `evalTest` tasks.
- Generated: No.
- Committed: Yes.

**`.jmix/`:**
- Purpose: Local Jmix runtime/database artifacts for development.
- Generated: Yes.
- Committed: Project-dependent; treat runtime database contents as local artifacts unless already tracked intentionally.

**`build/` and `.gradle/`:**
- Purpose: Gradle outputs and caches for root, add-on, and host builds.
- Generated: Yes.
- Committed: No.

**`.idea/`:**
- Purpose: IntelliJ/Jmix Studio project metadata.
- Generated: Partially.
- Committed: Project-dependent; avoid changing IDE metadata unless the task explicitly requires it.

---

*Structure analysis: 2026-04-24*
