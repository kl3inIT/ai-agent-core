package com.vn.agent.parameters;

import com.vn.agent.entity.AiParameters;
import io.jmix.core.DataManager;
import io.jmix.core.FluentLoader;
import io.jmix.core.Metadata;
import io.jmix.core.security.SystemAuthenticator;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * E-12 rubric: {@link DefaultParamsSeeder} idempotence (PARAM-04, D-04).
 *
 * <p>Pure Mockito: confirms (a) first boot with empty table seeds one "default" row from the
 * classpath YAML, (b) subsequent boots with any pre-existing rows (count &gt; 0) are a strict
 * no-op, (c) re-running the same seeder instance twice never produces duplicate rows — the
 * count probe short-circuits the write path.</p>
 */
@Tag("eval")
class DefaultParamsSeederTest {

    private DataManager dataManager;
    private Metadata metadata;
    private AiParametersBodyYamlMapper yamlMapper;
    private ResourceLoader resourceLoader;
    private SystemAuthenticator systemAuthenticator;

    private DefaultParamsSeeder seeder;

    private static final String VALID_YAML =
            "model: openai/gpt-4o-mini\n" +
                    "temperature: 0.2\n" +
                    "topP: 1.0\n" +
                    "maxTokens: 2048\n" +
                    "systemPrompt: \"You are an assistant.\"\n";

    @BeforeEach
    void setUp() {
        dataManager = mock(DataManager.class);
        metadata = mock(Metadata.class);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        yamlMapper = new AiParametersBodyYamlMapper(validator);
        resourceLoader = mock(ResourceLoader.class);
        systemAuthenticator = mock(SystemAuthenticator.class);

        // SystemAuthenticator.runWithSystem(Runnable) — invoke the runnable inline.
        Mockito.doAnswer(inv -> {
            Runnable r = inv.getArgument(0);
            r.run();
            return null;
        }).when(systemAuthenticator).runWithSystem(any(Runnable.class));

        when(metadata.create(AiParameters.class)).thenAnswer(inv -> new AiParameters());

        seeder = new DefaultParamsSeeder(
                dataManager, metadata, yamlMapper, resourceLoader, systemAuthenticator);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubCount(long count) {
        FluentLoader<AiParameters> loader = mock(FluentLoader.class);
        @SuppressWarnings("rawtypes")
        FluentLoader.ByCondition byCondition = mock(FluentLoader.ByCondition.class);
        when(dataManager.load(AiParameters.class)).thenReturn(loader);
        when(loader.all()).thenReturn(byCondition);
        when(byCondition.maxResults(1)).thenReturn(byCondition);
        when(byCondition.list()).thenReturn(count > 0 ? java.util.List.of(new AiParameters()) : java.util.List.of());
    }

    private void stubResource(boolean exists, String content) {
        Resource resource = exists
                ? new ByteArrayResource(content.getBytes())
                : mock(Resource.class);
        if (!exists) {
            when(resource.exists()).thenReturn(false);
        }
        when(resourceLoader.getResource(anyString())).thenReturn(resource);
    }

    @Test
    void seedsOnFirstBootWhenTableIsEmpty() {
        stubCount(0L);
        stubResource(true, VALID_YAML);

        seeder.seedIfEmpty();

        ArgumentCaptor<AiParameters> saved = ArgumentCaptor.forClass(AiParameters.class);
        verify(dataManager).save(saved.capture());
        AiParameters row = saved.getValue();
        assertThat(row.getProfileName()).isEqualTo("default");
        assertThat(row.getActive()).isTrue();
        assertThat(row.getBodyYaml()).contains("openai/gpt-4o-mini");
    }

    @Test
    void skipsWhenTableAlreadyHasRows() {
        stubCount(1L);
        // Resource loader MUST NOT be consulted when the count > 0 — idempotency short-circuit.
        seeder.seedIfEmpty();

        verify(dataManager, never()).save(any(AiParameters.class));
        verify(resourceLoader, never()).getResource(anyString());
    }

    @Test
    void idempotentWhenInvokedTwice() {
        // First boot: empty → seed. Second boot: count now 1 → no-op.
        stubCount(0L);
        stubResource(true, VALID_YAML);
        seeder.seedIfEmpty();
        verify(dataManager, Mockito.times(1)).save(any(AiParameters.class));

        // Simulate subsequent boot: table now populated.
        stubCount(1L);
        seeder.seedIfEmpty();
        // Still only ONE save across both invocations.
        verify(dataManager, Mockito.times(1)).save(any(AiParameters.class));
    }

    @Test
    void skipsSilentlyWhenResourceMissing() {
        stubCount(0L);
        stubResource(false, "");

        seeder.seedIfEmpty();

        verify(dataManager, never()).save(any(AiParameters.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void skipsSilentlyWhenProbeFails() {
        FluentLoader<AiParameters> loader = mock(FluentLoader.class);
        FluentLoader.ByCondition byCondition = mock(FluentLoader.ByCondition.class, withSettings().stubOnly());
        when(dataManager.load(AiParameters.class)).thenReturn(loader);
        when(loader.all()).thenReturn(byCondition);
        when(byCondition.maxResults(1)).thenReturn(byCondition);
        when(byCondition.list()).thenThrow(new IllegalArgumentException("entity unknown"));

        seeder.seedIfEmpty();

        verify(dataManager, never()).save(any(AiParameters.class));
        verify(resourceLoader, never()).getResource(anyString());
    }
}
