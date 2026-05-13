package com.vn.agent.admin.config;

import com.vn.agent.entity.AiUiSettings;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 16 Plan 02 — bean-validation gate on the 12 new Tier-1 columns of
 * {@link AiUiSettings}.
 *
 * <p>The plan's original shape (@SpringBootTest + UnconstrainedDataManager.save → expect
 * ConstraintViolationException at JPA flush) cannot run today because the ai-agent
 * functional module's Spring context boot is blocked by the pre-existing Phase 11/13
 * regression documented in
 * {@code .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md}
 * (AnnotatedResourceRoleProvider / agentstoreEntityManagerFactory). Plans 13.1-06,
 * 13.1-07, 14-01, 14-02, and 16-01 all hit the same blocker and switched to pure
 * JSR-380 Validator-driven unit tests against the entity annotations.
 *
 * <p>That switch preserves the test contract: the {@code @Min}/{@code @Max} annotations
 * declared on the entity fields are read by the same Hibernate Validator engine that
 * Jmix's JPA flush hooks into — out-of-range values produce a {@link ConstraintViolation}
 * here exactly as they would produce a {@code ConstraintViolationException} at JPA flush
 * once the boot regression is fixed.
 *
 * <p>Covers RESEARCH §8 bounds + Phase 13.1 sentinel-{@code -1} invariant + codex HIGH
 * Concern #4 ({@code taskFileMaxFileSizeBytes} is a distinct column from
 * {@code uploadMaxFileSizeBytes}).
 */
@Tag("unit")
class AiUiSettingsBeanValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    private static AiUiSettings baseline() {
        // Build a Tier-1-empty AiUiSettings. The two NotNull legacy columns
        // (enabledSurfaceIds / defaultSurface) default to non-null values in the field
        // initialisers, so the baseline already validates clean.
        return new AiUiSettings();
    }

    // --- TASK_FILE_TTL_SECONDS — sentinel -1 + range [0, 604800] ---

    @Test
    void taskFileTtlBelowMinusOneRejected() {
        AiUiSettings settings = baseline();
        settings.setTaskFileTtlSeconds(-2L);
        assertThat(validator.validate(settings))
                .as("taskFileTtlSeconds=-2 must violate @Min(-1)")
                .anyMatch(v -> "taskFileTtlSeconds".equals(v.getPropertyPath().toString()));
    }

    @Test
    void taskFileTtlMinusOneSentinelAccepted() {
        AiUiSettings settings = baseline();
        settings.setTaskFileTtlSeconds(-1L);
        Set<ConstraintViolation<AiUiSettings>> violations = validator.validate(settings);
        assertThat(violations)
                .as("taskFileTtlSeconds=-1 sentinel must pass @Min(-1) — Phase 13.1 contract")
                .noneMatch(v -> "taskFileTtlSeconds".equals(v.getPropertyPath().toString()));
        // Round-trip the value through the getter.
        assertThat(settings.getTaskFileTtlSeconds()).isEqualTo(-1L);
    }

    @Test
    void taskFileTtlAboveMaxRejected() {
        AiUiSettings settings = baseline();
        settings.setTaskFileTtlSeconds(604_801L);
        assertThat(validator.validate(settings))
                .as("taskFileTtlSeconds=604801 must violate @Max(604800) — 7-day cap")
                .anyMatch(v -> "taskFileTtlSeconds".equals(v.getPropertyPath().toString()));
    }

    // --- MUTATION_BULK_MAX_ROWS — range [1, 500] (no sentinel) ---

    @Test
    void mutationBulkMaxRowsBelowMinRejected() {
        AiUiSettings settings = baseline();
        settings.setMutationBulkMaxRows(0);
        assertThat(validator.validate(settings))
                .as("mutationBulkMaxRows=0 must violate @Min(1)")
                .anyMatch(v -> "mutationBulkMaxRows".equals(v.getPropertyPath().toString()));
    }

    @Test
    void mutationBulkMaxRowsAboveMaxRejected() {
        AiUiSettings settings = baseline();
        settings.setMutationBulkMaxRows(501);
        assertThat(validator.validate(settings))
                .as("mutationBulkMaxRows=501 must violate @Max(500) — Phase 13 D-02 DoS guard")
                .anyMatch(v -> "mutationBulkMaxRows".equals(v.getPropertyPath().toString()));
    }

    // --- UPLOAD_MAX_FILE_SIZE_BYTES — 1 KiB floor + 500 MB ceiling ---

    @Test
    void uploadMaxFileSizeBytesBelowFloorRejected() {
        AiUiSettings settings = baseline();
        settings.setUploadMaxFileSizeBytes(1023L);
        assertThat(validator.validate(settings))
                .as("uploadMaxFileSizeBytes=1023 must violate @Min(1024) — KB/RAG upload cap")
                .anyMatch(v -> "uploadMaxFileSizeBytes".equals(v.getPropertyPath().toString()));
    }

    // --- TASK_FILE_MAX_FILE_SIZE_BYTES — codex HIGH Concern #4 (new column) ---

    @Test
    void taskFileMaxFileSizeBytesBelowFloorRejected() {
        AiUiSettings settings = baseline();
        settings.setTaskFileMaxFileSizeBytes(1023L);
        assertThat(validator.validate(settings))
                .as("taskFileMaxFileSizeBytes=1023 must violate @Min(1024) — chat task-file cap " +
                        "(codex HIGH Concern #4 — distinct from uploadMaxFileSizeBytes)")
                .anyMatch(v -> "taskFileMaxFileSizeBytes".equals(v.getPropertyPath().toString()));
    }

    // --- MUTATION_CONFIRMATION_REQUIRED — no bounds, null + true + false all pass ---

    @Test
    void mutationConfirmationRequiredAcceptsAnyBooleanIncludingNull() {
        AiUiSettings settingsNull = baseline();
        settingsNull.setMutationConfirmationRequired(null);
        assertThat(validator.validate(settingsNull))
                .as("mutationConfirmationRequired=null must validate (null = fall through to default)")
                .noneMatch(v -> "mutationConfirmationRequired".equals(v.getPropertyPath().toString()));
        assertThat(settingsNull.getMutationConfirmationRequired()).isNull();

        AiUiSettings settingsFalse = baseline();
        settingsFalse.setMutationConfirmationRequired(false);
        assertThat(validator.validate(settingsFalse))
                .as("mutationConfirmationRequired=false must validate")
                .noneMatch(v -> "mutationConfirmationRequired".equals(v.getPropertyPath().toString()));
        assertThat(settingsFalse.getMutationConfirmationRequired()).isFalse();

        AiUiSettings settingsTrue = baseline();
        settingsTrue.setMutationConfirmationRequired(true);
        assertThat(validator.validate(settingsTrue))
                .as("mutationConfirmationRequired=true must validate")
                .noneMatch(v -> "mutationConfirmationRequired".equals(v.getPropertyPath().toString()));
        assertThat(settingsTrue.getMutationConfirmationRequired()).isTrue();
    }
}
