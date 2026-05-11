package com.vn.agent.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.test_support.EvalFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic eval contract for Phase 14 extraction. The corpus represents
 * AI-SPEC critical failures without invoking a live model or booting Jmix.
 */
class ExtractionEvaluationContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String FIXTURE_FILE = "extraction-fixtures.yaml";
    private static final Set<String> REQUIRED_SCENARIOS = Set.of(
            "happy_customer",
            "schema_discipline_failure",
            "source_faithfulness_failure",
            "denied_attribute_payload",
            "expired_draft_click",
            "exposure_denied_entity",
            "concurrent_same_entity_draft_creation",
            "schema_change_unknown_attribute"
    );

    @Test
    void corpusCoversAllPhase14CriticalFailureModes() {
        Set<String> scenarios = cases().stream()
                .map(fixture -> string(fixture, "scenario"))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(scenarios)
                .as("extraction-fixtures.yaml must cover every AI-SPEC critical scenario")
                .containsAll(REQUIRED_SCENARIOS);
    }

    @Test
    void referenceCustomerFixtureUsesOnlyNameEmailAndPhone() {
        Map<String, Object> fixture = caseByScenario("happy_customer");

        assertThat(stringList(fixture, "expectedFields"))
                .as("D-21 reference Customer eval fields")
                .containsExactly("name", "email", "phone");
        assertThat(map(fixture, "payload").keySet())
                .containsExactly("name", "email", "phone");
        assertThat(map(fixture, "payload"))
                .doesNotContainKeys("recommendedProducts", "accountStatus", "notes");
        assertThat(booleanValue(fixture, "confirmButtonVisible")).isTrue();
    }

    @Test
    void schemaDisciplineFailureProducesFailedAuditAndNoConfirmButton() {
        Map<String, Object> fixture = caseByScenario("schema_discipline_failure");

        assertThat(string(fixture, "rawModelOutput"))
                .as("Fixture must represent markdown-fenced or otherwise non-strict JSON")
                .contains("```json");
        assertThat(stringList(fixture, "schemaViolations"))
                .contains("markdown_fence", "extra_key");
        assertThat(booleanValue(fixture, "draftCreated")).isFalse();
        assertThat(booleanValue(fixture, "confirmButtonVisible")).isFalse();
        Map<String, Object> auditRow = singleAuditRow(fixture, "prepare_form_draft");
        assertThat(string(auditRow, "outcome")).isEqualTo("FAILED");
        assertThat(map(auditRow, "resultSummary"))
                .containsEntry("failureCode", "schema_parse_failure")
                .containsEntry("extractedFieldCount", 0);
    }

    @Test
    void sourceFaithfulnessFailureIsRepresentedWithoutPromotingDraft() {
        Map<String, Object> fixture = caseByScenario("source_faithfulness_failure");

        String sourceText = string(fixture, "sourceText");
        String fabricatedValue = string(fixture, "fabricatedValue");
        assertThat(sourceText)
                .as("The fabricated fixture value must not be present in the source text")
                .doesNotContain(fabricatedValue);
        assertThat(string(fixture, "expectedOutcome")).isEqualTo("FABRICATION_FLAG");
        assertThat(booleanValue(fixture, "draftCreated")).isFalse();
        assertThat(booleanValue(fixture, "confirmButtonVisible")).isFalse();
    }

    @Test
    void deniedAndUnknownAttributesAreAuditedByNameAndCountOnly() {
        for (String scenario : List.of("denied_attribute_payload", "schema_change_unknown_attribute")) {
            Map<String, Object> fixture = caseByScenario(scenario);
            Map<String, Object> applyAudit = singleAuditRow(fixture, "extraction.draft_applied");
            Map<String, Object> summary = map(applyAudit, "resultSummary");

            assertThat(((Number) summary.get("deniedAttributeCount")).intValue()).isPositive();
            assertThat(stringList(summary, "deniedAttributes"))
                    .hasSizeLessThanOrEqualTo(16)
                    .containsAll(stringList(fixture, "deniedAttributes"));
            assertThat(summary)
                    .as("Audit summary must use counts and attribute names only")
                    .doesNotContainValue("vip")
                    .doesNotContainValue("555-0102");
        }
    }

    @Test
    void expiredAndDeniedFlowsDoNotOpenDrafts() {
        Map<String, Object> expired = caseByScenario("expired_draft_click");
        assertThat(booleanValue(expired, "draftExistsAtClick")).isFalse();
        assertThat(booleanValue(expired, "loadsDifferentDraft")).isFalse();
        assertThat(string(expired, "expectedButtonState")).isEqualTo("disabled");
        assertThat(string(expired, "expectedMessageKey")).isEqualTo("chatView.intent.draftExpired");

        Map<String, Object> denied = caseByScenario("exposure_denied_entity");
        assertThat(booleanValue(denied, "draftCreated")).isFalse();
        assertThat(booleanValue(denied, "confirmButtonVisible")).isFalse();
        List<Map<String, Object>> auditRows = auditRows(denied);
        assertThat(auditRows).hasSize(1);
        assertThat(auditRows.get(0))
                .containsEntry("eventName", "prepare_form_draft")
                .containsEntry("outcome", "DENIED")
                .containsEntry("denialReason", "exposure_rule");
    }

    @Test
    void concurrentSameEntityDraftsAreAddressedByDraftId() {
        Map<String, Object> fixture = caseByScenario("concurrent_same_entity_draft_creation");
        List<Map<String, Object>> drafts = drafts(fixture);

        assertThat(string(fixture, "addressBy")).isEqualTo("draftId");
        assertThat(drafts).hasSize(2);
        assertThat(string(drafts.get(0), "entityName"))
                .isEqualTo(string(fixture, "entityName"));
        assertThat(drafts)
                .extracting(draft -> UUID.fromString(string(draft, "draftId")))
                .doesNotHaveDuplicates();
        assertThat(drafts)
                .allSatisfy(draft -> assertThat(string(draft, "confirmTargetDraftId"))
                        .isEqualTo(string(draft, "draftId")));
    }

    @Test
    void auditSummariesDoNotContainRawFixturePiiValues() throws Exception {
        for (Map<String, Object> fixture : cases()) {
            List<String> piiValues = piiValues(fixture);
            if (piiValues.isEmpty()) {
                continue;
            }
            for (Map<String, Object> auditRow : auditRows(fixture)) {
                String summaryJson = OBJECT_MAPPER.writeValueAsString(map(auditRow, "resultSummary"));
                for (String piiValue : piiValues) {
                    assertThat(summaryJson)
                            .as("Audit resultSummary for fixture %s must not contain raw fixture PII value %s",
                                    fixture.get("id"), piiValue)
                            .doesNotContain(piiValue);
                }
            }
        }
    }

    private static List<Map<String, Object>> cases() {
        return EvalFixtures.loadCases(FIXTURE_FILE);
    }

    private static Map<String, Object> caseByScenario(String scenario) {
        return cases().stream()
                .filter(fixture -> scenario.equals(fixture.get("scenario")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing fixture scenario " + scenario));
    }

    private static Map<String, Object> singleAuditRow(Map<String, Object> fixture, String eventName) {
        return auditRows(fixture).stream()
                .filter(row -> eventName.equals(row.get("eventName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing audit row " + eventName
                        + " in fixture " + fixture.get("id")));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> auditRows(Map<String, Object> fixture) {
        Object raw = fixture.get("auditRows");
        return raw instanceof List<?> rows ? (List<Map<String, Object>>) rows : List.of();
    }

    private static List<String> piiValues(Map<String, Object> fixture) {
        Object payload = fixture.get("payload");
        if (!(payload instanceof Map<?, ?> payloadMap)) {
            return List.of();
        }
        return payloadMap.entrySet().stream()
                .filter(entry -> Set.of("name", "email", "phone").contains(String.valueOf(entry.getKey())))
                .map(Map.Entry::getValue)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> drafts(Map<String, Object> source) {
        return (List<Map<String, Object>>) source.get("drafts");
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> source, String key) {
        return (List<String>) source.get(key);
    }

    private static String string(Map<String, Object> source, String key) {
        return String.valueOf(source.get(key));
    }

    private static boolean booleanValue(Map<String, Object> source, String key) {
        return Boolean.TRUE.equals(source.get(key));
    }
}
