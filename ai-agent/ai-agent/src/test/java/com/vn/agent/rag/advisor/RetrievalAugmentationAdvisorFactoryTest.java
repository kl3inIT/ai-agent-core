package com.vn.agent.rag.advisor;

import com.vn.agent.rag.config.AiAgentRagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.vectorstore.VectorStore;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RetrievalAugmentationAdvisorFactoryTest {

    @Test
    void retrievalAdvisor_allowsEmptyContextByDefault() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        AiAgentRagProperties props = new AiAgentRagProperties(
                null, null, null, null, null, null, null, null, null);

        RetrievalAugmentationAdvisor advisor =
                new RetrievalAugmentationAdvisorFactory().retrievalAugmentationAdvisor(vectorStore, props);

        Field queryAugmenterField = RetrievalAugmentationAdvisor.class.getDeclaredField("queryAugmenter");
        queryAugmenterField.setAccessible(true);
        Object queryAugmenter = queryAugmenterField.get(advisor);
        assertThat(queryAugmenter).isInstanceOf(ContextualQueryAugmenter.class);

        Field allowEmptyContextField = ContextualQueryAugmenter.class.getDeclaredField("allowEmptyContext");
        allowEmptyContextField.setAccessible(true);
        boolean allowEmptyContext = allowEmptyContextField.getBoolean(queryAugmenter);
        assertThat(allowEmptyContext).isTrue();
    }
}
