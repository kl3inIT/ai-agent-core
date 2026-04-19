package com.vn.agent.metadata;

import java.util.List;

/**
 * Per-attribute metadata exposed to the LLM via {@code describe_entity} (D-02).
 * <ul>
 *   <li>{@code typeLabel}: human-friendly type (e.g. "String", "LocalDate", "UUID", "BigDecimal",
 *       "enum:OrderStatus", "ref:jmixapp_Customer").</li>
 *   <li>{@code relationshipTarget}: non-null only for association attributes; carries target Jmix
 *       entity name.</li>
 *   <li>{@code enumValues}: non-null only for enum-typed attributes; enum constant names.</li>
 *   <li>{@code validationConstraints}: simple list of constraint descriptors (e.g. "NotNull",
 *       "Size(max=32)", "Pattern(regexp=...)") — no Jmix internals, no DDL.</li>
 *   <li>{@code localizedLabel}: resolved per-request by {@link EffectiveSchemaComputer} (D-04);
 *       scanner leaves {@code null}.</li>
 * </ul>
 */
public record AiAttributeInfo(String name,
                              String typeLabel,
                              boolean nullable,
                              List<String> enumValues,
                              String relationshipTarget,
                              List<String> validationConstraints,
                              String localizedLabel) {
    public AiAttributeInfo {
        enumValues = enumValues == null ? null : List.copyOf(enumValues);
        validationConstraints = List.copyOf(validationConstraints);
    }

    public AiAttributeInfo withLocalizedLabel(String label) {
        return new AiAttributeInfo(name, typeLabel, nullable, enumValues, relationshipTarget,
                validationConstraints, label);
    }
}
