package com.vn.agent.extraction;

import com.vn.agent.exposure.LlmExposurePolicy;
import com.vn.agent.spi.IntentExtractor;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.Session;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentRegistryTest {

    @Test
    void eligibleForCurrentUserSortsByLocalizedLabelThenIntentId() {
        MetaClass customer = metaClass("jmixapp_Customer");
        MetaClass order = metaClass("jmixapp_Order");
        Metadata metadata = metadata(customer, order);
        LlmExposurePolicy exposurePolicy = mock(LlmExposurePolicy.class);
        when(exposurePolicy.canReadEntity(customer)).thenReturn(true);
        when(exposurePolicy.canCreate(customer)).thenReturn(true);
        when(exposurePolicy.canReadEntity(order)).thenReturn(true);
        when(exposurePolicy.canCreate(order)).thenReturn(true);
        Messages messages = messages(Locale.ENGLISH);
        when(messages.getMessage("chatView.intent.order.label", Locale.ENGLISH)).thenReturn("Alpha");
        when(messages.getMessage("chatView.intent.customer.label", Locale.ENGLISH)).thenReturn("Beta");

        IntentRegistry registry = new IntentRegistry(List.of(
                extractor("customer", "Customer", "Customer draft", "jmixapp_Customer"),
                extractor("order", "Order", "Order draft", "jmixapp_Order")
        ), metadata, exposurePolicy, messages, currentAuthentication(Locale.ENGLISH));

        assertThat(registry.eligibleForCurrentUser())
                .extracting(IntentOption::intentId)
                .containsExactly("order", "customer");
    }

    @Test
    void unknownIntentHasOptionalAndStableExceptionPaths() {
        IntentRegistry registry = new IntentRegistry(List.of(),
                metadata(), mock(LlmExposurePolicy.class), messages(Locale.ENGLISH),
                currentAuthentication(Locale.ENGLISH));

        assertThat(registry.find("missing")).isEmpty();
        assertThatThrownBy(() -> registry.require("missing"))
                .isInstanceOf(UnknownIntentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void exposureDenialExcludesExtractorFromEligibleOptions() {
        MetaClass allowed = metaClass("jmixapp_Customer");
        MetaClass denied = metaClass("jmixapp_Secret");
        Metadata metadata = metadata(allowed, denied);
        LlmExposurePolicy exposurePolicy = mock(LlmExposurePolicy.class);
        when(exposurePolicy.canReadEntity(allowed)).thenReturn(true);
        when(exposurePolicy.canCreate(allowed)).thenReturn(true);
        when(exposurePolicy.canReadEntity(denied)).thenReturn(false);
        when(exposurePolicy.canCreate(denied)).thenReturn(true);
        Messages messages = messages(Locale.ENGLISH);

        IntentRegistry registry = new IntentRegistry(List.of(
                extractor("allowed", "Allowed", "Allowed draft", "jmixapp_Customer"),
                extractor("denied", "Denied", "Denied draft", "jmixapp_Secret")
        ), metadata, exposurePolicy, messages, currentAuthentication(Locale.ENGLISH));

        assertThat(registry.eligibleForCurrentUser())
                .extracting(IntentOption::intentId)
                .containsExactly("allowed");
    }

    private static IntentExtractor<Object> extractor(String intentId,
                                                     String label,
                                                     String description,
                                                     String entityName) {
        return new IntentExtractor<>() {
            @Override
            public String intentId() {
                return intentId;
            }

            @Override
            public String label() {
                return label;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Class<Object> targetType() {
                return Object.class;
            }

            @Override
            public String entityName() {
                return entityName;
            }

            @Override
            public Object extract(ExtractionInput input) {
                return null;
            }
        };
    }

    private static MetaClass metaClass(String entityName) {
        MetaClass metaClass = mock(MetaClass.class);
        lenient().when(metaClass.getName()).thenReturn(entityName);
        return metaClass;
    }

    private static Metadata metadata(MetaClass... metaClasses) {
        Metadata metadata = mock(Metadata.class);
        Session session = mock(Session.class);
        when(metadata.getSession()).thenReturn(session);
        for (MetaClass metaClass : metaClasses) {
            when(session.findClass(metaClass.getName())).thenReturn(metaClass);
        }
        return metadata;
    }

    private static Messages messages(Locale locale) {
        Messages messages = mock(Messages.class);
        lenient().when(messages.getMessage(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(locale)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return messages;
    }

    private static CurrentAuthentication currentAuthentication(Locale locale) {
        CurrentAuthentication currentAuthentication = mock(CurrentAuthentication.class);
        when(currentAuthentication.getLocale()).thenReturn(locale);
        return currentAuthentication;
    }
}
