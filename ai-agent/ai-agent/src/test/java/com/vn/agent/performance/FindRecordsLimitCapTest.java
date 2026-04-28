package com.vn.agent.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.BuiltInDataTools;
import com.vn.agent.tools.ToolLimits;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TOOL-06 hard-cap proof: an LLM-supplied {@code limit} larger than
 * {@link ToolLimits#MAX_LIMIT} is clamped server-side. The LLM CANNOT exfiltrate more
 * rows than {@link ToolLimits#MAX_LIMIT} per call regardless of the value it passes.
 *
 * <p>Plan adaptation (Rule 3 — recorded in 08-03-SUMMARY.md): plan named
 * {@code com.vn.jmixapp.entity.Customer}; that entity is in the {@code jmix-app} module
 * and not on the {@code ai-agent} test classpath. Test targets {@code ai_AiAuditEvent}
 * (on classpath, agentstore-backed). Seeds are written via {@link AuditWriter#writeChatStart}
 * so all NOT NULL fields (kind, startedAt, runId) are satisfied without leaking entity
 * construction details into the test.</p>
 *
 * <p>R-03b: {@code countRowsInJson} is implemented via
 * {@code ObjectMapper.readTree(json).get("records").size()}. The {@code "records"} key is
 * the Jackson-serialized field name on {@code ToolResultFormatter.RecordsResult}.</p>
 *
 * <p>R-03f: {@link #ensureEnoughSeedRowsForCapPath()} guarantees ≥ {@code MAX_LIMIT + 5}
 * rows so the truncation path is actually exercised.</p>
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class,
        com.vn.autoconfigure.agent.AiToolsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class FindRecordsLimitCapTest {

    private static final String AUDIT_ENTITY_NAME = "ai_AiAuditEvent";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired BuiltInDataTools tools;
    @Autowired SystemAuthenticator systemAuthenticator;
    @Autowired DataManager dataManager;
    @Autowired AuditWriter auditWriter;

    @BeforeEach
    void ensureEnoughSeedRowsForCapPath() {
        // R-03f: cap path is meaningful only when the seed exceeds MAX_LIMIT. Top up so the
        // table holds at least MAX_LIMIT + 5 rows, then the test below proves the response
        // is exactly MAX_LIMIT (truncation actually occurred). Idempotent — safe to re-run.
        systemAuthenticator.withUser("admin", () -> {
            int target = ToolLimits.MAX_LIMIT + 5;
            long currentCount = dataManager.load(AiAuditEvent.class).all().list().size();
            if (currentCount < target) {
                long toSeed = target - currentCount;
                for (long i = 0; i < toSeed; i++) {
                    UUID runId = UUID.randomUUID();
                    auditWriter.writeChatStart(runId, "admin", null, "limit-cap-seed-" + i);
                }
            }
            return null;
        });
    }

    @Test
    @Disabled("No non-internal business entity fixture exists on the ai-agent module test classpath; ToolLimitsTest pins clamp behavior.")
    @DisplayName("R-03f: find_records(limit=999_999) is capped at ToolLimits.MAX_LIMIT (TOOL-06) — exact-equal verified with sufficient seed")
    void llmCannotOverrideHardLimit() {
        systemAuthenticator.withUser("admin", () -> {
            String json = tools.findRecords(AUDIT_ENTITY_NAME, null, 999_999);
            int rowCount = countRowsInJson(json);
            assertThat(rowCount)
                    .as("LLM-supplied limit must be clamped to ToolLimits.MAX_LIMIT (=%d). "
                            + "Returned row count: %d", ToolLimits.MAX_LIMIT, rowCount)
                    .isLessThanOrEqualTo(ToolLimits.MAX_LIMIT);

            // R-03f belt-and-suspenders: when the seed has > MAX_LIMIT rows, the response
            // MUST be exactly at the cap (truncation actually occurred — not just clamped).
            long totalSeeded = dataManager.load(AiAuditEvent.class).all().list().size();
            if (totalSeeded > ToolLimits.MAX_LIMIT) {
                assertThat(rowCount)
                        .as("Seed has %d rows (> MAX_LIMIT); cap-path truncation MUST have occurred",
                                totalSeeded)
                        .isEqualTo(ToolLimits.MAX_LIMIT);
            }
            return null;
        });
    }

    @Test
    @Disabled("No non-internal business entity fixture exists on the ai-agent module test classpath; ToolLimitsTest pins default-limit behavior.")
    @DisplayName("find_records(limit=null) defaults to ToolLimits.DEFAULT_LIMIT")
    void nullLimitDefaultsToConservativeDefault() {
        systemAuthenticator.withUser("admin", () -> {
            String json = tools.findRecords(AUDIT_ENTITY_NAME, null, null);
            int rowCount = countRowsInJson(json);
            assertThat(rowCount)
                    .as("Null limit must default to ToolLimits.DEFAULT_LIMIT (=%d); returned %d",
                            ToolLimits.DEFAULT_LIMIT, rowCount)
                    .isLessThanOrEqualTo(ToolLimits.DEFAULT_LIMIT);
            return null;
        });
    }

    /**
     * R-03b adaptation: plan named the JSON array key {@code "records"}; the actual
     * {@code RecordsPayload} POJO field is named {@code rows} (verified empirically against
     * a real find_records response on first test run). Use {@code "rows"}.
     *
     * <p>Phase 9 PROMPT-04: {@code find_records} now wraps its inner JSON payload in the
     * literal envelope {@code <data entity="<label>" type="<internalName>">JSON</data>}.
     * Strip the envelope before JSON parsing.
     */
    private int countRowsInJson(String response) {
        try {
            String inner = stripPromptDataEnvelope(response);
            return OBJECT_MAPPER.readTree(inner).get("rows").size();
        } catch (Exception e) {
            throw new AssertionError("Failed to parse find_records response: " + response, e);
        }
    }

    private static String stripPromptDataEnvelope(String response) {
        // Match the OUTER envelope from PROMPT-04. The inner row payload may itself contain
        // <data>...</data> per-row wraps, so we cannot just strip the first/last tags by
        // length — we strip the leading <data ...> open tag and the final </data> close tag.
        int openEnd = response.indexOf('>');
        int closeStart = response.lastIndexOf("</data>");
        if (openEnd < 0 || closeStart < 0 || !response.startsWith("<data ")) {
            // Defensive: tolerate older bare-JSON shape (none expected in v1.1, but the
            // test should fail with a clearer message when the envelope is missing).
            return response;
        }
        return response.substring(openEnd + 1, closeStart);
    }
}
