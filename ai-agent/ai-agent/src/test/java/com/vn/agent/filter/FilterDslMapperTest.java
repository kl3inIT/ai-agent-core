package com.vn.agent.filter;

import com.vn.agent.metadata.EffectiveSchemaComputer;
import com.vn.agent.tools.ToolUserError;
import io.jmix.core.metamodel.datatype.Datatype;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import io.jmix.core.querycondition.Condition;
import io.jmix.core.querycondition.LogicalCondition;
import io.jmix.core.querycondition.PropertyCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins every branch of {@link FilterDslMapper}: all 13 D-05 operators, DeMorgan NOT
 * expansion over AND/OR and leaves, depth-cap rejection, denied-attribute rejection,
 * unknown-op rejection, NOT-over-STARTS_WITH/ENDS_WITH rejection. Mocks
 * {@link EffectiveSchemaComputer} to exercise access-denied paths without Spring.
 */
class FilterDslMapperTest {

    private LiteralCoercer literalCoercer;
    private EffectiveSchemaComputer schemaComputer;
    private FilterDslMapper mapper;
    private MetaClass orderMc;
    private MetaProperty nameProp;
    private MetaProperty amountProp;
    private MetaProperty activeProp;

    @BeforeEach
    void setUp() {
        literalCoercer = new LiteralCoercer();
        schemaComputer = mock(EffectiveSchemaComputer.class);
        // Default: every attribute is readable. Individual tests override.
        lenient().when(schemaComputer.canReadAttribute(org.mockito.ArgumentMatchers.any(), anyString()))
                .thenReturn(true);
        lenient().when(schemaComputer.canReadEntity(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        mapper = new FilterDslMapper(literalCoercer, schemaComputer, 3);

        orderMc = mock(MetaClass.class);
        lenient().when(orderMc.getName()).thenReturn("Order");

        nameProp = datatypeProp(String.class, "name");
        amountProp = datatypeProp(java.math.BigDecimal.class, "amount");
        activeProp = datatypeProp(Boolean.class, "active");

        lenient().when(orderMc.findProperty("name")).thenReturn(nameProp);
        lenient().when(orderMc.findProperty("amount")).thenReturn(amountProp);
        lenient().when(orderMc.findProperty("active")).thenReturn(activeProp);
    }

    // --------- helpers ---------

    private static MetaProperty datatypeProp(Class<?> javaClass, String name) {
        MetaProperty mp = mock(MetaProperty.class);
        Range range = mock(Range.class);
        @SuppressWarnings("rawtypes")
        Datatype dt = mock(Datatype.class);
        lenient().when(mp.getName()).thenReturn(name);
        lenient().when(mp.getRange()).thenReturn(range);
        lenient().when(range.isClass()).thenReturn(false);
        lenient().when(range.isEnum()).thenReturn(false);
        lenient().when(range.isDatatype()).thenReturn(true);
        lenient().when(range.asDatatype()).thenReturn(dt);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class raw = javaClass;
        lenient().when(dt.getJavaClass()).thenReturn(raw);
        // LiteralCoercer (after finding #1) delegates scalar datatype parsing to Datatype.parse.
        // For test fixtures using BigDecimal amount with string values like "10", stub parse
        // to echo back a BigDecimal. String and Boolean short-circuit inside the coercer.
        try {
            lenient().when(dt.parse(anyString())).thenAnswer(inv -> {
                String s = inv.getArgument(0);
                if (javaClass == java.math.BigDecimal.class) return new java.math.BigDecimal(s);
                if (javaClass == Long.class) return Long.valueOf(s);
                if (javaClass == Integer.class) return Integer.valueOf(s);
                return s;
            });
        } catch (java.text.ParseException e) {
            throw new AssertionError(e);
        }
        return mp;
    }

    private static PropertyCondition asProperty(Condition c) {
        assertThat(c).isInstanceOf(PropertyCondition.class);
        return (PropertyCondition) c;
    }

    private static LogicalCondition asLogical(Condition c) {
        assertThat(c).isInstanceOf(LogicalCondition.class);
        return (LogicalCondition) c;
    }

    // --------- 13 operators (D-05) ---------

    @ParameterizedTest
    @CsvSource({
            "EQUAL,            EQUAL,            name,    alice",
            "NOT_EQUAL,        NOT_EQUAL,        name,    alice",
            "GREATER,          GREATER,          amount,  10",
            "GREATER_OR_EQUAL, GREATER_OR_EQUAL, amount,  10",
            "LESS,             LESS,             amount,  10",
            "LESS_OR_EQUAL,    LESS_OR_EQUAL,    amount,  10",
            "CONTAINS,         CONTAINS,         name,    al",
            "DOES_NOT_CONTAIN, NOT_CONTAINS,     name,    al",
            "STARTS_WITH,      STARTS_WITH,      name,    al",
            "ENDS_WITH,        ENDS_WITH,        name,    al"
    })
    void eachOperatorMapsToMatchingJmixConstant(String dslOp, String jmixOp, String propName, String rawValue) {
        LeafNode leaf = new LeafNode(propName, dslOp, rawValue);
        Condition out = mapper.map(leaf, orderMc);
        PropertyCondition pc = asProperty(out);
        assertThat(pc.getProperty()).isEqualTo(propName);
        assertThat(pc.getOperation()).isEqualTo(expectedOp(jmixOp));
    }

    private static String expectedOp(String jmixOp) {
        return switch (jmixOp) {
            case "EQUAL" -> PropertyCondition.Operation.EQUAL;
            case "NOT_EQUAL" -> PropertyCondition.Operation.NOT_EQUAL;
            case "GREATER" -> PropertyCondition.Operation.GREATER;
            case "GREATER_OR_EQUAL" -> PropertyCondition.Operation.GREATER_OR_EQUAL;
            case "LESS" -> PropertyCondition.Operation.LESS;
            case "LESS_OR_EQUAL" -> PropertyCondition.Operation.LESS_OR_EQUAL;
            case "CONTAINS" -> PropertyCondition.Operation.CONTAINS;
            case "NOT_CONTAINS" -> PropertyCondition.Operation.NOT_CONTAINS;
            case "STARTS_WITH" -> PropertyCondition.Operation.STARTS_WITH;
            case "ENDS_WITH" -> PropertyCondition.Operation.ENDS_WITH;
            case "IN_LIST" -> PropertyCondition.Operation.IN_LIST;
            case "NOT_IN_LIST" -> PropertyCondition.Operation.NOT_IN_LIST;
            case "IS_SET" -> PropertyCondition.Operation.IS_SET;
            default -> throw new IllegalArgumentException("unexpected " + jmixOp);
        };
    }

    @Test
    void inListMapsToInListWithCoercedValues() {
        LeafNode leaf = new LeafNode("amount", "IN_LIST", List.of("1", "2"));
        PropertyCondition pc = asProperty(mapper.map(leaf, orderMc));
        assertThat(pc.getOperation()).isEqualTo(PropertyCondition.Operation.IN_LIST);
    }

    @Test
    void notInListMapsToNotInList() {
        LeafNode leaf = new LeafNode("amount", "NOT_IN_LIST", List.of("1"));
        PropertyCondition pc = asProperty(mapper.map(leaf, orderMc));
        assertThat(pc.getOperation()).isEqualTo(PropertyCondition.Operation.NOT_IN_LIST);
    }

    @Test
    void isSetMapsToIsSet() {
        LeafNode leaf = new LeafNode("active", "IS_SET", true);
        PropertyCondition pc = asProperty(mapper.map(leaf, orderMc));
        assertThat(pc.getOperation()).isEqualTo(PropertyCondition.Operation.IS_SET);
        assertThat(pc.getParameterValue()).isEqualTo(Boolean.TRUE);
    }

    // --------- AND / OR ---------

    @Test
    void andNodeProducesLogicalAnd() {
        LeafNode a = new LeafNode("name", "EQUAL", "alice");
        LeafNode b = new LeafNode("amount", "GREATER", "10");
        Condition out = mapper.map(new AndNode(List.of(a, b)), orderMc);
        LogicalCondition lc = asLogical(out);
        assertThat(lc.getType()).isEqualTo(LogicalCondition.Type.AND);
        assertThat(lc.getConditions()).hasSize(2);
    }

    @Test
    void orNodeProducesLogicalOr() {
        LeafNode a = new LeafNode("name", "EQUAL", "alice");
        LeafNode b = new LeafNode("name", "EQUAL", "bob");
        LogicalCondition lc = asLogical(mapper.map(new OrNode(List.of(a, b)), orderMc));
        assertThat(lc.getType()).isEqualTo(LogicalCondition.Type.OR);
        assertThat(lc.getConditions()).hasSize(2);
    }

    // --------- DeMorgan NOT ---------

    @Test
    void notOverEqualLeafBecomesNotEqual() {
        NotNode not = new NotNode(new LeafNode("name", "EQUAL", "alice"));
        PropertyCondition pc = asProperty(mapper.map(not, orderMc));
        assertThat(pc.getOperation()).isEqualTo(PropertyCondition.Operation.NOT_EQUAL);
    }

    @Test
    void notOverAndBecomesOrOfNegatedLeaves() {
        LeafNode a = new LeafNode("name", "EQUAL", "alice");
        LeafNode b = new LeafNode("amount", "GREATER", "10");
        NotNode not = new NotNode(new AndNode(List.of(a, b)));
        LogicalCondition lc = asLogical(mapper.map(not, orderMc));
        // DeMorgan: NOT(A AND B) => (NOT A) OR (NOT B)
        assertThat(lc.getType()).isEqualTo(LogicalCondition.Type.OR);
        assertThat(lc.getConditions()).hasSize(2);
        PropertyCondition child0 = (PropertyCondition) lc.getConditions().get(0);
        PropertyCondition child1 = (PropertyCondition) lc.getConditions().get(1);
        assertThat(child0.getOperation()).isEqualTo(PropertyCondition.Operation.NOT_EQUAL);
        assertThat(child1.getOperation()).isEqualTo(PropertyCondition.Operation.LESS_OR_EQUAL);
    }

    @Test
    void doubleNotCancels() {
        NotNode nn = new NotNode(new NotNode(new LeafNode("name", "EQUAL", "alice")));
        PropertyCondition pc = asProperty(mapper.map(nn, orderMc));
        assertThat(pc.getOperation()).isEqualTo(PropertyCondition.Operation.EQUAL);
    }

    // --------- Rejection paths (fail-closed) ---------

    @Test
    void depthCapRejects() {
        // path "a.b.c.d" — 4 segments, exceeds default maxFilterDepth=3
        LeafNode leaf = new LeafNode("a.b.c.d", "EQUAL", "x");
        assertThatThrownBy(() -> mapper.map(leaf, orderMc))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error())
                        .isEqualTo("filter_depth_exceeded"));
    }

