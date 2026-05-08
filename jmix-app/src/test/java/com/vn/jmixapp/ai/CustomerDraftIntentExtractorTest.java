package com.vn.jmixapp.ai;

import com.vn.agent.extraction.ExtractionInput;
import com.vn.agent.extraction.ExtractionSchemaException;
import com.vn.agent.extraction.MetaClassDtoSynthesizer;
import com.vn.jmixapp.entity.Customer;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerDraftIntentExtractorTest {

    private static final ValidatorFactory VALIDATOR_FACTORY =
            Validation.buildDefaultValidatorFactory();

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private MetaClassDtoSynthesizer schemaSynthesizer;
    private CustomerDraftIntentExtractor extractor;

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        schemaSynthesizer = mock(MetaClassDtoSynthesizer.class);
        Validator validator = VALIDATOR_FACTORY.getValidator();

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(
                org.mockito.ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any()))
                .thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(schemaSynthesizer.buildSchema(Customer.class)).thenReturn(customerSchema());

        extractor = new CustomerDraftIntentExtractor(chatClient, schemaSynthesizer,
                new com.fasterxml.jackson.databind.ObjectMapper(), validator);
    }

    @Test
    void mapsFixtureJsonToCustomerFields() {
        stubModelPayload(payload("Acme Trading", "billing@acme.example", "+84 901 234 567"));

        Customer customer = extractor.extract(input("Create a customer draft", List.of()));

        assertThat(customer.getName()).isEqualTo("Acme Trading");
        assertThat(customer.getEmail()).isEqualTo("billing@acme.example");
        assertThat(customer.getPhone()).isEqualTo("+84 901 234 567");
    }

    @Test
    void promptUsesSchemaLimitedToCustomerReferenceFields() {
        stubModelPayload(payload("Acme Trading", "billing@acme.example", "+84 901 234 567"));

        extractor.extract(input("Use the customer data from this note", List.of()));
        ChatClient.PromptUserSpec userSpec = runCapturedUserSpec();

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(userSpec).text(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("\"name\"")
                .contains("\"email\"")
                .contains("\"phone\"")
                .doesNotContain("recommendedProducts");
    }

    @Test
    void zeroFileInputUsesUserMessageAndDoesNotAttachMedia() {
        stubModelPayload(payload("Text Only", "text.only@example.test", "090123456"));

        extractor.extract(input("Customer name Text Only, email text.only@example.test", List.of()));
        ChatClient.PromptUserSpec userSpec = runCapturedUserSpec();

        verify(userSpec).text(org.mockito.ArgumentMatchers.contains("Customer name Text Only"));
        verify(userSpec, never()).media(any(Media[].class));
    }

    @Test
    void mediaInputIsPassedToSpringAiUserMessage() {
        Media media = Media.builder()
                .mimeType(MimeTypeUtils.TEXT_PLAIN)
                .data(new ByteArrayResource("Customer: Media Co".getBytes(StandardCharsets.UTF_8)))
                .name("customer.txt")
                .build();
        stubModelPayload(payload("Media Co", "media@example.test", "090123456"));

        extractor.extract(input("Use the attached file", List.of(media)));
        ChatClient.PromptUserSpec userSpec = runCapturedUserSpec();

        ArgumentCaptor<Media[]> mediaCaptor = ArgumentCaptor.forClass(Media[].class);
        verify(userSpec).media(mediaCaptor.capture());
        assertThat(mediaCaptor.getValue()).containsExactly(media);
    }

    @Test
    void invalidEmailThrowsSchemaExceptionWithoutRawValue() {
        stubModelPayload(payload("Bad Email", "not-an-email", "090123456"));

        assertThatThrownBy(() -> extractor.extract(input("bad email", List.of())))
                .isInstanceOf(ExtractionSchemaException.class)
                .extracting("code")
                .isEqualTo(ExtractionSchemaException.CODE_VALIDATION_FAILURE);
    }

    @Test
    void invalidPhoneThrowsSchemaExceptionWithoutRawValue() {
        stubModelPayload(payload("Bad Phone", "phone@example.test", "call-me"));

        assertThatThrownBy(() -> extractor.extract(input("bad phone", List.of())))
                .isInstanceOf(ExtractionSchemaException.class)
                .hasMessageNotContaining("call-me")
                .extracting("code")
                .isEqualTo(ExtractionSchemaException.CODE_VALIDATION_FAILURE);
    }

    @Test
    void extraKeyThrowsSchemaExceptionWithoutRawValue() {
        LinkedHashMap<String, Object> payload = payload(
                "Extra Key", "extra@example.test", "090123456");
        payload.put("recommendedProducts", "secret recommendation");
        stubModelPayload(payload);

        assertThatThrownBy(() -> extractor.extract(input("extra key", List.of())))
                .isInstanceOf(ExtractionSchemaException.class)
                .hasMessageNotContaining("secret recommendation")
                .extracting("code")
                .isEqualTo(ExtractionSchemaException.CODE_VALIDATION_FAILURE);
    }

    private void stubModelPayload(Map<String, Object> payload) {
        when(callSpec.entity(
                org.mockito.ArgumentMatchers.<ParameterizedTypeReference<Map<String, Object>>>any()))
                .thenReturn(payload);
    }

    private ExtractionInput input(String userMessage, List<Media> media) {
        return new ExtractionInput(CustomerDraftIntentExtractor.INTENT_ID, UUID.randomUUID(),
                userMessage, List.of(UUID.randomUUID()), media);
    }

    private ChatClient.PromptUserSpec runCapturedUserSpec() {
        ChatClient.PromptUserSpec userSpec = mock(ChatClient.PromptUserSpec.class);
        when(userSpec.text(any(String.class))).thenReturn(userSpec);
        when(userSpec.media(any(Media[].class))).thenReturn(userSpec);
        capturedUserConsumer().accept(userSpec);
        return userSpec;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Consumer<ChatClient.PromptUserSpec> capturedUserConsumer() {
        ArgumentCaptor<Consumer> userCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(requestSpec).user(userCaptor.capture());
        return (Consumer<ChatClient.PromptUserSpec>) userCaptor.getValue();
    }

    private static LinkedHashMap<String, Object> payload(String name, String email, String phone) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("email", email);
        payload.put("phone", phone);
        return payload;
    }

    private static MetaClassDtoSynthesizer.SynthesizedSchema customerSchema() {
        return new MetaClassDtoSynthesizer.SynthesizedSchema(
                """
                        {"type":"object","additionalProperties":false,"properties":{"name":{"type":"string"},"email":{"type":"string"},"phone":{"type":"string"}},"required":["name"]}
                        """.trim(),
                List.of("name", "email", "phone"),
                List.of("name"));
    }
}
