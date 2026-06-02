package com.vn.agent.orchestration;

import com.vn.agent.entity.AiParameters;
import io.jmix.core.DataManager;
import io.jmix.core.FluentLoader;
import io.jmix.core.FluentLoader.ByQuery;
import io.jmix.core.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class AiParametersResolverTest {

    private DataManager dataManager;
    private Metadata metadata;
    private AiAgentDefaultsProperties defaults;
    private AiParametersResolver resolver;

    @BeforeEach
    void setUp() {
        dataManager = Mockito.mock(DataManager.class, Mockito.RETURNS_DEEP_STUBS);
        metadata = Mockito.mock(Metadata.class);
        defaults = new AiAgentDefaultsProperties("openai/gpt-4o-mini", 0.2, 1.0, 1500, "You are an assistant.");
        Mockito.when(metadata.create(AiParameters.class)).thenAnswer(inv -> new AiParameters());
        resolver = new AiParametersResolver(dataManager, metadata, defaults, List.of());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void fallback_synthesises_AiParameters_when_no_active_row() {
        ByQuery byQuery = Mockito.mock(ByQuery.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(dataManager.load(AiParameters.class).query(Mockito.anyString())).thenReturn(byQuery);
        Mockito.when(byQuery.optional()).thenReturn(Optional.empty());

        AiParameters out = resolver.resolveActive();

        assertThat(out.getProfileName()).isEqualTo("__defaults__");
        assertThat(out.getActive()).isTrue();
        assertThat(resolver.effectiveModel(out)).isEqualTo("openai/gpt-4o-mini");
        assertThat(resolver.effectiveTemperature(out)).isEqualTo(0.2);
        assertThat(resolver.effectiveSystemPrompt(out)).isEqualTo("You are an assistant.");
    }

    @Test
    void invalid_model_slug_without_slash_throws() {
        AiParameters bad = new AiParameters();
        bad.setBodyYaml("model: gpt-4o-mini\n");
        assertThatThrownBy(() -> resolver.effectiveModel(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider/model");
    }

    @Test
    void yaml_overrides_defaults_per_key() {
        AiParameters p = new AiParameters();
        p.setBodyYaml("model: anthropic/claude-3.5-sonnet\ntemperature: 0.7\n");
        assertThat(resolver.effectiveModel(p)).isEqualTo("anthropic/claude-3.5-sonnet");
        assertThat(resolver.effectiveTemperature(p)).isEqualTo(0.7);
        assertThat(resolver.effectiveSystemPrompt(p)).isEqualTo("You are an assistant.");  // fallback
    }

    @Test
    void enabledTools_null_when_body_absent() {
        AiParameters p = new AiParameters();
        assertThat(resolver.effectiveEnabledTools(p)).isNull();
    }

    @Test
    void enabledTools_null_when_key_absent_or_empty() {
        AiParameters noKey = new AiParameters();
        noKey.setBodyYaml("model: anthropic/claude-3.5-sonnet\n");
        assertThat(resolver.effectiveEnabledTools(noKey)).isNull();

        AiParameters emptyList = new AiParameters();
        emptyList.setBodyYaml("model: anthropic/claude-3.5-sonnet\nenabledTools: []\n");
        assertThat(resolver.effectiveEnabledTools(emptyList)).isNull();
    }

    @Test
    void enabledTools_returns_selected_tool_names() {
        AiParameters p = new AiParameters();
        p.setBodyYaml("model: anthropic/claude-3.5-sonnet\n"
                + "enabledTools:\n  - find_records\n  - create_record\n");
        assertThat(resolver.effectiveEnabledTools(p))
                .containsExactly("find_records", "create_record");
    }

    @Test
    void enabledTools_drops_blank_entries() {
        AiParameters p = new AiParameters();
        p.setBodyYaml("model: anthropic/claude-3.5-sonnet\n"
                + "enabledTools:\n  - find_records\n  - '  '\n");
        assertThat(resolver.effectiveEnabledTools(p)).containsExactly("find_records");
    }
}
