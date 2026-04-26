package com.vn.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.metadata.CurrentUserSchemaAccess;
import io.jmix.core.AccessManager;
import io.jmix.core.MessageTools;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaPropertyPath;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BaselineContextProviderTest {

    // --- Existing baseline-key tests (Phase 4 / Plan 04-04) — must continue to pass --- //

    @Test
    void compose_populates_all_baseline_keys() {
        BaselineContextProvider provider = newProvider(b -> b
                .user("alice", "ROLE_USER")
                .locale(Locale.US)
                .schema(emptySchema()));

        UUID convId = UUID.randomUUID();
        Map<String, Object> ctx = provider.compose(convId);

        assertThat(ctx).containsKeys("agent.userId", "agent.username", "agent.roles", "agent.locale", "agent.conversationId");
        assertThat(ctx.get("agent.username")).isEqualTo("alice");
        assertThat(ctx.get("agent.roles")).isEqualTo(Set.of("ROLE_USER"));
        assertThat(ctx.get("agent.locale")).isEqualTo("en_US");
        assertThat(ctx.get("agent.conversationId")).isEqualTo(convId.toString());
    }

    @Test
    void compose_handles_anonymous_user_gracefully() {
        BaselineContextProvider provider = newProvider(b -> b
                .anonymous()
                .schema(emptySchema()));

        Map<String, Object> ctx = provider.compose(null);
        assertThat(ctx.get("agent.username")).isEqualTo("");
        assertThat(ctx.get("agent.roles")).isEqualTo(Set.of());
        assertThat(ctx.get("agent.locale")).isEqualTo(Locale.ROOT.toString());
        assertThat(ctx.get("agent.conversationId")).isNull();
    }

    @Test
    void composeRendersAsTextWithSortedAgentKeys() {
        // Verifies renderAsText() sorts keys alphabetically and joins with \n.
        // Consumed by DefaultChatServiceImpl (Plan 04-04) - byte-stable output is required.
        BaselineContextProvider provider = newProvider(b -> b
                .user("bob", "ROLE_USER")
                .locale(Locale.US)
                .schema(emptySchema()));

        UUID convId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String text = provider.renderAsText(convId);

        for (String line : text.split("\n")) {
            assertThat(line).startsWith("agent.");
        }
        String[] lines = text.split("\n");
        assertThat(lines).hasSize(5);
        assertThat(lines[0]).startsWith("agent.conversationId=");
        assertThat(lines[1]).startsWith("agent.locale=");
        assertThat(lines[2]).startsWith("agent.roles=");
        assertThat(lines[3]).startsWith("agent.userId=");
        assertThat(lines[4]).startsWith("agent.username=");
        assertThat(text).contains("agent.username=bob");
        assertThat(text).contains("agent.conversationId=00000000-0000-0000-0000-000000000001");
    }

    // --- Plan 09-03: agent.entities + agent.permissions tests --- //

    @Test
    void compose_addsEntitiesAndPermissions_whenSchemaNonEmpty() {
        // Three readable entities, full CRUD on Customer, only read on Order, all-zero on User
        // (User entry should be omitted from agent.permissions per D-02).
        Map<MetaClass, Set<String>> schema = new LinkedHashMap<>();
        MetaClass customer = mockMetaClass("acme_Customer");
        MetaClass order = mockMetaClass("acme_Order");
        MetaClass user = mockMetaClass("jmixapp_User");
        schema.put(customer, linkedSet("name", "email"));
        schema.put(order, linkedSet("number"));
        schema.put(user, linkedSet("login"));

        BaselineContextProvider provider = newProvider(b -> b
                .user("alice", "ROLE_USER")
                .locale(Locale.ENGLISH)
                .schema(schema)
                .entityCaption(customer, "Customer")
                .entityCaption(order, "Order")
                .entityCaption(user, "User")
                .crud(customer, true, true, false, false)
                .crud(order, true, false, false, false)
                .crud(user, false, false, false, false)
                .modifiableAttributes(customer, "name", "email")
                .modifiableAttributes(order)
                .modifiableAttributes(user));

        Map<String, Object> ctx = provider.compose(UUID.randomUUID());

        assertThat(ctx).containsKey("agent.entities");
        assertThat(ctx).containsKey("agent.permissions");
        assertThat(ctx.get("agent.entities")).isEqualTo(
                "acme_Customer (Customer)\n"
                        + "acme_Order (Order)\n"
                        + "jmixapp_User (User)");
        // User is omitted (all CRUD bits are zero per D-02).
        assertThat((String) ctx.get("agent.permissions")).isEqualTo(
                "{\"acme_Customer\":{\"r\":1,\"u\":1,\"c\":0,\"d\":0,\"modifiable\":[\"email\",\"name\"]},"
                        + "\"acme_Order\":{\"r\":1,\"u\":0,\"c\":0,\"d\":0,\"modifiable\":[]}}");
    }

    @Test
    void compose_omitsEntitiesAndPermissions_whenSchemaEmpty() {
        BaselineContextProvider provider = newProvider(b -> b
                .anonymous()
                .schema(emptySchema()));

        Map<String, Object> ctx = provider.compose(null);

        assertThat(ctx).doesNotContainKey("agent.entities");
        assertThat(ctx).doesNotContainKey("agent.permissions");
        // Existing five baseline keys still emitted.
        assertThat(ctx).containsKeys("agent.userId", "agent.username", "agent.roles", "agent.locale", "agent.conversationId");
    }

    @Test
    void compose_entityLines_areAlphaSortedByMetaClassName() {
        // Insert in non-alpha order; assert output is alpha-sorted by MetaClass.getName().
        Map<MetaClass, Set<String>> schema = new LinkedHashMap<>();
        MetaClass z = mockMetaClass("zoo_Animal");
        MetaClass a = mockMetaClass("acme_Customer");
        MetaClass m = mockMetaClass("mid_Widget");
        schema.put(z, linkedSet());
        schema.put(a, linkedSet());
        schema.put(m, linkedSet());

        BaselineContextProvider provider = newProvider(b -> b
                .user("alice", "ROLE_USER")
                .locale(Locale.ENGLISH)
                .schema(schema)
                .entityCaption(z, "Animal")
                .entityCaption(a, "Customer")
                .entityCaption(m, "Widget")
                .crud(z, true, false, false, false)
                .crud(a, true, false, false, false)
                .crud(m, true, false, false, false));

        Map<String, Object> ctx = provider.compose(UUID.randomUUID());
        assertThat(ctx.get("agent.entities")).isEqualTo(
                "acme_Customer (Customer)\n"
                        + "mid_Widget (Widget)\n"
                        + "zoo_Animal (Animal)");
    }

    @Test
    void compose_permissionsJson_hasFixedKeyOrderAndAlphaSortedModifiable() {
        // Single entity, multiple modifiable attrs in non-alpha insertion order — modifiable[]
        // must come back alpha-sorted; CRUD keys must be in r,u,c,d,modifiable order.
        Map<MetaClass, Set<String>> schema = new LinkedHashMap<>();
        MetaClass customer = mockMetaClass("acme_Customer");
        // intentionally non-alpha insertion to verify alpha sort
        schema.put(customer, linkedSet("phone", "email", "name", "address"));

        BaselineContextProvider provider = newProvider(b -> b
                .user("alice", "ROLE_USER")
                .locale(Locale.ENGLISH)
                .schema(schema)
                .entityCaption(customer, "Customer")
                .crud(customer, true, true, true, true)
                .modifiableAttributes(customer, "phone", "name"));

        Map<String, Object> ctx = provider.compose(UUID.randomUUID());

        // Fixed key order r,u,c,d,modifiable; modifiable alpha-sorted.
        assertThat((String) ctx.get("agent.permissions")).isEqualTo(
                "{\"acme_Customer\":{\"r\":1,\"u\":1,\"c\":1,\"d\":1,\"modifiable\":[\"name\",\"phone\"]}}");
    }

    @Test
    void compose_permissionsJson_omitsEntriesWithAllCrudBitsZero() {
        // Two entities visible in agent.entities (because they are in the readable schema), but
        // one has all CRUD bits zero (e.g. read removed by row-level rule mid-render). That entity
        // is OMITTED from agent.permissions per D-02.
        Map<MetaClass, Set<String>> schema = new LinkedHashMap<>();
        MetaClass customer = mockMetaClass("acme_Customer");
        MetaClass denied = mockMetaClass("acme_Denied");
        schema.put(customer, linkedSet("name"));
        schema.put(denied, linkedSet("name"));

        BaselineContextProvider provider = newProvider(b -> b
                .user("alice", "ROLE_USER")
                .locale(Locale.ENGLISH)
                .schema(schema)
                .entityCaption(customer, "Customer")
                .entityCaption(denied, "Denied")
                .crud(customer, true, false, false, false)
                .crud(denied, false, false, false, false));

        Map<String, Object> ctx = provider.compose(UUID.randomUUID());

        // Both entities appear in agent.entities (they are in the readable schema).
        assertThat((String) ctx.get("agent.entities"))
                .contains("acme_Customer (Customer)")
                .contains("acme_Denied (Denied)");
        // Only Customer appears in agent.permissions; Denied is dropped.
        assertThat((String) ctx.get("agent.permissions")).isEqualTo(
                "{\"acme_Customer\":{\"r\":1,\"u\":0,\"c\":0,\"d\":0,\"modifiable\":[]}}");
    }

    @Test
    void compose_permissionsJson_isLocaleInvariant_betweenEnglishAndVietnamese() {
        // Render the same schema twice — once with English captions, once with Vietnamese — and
        // assert agent.permissions is byte-equal across both runs (P-8 cache-key safety).
        Map<MetaClass, Set<String>> schema = new LinkedHashMap<>();
        MetaClass customer = mockMetaClass("acme_Customer");
        schema.put(customer, linkedSet("name"));

        BaselineContextProvider english = newProvider(b -> b
                .user("alice", "ROLE_USER")
                .locale(Locale.ENGLISH)
                .schema(schema)
                .entityCaption(customer, "Customer")
                .crud(customer, true, true, false, false)
                .modifiableAttributes(customer, "name"));

        BaselineContextProvider vietnamese = newProvider(b -> b
                .user("alice", "ROLE_USER")
                .locale(Locale.of("vi", "VN"))
                .schema(schema)
                .entityCaption(customer, "Khách hàng")
                .crud(customer, true, true, false, false)
                .modifiableAttributes(customer, "name"));

        Map<String, Object> englishCtx = english.compose(UUID.randomUUID());
        Map<String, Object> vietnameseCtx = vietnamese.compose(UUID.randomUUID());

        // agent.permissions MUST be byte-equal across locales — labels are NOT in this value.
        assertThat(englishCtx.get("agent.permissions")).isEqualTo(vietnameseCtx.get("agent.permissions"));
        // agent.entities WILL differ in the parenthesized label only.
        assertThat(englishCtx.get("agent.entities")).isEqualTo("acme_Customer (Customer)");
        assertThat(vietnameseCtx.get("agent.entities")).isEqualTo("acme_Customer (Khách hàng)");
    }

    @Test
    void compose_entitiesBlock_truncatesAtConfiguredLimitAndAppendsHint() {
        // 150 readable entities; default limit 100 — agent.entities renders 100 lines + 1 hint line.
        Map<MetaClass, Set<String>> schema = new LinkedHashMap<>();
        for (int i = 0; i < 150; i++) {
            String name = String.format("acme_Entity%03d", i);
            MetaClass mc = mockMetaClass(name);
            schema.put(mc, linkedSet());
        }

        BaselineContextProvider provider = newProvider(b -> b
                .user("alice", "ROLE_USER")
                .locale(Locale.ENGLISH)
                .schema(schema)
                .captionAllAs("L")
                .crudAllAs(true, false, false, false));

        Map<String, Object> ctx = provider.compose(UUID.randomUUID());
        String entitiesBlock = (String) ctx.get("agent.entities");
        String[] lines = entitiesBlock.split("\n");
        assertThat(lines).hasSize(101);
        assertThat(lines[100]).isEqualTo("... (truncated, call list_entities for full list)");
        assertThat(lines[0]).isEqualTo("acme_Entity000 (L)");
        assertThat(lines[99]).isEqualTo("acme_Entity099 (L)");
    }

    @Test
    void compose_permissionsDoesNotMentionEntitiesBeyondInventoryLimit() {
        // Review fix: the same sorted/capped entity list drives both blocks. Entities 100..149
        // must NOT appear in agent.permissions JSON.
        Map<MetaClass, Set<String>> schema = new LinkedHashMap<>();
        for (int i = 0; i < 150; i++) {
            String name = String.format("acme_Entity%03d", i);
            MetaClass mc = mockMetaClass(name);
            schema.put(mc, linkedSet());
        }

        BaselineContextProvider provider = newProvider(b -> b
                .user("alice", "ROLE_USER")
                .locale(Locale.ENGLISH)
                .schema(schema)
                .captionAllAs("L")
                .crudAllAs(true, false, false, false));

        Map<String, Object> ctx = provider.compose(UUID.randomUUID());
        String permissionsJson = (String) ctx.get("agent.permissions");
        assertThat(permissionsJson).contains("\"acme_Entity099\":");
        assertThat(permissionsJson).doesNotContain("acme_Entity100");
        assertThat(permissionsJson).doesNotContain("acme_Entity149");
    }

    @Test
    void renderAsText_emitsAgentEntitiesAndAgentPermissionsLinesInAlphaOrder() {
        Map<MetaClass, Set<String>> schema = new LinkedHashMap<>();
        MetaClass customer = mockMetaClass("acme_Customer");
        schema.put(customer, linkedSet("name"));

        BaselineContextProvider provider = newProvider(b -> b
                .user("bob", "ROLE_USER")
                .locale(Locale.US)
                .schema(schema)
                .entityCaption(customer, "Customer")
                .crud(customer, true, false, false, false)
                .modifiableAttributes(customer));

        UUID convId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        String text = provider.renderAsText(convId);
        String[] lines = text.split("\n");
        // Outer keys are alpha-sorted: conversationId, entities (multi-line), locale, permissions, roles, userId, username.
        // Note: the entities VALUE itself contains a literal '\n'; that joins on the outer split.
        assertThat(lines[0]).startsWith("agent.conversationId=");
        // After conversationId line, "agent.entities=acme_Customer (Customer)" line begins.
        assertThat(text).contains("agent.entities=acme_Customer (Customer)");
        assertThat(text).contains("agent.permissions={\"acme_Customer\":{\"r\":1,\"u\":0,\"c\":0,\"d\":0,\"modifiable\":[]}}");
    }

    // --- Helpers --- //

    private static Map<MetaClass, Set<String>> emptySchema() {
        return Map.of();
    }

    private static Set<String> linkedSet(String... values) {
        Set<String> s = new LinkedHashSet<>();
        for (String v : values) s.add(v);
        return s;
    }

    private static MetaClass mockMetaClass(String name) {
        MetaClass mc = mock(MetaClass.class);
        when(mc.getName()).thenReturn(name);
        // Stub getPropertyPath(...) so `new EntityAttributeContext(metaClass, attribute)` succeeds.
        // The constructor calls metaClass.getPropertyPath(attribute) and immediately stores it.
        // We hand back a real MetaPropertyPath built from a mocked single-element MetaProperty
        // chain so getMetaClass() and toString() behave normally inside the AccessManager stub.
        when(mc.getPropertyPath(Mockito.anyString())).thenAnswer(inv -> {
            String attr = inv.getArgument(0);
            io.jmix.core.metamodel.model.MetaProperty mp = mock(io.jmix.core.metamodel.model.MetaProperty.class);
            when(mp.getName()).thenReturn(attr);
            return new MetaPropertyPath(mc, mp);
        });
        return mc;
    }

    private static BaselineContextProvider newProvider(java.util.function.Consumer<BuilderConfig> configure) {
        BuilderConfig cfg = new BuilderConfig();
        configure.accept(cfg);

        CurrentAuthentication ca = mock(CurrentAuthentication.class);
        if (cfg.anonymous) {
            when(ca.getUser()).thenReturn(null);
            when(ca.getLocale()).thenReturn(null);
        } else {
            when(ca.getUser()).thenReturn(new User(cfg.username, "x",
                    List.of(new SimpleGrantedAuthority(cfg.role))));
            when(ca.getLocale()).thenReturn(cfg.locale);
        }

        CurrentUserSchemaAccess schemaAccess = mock(CurrentUserSchemaAccess.class);
        when(schemaAccess.getReadableSchema()).thenReturn(cfg.schema);

        AccessManager accessManager = mock(AccessManager.class);
        // Apply CRUD bits to whatever CrudEntityContext is passed in.
        // Apply modifiable bits to whatever EntityAttributeContext is passed in.
        doAnswer(invocation -> {
            Object ctxArg = invocation.getArgument(0);
            if (ctxArg instanceof CrudEntityContext crud) {
                MetaClass mc = crud.getEntityClass();
                CrudBits bits = cfg.crudByEntity.getOrDefault(mc, cfg.defaultCrud);
                if (bits != null) {
                    if (!bits.read) crud.setReadDenied();
                    if (!bits.update) crud.setUpdateDenied();
                    if (!bits.create) crud.setCreateDenied();
                    if (!bits.delete) crud.setDeleteDenied();
                }
            } else if (ctxArg instanceof EntityAttributeContext attr) {
                MetaPropertyPath path = attr.getPropertyPath();
                MetaClass mc = path.getMetaClass();
                String attributeName = path.toString();
                Set<String> modifiable = cfg.modifiableByEntity.get(mc);
                boolean canModify = modifiable != null && modifiable.contains(attributeName);
                if (!canModify) {
                    attr.setModifyDenied();
                }
            }
            return null;
        }).when(accessManager).applyRegisteredConstraints(any());

        MessageTools messageTools = mock(MessageTools.class);
        when(messageTools.getEntityCaption(any(MetaClass.class))).thenAnswer(inv -> {
            MetaClass mc = inv.getArgument(0);
            return cfg.captionsByEntity.getOrDefault(mc, cfg.defaultCaption != null ? cfg.defaultCaption : mc.getName());
        });

        ObjectMapper objectMapper = new ObjectMapper();
        AiAgentPromptProperties promptProperties = new AiAgentPromptProperties(null);

        return new BaselineContextProvider(ca, schemaAccess, accessManager, messageTools,
                objectMapper, promptProperties);
    }

    private record CrudBits(boolean read, boolean update, boolean create, boolean delete) {
    }

    private static final class BuilderConfig {
        boolean anonymous = false;
        String username = "alice";
        String role = "ROLE_USER";
        Locale locale = Locale.ENGLISH;
        Map<MetaClass, Set<String>> schema = Map.of();
        Map<MetaClass, String> captionsByEntity = new LinkedHashMap<>();
        String defaultCaption = null;
        Map<MetaClass, CrudBits> crudByEntity = new LinkedHashMap<>();
        CrudBits defaultCrud = null;
        Map<MetaClass, Set<String>> modifiableByEntity = new LinkedHashMap<>();

        BuilderConfig anonymous() {
            this.anonymous = true;
            return this;
        }

        BuilderConfig user(String username, String role) {
            this.username = username;
            this.role = role;
            return this;
        }

        BuilderConfig locale(Locale locale) {
            this.locale = locale;
            return this;
        }

        BuilderConfig schema(Map<MetaClass, Set<String>> schema) {
            this.schema = schema;
            return this;
        }

        BuilderConfig entityCaption(MetaClass mc, String caption) {
            captionsByEntity.put(mc, caption);
            return this;
        }

        BuilderConfig captionAllAs(String caption) {
            this.defaultCaption = caption;
            return this;
        }

        BuilderConfig crud(MetaClass mc, boolean r, boolean u, boolean c, boolean d) {
            crudByEntity.put(mc, new CrudBits(r, u, c, d));
            return this;
        }

        BuilderConfig crudAllAs(boolean r, boolean u, boolean c, boolean d) {
            this.defaultCrud = new CrudBits(r, u, c, d);
            return this;
        }

        BuilderConfig modifiableAttributes(MetaClass mc, String... attrs) {
            modifiableByEntity.put(mc, Set.of(attrs));
            return this;
        }
    }

    @SuppressWarnings("unused")
    private static void touch(Mockito m) {
        // forces import resolution
    }
}
