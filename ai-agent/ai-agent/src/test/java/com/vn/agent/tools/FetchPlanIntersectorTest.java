package com.vn.agent.tools;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.metadata.CurrentUserSchemaAccess;
import com.vn.agent.spi.FetchPlanContext;
import com.vn.agent.spi.ToolFetchPlanCustomizer;
import com.vn.agent.tools.fetchplan.FetchPlanIntersector;
import com.vn.agent.tools.fetchplan.FetchPlanResolver;
import io.jmix.core.FetchMode;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlanBuilder;
import io.jmix.core.FetchPlanProperty;
import io.jmix.core.FetchPlans;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.userdetails.User;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FetchPlanIntersector} and {@link FetchPlanResolver}. Mockito-based
 * (no Spring) so the test runs in milliseconds and isolates the narrowing/audit/resolver
 * contracts from Jmix container wiring.
 *
 * <p>The {@link FetchPlanBuilder} chain is mocked: every fluent setter returns the same
 * builder mock; {@code build()} returns a fresh per-call narrowed {@link FetchPlan} mock so
 * the recursion can stitch nested plans together without depending on the real Jmix
 * implementation.
 */
class FetchPlanIntersectorTest {

    private FetchPlans fetchPlans;
    private CurrentUserSchemaAccess schemaAccess;
    private AuditWriter auditWriter;
    private CurrentAuthentication currentAuthentication;
    private FetchPlanIntersector intersector;

    @BeforeEach
    void setUp() {
        fetchPlans = mock(FetchPlans.class);
        schemaAccess = mock(CurrentUserSchemaAccess.class);
        auditWriter = mock(AuditWriter.class);
        currentAuthentication = mock(CurrentAuthentication.class);
        intersector = new FetchPlanIntersector(fetchPlans, schemaAccess, auditWriter, currentAuthentication);

        // Default authentication returns a stub user; tests can override.
        lenient().when(currentAuthentication.getUser())
                .thenReturn(new User("admin", "x", Collections.emptyList()));
    }

    // ---- Test 1: Pass-through when every property is readable ----

    @Test
    void passThrough_whenAllPropertiesReadable_noAuditEmitted() {
        MetaClass rootMetaClass = newMetaClass("acme_Order");
        FetchPlanProperty numberProp = terminalProperty("number", FetchMode.AUTO);
        FetchPlanProperty statusProp = terminalProperty("status", FetchMode.AUTO);
        FetchPlan inputPlan = mock(FetchPlan.class);
        when(inputPlan.loadPartialEntities()).thenReturn(false);
        when(inputPlan.getProperties()).thenReturn(List.of(numberProp, statusProp));

        when(schemaAccess.canReadAttribute(rootMetaClass, "number")).thenReturn(true);
        when(schemaAccess.canReadAttribute(rootMetaClass, "status")).thenReturn(true);

        BuilderRecorder recorder = wireBuilder(rootMetaClass);

        FetchPlan narrowed = intersector.intersectWithAcl(inputPlan, rootMetaClass, "find_records");

        assertThat(narrowed).isSameAs(recorder.builtPlan);
        assertThat(recorder.mergedPropertyNames).containsExactly("number", "status");
        assertThat(recorder.partialFlag).isFalse();
        verifyNoInteractions(auditWriter);
    }

    // ---- Test 2: Property drop emits audit row with PLAN_NARROWED denialReason ----

    @Test
    void droppedProperty_emitsAuditWithPlanNarrowedDenialReason() {
        MetaClass rootMetaClass = newMetaClass("acme_Order");
        FetchPlanProperty numberProp = terminalProperty("number", FetchMode.AUTO);
        FetchPlanProperty internalNotesProp = terminalProperty("internalNotes", FetchMode.AUTO);
        FetchPlan inputPlan = mock(FetchPlan.class);
        when(inputPlan.loadPartialEntities()).thenReturn(false);
        when(inputPlan.getProperties()).thenReturn(List.of(numberProp, internalNotesProp));

        when(schemaAccess.canReadAttribute(rootMetaClass, "number")).thenReturn(true);
        when(schemaAccess.canReadAttribute(rootMetaClass, "internalNotes")).thenReturn(false);

        BuilderRecorder recorder = wireBuilder(rootMetaClass);

        intersector.intersectWithAcl(inputPlan, rootMetaClass, "find_records");

        // Dropped property is not added to the rebuilt plan
        assertThat(recorder.mergedPropertyNames).containsExactly("number");

        ArgumentCaptor<String> denialReasonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiToolCallOutcome> outcomeCaptor = ArgumentCaptor.forClass(AiToolCallOutcome.class);
        verify(auditWriter, times(1)).writeToolCall(
                any(), any(), any(), any(),
                eq("find_records"),
                isNull(),
                anyString(),
                anyLong(),
                outcomeCaptor.capture(),
                denialReasonCaptor.capture(),
                isNull());
        assertThat(denialReasonCaptor.getValue())
                .startsWith("PLAN_NARROWED:")
                .contains("acme_Order.internalNotes");
        // Phase 9 commitment: no new AiToolCallOutcome enum value; FLAGGED is reused.
        assertThat(outcomeCaptor.getValue()).isEqualTo(AiToolCallOutcome.FLAGGED);
    }