    @Test
    void unknownOperationRejects() {
        LeafNode leaf = new LeafNode("name", "MATCHES_REGEX", "x");
        assertThatThrownBy(() -> mapper.map(leaf, orderMc))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> {
                    ToolUserError tue = (ToolUserError) e;
                    assertThat(tue.toDto().error()).isEqualTo("unknown_operation");
                    assertThat(tue.toDto().expected()).contains(
                            "EQUAL", "NOT_EQUAL", "GREATER", "GREATER_OR_EQUAL",
                            "LESS", "LESS_OR_EQUAL", "CONTAINS", "DOES_NOT_CONTAIN",
                            "STARTS_WITH", "ENDS_WITH", "IN_LIST", "NOT_IN_LIST", "IS_SET");
                });
    }

    @Test
    void deniedAttributeRejects() {
        when(schemaComputer.canReadAttribute(eq(orderMc), eq("name"))).thenReturn(false);
        LeafNode leaf = new LeafNode("name", "EQUAL", "alice");
        assertThatThrownBy(() -> mapper.map(leaf, orderMc))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error())
                        .isEqualTo("access_denied"));
    }

    @Test
    void unknownAttributeRejects() {
        LeafNode leaf = new LeafNode("ghost", "EQUAL", "x");
        assertThatThrownBy(() -> mapper.map(leaf, orderMc))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error())
                        .isEqualTo("unknown_attribute"));
    }

    @Test
    void notOverStartsWithRejects() {
        NotNode not = new NotNode(new LeafNode("name", "STARTS_WITH", "al"));
        assertThatThrownBy(() -> mapper.map(not, orderMc))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error())
                        .isEqualTo("not_negatable"));
    }

    @Test
    void notOverEndsWithRejects() {
        NotNode not = new NotNode(new LeafNode("name", "ENDS_WITH", "e"));
        assertThatThrownBy(() -> mapper.map(not, orderMc))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error())
                        .isEqualTo("not_negatable"));
    }

    @Test
    void nullRootRejects() {
        assertThatThrownBy(() -> mapper.map(null, orderMc))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error())
                        .isEqualTo("invalid_filter"));
    }

    @Test
    void emptyAndRejects() {
        AndNode empty = new AndNode(List.of());
        assertThatThrownBy(() -> mapper.map(empty, orderMc))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error())
                        .isEqualTo("invalid_filter"));
    }
}
