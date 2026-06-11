package com.vn.agent.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiExtractionDraft;
import com.vn.agent.entity.AiTaskFile;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.metadata.LlmExposurePolicy;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.spi.IntentExtractor;
import com.vn.agent.taskfile.AiTaskFileMediaResolver;
import io.jmix.core.DataManager;
import io.jmix.core.MessageTools;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.CurrentAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates named extraction intent execution, draft persistence, and append-only audit.
 *
 * <p>This service is intentionally not transactional. Extraction can perform an LLM call, so
 * database work is kept to short secured {@link DataManager} operations and audit writes rely on
 * {@link AuditWriter}'s existing transaction boundary.</p>
 */
@Service
public class ExtractionService {

    static final String TOOL_NAME = "prepare_form_draft";
    private static final int AUDIT_ATTRIBUTE_NAME_LIMIT = 16;
    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    private final IntentRegistry intentRegistry;
    private final DataManager dataManager;
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final MessageTools messageTools;
    private final LlmExposurePolicy llmExposurePolicy;
    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;
    private final AiExtractionProperties extractionProperties;
    private final AiTaskFileMediaResolver taskFileMediaResolver;
    private final MetaClassDtoSynthesizer schemaSynthesizer;
    private final ObjectMapper objectMapper;

    public ExtractionService(IntentRegistry intentRegistry,
                             DataManager dataManager,
                             Metadata metadata,
                             MetadataTools metadataTools,
                             MessageTools messageTools,
                             LlmExposurePolicy llmExposurePolicy,
                             AuditWriter auditWriter,
                             CurrentAuthentication currentAuthentication,
                             AiExtractionProperties extractionProperties,
                             AiTaskFileMediaResolver taskFileMediaResolver,
                             MetaClassDtoSynthesizer schemaSynthesizer,
                             ObjectMapper objectMapper) {
        this.intentRegistry = intentRegistry;
        this.dataManager = dataManager;
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.messageTools = messageTools;
        this.llmExposurePolicy = llmExposurePolicy;
        this.auditWriter = auditWriter;
        this.currentAuthentication = currentAuthentication;
        this.extractionProperties = extractionProperties;
        this.taskFileMediaResolver = taskFileMediaResolver;
        this.schemaSynthesizer = schemaSynthesizer;
        this.objectMapper = objectMapper;
    }

    public ExtractionResult prepare(String intentId, Map<String, Object> contextRefs) {
        UUID conversationId = contextConversationId(contextRefs);
        if (conversationId == null) {
            conversationId = RunContext.getConversationId();
        }
        String userMessage = contextUserMessage(contextRefs);
        List<UUID> activeTaskFileIds = loadActiveTaskFileIds(conversationId);
        AiTaskFileMediaResolver.Resolved resolvedMedia = conversationId == null
                ? AiTaskFileMediaResolver.Resolved.empty()
                : taskFileMediaResolver.resolveActive(conversationId);
        return prepare(intentId, new ExtractionInput(intentId, conversationId, userMessage,
                activeTaskFileIds, resolvedMedia.media(), sourceTexts(resolvedMedia.documentTexts())));
    }

    public ExtractionResult prepare(String intentId, ExtractionInput input) {
        long startNanos = System.nanoTime();
        ExtractionInput effectiveInput = normalizeInput(intentId, input);
        LinkedHashMap<String, Object> argumentsJson = argumentsPayload(intentId, effectiveInput);
        String userUsername = currentUsername();
        UUID runId = runIdOrNew();

        try {
            validateInput(intentId, effectiveInput);
            IntentExtractor<?> extractor = intentRegistry.find(intentId)
                    .orElseThrow(() -> ExtractionSchemaException.unknownIntent(intentId));
            MetaClass metaClass = resolveMetaClass(extractor);
            ensureExposed(intentId, metaClass);

            Object extractedPayload = extractor.extract(effectiveInput);
            LinkedHashMap<String, Object> payloadMap = ExtractionJsonSupport.toPayloadMap(
                    objectMapper, extractedPayload, intentId, metaClass.getName());
            MetaClassDtoSynthesizer.SynthesizedSchema schema = schemaSynthesizer.buildSchema(metaClass);
            LinkedHashMap<String, Object> filteredPayloadMap = filterPayloadToSchema(payloadMap, schema);
            String payloadJson = ExtractionJsonSupport.writeJson(objectMapper, filteredPayloadMap,
                    intentId, metaClass.getName(), filteredPayloadMap.size());

            AiExtractionDraft draft = dataManager.create(AiExtractionDraft.class);
            UUID draftId = ensureDraftId(draft);
            String instanceName = computeInstanceName(extractedPayload, metaClass, draftId);
            OffsetDateTime now = OffsetDateTime.now();

            draft.setInstanceName(instanceName);
            draft.setUserUsername(userUsername);
            draft.setTargetEntityName(metaClass.getName());
            draft.setIntentId(intentId);
            draft.setPayloadJson(payloadJson);
            draft.setSourceConversationId(effectiveInput.conversationId());
            draft.setSourceTaskFileId(firstTaskFileId(effectiveInput.taskFileIds()));
            draft.setCreatedAt(now);
            draft.setExpiresAt(now.plusSeconds(extractionProperties.getTtlSeconds()));
            draft.setConfirmed(false);

            dataManager.save(draft);
            ExtractionResult result = new ExtractionResult(draftId, metaClass.getName(), instanceName);
            try {
                writeSuccessAudit(runId, userUsername, effectiveInput.conversationId(), argumentsJson,
                        result, filteredPayloadMap, elapsedMillis(startNanos));
            } catch (RuntimeException auditFailure) {
                // Best-effort audit, mirroring auditDenial / task_file_budget_exceeded: a transient
                // audit-write failure must not reclassify a persisted draft as a validation failure.
                log.warn("Failed to write success audit for prepare_form_draft draftId={}", draftId, auditFailure);
            }
            return result;
        } catch (ExtractionDeniedException denied) {
            writeDeniedAudit(runId, userUsername, effectiveInput.conversationId(), argumentsJson,
                    denied, elapsedMillis(startNanos));
            throw denied;
        } catch (ExtractionSchemaException schemaFailure) {
            writeFailedAudit(runId, userUsername, effectiveInput.conversationId(), argumentsJson,
                    schemaFailure, elapsedMillis(startNanos));
            throw schemaFailure;
        } catch (RuntimeException runtimeFailure) {
            ExtractionSchemaException safeFailure = ExtractionSchemaException.validationFailure(
                    intentId, null, 0);
            writeFailedAudit(runId, userUsername, effectiveInput.conversationId(), argumentsJson,
                    safeFailure, elapsedMillis(startNanos), runtimeFailure.getClass().getName());
            throw safeFailure;
        }
    }

