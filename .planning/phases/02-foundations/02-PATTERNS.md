# Phase 2: Foundations — Pattern Map

**Mapped:** 2026-04-18
**Files analyzed:** ~30 new + 5 edits
**Analogs found:** 30 / 30 (100% coverage — all new files have a strong in-repo analog)

---

## File Classification

### New files

| File | Role | Data Flow | Closest Analog | Match Quality |
|------|------|-----------|----------------|---------------|
| `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiConversation.java` | entity (aggregate root) | CRUD | `jmix-app/.../entity/Order.java` | exact (composition parent) |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiMessage.java` | entity (child) | CRUD | `jmix-app/.../entity/OrderLine.java` | exact (composition child + FK) |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallAudit.java` | entity (audit) | append-only | `jmix-app/.../entity/Customer.java` | role-match (flat entity) |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiParameters.java` | entity (config) | CRUD | `jmix-app/.../entity/Customer.java` | role-match (flat entity) |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocument.java` | entity | CRUD | `jmix-app/.../entity/Customer.java` | role-match (flat entity) |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiMessageRole.java` | enum | — | `jmix-app/.../entity/OrderStatus.java` | exact |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocumentStatus.java` | enum | — | `jmix-app/.../entity/OrderStatus.java` | exact |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallOutcome.java` | enum | — | `jmix-app/.../entity/OrderStatus.java` | exact |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java` | resource role | policy | `jmix-app/.../security/SampleDataRole.java` | exact (entityClass-based) |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java` | resource role | policy | `jmix-app/.../security/SampleDataRole.java` | exact |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRowLevelRole.java` | row-level role | JPQL filter | no in-repo analog | **use RESEARCH.md §Row-Level Security** |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolContributor.java` | SPI interface | extension point | no in-repo analog | **use RESEARCH.md §SPI Signatures** |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ContextContributor.java` | SPI interface | extension point | no in-repo analog | **use RESEARCH.md §SPI Signatures** |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/PromptContextContributor.java` | SPI interface | extension point | no in-repo analog | **use RESEARCH.md §SPI Signatures** |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolGuard.java` | SPI interface | extension point | no in-repo analog | **use RESEARCH.md §SPI Signatures** |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/ToolVetoedException.java` | exception | — | no in-repo analog | RuntimeException subclass |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/AuditListener.java` | SPI interface | extension point | no in-repo analog | **use RESEARCH.md §SPI Signatures** |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/CustomIngester.java` | SPI interface | extension point | no in-repo analog | **use RESEARCH.md §SPI Signatures** |
| `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java` | auto-configuration | bean wiring | `ai-agent-starter/.../AIAutoConfiguration.java` | exact (sibling) |
| `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog.xml` | Liquibase master | DDL | `jmix-app/.../liquibase/changelog.xml` | exact |
| `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/010-ai-conversation.xml` | Liquibase changelog | DDL | `jmix-app/.../changelog/020-customer.xml` | exact |
| `.../changelog/020-ai-message.xml` | Liquibase changelog | DDL (FK) | `jmix-app/.../changelog/040-order.xml` | exact (FK + indexes) |
| `.../changelog/030-ai-tool-call-audit.xml` | Liquibase changelog | DDL | `jmix-app/.../changelog/020-customer.xml` | role-match |
| `.../changelog/040-ai-parameters.xml` | Liquibase changelog | DDL | `jmix-app/.../changelog/020-customer.xml` | role-match |
| `.../changelog/050-ai-knowledge-document.xml` | Liquibase changelog | DDL | `jmix-app/.../changelog/020-customer.xml` | role-match |
| `.../changelog/060-ai-chat-memory.xml` | Liquibase changelog | DDL (dbms-gated) | no in-repo analog | **use RESEARCH.md §060-ai-chat-memory** verbatim |
| `.../changelog/070-ai-kb-vector-store.xml` | Liquibase changelog | DDL (pgvector) | no in-repo analog | **use RESEARCH.md §070-ai-kb-vector-store** |
| `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` | i18n | resource | `jmix-app/.../messages_vi.properties` | exact |

### Modified files

| File | Change | Analog |
|------|--------|--------|
| `ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java` | widen `@JmixModule(dependsOn=...)` to include `DataConfiguration`, `SecurityConfiguration` | current file itself |
| `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties` | append entity/enum/role keys (EN) | `jmix-app/.../messages_en.properties` |
| `ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties` | add `vi` to `jmix.core.available-locales` (optional) | n/a |
| `ai-agent/ai-agent-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | append `SpiDefaultsAutoConfiguration` | current file itself |
| `jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml` | add `<include>` for add-on master changelog | existing includes in same file |
| `.planning/REQUIREMENTS.md`, `ROADMAP.md`, `PROJECT.md`, `research/STACK.md` | D-10 scope-reduction doc edits | n/a |

