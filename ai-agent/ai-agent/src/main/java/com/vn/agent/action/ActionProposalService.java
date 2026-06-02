package com.vn.agent.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.entity.AiExtractionDraft;
import com.vn.agent.extraction.AiExtractionProperties;
import com.vn.agent.extraction.MetaClassDtoSynthesizer;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.tools.ToolEntityResolver;
import com.vn.agent.tools.ToolUserError;
import com.vn.agent.security.AiAgentMutationRole;
import com.vn.agent.tools.mutation.MutationAuthorizationService;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.MessageTools;
import io.jmix.core.Metadata;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.exception.NoSuchViewException;
import io.jmix.flowui.view.ViewInfo;
import io.jmix.flowui.view.ViewRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Validates action proposals and creates generic draft rows for the prefill action.
 */
@Service
public class ActionProposalService {

    private static final String OPERATION_CREATE = "create";

    private final ToolEntityResolver toolEntityResolver;
    private final MutationAuthorizationService mutationAuthorizationService;
    private final MetaClassDtoSynthesizer schemaSynthesizer;
    private final AccessManager accessManager;
    private final ViewRegistry viewRegistry;
    private final Metadata metadata;
    private final MessageTools messageTools;
    private final DataManager dataManager;
    private final AiExtractionProperties extractionProperties;
    private final ObjectMapper objectMapper;
    private final CurrentAuthentication currentAuthentication;

    public ActionProposalService(ToolEntityResolver toolEntityResolver,
                                 MutationAuthorizationService mutationAuthorizationService,
                                 MetaClassDtoSynthesizer schemaSynthesizer,
                                 AccessManager accessManager,
                                 ViewRegistry viewRegistry,
                                 Metadata metadata,
                                 MessageTools messageTools,
                                 DataManager dataManager,
                                 AiExtractionProperties extractionProperties,
                                 ObjectMapper objectMapper,
                                 CurrentAuthentication currentAuthentication) {
        this.toolEntityResolver = toolEntityResolver;
        this.mutationAuthorizationService = mutationAuthorizationService;
        this.schemaSynthesizer = schemaSynthesizer;
        this.accessManager = accessManager;
        this.viewRegistry = viewRegistry;
        this.metadata = metadata;
        this.messageTools = messageTools;
        this.dataManager = dataManager;
        this.extractionProperties = extractionProperties;
        this.objectMapper = objectMapper;
        this.currentAuthentication = currentAuthentication;
    }

    public ActionProposalResult validate(ActionProposal rawProposal) {
        ActionProposal proposal = normalize(rawProposal);
        if (!OPERATION_CREATE.equalsIgnoreCase(nullToEmpty(proposal.operation()))) {
            return ActionProposalResult.invalid(proposal, "chatView.actionChoice.invalidProposal");
        }

        MetaClass metaClass;
        try {
            metaClass = resolveCreatableEntity(proposal.targetEntityName());
        } catch (ToolUserError deniedOrUnknown) {
            return ActionProposalResult.accessDenied(proposal);
        }

        if (proposal.isBulk()) {
            return validateBulk(proposal, metaClass);
        }

        MetaClassDtoSynthesizer.SynthesizedSchema schema = schemaSynthesizer.buildSchema(metaClass);
        if (!schema.attributeNames().containsAll(proposal.values().keySet())) {
            return ActionProposalResult.invalid(proposal, "chatView.actionChoice.invalidProposal");
        }

        List<String> missingFields = missingFields(proposal, schema);
        if (!missingFields.isEmpty()) {
            return ActionProposalResult.missingFields(proposal, missingFields);
        }

        List<String> allowedChoices = allowedChoices(proposal, metaClass);
        if (allowedChoices.isEmpty()) {
            return ActionProposalResult.accessDenied(proposal);
        }
        return ActionProposalResult.ready(withChoices(proposal, allowedChoices, metaClass), allowedChoices);
    }

    /**
     * Workstream A — bulk variant: validate ≥2 rows of the same entity and offer only the
     * {@link ActionIntentId#BULK_CREATE_NOW} choice. Authorization mirrors the single-record
     * create-now path (mutation role + create + per-row attribute write access). A single READY
     * proposal carrying all rows preserves the one-confirmation-gate-for-N-rows model.
     */
    private ActionProposalResult validateBulk(ActionProposal proposal, MetaClass metaClass) {
        if (proposal.valuesList().size() < 2) {
            return ActionProposalResult.invalid(proposal, "chatView.actionChoice.invalidProposal");
        }
        MetaClassDtoSynthesizer.SynthesizedSchema schema = schemaSynthesizer.buildSchema(metaClass);

        Set<String> allAttributeNames = new LinkedHashSet<>();
        for (Map<String, Object> row : proposal.valuesList()) {
            if (!schema.attributeNames().containsAll(row.keySet())) {
                return ActionProposalResult.invalid(proposal, "chatView.actionChoice.invalidProposal");
            }
            allAttributeNames.addAll(row.keySet());
        }

        Set<String> missing = new LinkedHashSet<>();
        for (Map<String, Object> row : proposal.valuesList()) {
            missing.addAll(missingFieldsForRow(row, schema));
        }
        if (!missing.isEmpty()) {
            return ActionProposalResult.missingFields(proposal, List.copyOf(missing));
        }

        if (!canCreateNow(metaClass, allAttributeNames)) {
            return ActionProposalResult.accessDenied(proposal);
        }
        List<String> bulkChoices = List.of(ActionIntentId.BULK_CREATE_NOW);
        return ActionProposalResult.ready(withBulkChoices(proposal, bulkChoices, metaClass), bulkChoices);
    }

