package com.vn.agent.metadata;

import io.jmix.core.AccessManager;
import io.jmix.core.Metadata;
import io.jmix.core.annotation.Secret;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.entity.annotation.SystemLevel;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Session;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.lang.reflect.AnnotatedElement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link CurrentUserSchemaAccess} — stateless per-request read-access filter.
 * Uses {@link Mockito#mockConstruction(Class, MockedConstruction.MockInitializer)} to
 * control {@link CrudEntityContext#isReadPermitted()} / {@link EntityAttributeContext#canView()}
 * by-MetaClass, so denied entities are proved absent from {@code getReadableSchema()}
 * (success criterion #2 — restricted user sees zero entities).
 */
class CurrentUserSchemaAccessTest {

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

    private static MetaClass entityMockWithProperties(String name, MetaProperty... properties) {
        MetaClass mc = mock(MetaClass.class);
        lenient().when(mc.getName()).thenReturn(name);
        lenient().when(mc.getJavaClass()).thenReturn((Class) Object.class);
        lenient().when(mc.getProperties()).thenReturn(List.of(properties));
        return mc;
    }

    private static MetaProperty propertyMock(String name) {
        MetaProperty property = mock(MetaProperty.class);
        lenient().when(property.getName()).thenReturn(name);
        return property;
    }

    private static MetaProperty sensitivePropertyMock(
            String name, Class<? extends java.lang.annotation.Annotation> annotationClass) {
        MetaProperty property = propertyMock(name);
        AnnotatedElement annotatedElement = mock(AnnotatedElement.class);
        lenient().when(annotatedElement.isAnnotationPresent(annotationClass)).thenReturn(true);
        lenient().when(property.getAnnotatedElement()).thenReturn(annotatedElement);
        return property;
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

            CurrentUserSchemaAccess currentUserSchemaAccess = new CurrentUserSchemaAccess(accessManager, metadata);
            Map<MetaClass, Set<String>> out = currentUserSchemaAccess.getReadableSchema();

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

            CurrentUserSchemaAccess currentUserSchemaAccess = new CurrentUserSchemaAccess(accessManager, metadata);
            assertThat(currentUserSchemaAccess.getReadableSchema()).isEmpty();
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

            CurrentUserSchemaAccess currentUserSchemaAccess = new CurrentUserSchemaAccess(accessManager, metadata);
            Map<MetaClass, Set<String>> out = currentUserSchemaAccess.getReadableSchema();

            assertThat(out).hasSize(1);
            Set<String> visible = out.values().iterator().next();
            assertThat(visible).containsExactly("visible").doesNotContain("hidden");
        }
    }

    @Test
    void systemLevelAndSecretAttributesAreFilteredBeforePromptSchema() {
        AccessManager accessManager = mock(AccessManager.class);
        MetaProperty username = propertyMock("username");
        MetaProperty password = sensitivePropertyMock("password", SystemLevel.class);
        MetaProperty apiToken = sensitivePropertyMock("apiToken", Secret.class);
        MetaClass mc = entityMockWithProperties("jmixapp_User", username, password, apiToken);
        Metadata metadata = metadataWith(mc);

        try (MockedConstruction<CrudEntityContext> crud = Mockito.mockConstruction(
                CrudEntityContext.class,
                (mockCtx, ctx) -> when(mockCtx.isReadPermitted()).thenReturn(true));
             MockedConstruction<EntityAttributeContext> attr = Mockito.mockConstruction(
                     EntityAttributeContext.class,
                     (mockCtx, ctx) -> when(mockCtx.canView()).thenReturn(true))) {

            CurrentUserSchemaAccess currentUserSchemaAccess = new CurrentUserSchemaAccess(accessManager, metadata);
            Map<MetaClass, Set<String>> out = currentUserSchemaAccess.getReadableSchema();

            assertThat(out).hasSize(1);
            assertThat(out.values().iterator().next())
                    .containsExactly("username")
                    .doesNotContain("password", "apiToken");
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

            CurrentUserSchemaAccess currentUserSchemaAccess = new CurrentUserSchemaAccess(accessManager, metadata);
            assertThat(currentUserSchemaAccess.canReadEntity(mc)).isFalse();
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

            CurrentUserSchemaAccess currentUserSchemaAccess = new CurrentUserSchemaAccess(accessManager, metadata);
            assertThat(currentUserSchemaAccess.canReadAttribute(mc, "someAttr")).isTrue();
        }
    }
}
