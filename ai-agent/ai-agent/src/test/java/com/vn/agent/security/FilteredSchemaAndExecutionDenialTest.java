package com.vn.agent.security;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.metadata.CurrentUserSchemaAccess;
import com.vn.agent.test_support.NoCustomerReadRoleConfiguration;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.BuiltInDataTools;
import io.jmix.core.Metadata;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 08-01 Task 2 — TEST-04 negative-case suite (CONTEXT D-03 + D-05).
 *
 * <p>Two assertions, both targeting the {@code carol} persona declared by
 * {@link NoCustomerReadRoleConfiguration}:
 * <ol>
 *   <li>{@code carol_filteredSchema_excludesDenied_andDeniedAttributes} — direct
 *       {@link CurrentUserSchemaAccess#getReadableSchema()} call (CONTEXT D-05 intent
 *       preserved; the planning-doc symbol referenced an earlier API name that was
 *       superseded — the real API on main is {@code getReadableSchema()}). Asserts both
 *       entity-level absence (R-01f base) AND attribute-level absence (R-01f tightening:
 *       even if a denied entity is reachable through a relation from a permitted entity,
 *       its protected attributes must not appear in the readable-attribute set).</li>
 *   <li>{@code carol_findRecordsDeniedEntity_returnsAccessDeniedJson} — exercises
 *       {@link BuiltInDataTools#findRecords(String, com.vn.agent.tools.FilterNode, Integer)}
 *       on a denied entity name. R-01a: pinned to the actual error envelope returned by
 *       the SUT — JSON contains {@code "error":"access_denied"} (the literal {@code error}
 *       code from {@code BuiltInDataTools.resolveReadableEntityOrThrow} line 280 + the
 *       JSON field name from {@link com.vn.agent.tools.ToolErrorDto#error()}).</li>
 * </ol>
 *
 * <p><b>Plan 08-01 deviation (Rule 3):</b> the planner originally named the demo
 * {@code Customer} entity (from {@code com.vn.jmixapp}) as the denial target. That entity
 * is not on this module's test classpath, so it would never appear in
 * {@code metadata.getSession().getClasses()} regardless of role — the assertion would
 * pass trivially for the wrong reason. Pivoted to {@code ai_AiAuditEvent}, which IS on
 * the classpath and which {@code carol}'s role does not grant read access to. Two
 * non-PK attributes ({@code userUsername}, {@code argumentsJson}) drive the R-01f
 * attribute-granularity assertion. See {@link NoCustomerReadRoleConfiguration} Javadoc
 * for the full deviation rationale.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        NoCustomerReadRoleConfiguration.class})
class FilteredSchemaAndExecutionDenialTest {

    /**
     * R-01d: entity name pre-resolved against the on-classpath metamodel. The original
     * plan literal "Customer" (or {@code sample_Customer}/{@code jmixapp_Customer}) is not
     * on this module's classpath. {@code ai_AiAuditEvent} is in the
     * {@code com.vn.agent.entity} package and is denied by carol's role.
     */
    private static final String CUSTOMER_ENTITY_NAME = "ai_AiAuditEvent";

    /**
     * R-01f: two non-PK attributes that exist on {@code ai_AiAuditEvent} (verified against
     * AiAuditEvent.java fields). These are the protected attributes that must not appear
     * in carol's readable-attribute set even if a Customer-shaped MetaClass were somehow
     * reachable through a relation hop.
     */
    private static final String[] CUSTOMER_PROTECTED_ATTRIBUTES = {"userUsername", "argumentsJson"};

    @Autowired CurrentUserSchemaAccess currentUserSchemaAccess;
    @Autowired BuiltInDataTools builtInDataTools;
    @Autowired SystemAuthenticator systemAuthenticator;
    @Autowired Metadata metadata;

    @Test
    void carol_filteredSchema_excludesDenied_andDeniedAttributes() {
        systemAuthenticator.withUser("carol", () -> {
            Map<MetaClass, Set<String>> schema = currentUserSchemaAccess.getReadableSchema();

            // (a) Entity-level: denied entity key absent from filtered schema (D-05).
            assertThat(schema.keySet().stream().map(MetaClass::getName))
                    .as("Denied entity %s must be absent from filtered schema for restricted user (D-05)",
                            CUSTOMER_ENTITY_NAME)
                    .doesNotContain(CUSTOMER_ENTITY_NAME);

            // (b) Attribute-level (R-01f): if any MetaClass with the denied entity's name
            // is reachable (e.g. via a relation target from a permitted entity), its
            // attribute set must NOT expose the protected fields. Match by entity name to
            // avoid coupling to a specific MetaClass instance.
            Optional<Map.Entry<MetaClass, Set<String>>> deniedEntry = schema.entrySet().stream()
                    .filter(e -> CUSTOMER_ENTITY_NAME.equals(e.getKey().getName()))
                    .findFirst();
            deniedEntry.ifPresent(entry -> assertThat(entry.getValue())
                    .as("If %s reachable via relation, protected attributes %s must not appear",
                            CUSTOMER_ENTITY_NAME, java.util.Arrays.toString(CUSTOMER_PROTECTED_ATTRIBUTES))
                    .doesNotContain(CUSTOMER_PROTECTED_ATTRIBUTES));
            return null;
        });
    }

    @Test
    void carol_findRecordsDeniedEntity_returnsAccessDeniedJson() {
        systemAuthenticator.withUser("carol", () -> {
            // R-01a: BuiltInDataTools.findRecords does NOT throw on access denial — it
            // returns toolResultFormatter.error(ToolUserError) JSON. The error code emitted
            // by resolveReadableEntityOrThrow is the literal "access_denied"
            // (BuiltInDataTools.java:280). The JSON field name is "error" (ToolErrorDto record
            // component name). Pin the assertion to BOTH literals.
            String result = builtInDataTools.findRecords(CUSTOMER_ENTITY_NAME, null, 10);
            assertThat(result)
                    .as("find_records denial must return JSON error envelope, not throw")
                    .contains("\"error\"")
                    .contains("access_denied");
            return null;
        });
    }

    /**
     * WR-008: non-vacuous attribute-denial assertion. The carol-driven test above checks
     * attribute-denial only on the entity-DENIED path — where the MetaClass is always
     * absent, so the {@code findFirst()} optional is empty and the {@code .doesNotContain}
     * assertion never executes. To prove the schema collector actually filters per-
     * attribute, we use persona {@code dave} who has entity-level READ on
     * {@code ai_AiAuditEvent} but with VIEW granted only on a curated attribute subset
     * that EXCLUDES {@code userUsername} + {@code argumentsJson}. The entity must be
     * present in the readable schema; the protected attributes must be absent from its
     * readable-attribute set.
     */
    @Test
    void dave_filteredSchema_includesEntity_butExcludesProtectedAttributes() {
        systemAuthenticator.withUser("dave", () -> {
            Map<MetaClass, Set<String>> schema = currentUserSchemaAccess.getReadableSchema();

            Optional<Map.Entry<MetaClass, Set<String>>> auditEntry = schema.entrySet().stream()
                    .filter(e -> CUSTOMER_ENTITY_NAME.equals(e.getKey().getName()))
                    .findFirst();

            assertThat(auditEntry)
                    .as("dave has entity-level READ on %s — it MUST appear in the readable schema",
                            CUSTOMER_ENTITY_NAME)
                    .isPresent();

            assertThat(auditEntry.get().getValue())
                    .as("dave's role omits VIEW on %s for %s — the readable-attribute set must "
                            + "EXCLUDE the protected attributes",
                            java.util.Arrays.toString(CUSTOMER_PROTECTED_ATTRIBUTES),
                            CUSTOMER_ENTITY_NAME)
                    .doesNotContain(CUSTOMER_PROTECTED_ATTRIBUTES);
            return null;
        });
    }
}
