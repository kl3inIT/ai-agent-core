package com.vn.agent.spi;

import com.vn.agent.extraction.ExtractionInput;

/**
 * Host extension point for named intent-driven extraction.
 *
 * @param <T> host entity or DTO type produced by the extractor
 */
public interface IntentExtractor<T> {

    String intentId();

    String label();

    String description();

    Class<T> targetType();

    String entityName();

    T extract(ExtractionInput input);
}