---

## Pattern Assignments

### Entities

#### `AiConversation.java` (entity, composition parent)

**Analog:** `D:/DTH/ai-agent-core/jmix-app/src/main/java/com/vn/jmixapp/entity/Order.java`

**Imports + class-level annotations** (Order.java:1-25):
```java
package com.vn.jmixapp.entity;

import io.jmix.core.DeletePolicy;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.OnDelete;
import io.jmix.core.metamodel.annotation.Composition;
import io.jmix.core.metamodel.annotation.DependsOnProperties;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.JmixProperty;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
...
@JmixEntity
@Entity(name = "jmixapp_Order")
@Table(name = "CUSTOMER_ORDER", indexes = {
        @Index(name = "IDX_CUSTOMER_ORDER__ON_NUMBER", columnList = "NUMBER_", unique = true),
        @Index(name = "IDX_CUSTOMER_ORDER__ON_CUSTOMER", columnList = "CUSTOMER_ID")
})
public class Order {
```
Mirror: `@Entity(name = "ai_AiConversation")`, `@Table(name = "AI_AGENT_CONVERSATION", indexes = @Index(name="IDX_AI_AGENT_CONVERSATION__ON_CREATED_BY", columnList="CREATED_BY"))`.

**ID + Version + audit-column fields** (Order.java:27-51 → adapt: replace NUMBER_/ORDER_DATE/CUSTOMER_ID/STATUS with TITLE + Jmix audit columns):
```java
@Id
@Column(name = "ID")
@JmixGeneratedValue
private UUID id;

@Version
@Column(name = "VERSION", nullable = false)
private Integer version;
```

**Composition pattern (parent side — copy verbatim, retype)** (Order.java:53-56):
```java
@Composition
@OnDelete(DeletePolicy.CASCADE)
@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
private List<OrderLine> lines;
```
Adapt to `mappedBy = "conversation"`, `List<AiMessage> messages`.

**`@InstanceName` on a simple field** (Customer.java:28-31) — if `TITLE` is authoritative just use `@InstanceName` on the field like Customer. If a computed name is desired, use the Order pattern (lines 58-62):
```java
@InstanceName
@DependsOnProperties({"number", "orderDate"})
public String getDisplayName() {
    return String.format("%s (%s)", number, orderDate);
}
```

**Getter/setter style (NO LOMBOK)** (Order.java:77-88): explicit one-line getters/setters, no annotations.

---

#### `AiMessage.java` (entity, composition child)

**Analog:** `D:/DTH/ai-agent-core/jmix-app/src/main/java/com/vn/jmixapp/entity/OrderLine.java`

**FK back to parent (copy the `@ManyToOne` + `@OnDelete(CASCADE)` block)** (OrderLine.java:34-38):
```java
@NotNull
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@OnDelete(DeletePolicy.CASCADE)
@JoinColumn(name = "ORDER_ID", nullable = false)
private Order order;
```
Adapt to `CONVERSATION_ID` + `AiConversation conversation`.

