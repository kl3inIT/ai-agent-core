# Coding Guidelines

This file provides guidance to AI coding agents when working with code in this repository.

## Skills and MCP

- For detailed guidance on specific Jmix features, ALWAYS use the Skill tool and available Jmix skills.
- Use Context7 jmix-framework/jmix-context7 library for Jmix reference information and code examples.
- When DB inspection/query is needed, use `postgresmcp` first instead of JetBrains MCP.
- Use Jetbrains MCP to check file problems with `get_file_problems("path/to/file.ext", onlyErrors=false)`

## Local Memory

- If additional reference implementation is needed, use `D:\Study materials spring 2026\EXE101\ai\jmix-ai-backend`.
- If OpenRouter access is needed, read `OPENROUTER_API_KEY` from `.env` (do not hardcode secrets).

## Project

Technology Stack:
- Java 17
- Jmix 2.8 (Spring Boot 3, Vaadin Flow UI)
- Relational database
- Gradle build system

### Project Structure

Standard Gradle project layout with `src/main` and `src/test` directories. Java classes are placed in `src/main/java`, resources in `src/main/resources`.

The codebase follows a modular organization under the base package:

- `entity/` - Domain entities
- `service/` - Business logic layer
- `view/` - UI layer
    - Each view has a Java controller and XML layout descriptor
    - Views are organized by entity (client, order, etc.)
- `security/` - Role-based access control with roles interfaces

Tests are organized in packages by feature domain. The `test_support` package provides utilities for testing.

## Build & Run Commands

### Development

```bash
# Run application (starts on http://localhost:8080, log in as admin/admin)
./gradlew bootRun
```

### Testing

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "com.company.sample.order.OrderServiceTest"

