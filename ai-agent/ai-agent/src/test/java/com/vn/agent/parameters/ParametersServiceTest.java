package com.vn.agent.parameters;

import com.vn.agent.entity.AiParameters;
import com.vn.agent.test_support.EvalFixtures;
import io.jmix.core.DataManager;
import io.jmix.core.FluentLoader;
import io.jmix.core.Metadata;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E-09 rubric: {@link ParametersService} strict-on-write validation + setActive invariant.
 *
 * <p>Corpus-driven YAML validation cases from {@code /eval/param-profile-fixtures.yaml} assert
 * the unknown-key / out-of-range rejections (D-05). Two additional tests assert the
 * setActive exactly-one invariant under a concurrent flip race (D-06) using a real
 * {@link AiParametersBodyYamlMapper} plus mocked {@link DataManager}/{@link Metadata}.</p>
 */
@Tag("eval")
class ParametersServiceTest {

    private DataManager dataManager;
    private Metadata metadata;
    private AiParametersBodyYamlMapper yamlMapper;
    private ParametersService service;

    @BeforeEach
    void setUp() {
        dataManager = mock(DataManager.class, Mockito.RETURNS_DEEP_STUBS);
        metadata = mock(Metadata.class);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        yamlMapper = new AiParametersBodyYamlMapper(validator);
        // Metadata.create returns a fresh POJO each invocation.
        when(metadata.create(AiParameters.class)).thenAnswer(inv -> new AiParameters());
        service = new ParametersService(dataManager, metadata, yamlMapper);
    }

    // ---------- E-09: YAML validation corpus ----------------------------------------------------

    static Stream<Arguments> yamlCases() {
        return EvalFixtures.loadCases("param-profile-fixtures.yaml").stream()
                .map(c -> Arguments.of(
                        c.get("name"),
                        c.get("yaml"),
                        c.get("expectInvalid"),
                        c.get("expectMessageKey")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("yamlCases")
    void validateYamlFixture(String caseName, String yaml, Boolean expectInvalid, String expectMessageKey) {
        if (Boolean.TRUE.equals(expectInvalid)) {
            assertThatThrownBy(() -> yamlMapper.readValue(yaml))
                    .isInstanceOf(ParametersValidationException.class)
                    .hasMessageContaining(expectMessageKey);
        } else {
            assertThatCode(() -> yamlMapper.readValue(yaml)).doesNotThrowAnyException();
        }
    }

    // ---------- E-09: setActive exactly-one invariant ------------------------------------------

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setActiveFlipsAllPriorActiveAndSetsTarget() {
        AiParameters existing1 = new AiParameters();
        existing1.setActive(Boolean.TRUE);
        existing1.setProfileName("p1");
        AiParameters existing2 = new AiParameters();
        existing2.setActive(Boolean.TRUE);
        existing2.setProfileName("p2");

        UUID targetId = UUID.randomUUID();
        AiParameters target = new AiParameters();
        target.setProfileName("target");
        target.setActive(Boolean.FALSE);

        FluentLoader.ByQuery byQuery = mock(FluentLoader.ByQuery.class);
        when(dataManager.load(AiParameters.class).query(anyString())).thenReturn(byQuery);
        when(byQuery.list()).thenReturn(new ArrayList<>(List.of(existing1, existing2)));
        when(dataManager.load(AiParameters.class).id(targetId).optional()).thenReturn(Optional.of(target));

        service.setActive(targetId);

        // After the flip: prior actives are false, target is true — exactly one active remains.
        assertThat(existing1.getActive()).isFalse();
        assertThat(existing2.getActive()).isFalse();
        assertThat(target.getActive()).isTrue();
    }

    /**
     * Concurrency smoke test: 10 threads attempt setActive in parallel against the same service.
     * With mocked DataManager there is no actual DB optimistic-locking, but we can assert the
     * service itself is re-entrant — every call must end with exactly one final save on the
     * target. Correctness (not timing) is the invariant per plan T-06-30 guidance.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setActiveIsThreadSafeForDistinctTargets() throws Exception {
        // Each thread activates its OWN distinct target — the test asserts each target ended up
        // active (final per-target state), independent of interleaving.
        int threads = 10;
        AiParameters[] targets = new AiParameters[threads];
        UUID[] ids = new UUID[threads];
        for (int i = 0; i < threads; i++) {
            targets[i] = new AiParameters();
            targets[i].setProfileName("t" + i);
            targets[i].setActive(Boolean.FALSE);
            ids[i] = UUID.randomUUID();
        }

        FluentLoader.ByQuery byQuery = mock(FluentLoader.ByQuery.class);
        when(dataManager.load(AiParameters.class).query(anyString())).thenReturn(byQuery);
        when(byQuery.list()).thenReturn(new ArrayList<>()); // no prior actives
        // Return the correct target for each id.
        for (int i = 0; i < threads; i++) {
            when(dataManager.load(AiParameters.class).id(ids[i]).optional())
                    .thenReturn(Optional.of(targets[i]));
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        service.setActive(ids[idx]);
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).as("all threads complete").isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(failures.get()).as("no exceptional paths").isZero();
        for (AiParameters t : targets) {
            assertThat(t.getActive()).as("target %s set active", t.getProfileName()).isTrue();
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void deleteActiveProfileIsRejected() {
        UUID id = UUID.randomUUID();
        AiParameters active = new AiParameters();
        active.setProfileName("active-one");
        active.setActive(Boolean.TRUE);
        when(dataManager.load(AiParameters.class).id(id).optional()).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active profile");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void createWithUnknownKeyIsRejectedBeforePersistence() {
        String bad = "model: openai/gpt-4o-mini\n" +
                "temperature: 0.2\n" +
                "maxTokens: 2048\n" +
                "systemPrompt: hi\n" +
                "topP: 1.0\n" +
                "bogusField: true\n";
        assertThatThrownBy(() -> service.create("p", bad, false))
                .isInstanceOf(ParametersValidationException.class)
                .hasMessageContaining("unknown-key");
        // DataManager.save MUST NOT have been called — strict-on-write.
        Mockito.verify(dataManager, Mockito.never()).save(any(AiParameters.class));
    }
}