    private MetaClass resolveCreatableEntity(String targetEntityName) {
        try {
            MetaClass exactMetaClass = toolEntityResolver.resolveCreatableEntityOrThrow(targetEntityName);
            if (exactMetaClass != null) {
                return exactMetaClass;
            }
        } catch (ToolUserError exactFailure) {
            String canonicalEntityName = findUniqueEntityAlias(targetEntityName);
            if (canonicalEntityName == null || canonicalEntityName.equals(targetEntityName)) {
                throw exactFailure;
            }
            return toolEntityResolver.resolveCreatableEntityOrThrow(canonicalEntityName);
        }
        throw new ToolUserError("unknown_entity", "no entity named " + targetEntityName);
    }

    private String findUniqueEntityAlias(String targetEntityName) {
        if (!StringUtils.hasText(targetEntityName)) {
            return null;
        }
        String normalizedTarget = targetEntityName.strip();
        String matchedEntityName = null;
        for (MetaClass candidateMetaClass : metadata.getSession().getClasses()) {
            if (!matchesEntityAlias(candidateMetaClass, normalizedTarget)) {
                continue;
            }
            if (matchedEntityName != null) {
                return null;
            }
            matchedEntityName = candidateMetaClass.getName();
        }
        return matchedEntityName;
    }

    private boolean matchesEntityAlias(MetaClass metaClass, String targetEntityName) {
        if (metaClass == null || !StringUtils.hasText(targetEntityName)) {
            return false;
        }
        if (targetEntityName.equalsIgnoreCase(metaClass.getName())) {
            return true;
        }
        Class<?> javaClass = metaClass.getJavaClass();
        if (javaClass != null && targetEntityName.equalsIgnoreCase(javaClass.getSimpleName())) {
            return true;
        }
        String name = metaClass.getName();
        int separatorIndex = name == null ? -1 : name.lastIndexOf('_');
        if (separatorIndex >= 0 && separatorIndex + 1 < name.length()
                && targetEntityName.equalsIgnoreCase(name.substring(separatorIndex + 1))) {
            return true;
        }
        try {
            String caption = messageTools.getEntityCaption(metaClass);
            return StringUtils.hasText(caption) && targetEntityName.equalsIgnoreCase(caption.strip());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public DraftResult createDraft(ActionProposal proposal) {
        return createDraft(proposal, RunContext.getConversationId(), null);
    }

    public DraftResult createDraft(ActionProposal proposal,
                                   UUID sourceConversationId,
                                   UUID sourceTaskFileId) {
        ActionProposalResult validation = validate(proposal);
        if (!ActionProposalResult.STATUS_READY.equals(validation.status())
                || !validation.choices().contains(ActionIntentId.PREFILL_FORM)) {
            throw new IllegalStateException("Prefill action is not available for this proposal");
        }
        UUID effectiveSourceConversationId = sourceConversationId != null
                ? sourceConversationId
                : RunContext.getConversationId();
        if (effectiveSourceConversationId == null) {
            throw new IllegalStateException("Action proposal draft requires a source conversation id");
        }

        ActionProposal readyProposal = validation.proposal();
        MetaClass metaClass = metadata.getClass(readyProposal.targetEntityName());
        AiExtractionDraft draft = dataManager.create(AiExtractionDraft.class);
        UUID draftId = draft.getId() == null ? UUID.randomUUID() : draft.getId();
        OffsetDateTime now = OffsetDateTime.now();

        draft.setId(draftId);
        draft.setInstanceName(instanceName(readyProposal, metaClass, draftId));
        draft.setUserUsername(currentUsername());
        draft.setTargetEntityName(metaClass.getName());
        draft.setIntentId("action:" + ActionIntentId.PREFILL_FORM);
        draft.setPayloadJson(writeJson(readyProposal.values()));
        draft.setSourceConversationId(effectiveSourceConversationId);
        draft.setSourceTaskFileId(sourceTaskFileId);
        draft.setCreatedAt(now);
        draft.setExpiresAt(now.plusSeconds(extractionProperties.getTtlSeconds()));
        draft.setConfirmed(false);

        dataManager.save(draft);
        return new DraftResult(draftId, metaClass.getName(), draft.getInstanceName());
    }

    private ActionProposal normalize(ActionProposal proposal) {
        if (proposal == null) {
            return new ActionProposal(null, "", "", "", Map.of(), List.of(), List.of());
        }
        return proposal;
    }

    private List<String> missingFields(ActionProposal proposal,
                                       MetaClassDtoSynthesizer.SynthesizedSchema schema) {
        Set<String> missing = new LinkedHashSet<>(proposal.missingFields());
        missing.addAll(missingFieldsForRow(proposal.values(), schema));
        return List.copyOf(missing);
    }

    private List<String> missingFieldsForRow(Map<String, Object> row,
                                             MetaClassDtoSynthesizer.SynthesizedSchema schema) {
        Set<String> missing = new LinkedHashSet<>();
        for (String requiredAttributeName : schema.requiredAttributeNames()) {
            Object value = row.get(requiredAttributeName);
            if (!row.containsKey(requiredAttributeName)
                    || value == null
                    || (value instanceof String stringValue && stringValue.isBlank())) {
                missing.add(requiredAttributeName);
            }
        }
        return List.copyOf(missing);
    }

    private List<String> allowedChoices(ActionProposal proposal, MetaClass metaClass) {
        List<String> requestedChoices = proposal.choices().isEmpty()
                ? List.of(ActionIntentId.CREATE_NOW, ActionIntentId.PREFILL_FORM)
                : proposal.choices();
        List<String> allowed = new ArrayList<>(requestedChoices.size());
        for (String choice : requestedChoices) {
            if (ActionIntentId.CREATE_NOW.equals(choice) && canCreateNow(metaClass, proposal.values().keySet())) {
                allowed.add(choice);
            } else if (ActionIntentId.PREFILL_FORM.equals(choice) && canPrefill(metaClass)) {
                allowed.add(choice);
            }
        }
        return List.copyOf(allowed);
    }

    private boolean canCreateNow(MetaClass metaClass, Set<String> attributeNames) {
        try {
            mutationAuthorizationService.enforceMutationRole(AiAgentMutationRole.CODE);
            mutationAuthorizationService.enforceCreatePermission(metaClass);
            mutationAuthorizationService.enforceAttributeWriteAccess(metaClass, attributeNames);
            return true;
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private boolean canPrefill(MetaClass metaClass) {
        if (!isCreatePermitted(metaClass)) {
            return false;
        }
        ViewInfo detailViewInfo = resolveDetailViewInfo(metaClass);
        return detailViewInfo != null && isViewPermitted(detailViewInfo.getId());
    }

    private boolean isCreatePermitted(MetaClass metaClass) {
        CrudEntityContext accessContext = new CrudEntityContext(metaClass);
        accessManager.applyRegisteredConstraints(accessContext);
        return accessContext.isCreatePermitted();
    }

    private ViewInfo resolveDetailViewInfo(MetaClass metaClass) {
        try {
            return viewRegistry.getDetailViewInfo(metaClass);
        } catch (NoSuchViewException ignored) {
            return null;
        }
    }

    private boolean isViewPermitted(String viewId) {
        UiShowViewContext accessContext = new UiShowViewContext(viewId);
        accessManager.applyRegisteredConstraints(accessContext);
        return accessContext.isPermitted();
    }

    private ActionProposal withChoices(ActionProposal proposal, List<String> choices, MetaClass metaClass) {
        return new ActionProposal(proposal.proposalId(), proposal.operation(), metaClass.getName(),
                instanceName(proposal, metaClass, UUID.randomUUID()),
                proposal.values(), List.of(), choices);
    }

    private ActionProposal withBulkChoices(ActionProposal proposal, List<String> choices, MetaClass metaClass) {
        return new ActionProposal(proposal.proposalId(), proposal.operation(), metaClass.getName(),
                bulkInstanceName(proposal, metaClass),
                Map.of(), proposal.valuesList(), List.of(), choices);
    }

    private String bulkInstanceName(ActionProposal proposal, MetaClass metaClass) {
        if (StringUtils.hasText(proposal.instanceName())) {
            return proposal.instanceName();
        }
        try {
            String caption = messageTools.getEntityCaption(metaClass);
            if (StringUtils.hasText(caption)) {
                return proposal.valuesList().size() + " " + caption;
            }
        } catch (RuntimeException ignored) {
            // fallback below
        }
        return proposal.valuesList().size() + " " + metaClass.getName();
    }

    private String instanceName(ActionProposal proposal, MetaClass metaClass, UUID draftId) {
        if (StringUtils.hasText(proposal.instanceName())) {
            return proposal.instanceName();
        }
        try {
            String caption = messageTools.getEntityCaption(metaClass);
            if (StringUtils.hasText(caption)) {
                return caption + " draft " + draftId;
            }
        } catch (RuntimeException ignored) {
            // fallback below
        }
        return metaClass.getName() + " draft " + draftId;
    }

    private String writeJson(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(values));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize action proposal draft", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String currentUsername() {
        try {
            UserDetails user = currentAuthentication.getUser();
            if (user != null && StringUtils.hasText(user.getUsername())) {
                return user.getUsername();
            }
        } catch (RuntimeException ignored) {
            // fallback below
        }
        return "anonymous";
    }

    public record DraftResult(UUID draftId, String entityName, String instanceName) {
    }
}