# Run with specific test method
./gradlew test --tests "com.company.sample.order.OrderServiceTest.testOrderCalculations"
```

## Development Guidelines

Refer to the relevant skills for detailed implementation patterns.

### Working with Entities

- JPA entities: use `@JmixEntity`, UUID + `@JmixGeneratedValue`, `@Version`, `@InstanceName`
- Relationships: Use `@Composition` for parent-child aggregates
- Computed properties: Use `@JmixProperty` with `@DependsOnProperties` for caching expensive calculations
- No Lombok on entities
- When asked to create entity:
  - Java class with UUID + Version + InstanceName
  - Liquibase changelog + include in `changelog.xml`
  - Messages in ALL locale files (`messages.properties`, `messages_*.properties`)
- Instantiate entities using `Metadata.create()` or `DataManager.create()` depending on what is available in the class. Don't use entity constructor directly.

### Working with Services

- Injection: Use constructor injection, not field injection

### Data Access

- Data access: Use `DataManager` (NOT `EntityManager`) and its fluent data loading interface for queries (see jmix-services skill))
- Fetch plans: Build optimized fetch plans to avoid N+1 queries (see jmix-fetch-plans skill))
- Transactions: Annotate with `@Transactional` when needed

### Working with Views

- View descriptors: XML files in `src/main/resources/**/view/**`
- Controllers: Java classes with `@ViewController` and `@ViewDescriptor` annotations, extend `StandardListView` / `StandardDetailView`
- Navigation: Use `ViewNavigators` for programmatic navigation between views
- When asked to create view:
  - XML descriptor + Java controller
  - Menu entry in `menu.xml`
  - Messages for title/labels in ALL locale files

### Working with Security

- Resource roles: Define as interfaces annotated with `@ResourceRole` in `security/` package and add policy annotations on methods
- Entity policies: Use `@EntityPolicy` for CRUD operations
- Attribute policies: Use `@EntityAttributePolicy` for field-level access
- View/Menu policies: Use `@ViewPolicy` and `@MenuPolicy` for UI access control

### Database Migrations

Liquibase changelogs are in `src/main/resources/**/liquibase/changelog/**.xml`:
- Organized by step numbers (`010-some-description.xml`, `020-other-description.xml`, etc.) or in hieracrhical time-based structure (`2026/02/19-105244-customer.xml`, `2026/02/20-120315-order.xml`)
- Include new changelogs to the main `changelog.xml`
- Run automatically on application startup

### Tests

- Prefer integration tests with `@SpringBootTest` for business logic and UI tests with `@UiTest`.
- Test database with automatic schema creation via Liquibase.

### Patterns

- Business logic in services, not in views
- Dependency Injection
    - Views: `@ViewComponent` for components defined in XML (visual components, data containers, data loaders, MessageBundle, DataContext)
    - Views: `@Autowired` for Spring beans (DataManager, DialogWindows, etc.)
    - Services: Constructor injection only

### Forbidden

- Lombok on entities
- Creating entity instances by constructor
- EntityManager
- Business logic in views
- Hardcoded UI text — ALL labels, titles, buttons MUST use `msg://` keys
- Single-locale messages — ALWAYS add to ALL locale files
- Edits in `frontend/generated/`

### Validation Checklist

- Entity: UUID + Version + InstanceName present
- Changelog added to `changelog.xml`
- Messages added for all components (entity, enum, view titles, labels)
- View: XML + Java pair; menu updated
- Security: role covers entity/view/menu

## Development Workflow

After writing or modifying code, validate using this sequence:

1. **Check file problems** — if `jetbrains` MCP available, use it to check file problems for each modified file with `get_file_problems("path/to/file.ext", onlyErrors=false)`
2. **Write tests** — create/update tests for new functionality
3. **Run tests** — `./gradlew test` to verify nothing is broken
4. **UI verification** (for views) — if `playwright` MCP available and app is running:
    - Navigate to the view
    - Verify data displays correctly
    - Click or do things that should trigger UI logic
    - Test CRUD operations

## Project Conventions (User Feedback)

Distilled standing preferences for this project. Apply by default; deviate only with explicit justification.

### Architecture & Security

- **AI is just another Jmix client.** Run under the current user's Jmix security context. Rely on `AccessManager` / `DataManager` for entity-, attribute-, and row-level control. Do NOT introduce an AI-specific exposure layer (e.g. `AiExposureRule`, `EntityExposurePolicy` SPI, `ExposureRuleListView`) unless a real use case surfaces that native Jmix security cannot express.
- **Reuse Jmix built-ins over parallel layers.** Before building any metadata, security, or query layer for the AI tool surface, check `Metadata`, `AccessManager`, `DataManager`, `FetchPlan`, `MetadataTools` first. The only pieces the add-on should own are the thin LLM-facing bits Jmix doesn't provide:
  1. Schema shaping for tools (what the LLM sees)
  2. Strict literal coercion (type safety at the prompt boundary)
  3. Path-depth governance (traversal limits)
  4. Result-size limits (context budget)
  5. Prompt-injection-safe result formatting
  "Ai*" DTOs are justified only for the LLM tool-surface shape.
- **Pragmatic module structure.** Keep module count minimal. Defer splits (e.g. headless/UI separation) until a named consumer or concrete requirement justifies it. "Future flexibility" is not sufficient justification.
- **SPI contributors only for app-specific behavior.** Baseline runtime context (current user, roles, locale, conversation id) is built into the add-on — contributors do NOT receive it as input. Reserve SPIs for domain-specific instructions, external-system context, or business rules the add-on cannot know in advance.
- **No ArchUnit (yet).** Enforce architectural constraints via code review and regular tests. Revisit only if the rule set grows or drift appears. The same bar applies to other static-enforcement tooling.

### Code Style

- **No abbreviated identifiers.** Spell names fully: `userEditableIndex`, `metaClass`, `metaProperty`, `datatype` — never `uei`, `mc`, `mp`, `dt`, `ctx`. Short loop vars (`i`, `e` in catch) remain fine.

### Jmix UI — Single Review Gate

**1. Jmix Flow UI first, raw Vaadin last.**
- XML view descriptors are the default for layout, fields, actions, data containers/loaders, dialogs, filters, bindings.
- Prefer Jmix components: `dataGrid`, `formLayout`, `tabSheet`, `upload`, `genericFilter`, `DialogWindows`/`Dialogs`, `ViewNavigators`, standard list/detail actions, role-based view/menu policies.
- Java view controllers are for orchestration, event handling, validation, and small glue — not for layout trees expressible in XML.
- Raw Vaadin or programmatic Java UI only when Jmix has no equivalent; the deviation must be explicitly justified in the PR/commit message.

**2. Event wiring via Jmix's official event types — never invent event class names.**
- Buttons: `@Subscribe("buttonId")` with `ClickEvent<Button>`, or bind the button to a declared `<action>` + `@Subscribe("componentId.actionId")` / `@Install`.
- Data loaders/containers: `@Install(to = "loaderId", subject = "loadDelegate")`, `@Subscribe(id = "containerId", target = Target.DATA_CONTAINER)` for `ItemChangeEvent`, `ItemPropertyChangeEvent`, `CollectionChangeEvent`.
- View lifecycle: `@Subscribe` on official events listed by Jmix Studio's Handlers panel (e.g. `InitEvent`, `BeforeShowEvent`, `ReadyEvent`, `BeforeCloseEvent`, `AfterCloseEvent`, `AttachEvent`, `DetachEvent`, `QueryParametersChangeEvent`).
- Fields: `@Subscribe("fieldId")` with `ComponentValueChangeEvent` / `TypedValueChangeEvent`.
- Renderers/validators/delegates: `@Supply` / `@Install` — not programmatic registration.
- Do NOT use raw Vaadin `addClickListener(...)` / `addValueChangeListener(...)` in controllers when `@Subscribe` can express the same wiring — raw listeners bypass the view lifecycle and DI.

**3. Resolving uncertainty (component choice, XML syntax, event type, pattern) — lookup order:**
1. **Jmix Studio Handlers panel** for the target component/view (canonical source of truth for "what events exist").
2. **Context7** — query `jmix-framework/jmix-context7` with a specific question (e.g. "Jmix Button handlers", "Jmix dataGrid selection event", "Jmix CollectionLoader events").
3. **Official Jmix docs** at `docs.jmix.io`.
4. **GitHub**: `jmix-framework/jmix`, `jmix-framework/jmix-samples`, and `jmix-ai-backend` for real usages.
5. `jmix-ai-backend` for analogous screens (chat, parameters, knowledge-base, audit, dialogs, navigation, data loading) — generalize patterns, don't copy domain details blindly.

Never guess annotation parameters, event class names, or XML element names from memory. If you can't cite where the event type is documented, you haven't verified it yet.

### Workflow — JetBrains MCP after Java work

When wrapping up a meaningful chunk of Java work (refactor, feature, multi-file change, anything you'd consider "done and ready to commit"), run `mcp__jetbrains__get_file_problems(filePath, onlyErrors=false)` on touched files in parallel and triage before reporting completion. Not required for trivial edits (typo, single-line rename, comment).

Triage rules:
- **Fix:** diamond operators, missing `@NonNull` on overrides in `@NonNullApi` packages, javadoc errors (e.g. `@link` to private member), `Objects::nonNull` over lambda, `List.getLast()` over `list.get(size-1)`, real bugs.
- **Skip:** defensive null checks in belt-and-suspenders guards, `@Tool`/SPI methods flagged "never used" (Spring AI reflection), stylistic `if → switch` rewrites, condition-always-true/false on intentional contract guards.

If JetBrains MCP is not connected, say so and wait — don't silently skip. Pair with module-scoped tests (`./gradlew :module:test`) when behavior could plausibly change.
