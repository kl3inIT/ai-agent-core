package com.vn.jmixapp.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.extraction.ExtractionInput;
import com.vn.agent.extraction.ExtractionSchemaException;
import com.vn.agent.extraction.ExtractionSourceText;
import com.vn.agent.extraction.MetaClassDtoSynthesizer;
import com.vn.agent.spi.IntentExtractor;
import com.vn.jmixapp.entity.Customer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Host-side reference intent proving the generic extraction engine against jmixapp_Customer.
 */
@Component
@ConditionalOnProperty(prefix = "ai-agent.intents.customer-reference",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CustomerDraftIntentExtractor implements IntentExtractor<Customer> {

    public static final String INTENT_ID = "customer-from-pdf";
    public static final String ENTITY_NAME = "jmixapp_Customer";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_OUTPUT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final ChatClient chatClient;
    private final MetaClassDtoSynthesizer schemaSynthesizer;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public CustomerDraftIntentExtractor(ChatClient chatClient,
                                        MetaClassDtoSynthesizer schemaSynthesizer,
                                        ObjectMapper objectMapper,
                                        Validator validator) {
        this.chatClient = chatClient;
        this.schemaSynthesizer = schemaSynthesizer;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @Override
    public String intentId() {
        return INTENT_ID;
    }

    @Override
    public String label() {
        return "Customer draft";
    }

    @Override
    public String description() {
        return "Extract a customer name, email, and phone into a draft form.";
    }

    @Override
    public Class<Customer> targetType() {
        return Customer.class;
    }

    @Override
    public String entityName() {
        return ENTITY_NAME;
    }

    @Override
    public Customer extract(ExtractionInput input) {
        MetaClassDtoSynthesizer.SynthesizedSchema schema = schemaSynthesizer.buildSchema(Customer.class);
        String promptText = promptText(input, schema);
        Map<String, Object> rawPayload = chatClient.prompt()
                .user(userSpec -> {
                    userSpec.text(promptText);
                    List<Media> media = input == null ? List.of() : input.taskFileMedia();
                    if (!media.isEmpty()) {
                        userSpec.media(media.toArray(new Media[0]));
                    }
                })
                .call()
                .entity(MAP_OUTPUT_TYPE);

        LinkedHashMap<String, Object> narrowedPayload = validatePayload(rawPayload, schema);
        assertSourceFaithful(narrowedPayload, input);
        Customer customer = convertToCustomer(narrowedPayload);
        validateCustomer(customer, narrowedPayload.size());
        return customer;
    }

    private String promptText(ExtractionInput input, MetaClassDtoSynthesizer.SynthesizedSchema schema) {
        String userMessage = input == null ? null : input.userMessage();
        return """
                You extract one Customer draft for a Jmix detail view.

                Respond with ONLY a single JSON object. No prose. No markdown fences.
                The object MUST strictly conform to this JSON schema and MUST NOT include extra keys:
                %s

                Field guidance:
                - name: customer or contact name exactly as supported by the source.
                - email: email address only when present in the source.
                - phone: phone number only when present in the source.
                - If a value is missing or ambiguous, omit the field or use null instead of inventing it.

                User message:
                %s
                """.formatted(schema.schemaJson(), StringUtils.hasText(userMessage) ? userMessage : "(no user message)");
    }

    private LinkedHashMap<String, Object> validatePayload(Map<String, Object> payload,
                                                          MetaClassDtoSynthesizer.SynthesizedSchema schema) {
        if (payload == null) {
            throw ExtractionSchemaException.schemaParseFailure(INTENT_ID, ENTITY_NAME, null);
        }

        Set<String> allowedAttributeNames = Set.copyOf(schema.attributeNames());
        List<String> extraKeys = payload.keySet().stream()
                .filter(key -> !allowedAttributeNames.contains(key))
                .sorted()
                .toList();
        if (!extraKeys.isEmpty()) {
            throw ExtractionSchemaException.validationFailure(INTENT_ID, ENTITY_NAME, payload.size());
        }

        LinkedHashMap<String, Object> narrowedPayload = new LinkedHashMap<>();
        for (String attributeName : schema.attributeNames()) {
            if (payload.containsKey(attributeName)) {
                Object value = payload.get(attributeName);
                if (value != null && !(value instanceof String)) {
                    throw ExtractionSchemaException.validationFailure(INTENT_ID, ENTITY_NAME, payload.size());
                }
                if ("phone".equals(attributeName) && value instanceof String phone && !isValidPhone(phone)) {
                    throw ExtractionSchemaException.validationFailure(INTENT_ID, ENTITY_NAME, payload.size());
                }
                narrowedPayload.put(attributeName, value);
            }
        }
        return narrowedPayload;
    }

    private void assertSourceFaithful(LinkedHashMap<String, Object> narrowedPayload,
                                      ExtractionInput input) {
        String rawEvidence = sourceEvidence(input);
        if (!StringUtils.hasText(rawEvidence)) {
            return;
        }
        String lowerCaseEvidence = rawEvidence.toLowerCase(Locale.ROOT);
        String normalizedEvidence = normalizeText(rawEvidence);
        String digitEvidence = digitsOnly(rawEvidence);

        for (Map.Entry<String, Object> entry : narrowedPayload.entrySet()) {
            if (!(entry.getValue() instanceof String value) || !StringUtils.hasText(value)) {
                continue;
            }
            boolean supported = switch (entry.getKey()) {
                case "email" -> lowerCaseEvidence.contains(value.toLowerCase(Locale.ROOT).trim());
                case "phone" -> {
                    String valueDigits = digitsOnly(value);
                    yield !valueDigits.isEmpty() && digitEvidence.contains(valueDigits);
                }
                default -> normalizedEvidence.contains(normalizeText(value));
            };
            if (!supported) {
                throw ExtractionSchemaException.validationFailure(
                        INTENT_ID, ENTITY_NAME, narrowedPayload.size());
            }
        }
    }

    private String sourceEvidence(ExtractionInput input) {
        if (input == null) {
            return "";
        }
        List<String> evidence = new ArrayList<>();
        if (StringUtils.hasText(input.userMessage())) {
            evidence.add(input.userMessage());
        }
        for (ExtractionSourceText sourceText : input.sourceTexts()) {
            if (sourceText != null && StringUtils.hasText(sourceText.text())) {
                evidence.add(sourceText.text());
            }
        }
        return String.join("\n", evidence);
    }

    private static String normalizeText(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String digitsOnly(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private boolean isValidPhone(String phone) {
        return phone.length() <= 32 && phone.matches("[+0-9 ().\\-/]*");
    }

    private Customer convertToCustomer(Map<String, Object> payload) {
        try {
            return objectMapper.convertValue(payload, Customer.class);
        } catch (IllegalArgumentException e) {
            throw ExtractionSchemaException.schemaParseFailure(INTENT_ID, ENTITY_NAME, e);
        }
    }

    private void validateCustomer(Customer customer, int extractedFieldCount) {
        Set<ConstraintViolation<Customer>> violations = validator.validate(customer);
        if (!violations.isEmpty()) {
            throw ExtractionSchemaException.validationFailure(INTENT_ID, ENTITY_NAME, extractedFieldCount);
        }
    }
}