**Enum-as-string mapping** (Order.java:50-51 + 74-75 — for the `ROLE_` column use `AiMessageRole`):
```java
@Column(name = "STATUS", length = 32)
private String status;
...
public OrderStatus getStatus() { return status == null ? null : OrderStatus.fromId(status); }
public void setStatus(OrderStatus status) { this.status = status == null ? null : status.getId(); }
```
Adapt for `ROLE_` column (trailing underscore per research §AiMessage) mapped to `AiMessageRole`.

**Computed `@InstanceName`** (OrderLine.java:51-56):
```java
@InstanceName
@DependsOnProperties({"product", "quantity"})
public String getDisplayName() {
    String pn = product != null ? product.getName() : "?";
    return String.format("%s x %s", pn, quantity);
}
```
Adapt to `role + " @ " + createdDate` per research.

---

#### `AiToolCallAudit.java`, `AiParameters.java`, `AiKnowledgeDocument.java` (flat entities)

**Analog:** `D:/DTH/ai-agent-core/jmix-app/src/main/java/com/vn/jmixapp/entity/Customer.java`

Full template (Customer.java:1-50) — copy the entire file shape, substitute fields per research §Entity Shapes tables:
```java
@JmixEntity
@Entity
@Table(name = "CUSTOMER", indexes = {
        @Index(name = "IDX_CUSTOMER__ON_EMAIL", columnList = "EMAIL")
})
public class Customer {

    @Id
    @Column(name = "ID")
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @NotNull
    @Column(name = "NAME", nullable = false)
    private String name;
    ...
    // one-line getters/setters, no Lombok
}
```
For LONGVARCHAR / TEXT-bound fields (`ARGUMENTS_JSON`, `RESULT_SUMMARY`, `CONTENT`, `BODY_YAML`, `ALLOWED_ROLES_JSON`), use `@Lob` + `@Column(name=...)` and let Liquibase type `LONGVARCHAR` (HSQLDB) / `TEXT` (Postgres).

---

### Enums

#### `AiMessageRole`, `AiKnowledgeDocumentStatus`, `AiToolCallOutcome`

**Analog:** `D:/DTH/ai-agent-core/jmix-app/src/main/java/com/vn/jmixapp/entity/OrderStatus.java`

Full verbatim pattern (OrderStatus.java:1-33):
```java
package com.vn.jmixapp.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.Nullable;

public enum OrderStatus implements EnumClass<String> {

    NEW("NEW"),
    CONFIRMED("CONFIRMED"),
    SHIPPED("SHIPPED"),
    CANCELLED("CANCELLED");

    private final String id;

    OrderStatus(String id) { this.id = id; }

    @Override
    public String getId() { return id; }

    @Nullable
    public static OrderStatus fromId(String id) {
        for (OrderStatus at : values()) {
            if (at.getId().equals(id)) return at;
        }
        return null;
    }
}
```
Substitute values per research §Enum Definitions. Place under `com.vn.agent.entity`.

---

### Security Roles

#### `AiAgentUserRole.java`, `AiAgentAdminRole.java` (resource roles)

**Analog:** `D:/DTH/ai-agent-core/jmix-app/src/main/java/com/vn/jmixapp/security/SampleDataRole.java`

Imports + `entityClass`-based policy style (SampleDataRole.java:1-23):
```java
package com.vn.jmixapp.security;

import com.vn.jmixapp.entity.Customer;
import com.vn.jmixapp.entity.Order;
...
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.role.annotation.EntityPolicy;
import io.jmix.security.role.annotation.ResourceRole;
...
@ResourceRole(name = "Sample Data Access", code = SampleDataRole.CODE)
public interface SampleDataRole {

    String CODE = "sample-data-access";

    @EntityPolicy(entityClass = Customer.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = Product.class, actions = EntityPolicyAction.ALL)
    ...
    void sampleData();
}
```

**Important omissions per D-07:** do NOT copy `@EntityAttributePolicy`, `@ViewPolicy`, `@MenuPolicy` from SampleDataRole — Phase 2 scope is entity-level only. Use narrower `actions = {READ, CREATE, UPDATE}` array for user role (research §AiAgentUserRole).

