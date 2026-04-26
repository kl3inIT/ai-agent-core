package com.vn.agent.tools.fetchplan;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.metadata.CurrentUserSchemaAccess;
import com.vn.agent.orchestration.RunContext;
import io.jmix.core.FetchMode;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlanBuilder;
import io.jmix.core.FetchPlanProperty;
import io.jmix.core.FetchPlans;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.security.CurrentAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Narrows a host-supplied or default {@link FetchPlan} to the attributes the current user can
 * read (TOOL-11, SPI-09 D-11). Walks the input plan recursively; any property whose
 * {@link CurrentUserSchemaAccess#canReadAttribute(MetaClass, String)} check fails is dropped,
 * and the returned plan is rebuilt from the surviving properties via {@link FetchPlans}.
 *
 * <p><b>{@value #PROJECTION_NOT_SECURITY_COMMENT}</b> The intersection here is a defense in
 * depth — Jmix {@code AccessManager} already gates {@code DataManager.load(...)} at the
 * row/attribute boundary. The narrowing additionally prevents over-fetching that would let
 * denied attributes briefly enter JVM memory before being filtered. Phase 9 ships this layer
 * because Phase 11+ mutation tools and Phase 14 form-prefill expect a tight projection
 * contract; loosening it later would be a regression.
 *
 * <p><b>Audit (D-12):</b> when a property is dropped, an audit row is emitted via
 * {@link AuditWriter#writeToolCall} with {@code denialReason="PLAN_NARROWED: ..."}. The
 * outcome enum stays {@link AiToolCallOutcome#FLAGGED} per the Phase 9 ROADMAP commitment of
 * no new {@code AiToolCallOutcome} values; reviewers grep on the {@code denialReason} prefix
 * {@code PLAN_NARROWED:} to find narrowing events.
 *
 * <p>The intersector is stateless ({@code @Component}) and resolves the current user
 * authentication per call.
 */
@Component
public class FetchPlanIntersector {

    private static final Logger log = LoggerFactory.getLogger(FetchPlanIntersector.class);

    /** Audit denialReason prefix used for narrowing events; greppable. */
    public static final String PLAN_NARROWED_PREFIX = "PLAN_NARROWED:";

    /**
     * Verbatim TOOL-11 contract phrase. Referenced from the class-level Javadoc via
     * {@value} and asserted by FetchPlanIntersectorTest. Externalising the phrase as a
     * constant makes the test robust across CI working-directory variations (no
     * Files.readString of source files needed).
     */
    public static final String PROJECTION_NOT_SECURITY_COMMENT = "fetch plan is projection, not security.";

    private final FetchPlans fetchPlans;
    private final CurrentUserSchemaAccess schemaAccess;
    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;

    public FetchPlanIntersector(FetchPlans fetchPlans,
                                CurrentUserSchemaAccess schemaAccess,
                                AuditWriter auditWriter,
                                CurrentAuthentication currentAuthentication) {
        this.fetchPlans = fetchPlans;
        this.schemaAccess = schemaAccess;
        this.auditWriter = auditWriter;
        this.currentAuthentication = currentAuthentication;
    }

    /**
     * Walk {@code original} and drop properties the current user cannot read. Returns a
     * freshly-built {@link FetchPlan}; never mutates the input.
     *
     * @param original input plan from a host {@link com.vn.agent.spi.ToolFetchPlanCustomizer}
     *                 or the add-on default {@link FetchPlan#BASE}
     * @param rootMetaClass the root entity {@link MetaClass}
     * @param toolName the {@code @Tool} method name (for audit)
     * @return a new plan containing only readable properties (may be empty)
     */
    public FetchPlan intersectWithAcl(FetchPlan original, MetaClass rootMetaClass, String toolName) {
        List<String> droppedAttributePaths = new ArrayList<>();
        FetchPlan narrowedPlan = walk(original, rootMetaClass, droppedAttributePaths);
        if (!droppedAttributePaths.isEmpty()) {
            emitNarrowingAudit(toolName, rootMetaClass, droppedAttributePaths);
        }
        return narrowedPlan;
    }

    private FetchPlan walk(FetchPlan plan, MetaClass metaClass, List<String> droppedAttributePaths) {
        FetchPlanBuilder builder = fetchPlans.builder(metaClass.getJavaClass());
        builder.partial(plan.loadPartialEntities());
        for (FetchPlanProperty property : plan.getProperties()) {
            String propertyName = property.getName();
            if (!schemaAccess.canReadAttribute(metaClass, propertyName)) {
                droppedAttributePaths.add(metaClass.getName() + "." + propertyName);
                continue;
            }
            FetchPlan nestedPlan = property.getFetchPlan();
            FetchMode fetchMode = property.getFetchMode();
            if (nestedPlan != null) {
                MetaProperty metaProperty = metaClass.findProperty(propertyName);
                if (metaProperty != null && metaProperty.getRange().isClass()) {
                    MetaClass nestedMetaClass = metaProperty.getRange().asClass();
                    if (!schemaAccess.canReadEntity(nestedMetaClass)) {
                        droppedAttributePaths.add(metaClass.getName() + "." + propertyName + " (target denied)");
                        continue;
                    }
                    FetchPlan narrowedNestedPlan = walk(nestedPlan, nestedMetaClass, droppedAttributePaths);
                    builder.mergeProperty(propertyName, narrowedNestedPlan, fetchMode);
                } else {
                    // Defensive: nested plan on non-class property — pass through name only.
                    builder.mergeProperty(propertyName, null, fetchMode);
                }
            } else {
                builder.mergeProperty(propertyName, null, fetchMode);
            }
        }
        return builder.build();
    }

    private void emitNarrowingAudit(String toolName, MetaClass rootMetaClass, List<String> droppedAttributePaths) {
        try {
            String username = currentUsernameOrNull();
            String denialReason = PLAN_NARROWED_PREFIX + " entity=" + rootMetaClass.getName()
                    + " dropped=" + droppedAttributePaths;
            auditWriter.writeToolCall(
                    RunContext.getRootAuditId(),
                    RunContext.get(),
                    username,
                    RunContext.getConversationId(),
                    toolName,
                    /* argumentsJson */ null,
                    /* resultSummary */ "fetch plan narrowed",
                    /* latencyMs */ 0L,
                    AiToolCallOutcome.FLAGGED,
                    denialReason,
                    /* errorClass */ null);
        } catch (RuntimeException auditFailure) {
            // Defensive: audit failures must not break the data load.
            log.warn("Failed to emit PLAN_NARROWED audit row for tool={}, entity={}: {}",
                    toolName, rootMetaClass.getName(), auditFailure.getMessage());
        }
    }

    private String currentUsernameOrNull() {
        try {
            UserDetails user = currentAuthentication.getUser();
            return user != null ? user.getUsername() : null;
        } catch (RuntimeException anonymous) {
            return null;
        }
    }
}
