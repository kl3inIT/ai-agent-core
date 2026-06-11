package com.vn.agent.filter;

import com.vn.agent.filter.literal.DatatypeLiteralValueConverter;
import com.vn.agent.filter.literal.EnumLiteralValueConverter;
import com.vn.agent.filter.literal.ReferenceLiteralValueConverter;
import com.vn.agent.tools.ToolUserError;
import io.jmix.core.metamodel.datatype.Datatype;
import io.jmix.core.metamodel.datatype.EnumClass;
import io.jmix.core.metamodel.datatype.Enumeration;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.lang.NonNull;

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
 * Pins every D-07 conversion path of {@link FilterLiteralValueConverter}: success + fail-closed
 * {@link ToolUserError} for UUID, Long, Integer, BigDecimal, Boolean, LocalDate,
 * LocalDateTime, Enum, and the {@code unsupported_type} fallback. No Spring context —
 * pure JUnit 5 + Mockito (matches {@code ChatServiceMockTest} style).
 *
 * <p>After the finding #1 fix the converter delegates scalar datatype parsing to
 * {@link Datatype#parse(String)}; the mocks stub that method to return typed values
 * or throw {@link ParseException} to exercise the fail-closed path.</p>
 */
class FilterLiteralValueConverterTest {

    private FilterLiteralValueConverter filterLiteralValueConverter;

    @BeforeEach
    void setUp() {
        filterLiteralValueConverter = new FilterLiteralValueConverter(List.of(
                new ReferenceLiteralValueConverter(),
                new EnumLiteralValueConverter(),
                new DatatypeLiteralValueConverter()));
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
        assertThat(filterLiteralValueConverter.convertValue("alice", mp)).isEqualTo("alice");
    }

    @Test
    void coercesStringRejectsNonString() {
        MetaProperty mp = datatypeProp(String.class, "name", null);
        assertThatThrownBy(() -> filterLiteralValueConverter.convertValue(42, mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    // --------- UUID via datatype ---------

    @Test
    void coercesUuidGood() {
        // UUID uses the dedicated UUID conversion path, not Datatype.parse.
        MetaProperty mp = datatypeProp(UUID.class, "id", null);
        UUID expected = UUID.fromString("4f2b8a90-9c8a-4f74-9a5f-3b7a2e4c1d88");
        assertThat(filterLiteralValueConverter.convertValue("4f2b8a90-9c8a-4f74-9a5f-3b7a2e4c1d88", mp))
                .isEqualTo(expected);
    }

    @Test
    void coercesUuidBad() {
        MetaProperty mp = datatypeProp(UUID.class, "id", null);
        assertThatThrownBy(() -> filterLiteralValueConverter.convertValue("not-a-uuid", mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    // --------- Association attribute (class range) ---------

    @Test
    void coercesAssociationUsesUuidRule() {
        MetaProperty mp = classProp("customer");
        UUID expected = UUID.fromString("4f2b8a90-9c8a-4f74-9a5f-3b7a2e4c1d88");
        assertThat(filterLiteralValueConverter.convertValue("4f2b8a90-9c8a-4f74-9a5f-3b7a2e4c1d88", mp))
                .isEqualTo(expected);
    }

    @Test
    void coercesAssociationRejectsNonString() {
        MetaProperty mp = classProp("customer");
        assertThatThrownBy(() -> filterLiteralValueConverter.convertValue(42, mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    // --------- Long ---------

    @Test
    void coercesLongFromNumber() {
        // Number short-circuit: does not touch Datatype.parse.
        MetaProperty mp = datatypeProp(Long.class, "seq", null);
        assertThat(filterLiteralValueConverter.convertValue(42, mp)).isEqualTo(42L);
    }

    @Test
    void coercesLongFromString() {
        MetaProperty mp = datatypeProp(Long.class, "seq", 42L);
        assertThat(filterLiteralValueConverter.convertValue("42", mp)).isEqualTo(42L);
    }

    @Test
    void coercesLongBad() {
        MetaProperty mp = datatypePropFailing(Long.class, "seq");
        assertThatThrownBy(() -> filterLiteralValueConverter.convertValue("not-a-long", mp))
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
        assertThat(filterLiteralValueConverter.convertValue(7L, mp)).isEqualTo(7);
    }

    @Test
    void coercesIntegerRejectsFractionalNumber() {
        MetaProperty mp = datatypeProp(Integer.class, "qty", null);
        assertThatThrownBy(() -> filterLiteralValueConverter.convertValue(7.5, mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    @Test
    void coercesIntegerRejectsOutOfRangeNumber() {
        MetaProperty mp = datatypeProp(Integer.class, "qty", null);
        assertThatThrownBy(() -> filterLiteralValueConverter.convertValue(3_000_000_000L, mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    @Test
    void coercesIntegerBad() {
        MetaProperty mp = datatypePropFailing(Integer.class, "qty");
        assertThatThrownBy(() -> filterLiteralValueConverter.convertValue("xyz", mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    // --------- BigDecimal ---------

    @Test
    void coercesBigDecimalGood() {
        MetaProperty mp = datatypeProp(BigDecimal.class, "amount", new BigDecimal("12.34"));
        assertThat(filterLiteralValueConverter.convertValue("12.34", mp)).isEqualTo(new BigDecimal("12.34"));
    }

    @Test
    void coercesBigDecimalBad() {
        MetaProperty mp = datatypePropFailing(BigDecimal.class, "amount");
        assertThatThrownBy(() -> filterLiteralValueConverter.convertValue("not-a-number", mp))
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
        assertThat(filterLiteralValueConverter.convertValue(true, mp)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void coercesBooleanFromString() {
        MetaProperty mp = datatypeProp(Boolean.class, "active", null);
        assertThat(filterLiteralValueConverter.convertValue("true", mp)).isEqualTo(Boolean.TRUE);
        assertThat(filterLiteralValueConverter.convertValue("False", mp)).isEqualTo(Boolean.FALSE);
    }

    @Test
    void coercesBooleanBad() {
        MetaProperty mp = datatypeProp(Boolean.class, "active", null);
        assertThatThrownBy(() -> filterLiteralValueConverter.convertValue("yes", mp))
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
        assertThat(filterLiteralValueConverter.convertValue("2026-01-15", mp)).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void coercesLocalDateIsoBeforeLocaleDatatypeParser() {
        MetaProperty mp = datatypePropFailing(LocalDate.class, "birthday");
        assertThat(filterLiteralValueConverter.convertValue("2026-01-15", mp))
                .isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void coercesLocalDateBad() {
        MetaProperty mp = datatypePropFailing(LocalDate.class, "birthday");
        assertThatThrownBy(() -> filterLiteralValueConverter.convertValue("01/15/2026", mp))
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
        assertThat(filterLiteralValueConverter.convertValue("2026-01-15T10:30:00", mp))
                .isEqualTo(LocalDateTime.of(2026, 1, 15, 10, 30));
    }

    @Test
    void coercesLocalDateTimeIsoBeforeLocaleDatatypeParser() {
        MetaProperty mp = datatypePropFailing(LocalDateTime.class, "createdAt");
        assertThat(filterLiteralValueConverter.convertValue("2026-01-15T10:30:00", mp))
                .isEqualTo(LocalDateTime.of(2026, 1, 15, 10, 30));
    }

    @Test
    void coercesLocalDateTimeBad() {
        MetaProperty mp = datatypePropFailing(LocalDateTime.class, "createdAt");
        assertThatThrownBy(() -> filterLiteralValueConverter.convertValue("yesterday", mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    // --------- Enum ---------

    enum SampleStatus { NEW, ACTIVE, CLOSED }

    enum NumberedStatus implements EnumClass<Integer> {
        DRAFT(10),
        POSTED(20);

        private final Integer id;

        NumberedStatus(Integer id) {
            this.id = id;
        }

        @Override
        @NonNull
        public Integer getId() {
            return id;
        }
    }

    enum ConflictingStatus implements EnumClass<String> {
        ACTIVE("DRAFT"),
        DRAFT("ACTIVE");

        private final String id;

        ConflictingStatus(String id) {
            this.id = id;
        }

        @Override
        @NonNull
        public String getId() {
            return id;
        }
    }

    @Test
    void coercesEnumGood() {
        MetaProperty mp = enumProp(SampleStatus.class, "status");
        assertThat(filterLiteralValueConverter.convertValue("ACTIVE", mp)).isEqualTo(SampleStatus.ACTIVE);
    }

    @Test
    void coercesEnumClassByNumericId() {
        MetaProperty mp = enumProp(NumberedStatus.class, "status");
        assertThat(filterLiteralValueConverter.convertValue(20, mp)).isEqualTo(NumberedStatus.POSTED);
        assertThat(filterLiteralValueConverter.convertValue("10", mp)).isEqualTo(NumberedStatus.DRAFT);
    }

    @Test
    void coercesEnumClassPrefersStableIdOverJavaName() {
        MetaProperty mp = enumProp(ConflictingStatus.class, "status");
        assertThat(filterLiteralValueConverter.convertValue("ACTIVE", mp)).isEqualTo(ConflictingStatus.DRAFT);
    }

    @Test
    void coercesEnumBad() {
        MetaProperty mp = enumProp(SampleStatus.class, "status");
        assertThatThrownBy(() -> filterLiteralValueConverter.convertValue("UNKNOWN", mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> {
                    ToolUserError tue = (ToolUserError) e;
                    assertThat(tue.toDto().error()).isEqualTo("invalid_literal");
                    assertThat(tue.toDto().expected()).containsExactlyInAnyOrder("NEW", "ACTIVE", "CLOSED");
                });
    }

    @Test
    void coercesEnumRejectsUnknownNumericId() {
        MetaProperty mp = enumProp(SampleStatus.class, "status");
        assertThatThrownBy(() -> filterLiteralValueConverter.convertValue(1, mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    // --------- null ---------

    @Test
    void rejectsNull() {
        MetaProperty mp = datatypeProp(String.class, "name", null);
        assertThatThrownBy(() -> filterLiteralValueConverter.convertValue(null, mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    // --------- convertListValues ---------

    @Test
    void convertListValuesWrapsEach() {
        MetaProperty mp = datatypeProp(Long.class, "ids", 3L);
        List<Object> out = filterLiteralValueConverter.convertListValues(List.of(1, 2, "3"), mp);
        assertThat(out).containsExactly(1L, 2L, 3L);
    }

    @Test
    void convertListValuesRejectsScalar() {
        MetaProperty mp = datatypeProp(Long.class, "ids", null);
        assertThatThrownBy(() -> filterLiteralValueConverter.convertListValues("42", mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    @Test
    void convertListValuesRejectsEmpty() {
        MetaProperty mp = datatypeProp(Long.class, "ids", null);
        assertThatThrownBy(() -> filterLiteralValueConverter.convertListValues(List.of(), mp))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> assertThat(((ToolUserError) e).toDto().error()).isEqualTo("invalid_literal"));
    }

    // --------- convertBooleanValue ---------

    @Test
    void convertBooleanValueGood() {
        assertThat(filterLiteralValueConverter.convertBooleanValue(true, "active")).isEqualTo(Boolean.TRUE);
        assertThat(filterLiteralValueConverter.convertBooleanValue("TRUE", "active")).isEqualTo(Boolean.TRUE);
        assertThat(filterLiteralValueConverter.convertBooleanValue("false", "active")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void convertBooleanValueBad() {
        assertThatThrownBy(() -> filterLiteralValueConverter.convertBooleanValue("maybe", "active"))
                .isInstanceOf(ToolUserError.class)
                .satisfies(e -> {
                    ToolUserError tue = (ToolUserError) e;
                    assertThat(tue.toDto().error()).isEqualTo("invalid_literal");
                    assertThat(tue.toDto().expected()).contains("true", "false");
                });
    }
}