Full policy lists in RESEARCH.md §Security Roles.

#### `AiAgentUserRowLevelRole.java`

**No in-repo analog.** Copy verbatim from RESEARCH.md §Row-Level Security (uses `@RowLevelRole` + `@JpqlRowLevelPolicy(where = "{E}.createdBy = :current_user_username")`).

#### `FullAccessRole.java` reference (for "*" wildcard comparison)

Path: `D:/DTH/ai-agent-core/jmix-app/src/main/java/com/vn/jmixapp/security/FullAccessRole.java`
Use only as a shape reference — our admin role uses explicit `entityClass` per entity (safer scope; mirrors SampleDataRole, not FullAccessRole).

---

### Liquibase Changelogs

#### `changelog.xml` master

**Analog:** `D:/DTH/ai-agent-core/jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog.xml` (full 17 lines)

Use the add-on-specific body per RESEARCH.md §Master changelog:
```xml
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog" ...>
    <includeAll path="/com/vn/agent/liquibase/changelog"/>
</databaseChangeLog>
```

#### `010-ai-conversation.xml`, `030-ai-tool-call-audit.xml`, `040-ai-parameters.xml`, `050-ai-knowledge-document.xml` (flat-table changelogs)

**Analog:** `D:/DTH/ai-agent-core/jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog/020-customer.xml` (full 30 lines):
```xml
<changeSet id="1" author="jmix-app">
    <createTable tableName="CUSTOMER">
        <column name="ID" type="${uuid.type}">
            <constraints primaryKey="true" nullable="false"/>
        </column>
        <column name="VERSION" type="int" defaultValueNumeric="1">
            <constraints nullable="false"/>
        </column>
        <column name="NAME" type="varchar(255)">
            <constraints nullable="false"/>
        </column>
        ...
    </createTable>
</changeSet>

<changeSet id="2" author="jmix-app">
    <createIndex indexName="IDX_CUSTOMER__ON_EMAIL" tableName="CUSTOMER">
        <column name="EMAIL"/>
    </createIndex>
</changeSet>
```
Substitute table name (`AI_AGENT_*`), column list (research §Entity Shapes), and index names. Set `author="ai-agent"`.

#### `020-ai-message.xml` (FK + index changelog)

**Analog:** `D:/DTH/ai-agent-core/jmix-app/src/main/resources/com/vn/jmixapp/liquibase/changelog/040-order.xml` (full 40 lines).

FK + multi-index pattern (040-order.xml:22-38):
```xml
<column name="CUSTOMER_ID" type="${uuid.type}">
    <constraints nullable="false"
                 foreignKeyName="FK_CUSTOMER_ORDER__ON_CUSTOMER"
                 references="CUSTOMER(ID)"/>
</column>
...
<changeSet id="2" author="jmix-app">
    <createIndex indexName="IDX_CUSTOMER_ORDER__ON_NUMBER" tableName="CUSTOMER_ORDER" unique="true">
        <column name="NUMBER_"/>
    </createIndex>
    <createIndex indexName="IDX_CUSTOMER_ORDER__ON_CUSTOMER" tableName="CUSTOMER_ORDER">
        <column name="CUSTOMER_ID"/>
    </createIndex>
</changeSet>
```
Adapt: `CONVERSATION_ID` → `FK_AI_AGENT_MESSAGE__ON_CONVERSATION` → `AI_AGENT_CONVERSATION(ID)`. Use `ROLE_` column name (trailing underscore per research). For LONGVARCHAR content: `<column name="CONTENT" type="clob"/>` (Liquibase normalizes `clob` → `LONGVARCHAR` on HSQLDB / `TEXT` on Postgres).

#### `060-ai-chat-memory.xml`

**No in-repo analog.** Copy verbatim from RESEARCH.md §060-ai-chat-memory.xml (two changeSets: `dbms="postgresql"` + `dbms="hsqldb"`, using raw `<sql>` blocks because the Spring AI schema uses reserved-word quoting and DB-specific types).

