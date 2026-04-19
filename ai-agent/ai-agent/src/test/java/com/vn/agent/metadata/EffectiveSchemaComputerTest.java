package com.vn.agent.metadata;

import io.jmix.core.AccessManager;
import io.jmix.core.MessageTools;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.metamodel.model.MetaClass;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link EffectiveSchemaComputer} — stateless per-request filter.
 * Uses {@link Mockito#mockConstruction(Class, MockedConstruction.MockInitializer)} to
 * control {@link CrudEntityContext#isReadPermitted()} / {@link EntityAttributeContext#canView()}
 * by-MetaClass, so denied entities are proved absent from {@code forCurrentUser()}
 * (success criterion #2 — restricted user sees zero entities).
 */
class EffectiveSchemaComputerTest {

    private static MetaClass entityMock(String name) {
        MetaClass mc = mock(MetaClass.class);
        lenient().when(mc.getName()).thenReturn(name);
        // forCurrentUser() calls mc.findProperty(attr.name()) for locale-label lookup.
        // Returning null is a valid result (property absent in metamodel) — the computer
        // guards with `if (mp != null)` before calling MessageTools. Keeps this unit test
        // free of the nested-stubbing Mockito pitfall (UnfinishedStubbingException).
        lenient().when(mc.findProperty(Mockito.anyString())).thenReturn(null);
        return mc;
    }

    private static AiEntityInfo entity(MetaClass mc, String name) {
        AiAttributeInfo attr = new AiAttributeInfo("name", "String", true, null, null, List.of(), null);
        return new AiEntityInfo(mc, name, null, List.of(attr));
    }

    @Test
    void deniedEntityIsAbsent() {
        AccessManager accessManager = mock(AccessManager.class);
        MessageTools messageTools = mock(MessageTools.class);
        MetamodelScanner scanner = mock(MetamodelScanner.class);

        MetaClass mcA = entityMock("entityA");
        MetaClass mcB = entityMock("entityB");
        when(scanner.getRawSchema()).thenReturn(new AiSchema(Map.of(
                "entityA", entity(mcA, "entityA"),
                "entityB", entity(mcB, "entityB"))));

        lenient().when(messageTools.getEntityCaption(Mockito.any())).thenReturn("label");
        lenient().when(messageTools.getPropertyCaption(Mockito.any())).thenReturn("attr-label");

        try (MockedConstruction<CrudEntityContext> crud = Mockito.mockConstruction(
                CrudEntityContext.class,
                (mockCtx, ctx) -> {
                    // Evaluate name BEFORE the when() call — calling a mocked method
                    // inside an ongoing stubbing trips Mockito's UnfinishedStubbingException.
                    MetaClass arg = (MetaClass) ctx.arguments().get(0);
                    String argName = arg.getName();
                    boolean permitted = !"entityA".equals(argName);
                    when(mockCtx.isReadPermitted()).thenReturn(permitted);
                });
             MockedConstruction<EntityAttributeContext> attr = Mockito.mockConstruction(
                     EntityAttributeContext.class,
                     (mockCtx, ctx) -> when(mockCtx.canView()).thenReturn(true))) {

            EffectiveSchemaComputer computer =
                    new EffectiveSchemaComputer(accessManager, scanner, messageTools);
            AiSchema effective = computer.forCurrentUser();

            assertThat(effective.entities()).containsOnlyKeys("entityB");
            assertThat(effective.entities()).doesNotContainKey("entityA");
        }
    }

    @Test
    void restrictedUserWithAllEntitiesDeniedReturnsEmpty() {
        AccessManager accessManager = mock(AccessManager.class);
        MessageTools messageTools = mock(MessageTools.class);
        MetamodelScanner scanner = mock(MetamodelScanner.class);

        MetaClass mcA = entityMock("entityA");
        MetaClass mcB = entityMock("entityB");
        when(scanner.getRawSchema()).thenReturn(new AiSchema(Map.of(
                "entityA", entity(mcA, "entityA"),
                "entityB", entity(mcB, "entityB"))));

        try (MockedConstruction<CrudEntityContext> crud = Mockito.mockConstruction(
                CrudEntityContext.class,
                (mockCtx, ctx) -> when(mockCtx.isReadPermitted()).thenReturn(false))) {

            EffectiveSchemaComputer computer =
                    new EffectiveSchemaComputer(accessManager, scanner, messageTools);
            AiSchema effective = computer.forCurrentUser();

            // Success criterion #2: a user with read policy missing returns zero entries.
            assertThat(effective.entities()).isEmpty();
        }
    }

    @Test
    void deniedAttributeIsFilteredButEntityKept() {
        AccessManager accessManager = mock(AccessManager.class);
        MessageTools messageTools = mock(MessageTools.class);
        MetamodelScanner scanner = mock(MetamodelScanner.class);

        MetaClass mc = entityMock("entityA");
        AiAttributeInfo a1 = new AiAttributeInfo("visible", "String", true, null, null, List.of(), null);
        AiAttributeInfo a2 = new AiAttributeInfo("hidden", "String", true, null, null, List.of(), null);
        when(scanner.getRawSchema()).thenReturn(new AiSchema(Map.of(
                "entityA", new AiEntityInfo(mc, "entityA", null, List.of(a1, a2)))));

        lenient().when(messageTools.getEntityCaption(Mockito.any())).thenReturn("A");
        lenient().when(messageTools.getPropertyCaption(Mockito.any())).thenReturn("label");

        try (MockedConstruction<CrudEntityContext> crud = Mockito.mockConstruction(
                CrudEntityContext.class,
                (mockCtx, ctx) -> when(mockCtx.isReadPermitted()).thenReturn(true));
             MockedConstruction<EntityAttributeContext> attr = Mockito.mockConstruction(
                     EntityAttributeContext.class,
                     (mockCtx, ctx) -> {
                         // argument 1 (index 1) is the attribute name
                         String n = (String) ctx.arguments().get(1);
                         when(mockCtx.canView()).thenReturn(!"hidden".equals(n));
                     })) {

            EffectiveSchemaComputer computer =
                    new EffectiveSchemaComputer(accessManager, scanner, messageTools);
            AiSchema effective = computer.forCurrentUser();

            AiEntityInfo kept = effective.entities().get("entityA");
            assertThat(kept).isNotNull();
            assertThat(kept.attributes()).extracting(AiAttributeInfo::name)
                    .containsExactly("visible")
                    .doesNotContain("hidden");
        }
    }

    @Test
    void canReadEntityDelegatesToAccessManager() {
        AccessManager accessManager = mock(AccessManager.class);
        MessageTools messageTools = mock(MessageTools.class);
        MetamodelScanner scanner = mock(MetamodelScanner.class);
        MetaClass mc = entityMock("x");

        try (MockedConstruction<CrudEntityContext> crud = Mockito.mockConstruction(
                CrudEntityContext.class,
                (mockCtx, ctx) -> when(mockCtx.isReadPermitted()).thenReturn(false))) {

            EffectiveSchemaComputer computer =
                    new EffectiveSchemaComputer(accessManager, scanner, messageTools);
            assertThat(computer.canReadEntity(mc)).isFalse();
        }
    }

    @Test
    void canReadAttributeDelegatesToAccessManager() {
        AccessManager accessManager = mock(AccessManager.class);
        MessageTools messageTools = mock(MessageTools.class);
        MetamodelScanner scanner = mock(MetamodelScanner.class);
        MetaClass mc = entityMock("x");

        try (MockedConstruction<EntityAttributeContext> attr = Mockito.mockConstruction(
                EntityAttributeContext.class,
                (mockCtx, ctx) -> when(mockCtx.canView()).thenReturn(true))) {

            EffectiveSchemaComputer computer =
                    new EffectiveSchemaComputer(accessManager, scanner, messageTools);
            assertThat(computer.canReadAttribute(mc, "someAttr")).isTrue();
        }
    }
}