    // ---- Test 3: Recursive nested-plan drop preserves FetchMode and partial flag ----

    @Test
    void nestedPlan_droppedAttributeIsPrunedAndFetchModePreserved() {
        MetaClass rootMetaClass = newMetaClass("acme_Order");
        MetaClass customerMetaClass = newMetaClass("acme_Customer");

        // root.customer (FetchMode.JOIN) -> nested plan with [name, secret]
        MetaProperty customerProperty = referenceProperty("customer", customerMetaClass);
        when(rootMetaClass.findProperty("customer")).thenReturn(customerProperty);

        FetchPlanProperty nameProp = terminalProperty("name", FetchMode.AUTO);
        FetchPlanProperty secretProp = terminalProperty("secret", FetchMode.AUTO);
        FetchPlan nestedPlan = mock(FetchPlan.class);
        when(nestedPlan.loadPartialEntities()).thenReturn(true);
        when(nestedPlan.getProperties()).thenReturn(List.of(nameProp, secretProp));

        FetchPlan inputPlan = mock(FetchPlan.class);
        when(inputPlan.loadPartialEntities()).thenReturn(false);
        FetchPlanProperty customerFetchProperty = mock(FetchPlanProperty.class);
        when(customerFetchProperty.getName()).thenReturn("customer");
        when(customerFetchProperty.getFetchMode()).thenReturn(FetchMode.JOIN);
        when(customerFetchProperty.getFetchPlan()).thenReturn(nestedPlan);
        when(inputPlan.getProperties()).thenReturn(List.of(customerFetchProperty));

        when(schemaAccess.canReadAttribute(rootMetaClass, "customer")).thenReturn(true);
        when(schemaAccess.canReadAttribute(customerMetaClass, "name")).thenReturn(true);
        when(schemaAccess.canReadAttribute(customerMetaClass, "secret")).thenReturn(false);

        BuilderRecorder rootRecorder = wireBuilder(rootMetaClass);
        BuilderRecorder nestedRecorder = wireBuilder(customerMetaClass);

        intersector.intersectWithAcl(inputPlan, rootMetaClass, "get_record");

        // Nested level: 'secret' dropped, 'name' kept; partial preserved as TRUE
        assertThat(nestedRecorder.mergedPropertyNames).containsExactly("name");
        assertThat(nestedRecorder.partialFlag).isTrue();

        // Root level: customer kept with JOIN preserved; nested narrowed plan attached
        assertThat(rootRecorder.mergedPropertyNames).containsExactly("customer");
        assertThat(rootRecorder.lastFetchMode).isEqualTo(FetchMode.JOIN);
        assertThat(rootRecorder.lastNestedPlan).isSameAs(nestedRecorder.builtPlan);
        assertThat(rootRecorder.partialFlag).isFalse();

        // Audit emitted exactly once with the dropped path
        ArgumentCaptor<String> denialReasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditWriter, times(1)).writeToolCall(
                any(), any(), any(), any(),
                eq("get_record"),
                isNull(), anyString(), anyLong(),
                eq(AiToolCallOutcome.FLAGGED),
                denialReasonCaptor.capture(),
                isNull());
        assertThat(denialReasonCaptor.getValue()).contains("acme_Customer.secret");
    }

    // ---- Test 4: Empty result when ALL properties denied ----

    @Test
    void allPropertiesDenied_returnsEmptyPlan_singleAuditRowEnumeratesAll() {
        MetaClass rootMetaClass = newMetaClass("acme_Order");
        FetchPlanProperty aProp = terminalProperty("a", FetchMode.AUTO);
        FetchPlanProperty bProp = terminalProperty("b", FetchMode.AUTO);
        FetchPlanProperty cProp = terminalProperty("c", FetchMode.AUTO);
        FetchPlan inputPlan = mock(FetchPlan.class);
        when(inputPlan.loadPartialEntities()).thenReturn(false);
        when(inputPlan.getProperties()).thenReturn(List.of(aProp, bProp, cProp));
        when(schemaAccess.canReadAttribute(eq(rootMetaClass), anyString())).thenReturn(false);

        BuilderRecorder recorder = wireBuilder(rootMetaClass);

        intersector.intersectWithAcl(inputPlan, rootMetaClass, "find_records");

        assertThat(recorder.mergedPropertyNames).isEmpty();

        ArgumentCaptor<String> denialReasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditWriter, times(1)).writeToolCall(
                any(), any(), any(), any(),
                eq("find_records"),
                isNull(), anyString(), anyLong(),
                eq(AiToolCallOutcome.FLAGGED),
                denialReasonCaptor.capture(),
                isNull());
        assertThat(denialReasonCaptor.getValue())
                .contains("acme_Order.a")
                .contains("acme_Order.b")
                .contains("acme_Order.c");
    }

    // ---- Test 6: Preservation of partial-entity flag when input plan is partial ----

    @Test
    void preservesPartialFlag_whenInputPlanIsPartial() {
        MetaClass rootMetaClass = newMetaClass("acme_Order");
        FetchPlanProperty numberProp = terminalProperty("number", FetchMode.AUTO);
        FetchPlan inputPlan = mock(FetchPlan.class);
        when(inputPlan.loadPartialEntities()).thenReturn(true);
        when(inputPlan.getProperties()).thenReturn(List.of(numberProp));
        when(schemaAccess.canReadAttribute(rootMetaClass, "number")).thenReturn(true);

        BuilderRecorder recorder = wireBuilder(rootMetaClass);
        intersector.intersectWithAcl(inputPlan, rootMetaClass, "find_records");

        assertThat(recorder.partialFlag).isTrue();
    }

    // ---- Test 7: Verbatim TOOL-11 phrase exposed as a public constant ----

    @Test
    void productionClass_exposesVerbatimProjectionNotSecurityCommentConstant() {
        assertThat(FetchPlanIntersector.PROJECTION_NOT_SECURITY_COMMENT)
                .isEqualTo("fetch plan is projection, not security.");
    }

    @Test
    void planNarrowedPrefixIsGreppable() {
        assertThat(FetchPlanIntersector.PLAN_NARROWED_PREFIX).isEqualTo("PLAN_NARROWED:");
    }

    // ---- Test 5: Resolver chain (in nested @Nested) ----

    @Nested
    class ResolverChain {

        @Test
        void firstNonEmptyCustomizerWins_andResultIsIntersected() {
            MetaClass rootMetaClass = newMetaClass("acme_Order");

            FetchPlanProperty numberProp = terminalProperty("number", FetchMode.AUTO);
            FetchPlan customizerPlan = mock(FetchPlan.class);
            when(customizerPlan.loadPartialEntities()).thenReturn(false);
            when(customizerPlan.getProperties()).thenReturn(List.of(numberProp));
            when(schemaAccess.canReadAttribute(rootMetaClass, "number")).thenReturn(true);

            ToolFetchPlanCustomizer first = mock(ToolFetchPlanCustomizer.class);
            when(first.overrideFor(eq("find_records"), eq(rootMetaClass), any(FetchPlanContext.class)))
                    .thenReturn(Optional.empty());
            ToolFetchPlanCustomizer second = mock(ToolFetchPlanCustomizer.class);
            when(second.overrideFor(eq("find_records"), eq(rootMetaClass), any(FetchPlanContext.class)))
                    .thenReturn(Optional.of(customizerPlan));
            ToolFetchPlanCustomizer third = mock(ToolFetchPlanCustomizer.class);

            BuilderRecorder recorder = wireBuilder(rootMetaClass);

            FetchPlanResolver resolver = new FetchPlanResolver(
                    List.of(first, second, third),
                    intersector,
                    fetchPlans,
                    currentAuthentication);

            FetchPlan resolved = resolver.resolve("find_records", rootMetaClass);

            assertThat(resolved).isSameAs(recorder.builtPlan);
            verify(first, times(1)).overrideFor(anyString(), any(MetaClass.class), any(FetchPlanContext.class));
            verify(second, times(1)).overrideFor(anyString(), any(MetaClass.class), any(FetchPlanContext.class));
            // Third customizer is never consulted because second won
            verify(third, never()).overrideFor(anyString(), any(MetaClass.class), any(FetchPlanContext.class));
        }

        @Test
        void allCustomizersEmpty_fallsBackToFetchPlanBase_thenIntersects() {
            MetaClass rootMetaClass = newMetaClass("acme_Order");

            // Builder for the BASE construction inside the resolver
            FetchPlan basePlan = mock(FetchPlan.class);
            lenient().when(basePlan.loadPartialEntities()).thenReturn(false);
            when(basePlan.getProperties()).thenReturn(Collections.emptyList());

            FetchPlanBuilder baseBuilder = mock(FetchPlanBuilder.class);
            when(baseBuilder.addFetchPlan(FetchPlan.BASE)).thenReturn(baseBuilder);
            when(baseBuilder.build()).thenReturn(basePlan);

            // Builder for the intersector's empty rebuild
            FetchPlan rebuilt = mock(FetchPlan.class);
            FetchPlanBuilder narrowedBuilder = mock(FetchPlanBuilder.class);
            when(narrowedBuilder.partial(anyBoolean())).thenReturn(narrowedBuilder);
            when(narrowedBuilder.build()).thenReturn(rebuilt);

            // Two builder instances are returned in order: first BASE, then narrowed rebuild
            when(fetchPlans.builder(any(Class.class)))
                    .thenReturn(baseBuilder, narrowedBuilder);

            ToolFetchPlanCustomizer noop = mock(ToolFetchPlanCustomizer.class);
            when(noop.overrideFor(anyString(), any(MetaClass.class), any(FetchPlanContext.class)))
                    .thenReturn(Optional.empty());

            FetchPlanResolver resolver = new FetchPlanResolver(
                    List.of(noop),
                    intersector,
                    fetchPlans,
                    currentAuthentication);

            FetchPlan resolved = resolver.resolve("find_records", rootMetaClass);

            assertThat(resolved).isSameAs(rebuilt);
            verify(baseBuilder, times(1)).addFetchPlan(FetchPlan.BASE);
            verify(baseBuilder, times(1)).build();
        }
    }

    // ---- helpers ----

    private int metaClassJavaClassCounter = 0;
    private final List<Class<?>> javaClassPool = List.of(
            FakeJavaA.class, FakeJavaB.class, FakeJavaC.class, FakeJavaD.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private MetaClass newMetaClass(String name) {
        MetaClass metaClass = mock(MetaClass.class);
        lenient().when(metaClass.getName()).thenReturn(name);
        // Each MetaClass mock gets a DISTINCT Java class so that fetchPlans.builder(Class)
        // stubs in wireBuilder do not collide across multiple MetaClasses in the same test.
        Class<?> javaClass = javaClassPool.get(metaClassJavaClassCounter++ % javaClassPool.size());
        lenient().when(metaClass.getJavaClass()).thenReturn((Class) javaClass);
        return metaClass;
    }

    private static final class FakeJavaA {}
    private static final class FakeJavaB {}
    private static final class FakeJavaC {}
    private static final class FakeJavaD {}

    private static FetchPlanProperty terminalProperty(String name, FetchMode fetchMode) {
        FetchPlanProperty property = mock(FetchPlanProperty.class);
        lenient().when(property.getName()).thenReturn(name);
        lenient().when(property.getFetchMode()).thenReturn(fetchMode);
        lenient().when(property.getFetchPlan()).thenReturn(null);
        return property;
    }

    private static MetaProperty referenceProperty(String name, MetaClass targetMetaClass) {
        MetaProperty property = mock(MetaProperty.class);
        lenient().when(property.getName()).thenReturn(name);
        Range range = mock(Range.class);
        lenient().when(range.isClass()).thenReturn(true);
        lenient().when(range.asClass()).thenReturn(targetMetaClass);
        lenient().when(property.getRange()).thenReturn(range);
        return property;
    }

    /**
     * Wire {@link FetchPlans#builder(Class)} for {@code metaClass.getJavaClass()} so that it
     * returns a builder mock recording {@code partial(...)} and {@code mergeProperty(...)}
     * calls and producing a fresh per-call {@link FetchPlan} mock from {@code build()}.
     */
    private BuilderRecorder wireBuilder(MetaClass metaClass) {
        BuilderRecorder recorder = new BuilderRecorder();
        FetchPlanBuilder builder = mock(FetchPlanBuilder.class);
        recorder.builtPlan = mock(FetchPlan.class);
        lenient().when(builder.partial(anyBoolean())).thenAnswer(invocation -> {
            recorder.partialFlag = invocation.getArgument(0);
            return builder;
        });
        // Mockito's any(Class<T>) does NOT match null; use nullable(Class<T>) so terminal
        // properties (mergeProperty(name, null, fetchMode)) and nested properties
        // (mergeProperty(name, nestedPlan, fetchMode)) both match.
        lenient().when(builder.mergeProperty(
                        anyString(),
                        org.mockito.ArgumentMatchers.nullable(FetchPlan.class),
                        org.mockito.ArgumentMatchers.nullable(FetchMode.class)))
                .thenAnswer(invocation -> {
                    recorder.mergedPropertyNames.add(invocation.getArgument(0));
                    recorder.lastNestedPlan = invocation.getArgument(1);
                    recorder.lastFetchMode = invocation.getArgument(2);
                    return builder;
                });
        lenient().when(builder.build()).thenReturn(recorder.builtPlan);
        when(fetchPlans.builder(metaClass.getJavaClass())).thenReturn(builder);
        return recorder;
    }

    private static final class BuilderRecorder {
        final List<String> mergedPropertyNames = new ArrayList<>();
        FetchPlan builtPlan;
        Boolean partialFlag;
        FetchPlan lastNestedPlan;
        FetchMode lastFetchMode;
    }
}