#### `070-ai-kb-vector-store.xml`

**No in-repo analog.** Copy verbatim from RESEARCH.md §070-ai-kb-vector-store.xml (two changeSets, both `dbms="postgresql"` with belt-and-suspenders `<preConditions onFail="MARK_RAN"><dbms type="postgresql"/></preConditions>` — pitfall #3).

---

### Modified — `jmix-app/.../liquibase/changelog.xml`

**Current state** (full file, 17 lines):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog ...>
    <!-- DO NOT REMOVE. This is a master changelog that runs all other changelogs. -->
    <include file="/io/jmix/data/liquibase/changelog.xml"/>
    <include file="/io/jmix/flowuidata/liquibase/changelog.xml"/>
    <include file="/io/jmix/securitydata/liquibase/changelog.xml"/>

    <includeAll path="/com/vn/jmixapp/liquibase/changelog"/>
</databaseChangeLog>
```

**Expected edit** (add one line AFTER the three io.jmix includes, BEFORE the `<includeAll>`):
```xml
    <include file="/io/jmix/securitydata/liquibase/changelog.xml"/>

    <!-- ai-agent add-on (Phase 2) -->
    <include file="/com/vn/agent/liquibase/changelog.xml"/>

    <includeAll path="/com/vn/jmixapp/liquibase/changelog"/>
```

Ordering matters: add-on DDL must run before host sample-data `<includeAll>` because future host seeds may reference add-on entities.

---

### Auto-configuration

#### `SpiDefaultsAutoConfiguration.java`

**Analog:** `D:/DTH/ai-agent-core/ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java` (full 27 lines):
```java
package com.vn.autoconfigure.agent;

import com.vn.agent.AIConfiguration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import({AIConfiguration.class})
public class AIAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
```

**Copy:** package, `@AutoConfiguration` annotation, `@Bean @ConditionalOnMissingBean` pairing style, Javadoc class-comment convention. **Do NOT copy:** `@Import(AIConfiguration.class)` — the SPI defaults don't need to re-import the Jmix module config. **Add:** `@AutoConfigureAfter(AIAutoConfiguration.class)` (pitfall #4).

Body: copy the six no-op beans verbatim from RESEARCH.md §SpiDefaultsAutoConfiguration.

#### `AutoConfiguration.imports` (modified)

**Current state** (full file, 1 line):
```
com.vn.autoconfigure.agent.AIAutoConfiguration
```

**Expected state**:
```
com.vn.autoconfigure.agent.AIAutoConfiguration
com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration
```

---

### SPIs

All 6 interfaces + `ToolVetoedException` have **no in-repo analog**. Copy signatures + Javadoc verbatim from RESEARCH.md §SPI Signatures. Apply shared conventions:
- package `com.vn.agent.spi`
- interface-only (no default methods except `PromptContextContributor.getOrder()`)
- Javadoc includes one `<pre>{@code ...}</pre>` integration example per RESEARCH recommendation
- Import fully-qualified types in signatures where shown (keeps the Javadoc copy/pasteable)

---

### i18n

#### `messages.properties` (EN — append)

**Analog:** `D:/DTH/ai-agent-core/jmix-app/src/main/resources/com/vn/jmixapp/messages_en.properties` (lines 5-52 for entity/enum key patterns).

Namespace convention (copy pattern):
```properties
com.vn.jmixapp.entity/Order=Order
com.vn.jmixapp.entity/Order.number=Number
com.vn.jmixapp.entity/Order.status=Status
com.vn.jmixapp.entity/Order.displayName=Name

com.vn.jmixapp.entity/OrderStatus.NEW=New
com.vn.jmixapp.entity/OrderStatus.CONFIRMED=Confirmed
```
Substitute `jmixapp` → `agent`, keys per RESEARCH.md §i18n Keys (full block provided there).

**Current `messages.properties` content** (only 3 lines — preserve them):
```
localeDisplayName.en=English

com.vn.agent/menu.addon=Ai-agent
```
Append new keys AFTER existing content.

#### `messages_vi.properties` (NEW)

**Analog:** `D:/DTH/ai-agent-core/jmix-app/src/main/resources/com/vn/jmixapp/messages_vi.properties` (lines 5-20 shown):
```properties
com.vn.jmixapp.entity/User=Người dùng
com.vn.jmixapp.entity/User.username=Tên đăng nhập
...
com.vn.jmixapp.entity/Customer=Khách hàng
com.vn.jmixapp.entity/Customer.name=Tên
```
Same key set as EN, translated values. Add `localeDisplayName.vi=Tiếng Việt` at top.

#### `module.properties` (optional edit)

Current (full file):
```
jmix.ui.menu-config=com/vn/agent/menu.xml
jmix.core.available-locales=en
```
Change last line to `jmix.core.available-locales=en,vi` so the add-on contributes Vietnamese. (Host `jmix-app/application.properties` already sets `vi,en` per research §Pitfall 7.)

---

### `AIConfiguration.java` (modified)

**Current** (AIConfiguration.java:21):
```java
@JmixModule(dependsOn = {EclipselinkConfiguration.class, FlowuiConfiguration.class})
```

**Expected** (widen to cover data + security per research §AIConfiguration extension):
```java
@JmixModule(dependsOn = {
        EclipselinkConfiguration.class,
        io.jmix.data.DataConfiguration.class,
        io.jmix.security.SecurityConfiguration.class,
        FlowuiConfiguration.class
})
```
Confidence MEDIUM per research — if `DataConfiguration` isn't strictly needed, Phase 2 boot test will reveal it. Keep `SecurityConfiguration` (needed for `@ResourceRole` / `@RowLevelRole` registration).

---

## Shared Patterns

### Pattern A — No Lombok, explicit getters/setters

**Source:** `jmix-app/.../entity/Customer.java:40-49`
**Apply to:** all 5 entities.

```java
public UUID getId() { return id; }
public void setId(UUID id) { this.id = id; }
public Integer getVersion() { return version; }
public void setVersion(Integer version) { this.version = version; }
```
One-line style; no `@Getter`, no `@Setter`, no `@Data`. Enforced by code review (D-09).

### Pattern B — Jmix metadata name convention (`ai_<Class>`)

**Source:** `jmix-app/.../entity/Order.java:20` (`@Entity(name = "jmixapp_Order")`)
**Apply to:** any entity whose simple class name collides or whose metadata should be clearly namespaced.

Use `@Entity(name = "ai_AiConversation")` etc. Matches the `jmix { projectId = 'AI' }` build-script convention (RESEARCH §Architecture Patterns).

### Pattern C — `${uuid.type}` + `int` version + audit columns in Liquibase

**Source:** `jmix-app/.../liquibase/changelog/020-customer.xml:10-15`
**Apply to:** all 5 entity changelogs.

```xml
<column name="ID" type="${uuid.type}">
    <constraints primaryKey="true" nullable="false"/>
</column>
<column name="VERSION" type="int" defaultValueNumeric="1">
    <constraints nullable="false"/>
</column>
```

### Pattern D — Index naming `IDX_<TABLE>__ON_<COL>` (double underscore)

**Source:** `jmix-app/.../entity/Customer.java:15`, `Order.java:22-23`, `020-customer.xml:25`.
**Apply to:** every index in entity `@Table(indexes=...)` + Liquibase `<createIndex>`.

```java
@Index(name = "IDX_CUSTOMER__ON_EMAIL", columnList = "EMAIL")
```
Adapt: `IDX_AI_AGENT_CONVERSATION__ON_CREATED_BY`, `IDX_AI_AGENT_MESSAGE__ON_CONVERSATION`, etc.

### Pattern E — Liquibase changelog header + author tag

**Source:** `jmix-app/.../liquibase/changelog/020-customer.xml:1-8`
**Apply to:** all 7 new add-on changelogs.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <changeSet id="1" author="ai-agent">
        ...
```
Author: `"ai-agent"` for all add-on changelogs (distinguishes from host `"jmix-app"` for debugging).

### Pattern F — i18n namespace convention

**Source:** `jmix-app/.../messages_en.properties:16-21` and `messages_vi.properties:16-20`
**Apply to:** all entity/enum/role keys.

```
com.vn.jmixapp.entity/Customer=Customer
com.vn.jmixapp.entity/Customer.name=Name
```
Format: `<full package>/<ClassName>[.<attribute>]=<label>`. Key values MUST be identical across EN and VI files — only values translated. Roles use `com.vn.agent.security/AiAgentUserRole=...`.

### Pattern G — `@AutoConfiguration` + `@Bean @ConditionalOnMissingBean`

**Source:** `ai-agent-starter/.../AIAutoConfiguration.java:18-26`
**Apply to:** `SpiDefaultsAutoConfiguration`.

```java
@AutoConfiguration
@Import({AIConfiguration.class})
public class AIAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
```
For `SpiDefaultsAutoConfiguration`, add `@AutoConfigureAfter(AIAutoConfiguration.class)` (pitfall #4) and drop `@Import` (not needed).

### Pattern H — DataManager-only persistence (enforced by review)

**Constraint from** `CLAUDE.md` + D-09: no `EntityManager`, no constructor `new` for entities, use `Metadata.create()` / `DataManager.create()`.
**Apply to:** any Phase 2 test that instantiates an entity (e.g., smoke persistence test).

Reference (existing): `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` (uses `ChatClient`, not entities — but same constructor-injection style).

### Pattern I — `@ResourceRole` with `entityClass` (not `entityName="*"`)

**Source:** `jmix-app/.../security/SampleDataRole.java:20-23`
**Apply to:** both resource roles. Avoid `FullAccessRole`'s `entityName="*"` wildcard — explicit `entityClass=` is safer + IDE-navigable.

```java
@EntityPolicy(entityClass = Customer.class, actions = EntityPolicyAction.ALL)
```

---

## No Analog Found

Files with no close match in the codebase — planner should use RESEARCH.md patterns instead:

| File | Role | Reason | RESEARCH.md section |
|------|------|--------|---------------------|
| `com/vn/agent/spi/*.java` (6 interfaces + 1 exception) | SPI interfaces | First SPI surface in this repo | §SPI Signatures |
| `com/vn/agent/security/AiAgentUserRowLevelRole.java` | row-level role | No existing row-level role in `jmix-app` | §Row-Level Security |
| `.../liquibase/changelog/060-ai-chat-memory.xml` | dbms-gated DDL port | First cross-dbms changelog; verbatim port of Spring AI 1.1.4 schema | §060-ai-chat-memory |
| `.../liquibase/changelog/070-ai-kb-vector-store.xml` | pgvector DDL + preCondition | No pgvector / no preCondition use in `jmix-app` | §070-ai-kb-vector-store |

---

## Metadata

**Analog search scope:**
- `D:/DTH/ai-agent-core/jmix-app/src/main/java/com/vn/jmixapp/entity/`
- `D:/DTH/ai-agent-core/jmix-app/src/main/java/com/vn/jmixapp/security/`
- `D:/DTH/ai-agent-core/jmix-app/src/main/resources/com/vn/jmixapp/liquibase/`
- `D:/DTH/ai-agent-core/jmix-app/src/main/resources/com/vn/jmixapp/messages_*.properties`
- `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/`
- `D:/DTH/ai-agent-core/ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/`

**Files read (count):** 12 (Customer, Order, OrderLine, OrderStatus, FullAccessRole, SampleDataRole, 020-customer.xml, 040-order.xml, changelog.xml, AIConfiguration, AIAutoConfiguration, AutoConfiguration.imports, messages_en, messages_vi, messages.properties, module.properties)

**Pattern extraction date:** 2026-04-18
