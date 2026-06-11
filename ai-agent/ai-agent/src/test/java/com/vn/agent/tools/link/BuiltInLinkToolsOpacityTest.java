package com.vn.agent.tools.link;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.metadata.LlmExposurePolicy;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.metamodel.model.MetaClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Link tools preserve the Phase 10 opacity contract: hidden entities look unknown, never denied.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class BuiltInLinkToolsOpacityTest {

    private static final String EXISTING_ENTITY = "ai_AiConversation";

    @Autowired
    private BuiltInLinkTools builtInLinkTools;

    @MockitoBean
    private LlmExposurePolicy llmExposurePolicy;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void hideEntitiesFromLlm() {
        when(llmExposurePolicy.canReadEntity(any(MetaClass.class))).thenReturn(false);
    }

    @Test
    void listLinkForHiddenEntity_returnsUnknownEntityNotAccessDenied() throws Exception {
        String json = builtInLinkTools.generateEntityListLink(EXISTING_ENTITY);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.path("error").asText())
                .as("hidden list-link entity must collapse to unknown_entity; raw=%s", json)
                .isEqualTo("unknown_entity");
        assertThat(json).doesNotContain("access_denied");
    }

    @Test
    void detailLinkForHiddenEntity_returnsUnknownEntityNotAccessDenied() throws Exception {
        String json = builtInLinkTools.generateEntityDetailLink(EXISTING_ENTITY, UUID.randomUUID().toString());

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.path("error").asText())
                .as("hidden detail-link entity must collapse to unknown_entity; raw=%s", json)
                .isEqualTo("unknown_entity");
        assertThat(json).doesNotContain("access_denied");
    }
}
