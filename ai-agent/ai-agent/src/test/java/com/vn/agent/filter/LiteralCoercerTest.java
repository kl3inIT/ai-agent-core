package com.vn.agent.filter;

import com.vn.agent.tools.ToolUserError;
import io.jmix.core.metamodel.datatype.Datatype;
import io.jmix.core.metamodel.datatype.Enumeration;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins every D-07 coercion path of {@link LiteralCoercer}: success + fail-closed
 * {@link ToolUserError} for UUID, Long, Integer, BigDecimal, Boolean, LocalDate,
 * LocalDateTime, Enum, and the {@code unsupported_type} fallback. No Spring context —
 * pure JUnit 5 + Mockito (matches {@code ChatServiceMockTest} style).
 *
 * <p>After the finding #1 fix the coercer delegates scalar datatype parsing to
 * {@link Datatype#parse(String)}; the mocks stub that method to return typed values
 * or throw {@link ParseException} to exercise the fail-closed path.</p>
 */
class LiteralCoercerTest {

    private LiteralCoercer coercer;

    @BeforeEach
    void setUp() {
        coercer = new LiteralCoercer();
    }

    // --------- helpers ---------

    /** Build a MetaProperty whose Datatype.parse returns {@code parsed} for any input. */
    private static MetaProperty datatypeProp(Class<?> javaClass, String name, Object parsed) {
        MetaProperty mp = mock(MetaProperty.class);
        Range range = mock(Range.class);
        @SuppressWarnings("rawtypes")
        Datatype dt = mock(Datatype.class);
        when(mp.getName()).thenReturn(name);
        when(mp.getRange()).thenReturn(range);
        lenient().when(range.isClass()).thenReturn(false);
        lenient().when(range.isEnum()).thenReturn(false);
        lenient().when(range.isDatatype()).thenReturn(true);
        lenient().when(range.asDatatype()).thenReturn(dt);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class raw = javaClass;
        lenient().when(dt.getJavaClass()).thenReturn(raw);
        try {
            lenient().when(dt.parse(anyString())).thenReturn(parsed);
        } catch (ParseException e) {
            throw new AssertionError(e);
        }
        return mp;
    }

    /** Build a MetaProperty whose Datatype.parse throws ParseException. */
    private static MetaProperty datatypePropFailing(Class<?> javaClass, String name) {
        MetaProperty mp = mock(MetaProperty.class);
        Range range = mock(Range.class);
        @SuppressWarnings("rawtypes")
        Datatype dt = mock(Datatype.class);
        when(mp.getName()).thenReturn(name);
        when(mp.getRange()).thenReturn(range);
        lenient().when(range.isClass()).thenReturn(false);
        lenient().when(range.isEnum()).thenReturn(false);
        lenient().when(range.isDatatype()).thenReturn(true);
        lenient().when(range.asDatatype()).thenReturn(dt);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class raw = javaClass;
        lenient().when(dt.getJavaClass()).thenReturn(raw);
        try {
            lenient().when(dt.parse(anyString()))
                    .thenThrow(new ParseException("bad", 0));
        } catch (ParseException e) {
            throw new AssertionError(e);
        }
        return mp;
    }

    private static MetaProperty classProp(String name) {
        MetaProperty mp = mock(MetaProperty.class);
        Range range = mock(Range.class);
        when(mp.getName()).thenReturn(name);
        when(mp.getRange()).thenReturn(range);
        when(range.isClass()).thenReturn(true);
        lenient().when(range.isEnum()).thenReturn(false);
        lenient().when(range.isDatatype()).thenReturn(false);
        return mp;
    }

    private static MetaProperty enumProp(Class<? extends Enum<?>> enumClass, String name) {
        MetaProperty mp = mock(MetaProperty.class);
        Range range = mock(Range.class);
        @SuppressWarnings("rawtypes")
        Enumeration enumeration = mock(Enumeration.class);
        when(mp.getName()).thenReturn(name);
        when(mp.getRange()).thenReturn(range);
        lenient().when(range.isClass()).thenReturn(false);
        when(range.isEnum()).thenReturn(true);
        lenient().when(range.isDatatype()).thenReturn(false);
        when(range.asEnumeration()).thenReturn(enumeration);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class raw = enumClass;
        lenient().when(enumeration.getJavaClass()).thenReturn(raw);
        lenient().when(enumeration.getValues()).thenReturn(List.of(enumClass.getEnumConstants()));
        return mp;
    }

    // --------- String ---------

    @Test
    void coercesStringPassesThrough() {
        MetaProperty mp = datatypeProp(String.class, "name", "alice");
        assertThat(coercer.coerce("alice", mp)).isEqualTo("alice");
    }

    // --------- UUID via datatype ---------

    @Test
    void coercesUuidGood() {
        // UUID uses the coerceUuidString path (special case in coerceDatatype), not Datatype.parse.
        MetaProperty mp = datatypeProp(UUID.class, "id", null);
        UUID expected = UUID.fromString("4f2b8a90-9c8a-4f74-9a5f-3b7a2e4c1d88");
        assertThat(coercer.coerce("4f2b8a90-9c8a-4f74-9a5f-3b7a2e4c1d88", mp))
                .isEqualTo(expected);
    }

    @Test
    void coercesUuidBad() {
        MetaProperty mp = datatypeProp(UUID.class, "id", null);
        assertThatThrownBy(() -> coercer.coerce("not-a-uuid", mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    // --------- Association attribute (class range) ---------

    @Test
    void coercesAssociationUsesUuidRule() {
        MetaProperty mp = classProp("customer");
        UUID expected = UUID.fromString("4f2b8a90-9c8a-4f74-9a5f-3b7a2e4c1d88");
        assertThat(coercer.coerce("4f2b8a90-9c8a-4f74-9a5f-3b7a2e4c1d88", mp))
                .isEqualTo(expected);
    }

    @Test
    void coercesAssociationRejectsNonString() {
        MetaProperty mp = classProp("customer");
        assertThatThrownBy(() -> coercer.coerce(42, mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    // --------- Long ---------

    @Test
    void coercesLongFromNumber() {
        // Number short-circuit: does not touch Datatype.parse.
        MetaProperty mp = datatypeProp(Long.class, "seq", null);
        assertThat(coercer.coerce(42, mp)).isEqualTo(42L);
    }

    @Test
    void coercesLongFromString() {
        MetaProperty mp = datatypeProp(Long.class, "seq", 42L);
        assertThat(coercer.coerce("42", mp)).isEqualTo(42L);
    }

    @Test
    void coercesLongBad() {
        MetaProperty mp = datatypePropFailing(Long.class, "seq");
        assertThatThrownBy(() -> coercer.coerce("not-a-long", mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> {
                    ToolUserError tue = (ToolUserError) e;
                    assertThat(tue.toDto().error()).isEqualTo("invalid_literal");
                    assertThat(tue.toDto().expected()).contains("Long");
                });
    }

    // --------- Integer ---------

    @Test
    void coercesIntegerFromNumber() {
        MetaProperty mp = datatypeProp(Integer.class, "qty", null);
        assertThat(coercer.coerce(7L, mp)).isEqualTo(7);
    }

    @Test
    void coercesIntegerBad() {
        MetaProperty mp = datatypePropFailing(Integer.class, "qty");
        assertThatThrownBy(() -> coercer.coerce("xyz", mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    // --------- BigDecimal ---------

    @Test
    void coercesBigDecimalGood() {
        MetaProperty mp = datatypeProp(BigDecimal.class, "amount", new BigDecimal("12.34"));
        assertThat(coercer.coerce("12.34", mp)).isEqualTo(new BigDecimal("12.34"));
    }

    @Test
    void coercesBigDecimalBad() {
        MetaProperty mp = datatypePropFailing(BigDecimal.class, "amount");
        assertThatThrownBy(() -> coercer.coerce("not-a-number", mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> {
                    ToolUserError tue = (ToolUserError) e;
                    assertThat(tue.toDto().error()).isEqualTo("invalid_literal");
                    assertThat(tue.toDto().expected()).contains("BigDecimal");
                });
    }

    // --------- Boolean ---------

    @Test
    void coercesBooleanFromBoolean() {
        MetaProperty mp = datatypeProp(Boolean.class, "active", null);
        assertThat(coercer.coerce(true, mp)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void coercesBooleanFromString() {
        MetaProperty mp = datatypeProp(Boolean.class, "active", null);
        assertThat(coercer.coerce("true", mp)).isEqualTo(Boolean.TRUE);
        assertThat(coercer.coerce("False", mp)).isEqualTo(Boolean.FALSE);
    }

    @Test
    void coercesBooleanBad() {
        MetaProperty mp = datatypeProp(Boolean.class, "active", null);
        assertThatThrownBy(() -> coercer.coerce("yes", mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> {
                    ToolUserError tue = (ToolUserError) e;
                    assertThat(tue.toDto().error()).isEqualTo("invalid_literal");
                    assertThat(tue.toDto().expected()).contains("true", "false");
                });
    }

    // --------- LocalDate ---------

    @Test
    void coercesLocalDateGood() {
        MetaProperty mp = datatypeProp(LocalDate.class, "birthday", LocalDate.of(2026, 1, 15));
        assertThat(coercer.coerce("2026-01-15", mp)).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void coercesLocalDateBad() {
        MetaProperty mp = datatypePropFailing(LocalDate.class, "birthday");
        assertThatThrownBy(() -> coercer.coerce("01/15/2026", mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> {
                    ToolUserError tue = (ToolUserError) e;
                    assertThat(tue.toDto().error()).isEqualTo("invalid_literal");
                    assertThat(tue.toDto().expected()).contains("LocalDate");
                });
    }

    // --------- LocalDateTime ---------

    @Test
    void coercesLocalDateTimeGood() {
        MetaProperty mp = datatypeProp(LocalDateTime.class, "createdAt",
                LocalDateTime.of(2026, 1, 15, 10, 30));
        assertThat(coercer.coerce("2026-01-15T10:30:00", mp))
                .isEqualTo(LocalDateTime.of(2026, 1, 15, 10, 30));
    }

    @Test
    void coercesLocalDateTimeBad() {
        MetaProperty mp = datatypePropFailing(LocalDateTime.class, "createdAt");
        assertThatThrownBy(() -> coercer.coerce("yesterday", mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    // --------- Enum ---------

    enum SampleStatus { NEW, ACTIVE, CLOSED }

    @Test
    void coercesEnumGood() {
        MetaProperty mp = enumProp(SampleStatus.class, "status");
        assertThat(coercer.coerce("ACTIVE", mp)).isEqualTo(SampleStatus.ACTIVE);
    }

    @Test
    void coercesEnumBad() {
        MetaProperty mp = enumProp(SampleStatus.class, "status");
        assertThatThrownBy(() -> coercer.coerce("UNKNOWN", mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> {
                    ToolUserError tue = (ToolUserError) e;
                    assertThat(tue.toDto().error()).isEqualTo("invalid_literal");
                    assertThat(tue.toDto().expected()).containsExactlyInAnyOrder("NEW", "ACTIVE", "CLOSED");
                });
    }

    @Test
    void coercesEnumRejectsNonString() {
        MetaProperty mp = enumProp(SampleStatus.class, "status");
        assertThatThrownBy(() -> coercer.coerce(1, mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    // --------- null ---------

    @Test
    void rejectsNull() {
        MetaProperty mp = datatypeProp(String.class, "name", null);
        assertThatThrownBy(() -> coercer.coerce(null, mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    // --------- coerceList ---------

    @Test
    void coerceListWrapsEach() {
        MetaProperty mp = datatypeProp(Long.class, "ids", 3L);
        List<Object> out = coercer.coerceList(List.of(1, 2, "3"), mp);
        assertThat(out).containsExactly(1L, 2L, 3L);
    }

    @Test
    void coerceListRejectsScalar() {
        MetaProperty mp = datatypeProp(Long.class, "ids", null);
        assertThatThrownBy(() -> coercer.coerceList("42", mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    @Test
    void coerceListRejectsEmpty() {
        MetaProperty mp = datatypeProp(Long.class, "ids", null);
        assertThatThrownBy(() -> coercer.coerceList(List.of(), mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    // --------- coerceBoolean ---------

    @Test
    void coerceBooleanGood() {
        assertThat(coercer.coerceBoolean(true, "active")).isEqualTo(Boolean.TRUE);
        assertThat(coercer.coerceBoolean("TRUE", "active")).isEqualTo(Boolean.TRUE);
        assertThat(coercer.coerceBoolean("false", "active")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void coerceBooleanBad() {
        assertThatThrownBy(() -> coercer.coerceBoolean("maybe", "active"))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> {
                    ToolUserError tue = (ToolUserError) e;
                    assertThat(tue.toDto().error()).isEqualTo("invalid_literal");
                    assertThat(tue.toDto().expected()).contains("true", "false");
                });
    }
}