    private ExtractionInput normalizeInput(String intentId, ExtractionInput input) {
        if (input == null) {
            return new ExtractionInput(intentId, RunContext.getConversationId(), RunContext.getUserMessage(),
                    RunContext.getTaskFileIds(), RunContext.getTaskFileMedia(), RunContext.getSourceTexts());
        }
        if (intentId.equals(input.intentId())) {
            return input;
        }
        return new ExtractionInput(intentId, input.conversationId(), input.userMessage(),
                input.taskFileIds(), input.taskFileMedia(), input.sourceTexts());
    }

    private void validateInput(String intentId, ExtractionInput input) {
        if (!StringUtils.hasText(intentId)) {
            throw ExtractionSchemaException.unknownIntent(intentId);
        }
        if (input.conversationId() == null) {
            throw ExtractionSchemaException.validationFailure(intentId, null, 0);
        }
    }

    private MetaClass resolveMetaClass(IntentExtractor<?> extractor) {
        MetaClass metaClass = metadata.getSession().findClass(extractor.entityName());
        if (metaClass == null) {
            throw ExtractionSchemaException.validationFailure(extractor.intentId(), extractor.entityName(), 0);
        }
        return metaClass;
    }

    private void ensureExposed(String intentId, MetaClass metaClass) {
        if (!llmExposurePolicy.canReadEntity(metaClass) || !llmExposurePolicy.canCreate(metaClass)) {
            throw ExtractionDeniedException.exposureDenied(intentId, metaClass.getName(), "exposure_rule");
        }
    }

    private LinkedHashMap<String, Object> filterPayloadToSchema(
            LinkedHashMap<String, Object> payloadMap,
            MetaClassDtoSynthesizer.SynthesizedSchema schema) {
        LinkedHashMap<String, Object> filteredPayload = new LinkedHashMap<>();
        for (String attributeName : schema.attributeNames()) {
            if (payloadMap.containsKey(attributeName)) {
                filteredPayload.put(attributeName, payloadMap.get(attributeName));
            }
        }
        return filteredPayload;
    }

    private String computeInstanceName(Object extractedPayload, MetaClass metaClass, UUID draftId) {
        try {
            String instanceName = metadataTools.getInstanceName(extractedPayload);
            if (StringUtils.hasText(instanceName)) {
                return instanceName;
            }
        } catch (RuntimeException ignored) {
            // Map outputs and DTOs without Jmix metadata fall back to the entity caption.
        }
        return entityCaption(metaClass) + " draft " + draftId;
    }

    private String entityCaption(MetaClass metaClass) {
        try {
            String caption = messageTools.getEntityCaption(metaClass);
            return StringUtils.hasText(caption) ? caption : metaClass.getName();
        } catch (RuntimeException ignored) {
            return metaClass.getName();
        }
    }

    private UUID ensureDraftId(AiExtractionDraft draft) {
        if (draft.getId() == null) {
            draft.setId(UUID.randomUUID());
        }
        return draft.getId();
    }

