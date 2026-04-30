package com.vn.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.filter.FilterLiteralValueConverter;
import com.vn.agent.filter.StructuredFilterConditionMapper;
import com.vn.agent.exposure.LlmExposurePolicy;
import com.vn.agent.tools.fetchplan.FetchPlanIntersector;
import com.vn.agent.tools.fetchplan.FetchPlanResolver;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlans;
import io.jmix.core.FluentLoader;
import io.jmix.core.MessageTools;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PROMPT-05 / D-14 — verifies the {@code unknown_entity} retry hints appear verbatim in the
 * locked order, and that the three data-load {@code @Tool} methods route their
 * {@code FetchPlan} through {@link FetchPlanResolver}.
 *
 * <p><b>Phase 10 (Plan 10-04, Fix R4) update:</b> denied entities now surface as
 * {@code unknown_entity} (full uniformity per EXP-09 + Phase 3 D-08). The LLM cannot
 * distinguish "no such entity" from "entity exists but denied" — denied and unknown
 * resolutions return the same error code and the same three retry hints.
 *
 * <p>Pure-unit Mockito test. The DataManager fluent loader chain is mocked end-to-end so
 * the test does not require a Spring context.
 */
class UnknownEntityRetryHintTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DataManager dataManager;
    private Metadata metadata;
    private MetadataTools metadataTools;
    private MessageTools messageTools;
    private FetchPlans fetchPlans;
    private LlmExposurePolicy schemaAccess;
    private StructuredFilterConditionMapper filterMapper;
    private FilterLiteralValueConverter literalConverter;
    private ToolResultFormatter formatter;
    private FetchPlanResolver fetchPlanResolver;
    private FetchPlanIntersector fetchPlanIntersector;
    private ToolEntityResolver toolEntityResolver;
    private BuiltInDataTools tools;

    @BeforeEach
    void setUp() {
        dataManager = mock(DataManager.class);
        metadata = mock(Metadata.class);
        metadataTools = mock(MetadataTools.class);
        messageTools = mock(MessageTools.class);
        fetchPlans = mock(FetchPlans.class);
        schemaAccess = mock(LlmExposurePolicy.class);
        filterMapper = mock(StructuredFilterConditionMapper.class);
        literalConverter = mock(FilterLiteralValueConverter.class);
        formatter = new ToolResultFormatter(
                OBJECT_MAPPER, mock(EntityStates.class), metadataTools, messageTools, mock(Messages.class));
        fetchPlanResolver = mock(FetchPlanResolver.class);
        fetchPlanIntersector = mock(FetchPlanIntersector.class);
        // Plan 11-04: BuiltInDataTools delegates entity resolution + id parsing to a shared
        // ToolEntityResolver. Construct a real instance wired to the same mocks the test
        // already drives so the existing behavioral assertions (R4 unknown_entity opacity,
        // FetchPlanResolver wiring) continue to hold.
        toolEntityResolver = new ToolEntityResolver(
                metadata, metadataTools, schemaAccess, literalConverter);
        tools = new BuiltInDataTools(
                dataManager, messageTools, fetchPlans,
                schemaAccess, filterMapper, formatter, fetchPlanResolver,
                fetchPlanIntersector, toolEntityResolver, OBJECT_MAPPER);
    }

    // ---- Test 1: Unknown entity name → three D-14 hints in locked order ----

    @Test
    void unknownEntityName_returnsThreeProceduralHintsInLockedOrder() throws Exception {
        when(metadata.getClass("nope")).thenReturn(null);

        String json = tools.describeEntity("nope");
        JsonNode root = OBJECT_MAPPER.readTree(json);
        assertThat(root.path("error").asText()).isEqualTo("unknown_entity");
        JsonNode expected = root.path("expected");
        assertThat(expected.isArray()).isTrue();
        assertThat(expected.size()).isEqualTo(3);
        assertThat(expected.get(0).asText()).isEqualTo("call list_entities exactly once");
        assertThat(expected.get(1).asText())
                .isEqualTo("if a name in list_entities matches your intent, retry the original tool with that exact name");
        // Em dash preserved verbatim per D-14
        assertThat(expected.get(2).asText())
                .isEqualTo("if no entity in list_entities matches, tell the user no such entity exists — do not guess");
    }

    // ---- Test 2: Blank entity name → same hint shape ----

    @Test
    void blankEntityName_returnsHintsAndBlankReason() throws Exception {
        String json = tools.describeEntity("");
        JsonNode root = OBJECT_MAPPER.readTree(json);
        assertThat(root.path("error").asText()).isEqualTo("unknown_entity");
        assertThat(root.path("reason").asText()).isEqualTo("entity name must not be blank");
        assertThat(root.path("expected").size()).isEqualTo(3);
    }

    // ---- Test 3: Denied entity → unknown_entity with three hints (Phase 10 Fix R4 unification) ----

    @Test
    void deniedEntity_returnsUnknownEntityWithThreeHints() throws Exception {
        MetaClass metaClass = mock(MetaClass.class);
        when(metadata.getClass("acme_Customer")).thenReturn(metaClass);
        when(schemaAccess.canReadEntity(metaClass)).thenReturn(false);

        String json = tools.describeEntity("acme_Customer");
        JsonNode root = OBJECT_MAPPER.readTree(json);
        assertThat(root.path("error").asText())
                .as("Phase 10 Fix R4 — full uniformity: denied entities surface as unknown_entity, "
                        + "indistinguishable from non-existent ones (EXP-09 + Phase 3 D-08)")
                .isEqualTo("unknown_entity");
        JsonNode expected = root.path("expected");
        assertThat(expected.isArray()).isTrue();
        assertThat(expected.size())
                .as("Phase 10 Fix R4 — denial path now carries the three retry hints, "
                        + "byte-for-byte identical to unknown-name path (TEST-08 invariant)")
                .isEqualTo(3);
        assertThat(expected.get(0).asText()).isEqualTo("call list_entities exactly once");
        assertThat(expected.get(1).asText())
                .isEqualTo("if a name in list_entities matches your intent, retry the original tool with that exact name");
        // Em dash preserved verbatim per D-14
        assertThat(expected.get(2).asText())
                .isEqualTo("if no entity in list_entities matches, tell the user no such entity exists — do not guess");
    }

    // ---- Test 4: find_records consults FetchPlanResolver ----

    @Test
    void findRecords_consultsFetchPlanResolver() {
        MetaClass metaClass = newReadableMetaClass("acme_Order");
        FetchPlan resolvedPlan = mock(FetchPlan.class);
        when(fetchPlanResolver.resolve("find_records", metaClass)).thenReturn(resolvedPlan);

        @SuppressWarnings("unchecked")
        FluentLoader<Object> loader = mock(FluentLoader.class);
        @SuppressWarnings("unchecked")
        FluentLoader.ByCondition<Object> byCondition = mock(FluentLoader.ByCondition.class);
        when(dataManager.load(any(Class.class))).thenReturn(loader);
        when(loader.all()).thenReturn(byCondition);
        when(byCondition.fetchPlan(any(FetchPlan.class))).thenReturn(byCondition);
        when(byCondition.maxResults(org.mockito.ArgumentMatchers.anyInt())).thenReturn(byCondition);
        when(byCondition.list()).thenReturn(List.of());

        tools.findRecords("acme_Order", null, null);

        verify(fetchPlanResolver).resolve("find_records", metaClass);
        verify(byCondition).fetchPlan(resolvedPlan);
    }

    // ---- Test 5: get_record consults FetchPlanResolver ----

    @Test
    void getRecord_consultsFetchPlanResolver() {
        MetaClass metaClass = newReadableMetaClass("acme_Order");
        UUID parsedId = UUID.randomUUID();
        MetaProperty primaryKey = mock(MetaProperty.class);
        when(metadataTools.getPrimaryKeyProperty(metaClass)).thenReturn(primaryKey);
        when(literalConverter.convertValue(any(), eq(primaryKey))).thenReturn(parsedId);

        FetchPlan resolvedPlan = mock(FetchPlan.class);
        when(fetchPlanResolver.resolve("get_record", metaClass)).thenReturn(resolvedPlan);

        @SuppressWarnings("unchecked")
        FluentLoader<Object> loader = mock(FluentLoader.class);
        @SuppressWarnings("unchecked")
        FluentLoader.ById<Object> byId = mock(FluentLoader.ById.class);
        when(dataManager.load(any(Class.class))).thenReturn(loader);
        when(loader.id(parsedId)).thenReturn(byId);
        when(byId.fetchPlan(any(FetchPlan.class))).thenReturn(byId);
        when(byId.optional()).thenReturn(java.util.Optional.empty());

        tools.getRecord("acme_Order", parsedId.toString());

        verify(fetchPlanResolver).resolve("get_record", metaClass);
        verify(byId).fetchPlan(resolvedPlan);
    }

    // ---- Test 6: get_related_records consults FetchPlanResolver for the data plan ----

    @Test
    void getRelatedRecords_consultsFetchPlanResolverForDataPlan() {
        MetaClass rootMetaClass = newReadableMetaClass("acme_Order");
        MetaClass targetMetaClass = newReadableMetaClass("acme_Customer");

        MetaProperty relationshipProperty = mock(MetaProperty.class);
        when(rootMetaClass.findProperty("customer")).thenReturn(relationshipProperty);
        Range range = mock(Range.class);
        when(range.isClass()).thenReturn(true);
        when(range.asClass()).thenReturn(targetMetaClass);
        when(relationshipProperty.getRange()).thenReturn(range);

        when(schemaAccess.canReadAttribute(rootMetaClass, "customer")).thenReturn(true);

        FetchPlan resolvedDataPlan = mock(FetchPlan.class);
        when(fetchPlanResolver.resolve("get_related_records", rootMetaClass)).thenReturn(resolvedDataPlan);

        // FetchPlans builder for composing data plan + INSTANCE_NAME relationship
        io.jmix.core.FetchPlanBuilder composedBuilder = mock(io.jmix.core.FetchPlanBuilder.class);
        FetchPlan composedPlan = mock(FetchPlan.class);
        FetchPlan narrowedPlan = mock(FetchPlan.class);
        when(fetchPlans.builder(any(Class.class))).thenReturn(composedBuilder);
        lenient().when(composedBuilder.addFetchPlan(any(FetchPlan.class))).thenReturn(composedBuilder);
        lenient().when(composedBuilder.addFetchPlan(any(String.class))).thenReturn(composedBuilder);
        lenient().when(composedBuilder.add(any(String.class), any(java.util.function.Consumer.class))).thenReturn(composedBuilder);
        when(composedBuilder.build()).thenReturn(composedPlan);
        when(fetchPlanIntersector.intersectWithAcl(composedPlan, rootMetaClass, "get_related_records"))
                .thenReturn(narrowedPlan);

        UUID parsedId = UUID.randomUUID();
        MetaProperty primaryKey = mock(MetaProperty.class);
        when(metadataTools.getPrimaryKeyProperty(rootMetaClass)).thenReturn(primaryKey);
        when(literalConverter.convertValue(any(), eq(primaryKey))).thenReturn(parsedId);

        @SuppressWarnings("unchecked")
        FluentLoader<Object> loader = mock(FluentLoader.class);
        @SuppressWarnings("unchecked")
        FluentLoader.ById<Object> byId = mock(FluentLoader.ById.class);
        when(dataManager.load(any(Class.class))).thenReturn(loader);
        when(loader.id(parsedId)).thenReturn(byId);
        when(byId.fetchPlan(any(FetchPlan.class))).thenReturn(byId);
        when(byId.optional()).thenReturn(java.util.Optional.empty());

        tools.getRelatedRecords("acme_Order", parsedId.toString(), "customer");

        verify(fetchPlanResolver).resolve("get_related_records", rootMetaClass);
        verify(composedBuilder).addFetchPlan(resolvedDataPlan);
        verify(fetchPlanIntersector).intersectWithAcl(composedPlan, rootMetaClass, "get_related_records");
        verify(byId).fetchPlan(narrowedPlan);
    }

    // ---- helpers ----

    @SuppressWarnings({"unchecked", "rawtypes"})
    private MetaClass newReadableMetaClass(String name) {
        MetaClass metaClass = mock(MetaClass.class);
        lenient().when(metaClass.getName()).thenReturn(name);
        lenient().when(metaClass.getJavaClass()).thenReturn((Class) Object.class);
        when(metadata.getClass(name)).thenReturn(metaClass);
        when(schemaAccess.canReadEntity(metaClass)).thenReturn(true);
        return metaClass;
    }
}
