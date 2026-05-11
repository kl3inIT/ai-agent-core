package com.vn.agent.extraction;

import com.vn.agent.exposure.LlmExposurePolicy;
import com.vn.agent.spi.IntentExtractor;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.CurrentAuthentication;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Request-scoped view of host-registered named extraction intents.
 *
 * <p>Eligibility is intentionally recalculated per call because Jmix security and Phase 10
 * LLM exposure rules are current-user concerns. The registry never exposes the synthetic
 * "Auto" chat option; UI code owns that option separately.</p>
 */
@Component
public class IntentRegistry {

    private static final String INTENT_LABEL_KEY_PREFIX = "chatView.intent.";
    private static final String INTENT_LABEL_KEY_SUFFIX = ".label";

    private final Map<String, IntentExtractor<?>> extractorsByIntentId;
    private final Metadata metadata;
    private final LlmExposurePolicy llmExposurePolicy;
    private final Messages messages;
    private final CurrentAuthentication currentAuthentication;

    public IntentRegistry(List<IntentExtractor<?>> extractors,
                          Metadata metadata,
                          LlmExposurePolicy llmExposurePolicy,
                          Messages messages,
                          CurrentAuthentication currentAuthentication) {
        this.extractorsByIntentId = indexByIntentId(extractors == null ? List.of() : extractors);
        this.metadata = metadata;
        this.llmExposurePolicy = llmExposurePolicy;
        this.messages = messages;
        this.currentAuthentication = currentAuthentication;
    }

    public Optional<IntentExtractor<?>> find(String intentId) {
        if (!StringUtils.hasText(intentId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(extractorsByIntentId.get(intentId));
    }

    public IntentExtractor<?> require(String intentId) {
        return find(intentId)
                .orElseThrow(() -> new UnknownIntentException(intentId));
    }

    public List<IntentOption> eligibleForCurrentUser() {
        List<IntentOption> options = new ArrayList<>();
        for (IntentExtractor<?> extractor : extractorsByIntentId.values()) {
            Optional<MetaClass> metaClass = resolveMetaClass(extractor.entityName());
            if (metaClass.isEmpty()) {
                continue;
            }
            if (!llmExposurePolicy.canReadEntity(metaClass.get())
                    || !llmExposurePolicy.canCreate(metaClass.get())) {
                continue;
            }
            options.add(new IntentOption(
                    extractor.intentId(),
                    localizedLabel(extractor),
                    extractor.description(),
                    metaClass.get().getName(),
                    false));
        }
        options.sort(Comparator.comparing(IntentOption::label)
                .thenComparing(IntentOption::intentId));
        return List.copyOf(options);
    }

    private Map<String, IntentExtractor<?>> indexByIntentId(List<IntentExtractor<?>> extractors) {
        Map<String, IntentExtractor<?>> result = new LinkedHashMap<>();
        for (IntentExtractor<?> extractor : extractors) {
            if (extractor == null) {
                continue;
            }
            String intentId = extractor.intentId();
            if (!StringUtils.hasText(intentId)) {
                throw new IllegalArgumentException("IntentExtractor intentId must not be blank");
            }
            IntentExtractor<?> previous = result.putIfAbsent(intentId, extractor);
            if (previous != null) {
                throw new IllegalStateException("Duplicate IntentExtractor intentId: " + intentId);
            }
        }
        return Map.copyOf(result);
    }

    private Optional<MetaClass> resolveMetaClass(String entityName) {
        if (!StringUtils.hasText(entityName)) {
            return Optional.empty();
        }
        return Optional.ofNullable(metadata.getSession().findClass(entityName));
    }

    private String localizedLabel(IntentExtractor<?> extractor) {
        String fallback = StringUtils.hasText(extractor.label())
                ? extractor.label()
                : extractor.intentId();
        String key = INTENT_LABEL_KEY_PREFIX + extractor.intentId() + INTENT_LABEL_KEY_SUFFIX;
        Locale locale = currentLocale();
        try {
            String localized = messages.getMessage(key, locale);
            return StringUtils.hasText(localized) && !localized.equals(key) ? localized : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private Locale currentLocale() {
        try {
            Locale locale = currentAuthentication.getLocale();
            return locale == null ? Locale.getDefault() : locale;
        } catch (RuntimeException ignored) {
            return Locale.getDefault();
        }
    }
}