    private void writeSuccessAudit(UUID runId,
                                   String userUsername,
                                   UUID conversationId,
                                   Map<String, Object> arguments,
                                   ExtractionResult result,
                                   Map<String, Object> payload,
                                   long latencyMs) {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("draftId", result.draftId().toString());
        summary.put("entityName", result.entityName());
        summary.put("extractedFieldCount", payload.size());
        summary.put("extractedAttributes", ExtractionJsonSupport.boundedAttributeNames(
                payload, AUDIT_ATTRIBUTE_NAME_LIMIT));
        summary.put("truncated", payload.size() > AUDIT_ATTRIBUTE_NAME_LIMIT);
        auditWriter.writeToolCall(RunContext.getRootAuditId(), runId, userUsername, conversationId,
                TOOL_NAME,
                ExtractionJsonSupport.writeJson(objectMapper, arguments, result.entityName(),
                        result.entityName(), 0),
                ExtractionJsonSupport.writeJson(objectMapper, summary, result.entityName(),
                        result.entityName(), payload.size()),
                latencyMs,
                AiToolCallOutcome.SUCCESS,
                null,
                null);
    }

    private void writeDeniedAudit(UUID runId,
                                  String userUsername,
                                  UUID conversationId,
                                  Map<String, Object> arguments,
                                  ExtractionDeniedException denied,
                                  long latencyMs) {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("failureCode", denied.getCode());
        summary.put("entityName", denied.getEntityName());
        summary.put("extractedFieldCount", 0);
        auditWriter.writeToolCall(RunContext.getRootAuditId(), runId, userUsername, conversationId,
                TOOL_NAME,
                ExtractionJsonSupport.writeJson(objectMapper, arguments, denied.getIntentId(),
                        denied.getEntityName(), 0),
                ExtractionJsonSupport.writeJson(objectMapper, summary, denied.getIntentId(),
                        denied.getEntityName(), 0),
                latencyMs,
                AiToolCallOutcome.DENIED,
                denied.getDenialReason(),
                denied.getClass().getName());
    }

    private void writeFailedAudit(UUID runId,
                                  String userUsername,
                                  UUID conversationId,
                                  Map<String, Object> arguments,
                                  ExtractionSchemaException failure,
                                  long latencyMs) {
        writeFailedAudit(runId, userUsername, conversationId, arguments, failure, latencyMs,
                failure.getClass().getName());
    }

    private void writeFailedAudit(UUID runId,
                                  String userUsername,
                                  UUID conversationId,
                                  Map<String, Object> arguments,
                                  ExtractionSchemaException failure,
                                  long latencyMs,
                                  String errorClass) {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("failureCode", failure.getCode());
        summary.put("entityName", failure.getEntityName());
        summary.put("extractedFieldCount", failure.getExtractedFieldCount() == null
                ? 0
                : failure.getExtractedFieldCount());
        auditWriter.writeToolCall(RunContext.getRootAuditId(), runId, userUsername, conversationId,
                TOOL_NAME,
                ExtractionJsonSupport.writeJson(objectMapper, arguments, failure.getIntentId(),
                        failure.getEntityName(), 0),
                ExtractionJsonSupport.writeJson(objectMapper, summary, failure.getIntentId(),
                        failure.getEntityName(), 0),
                latencyMs,
                AiToolCallOutcome.FAILED,
                null,
                errorClass);
    }

    private LinkedHashMap<String, Object> argumentsPayload(String intentId, ExtractionInput input) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("intentId", intentId);
        payload.put("conversationId", input.conversationId() == null ? null : input.conversationId().toString());
        List<String> taskFileIdStrings = new ArrayList<>();
        for (UUID taskFileId : input.taskFileIds()) {
            taskFileIdStrings.add(taskFileId == null ? null : taskFileId.toString());
        }
        payload.put("taskFileIds", taskFileIdStrings);
        payload.put("taskFileCount", taskFileIdStrings.size());
        payload.put("mediaCount", input.taskFileMedia().size());
        return payload;
    }

    private List<UUID> loadActiveTaskFileIds(UUID conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        return dataManager.load(AiTaskFile.class)
                .query("select e from ai_AiTaskFile e " +
                        "where e.conversation.id = :conversationId and e.expiresAt > :now " +
                        "order by e.createdDate desc")
                .parameter("conversationId", conversationId)
                .parameter("now", OffsetDateTime.now())
                .list()
                .stream()
                .map(AiTaskFile::getId)
                .toList();
    }

    private static List<ExtractionSourceText> sourceTexts(
            List<AiTaskFileMediaResolver.DocumentText> documentTexts) {
        if (documentTexts == null || documentTexts.isEmpty()) {
            return List.of();
        }
        return documentTexts.stream()
                .map(documentText -> new ExtractionSourceText(
                        documentText.filename(), documentText.text(), documentText.truncated()))
                .toList();
    }

    private UUID firstTaskFileId(List<UUID> taskFileIds) {
        return taskFileIds == null || taskFileIds.isEmpty() ? null : taskFileIds.get(0);
    }

    private UUID contextConversationId(Map<String, Object> contextRefs) {
        Object raw = contextRefs == null ? null : contextRefs.get("conversationId");
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        if (raw instanceof String stringValue && StringUtils.hasText(stringValue)) {
            try {
                return UUID.fromString(stringValue);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private String contextUserMessage(Map<String, Object> contextRefs) {
        Object raw = contextRefs == null ? null : contextRefs.get("userMessage");
        return raw instanceof String stringValue ? stringValue : null;
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
}
