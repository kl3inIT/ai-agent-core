package com.vn.agent.extraction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiExtractionDraft;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.metadata.LlmExposurePolicy;
import com.vn.agent.filter.FilterLiteralValueConverter;
import com.vn.agent.orchestration.RunContext;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.security.CurrentAuthentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies persisted extraction draft payloads to the entity instance already tracked by a
 * {@code StandardDetailView} data context.
 */
@Component
public class DraftLoader {

    static final String AUDIT_EVENT_NAME = "extraction.draft_applied";
    private static final int DENIED_ATTRIBUTE_NAME_LIMIT = 16;
    private static final TypeReference<LinkedHashMap<String, Object>> PAYLOAD_TYPE =
            new TypeReference<>() {
            };

    private final DataManager dataManager;
    private final ExtractionDraftAccess extractionDraftAccess;
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final AccessManager accessManager;
    private final LlmExposurePolicy llmExposurePolicy;
    private final FilterLiteralValueConverter filterLiteralValueConverter;
    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;
    private final ObjectMapper objectMapper;

    public DraftLoader(DataManager dataManager,
                       ExtractionDraftAccess extractionDraftAccess,
                       Metadata metadata,
                       MetadataTools metadataTools,
                       AccessManager accessManager,
                       LlmExposurePolicy llmExposurePolicy,
                       FilterLiteralValueConverter filterLiteralValueConverter,
                       AuditWriter auditWriter,
                       CurrentAuthentication currentAuthentication,
                       ObjectMapper objectMapper) {
        this.dataManager = dataManager;
        this.extractionDraftAccess = extractionDraftAccess;
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.accessManager = accessManager;
        this.llmExposurePolicy = llmExposurePolicy;
        this.filterLiteralValueConverter = filterLiteralValueConverter;
        this.auditWriter = auditWriter;
        this.currentAuthentication = currentAuthentication;
        this.objectMapper = objectMapper;
    }

    public DraftApplyResult apply(UUID draftId, Object editingEntity) {
        long startNanos = System.nanoTime();
        if (draftId == null) {
            throw new DraftNotFoundException(null);
        }
        if (editingEntity == null) {
            throw new IllegalArgumentException("Editing entity is required");
        }

        AiExtractionDraft draft = loadDraft(draftId);
        MetaClass metaClass = metadata.getClass(editingEntity);
        if (!draft.getTargetEntityName().equals(metaClass.getName())) {
            throw new IllegalArgumentException("Draft target entity does not match edited entity");
        }

        LinkedHashMap<String, Object> payload = readPayload(draft, metaClass);
        ApplyCounters counters = new ApplyCounters();

        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String attributeName = entry.getKey();
            MetaProperty property = metaClass.findProperty(attributeName);
            if (!isSettableProperty(metaClass, property) || !canModify(metaClass, attributeName)) {
                counters.deny(attributeName);
                continue;
            }

            try {
                Object coercedValue = coerceValue(property, entry.getValue());
                setValueIfPermitted(editingEntity, property, coercedValue);
                counters.appliedFieldCount++;
            } catch (RuntimeException skipped) {
                counters.deny(attributeName);
            }
        }

