package com.vn.agent.metadata;

import io.jmix.core.AccessManager;
import io.jmix.core.Metadata;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Session;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static MetaClass entityMock(String name, String... attrNames) {
        MetaClass mc = mock(MetaClass.class);
        lenient().when(mc.getName()).thenReturn(name);
        lenient().when(mc.getJavaClass()).thenReturn((Class) Object.class);
        List<MetaProperty> props = new java.util.ArrayList<>();
        for (String n : attrNames) {
            MetaProperty mp = mock(MetaProperty.class);
            lenient().when(mp.getName()).thenReturn(n);
            props.add(mp);
        }
        lenient().when(mc.getProperties()).thenReturn(props);
        return mc;
    }

    private static Metadata metadataWith(MetaClass... classes) {
        Metadata metadata = mock(Metadata.class);
        Session session = mock(Session.class);
        when(metadata.getSession()).thenReturn(session);
        lenient().when(session.getClasses()).thenReturn(List.of(classes));
        return metadata;
    }

    @Test
    void deniedEntityIsAbsent() {
        AccessManager accessManager = mock(AccessManager.class);
        MetaClass mcA = entityMock("entityA", "name");
        MetaClass mcB = entityMock("entityB", "name");
        Metadata metadata = metadataWith(mcA, mcB);

        try (MockedConstruction<CrudEntityContext> crud = Mockito.mockConstruction(
                CrudEntityContext.class,
                (mockCtx, ctx) -> {
                    MetaClass arg = (MetaClass) ctx.arguments().get(0);
                    String argName = arg.getName();
                    when(mockCtx.isReadPermitted()).thenReturn(!"entityA".equals(argName));
                });
             MockedConstruction<EntityAttributeContext> attr = Mockito.mockConstruction(
                     EntityAttributeContext.class,
                     (mockCtx, ctx) -> when(mockCtx.canView()).thenReturn(true))) {

            EffectiveSchemaComputer computer = new EffectiveSchemaComputer(accessManager, metadata);
            Map<MetaClass, Set<String>> out = computer.forCurrentUser();

            assertThat(out.keySet()).extracting(MetaClass::getName)
                    .containsExactly("entityB")
                    .doesNotContain("entityA");
        }
    }

    @Test
    void restrictedUserWithAllEntitiesDeniedReturnsEmpty() {
        AccessManager accessManager = mock(AccessManager.class);
        Metadata metadata = metadataWith(entityMock("entityA", "name"), entityMock("entityB", "name"));

        try (MockedConstruction<CrudEntityContext> crud = Mockito.mockConstruction(
                CrudEntityContext.class,
                (mockCtx, ctx) -> when(mockCtx.isReadPermitted()).thenReturn(false))) {

            EffectiveSchemaComputer computer = new EffectiveSchemaComputer(accessManager, metadata);
            assertThat(computer.forCurrentUser()).isEmpty();
        }
    }

    @Test
    void deniedAttributeIsFilteredButEntityKept() {
        AccessManager accessManager = mock(AccessManager.class);
        MetaClass mc = entityMock("entityA", "visible", "hidden");
        Metadata metadata = metadataWith(mc);

        try (MockedConstruction<CrudEntityContext> crud = Mockito.mockConstruction(
                CrudEntityContext.class,
                (mockCtx, ctx) -> when(mockCtx.isReadPermitted()).thenReturn(true));
             MockedConstruction<EntityAttributeContext> attr = Mockito.mockConstruction(
                     EntityAttributeContext.class,
                     (mockCtx, ctx) -> {
                         String n = (String) ctx.arguments().get(1);
                         when(mockCtx.canView()).thenReturn(!"hidden".equals(n));
                     })) {

            EffectiveSchemaComputer computer = new EffectiveSchemaComputer(accessManager, metadata);
            Map<MetaClass, Set<String>> out = computer.forCurrentUser();

            assertThat(out).hasSize(1);
            Set<String> visible = out.values().iterator().next();
            assertThat(visible).containsExactly("visible").doesNotContain("hidden");
        }
    }

    @Test
    void canReadEntityDelegatesToAccessManager() {
        AccessManager accessManager = mock(AccessManager.class);
        Metadata metadata = metadataWith();
        MetaClass mc = entityMock("x");

        try (MockedConstruction<CrudEntityContext> crud = Mockito.mockConstruction(
                CrudEntityContext.class,
                (mockCtx, ctx) -> when(mockCtx.isReadPermitted()).thenReturn(false))) {

            EffectiveSchemaComputer computer = new EffectiveSchemaComputer(accessManager, metadata);
            assertThat(computer.canReadEntity(mc)).isFalse();
        }
    }

    @Test
    void canReadAttributeDelegatesToAccessManager() {
        AccessManager accessManager = mock(AccessManager.class);
        Metadata metadata = metadataWith();
        MetaClass mc = entityMock("x");

        try (MockedConstruction<EntityAttributeContext> attr = Mockito.mockConstruction(
                EntityAttributeContext.class,
                (mockCtx, ctx) -> when(mockCtx.canView()).thenReturn(true))) {

            EffectiveSchemaComputer computer = new EffectiveSchemaComputer(accessManager, metadata);
            assertThat(computer.canReadAttribute(mc, "someAttr")).isTrue();
        }
    }
}
