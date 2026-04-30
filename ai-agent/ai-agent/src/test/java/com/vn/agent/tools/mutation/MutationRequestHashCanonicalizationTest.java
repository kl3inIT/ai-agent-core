package com.vn.agent.tools.mutation;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 11-10 Task 3 — pure-unit invariants for {@link MutationRequestHasher}.
 *
 * <p>Hashes the raw LLM call shape (Plan 11-07 / D-02). Per the must-haves contract:
 * <ul>
 *   <li>Key order in the {@code attributes} map (top level + nested) must NOT affect the hash.</li>
 *   <li>Numeric vs String values are NOT collapsed: {@code 7} and {@code "7"} must produce
 *       different hashes (the hash captures the LLM-supplied JSON shape; coercion is downstream).</li>
 *   <li>Nulls and booleans are represented deterministically.</li>
 *   <li>Different toolName / entityName / id / relationship / relatedId all change the hash.</li>
 * </ul>
 */
class MutationRequestHashCanonicalizationTest {

    private final MutationRequestHasher hasher = new MutationRequestHasher();

    @Test
    void keyOrderIndependence_topLevelAttributes() {
        Map<String, Object> aThenB = new LinkedHashMap<>();
        aThenB.put("name", "Alice");
        aThenB.put("priority", 7);

        Map<String, Object> bThenA = new LinkedHashMap<>();
        bThenA.put("priority", 7);
        bThenA.put("name", "Alice");

        String hashA = hasher.hash("create_record", "test_E", null, null, null, aThenB);
        String hashB = hasher.hash("create_record", "test_E", null, null, null, bThenA);
        assertThat(hashA)
                .as("top-level attribute key order must NOT affect the hash")
                .isEqualTo(hashB);
    }

    @Test
    void keyOrderIndependence_nestedMap() {
        Map<String, Object> nestedAB = new LinkedHashMap<>();
        nestedAB.put("alpha", 1);
        nestedAB.put("beta", 2);
        Map<String, Object> nestedBA = new LinkedHashMap<>();
        nestedBA.put("beta", 2);
        nestedBA.put("alpha", 1);

        Map<String, Object> attrsA = new LinkedHashMap<>();
        attrsA.put("config", nestedAB);
        Map<String, Object> attrsB = new LinkedHashMap<>();
        attrsB.put("config", nestedBA);

        String hashA = hasher.hash("update_record", "test_E", "id-1", null, null, attrsA);
        String hashB = hasher.hash("update_record", "test_E", "id-1", null, null, attrsB);
        assertThat(hashA)
                .as("nested map key order must NOT affect the hash")
                .isEqualTo(hashB);
    }

    @Test
    void numericVsStringValues_areNotCollapsed() {
        Map<String, Object> numeric = new LinkedHashMap<>();
        numeric.put("priority", 7);
        Map<String, Object> stringy = new LinkedHashMap<>();
        stringy.put("priority", "7");

        String hashNumeric = hasher.hash("create_record", "test_E", null, null, null, numeric);
        String hashStringy = hasher.hash("create_record", "test_E", null, null, null, stringy);
        assertThat(hashNumeric)
                .as("numeric 7 and string \"7\" must hash differently — Phase 11 hashes the raw "
                        + "LLM call shape before coercion (must-haves contract)")
                .isNotEqualTo(hashStringy);
    }

    @Test
    void nullAndBooleanValues_areRepresentedDeterministically() {
        Map<String, Object> withNullA = new LinkedHashMap<>();
        withNullA.put("name", null);
        withNullA.put("active", Boolean.TRUE);
        Map<String, Object> withNullB = new LinkedHashMap<>();
        withNullB.put("name", null);
        withNullB.put("active", Boolean.TRUE);

        String hashA = hasher.hash("update_record", "test_E", "id-1", null, null, withNullA);
        String hashB = hasher.hash("update_record", "test_E", "id-1", null, null, withNullB);
        assertThat(hashA)
                .as("identical maps with nulls + booleans must hash identically")
                .isEqualTo(hashB);

        // Sanity: differing boolean changes the hash.
        Map<String, Object> withFalse = new LinkedHashMap<>();
        withFalse.put("name", null);
        withFalse.put("active", Boolean.FALSE);
        String hashFalse = hasher.hash("update_record", "test_E", "id-1", null, null, withFalse);
        assertThat(hashFalse).isNotEqualTo(hashA);

        // null vs absent key: different hashes (the explicit null clear is a different shape).
        Map<String, Object> withoutName = new LinkedHashMap<>();
        withoutName.put("active", Boolean.TRUE);
        String hashWithoutName = hasher.hash("update_record", "test_E", "id-1", null, null, withoutName);
        assertThat(hashWithoutName)
                .as("explicit null and absent key are different call shapes; must hash differently")
                .isNotEqualTo(hashA);
    }

    @Test
    void differentEnvelopeMembers_changeTheHash() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "Alice");

        String base = hasher.hash("create_record", "test_E", null, null, null, attributes);
        // Different toolName.
        String differentTool = hasher.hash("update_record", "test_E", null, null, null, attributes);
        assertThat(differentTool).isNotEqualTo(base);
        // Different entityName.
        String differentEntity = hasher.hash("create_record", "test_F", null, null, null, attributes);
        assertThat(differentEntity).isNotEqualTo(base);
        // Different id.
        String differentId = hasher.hash("create_record", "test_E", "id-1", null, null, attributes);
        assertThat(differentId).isNotEqualTo(base);
        // Different relationship.
        String differentRelationship = hasher.hash(
                "add_related_record", "test_E", "id-1", "items", "id-2", Map.of());
        String differentRelationshipName = hasher.hash(
                "add_related_record", "test_E", "id-1", "tags", "id-2", Map.of());
        assertThat(differentRelationshipName).isNotEqualTo(differentRelationship);
        // Different relatedId.
        String differentRelatedId = hasher.hash(
                "add_related_record", "test_E", "id-1", "items", "id-3", Map.of());
        assertThat(differentRelatedId).isNotEqualTo(differentRelationship);
    }

    @Test
    void hashFormat_isHexString_64Chars() {
        String hash = hasher.hash("create_record", "test_E", null, null, null, Map.of("name", "x"));
        assertThat(hash).matches("[0-9a-fA-F]{64}");
    }
}