        DraftApplyResult result = counters.toResult();
        writeAudit(draft, result, elapsedMillis(startNanos));
        return result;
    }

    private AiExtractionDraft loadDraft(UUID draftId) {
        return extractionDraftAccess.loadOpenDraft(draftId)
                .orElseThrow(() -> new DraftNotFoundException(draftId));
    }

    private LinkedHashMap<String, Object> readPayload(AiExtractionDraft draft, MetaClass metaClass) {
        try {
            LinkedHashMap<String, Object> payload = objectMapper.readValue(draft.getPayloadJson(), PAYLOAD_TYPE);
            return payload == null ? new LinkedHashMap<>() : payload;
        } catch (Exception e) {
            throw ExtractionSchemaException.schemaParseFailure(
                    draft.getIntentId(), metaClass.getName(), e);
        }
    }

    private boolean isSettableProperty(MetaClass metaClass, MetaProperty property) {
        if (property == null || property.isReadOnly() || !metadataTools.isJpa(property)) {
            return false;
        }
        MetaProperty primaryKeyProperty = metadataTools.getPrimaryKeyProperty(metaClass);
        if (primaryKeyProperty != null && primaryKeyProperty.getName().equals(property.getName())) {
            return false;
        }
        return !property.getRange().getCardinality().isMany();
    }

    private boolean canModify(MetaClass metaClass, String attributeName) {
        EntityAttributeContext attributeContext = new EntityAttributeContext(metaClass, attributeName);
        accessManager.applyRegisteredConstraints(attributeContext);
        return attributeContext.canModify();
    }

    private Object coerceValue(MetaProperty property, Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (property.getRange().isClass()) {
            return coerceReferenceValue(property, rawValue);
        }
        return filterLiteralValueConverter.convertValue(rawValue, property);
    }

    private Object coerceReferenceValue(MetaProperty property, Object rawValue) {
        MetaClass targetMetaClass = property.getRange().asClass();
        if (!llmExposurePolicy.canReadEntity(targetMetaClass) || !canReadEntity(targetMetaClass)) {
            throw new DraftValueSkippedException();
        }
        UUID targetId = parseUuid(rawValue);
        return dataManager.load(targetMetaClass.getJavaClass())
                .id(targetId)
                .optional()
                .orElseThrow(DraftValueSkippedException::new);
    }

    private boolean canReadEntity(MetaClass metaClass) {
        CrudEntityContext entityContext = new CrudEntityContext(metaClass);
        accessManager.applyRegisteredConstraints(entityContext);
        return entityContext.isReadPermitted();
    }

    private static UUID parseUuid(Object rawValue) {
        if (rawValue instanceof UUID uuid) {
            return uuid;
        }
        if (rawValue instanceof String stringValue && StringUtils.hasText(stringValue)) {
            try {
                return UUID.fromString(stringValue);
            } catch (IllegalArgumentException ignored) {
                throw new DraftValueSkippedException();
            }
        }
        throw new DraftValueSkippedException();
    }

    private void setValueIfPermitted(Object entity, MetaProperty property, Object value) {
        EntityValues.setValue(entity, property.getName(), value);
    }

    private void writeAudit(AiExtractionDraft draft, DraftApplyResult result, long latencyMs) {
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("draftId", draft.getId() == null ? null : draft.getId().toString());

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("draftId", draft.getId() == null ? null : draft.getId().toString());
        summary.put("entityName", draft.getTargetEntityName());
        summary.put("appliedFieldCount", result.appliedFieldCount());
        summary.put("deniedAttributeCount", result.deniedAttributeCount());
        summary.put("deniedAttributes", result.deniedAttributeNames());
        summary.put("truncated", result.deniedAttributeNamesTruncated());

        auditWriter.writeToolCall(RunContext.getRootAuditId(), runIdOrNew(), currentUsername(),
                draft.getSourceConversationId(), AUDIT_EVENT_NAME,
                ExtractionJsonSupport.writeJson(objectMapper, arguments, draft.getIntentId(),
                        draft.getTargetEntityName(), 0),
                ExtractionJsonSupport.writeJson(objectMapper, summary, draft.getIntentId(),
                        draft.getTargetEntityName(), result.appliedFieldCount()),
                latencyMs,
                AiToolCallOutcome.SUCCESS,
                null,
                null);
    }

    private String currentUsername() {
        try {
            UserDetails user = currentAuthentication.getUser();
            if (StringUtils.hasText(user.getUsername())) {
                return user.getUsername();
            }
        } catch (RuntimeException ignored) {
            return "anonymous";
        }
        return "anonymous";
    }

    private UUID runIdOrNew() {
        UUID runId = RunContext.get();
        return runId != null ? runId : UUID.randomUUID();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static final class ApplyCounters {
        private int appliedFieldCount;
        private int deniedAttributeCount;
        private final List<String> deniedAttributeNames = new ArrayList<>();
        private boolean deniedAttributeNamesTruncated;

        private void deny(String attributeName) {
            deniedAttributeCount++;
            if (deniedAttributeNames.size() < DENIED_ATTRIBUTE_NAME_LIMIT) {
                deniedAttributeNames.add(attributeName);
            } else {
                deniedAttributeNamesTruncated = true;
            }
        }

        private DraftApplyResult toResult() {
            return new DraftApplyResult(appliedFieldCount, deniedAttributeCount,
                    deniedAttributeNames, deniedAttributeNamesTruncated);
        }
    }

    private static final class DraftValueSkippedException extends RuntimeException {
    }
}
