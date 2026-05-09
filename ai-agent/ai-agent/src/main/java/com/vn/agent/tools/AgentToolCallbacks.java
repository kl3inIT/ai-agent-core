package com.vn.agent.tools;

import com.vn.agent.action.ActionIntentId;
import com.vn.agent.action.ActionProposalTool;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.audit.MutationArgumentSanitizer;
import com.vn.agent.audit.MutationToolCallbackBoundaryDecorator;
import com.vn.agent.audit.ToolCallbackAuditDecorator;
import com.vn.agent.extraction.ExtractionToolBridge;
import com.vn.agent.orchestration.StreamingSinkHolder;
import com.vn.agent.spi.ToolContributor;
import com.vn.agent.tools.link.BuiltInLinkTools;
import com.vn.agent.tools.mutation.BuiltInMutationTools;
import io.jmix.core.security.CurrentAuthentication;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Per-request tool-callback assembly (D-10). Phase 4's {@code ChatClientFactory} will call
 * {@link #forCurrentUser()} inside {@code ChatClient.prompt().toolCallbacks(...)} — NEVER the
 * builder-level defaults variant — because the effective schema the built-in tools see is
 * user-specific and {@link ToolContributor}s may gate on role.
 *
 * <p>Implementation note: the plan's {@code <interfaces>} block referenced a
 * {@code ToolCallbacks.from(Object)} helper. In Spring AI 1.1.4 that class does not exist; the
 * equivalent public API is
 * {@link MethodToolCallbackProvider}{@code .builder().toolObjects(bean).build().getToolCallbacks()}
 * which performs the same {@code @Tool}-method reflection pass. Behavior is identical; the
 * contract (fresh array per call, no caching) is preserved.</p>
 *
 * <h2>Phase 11 wiring (D-09)</h2>
 * <ul>
 *   <li>{@link BuiltInLinkTools} is always-on (Plan 11-08, D-05) and is wrapped in the standard
 *       {@link ToolCallbackAuditDecorator} loop alongside the read-only {@link BuiltInDataTools}
 *       and host {@link ToolContributor} beans.</li>
 *   <li>{@link BuiltInMutationTools} is conditional on
 *       {@code ai-agent.tools.mutation.enabled=true}; the bean is absent from the context under
 *       default config. RESEARCH Q5 mandates {@link ObjectProvider} (NOT
 *       {@code @Autowired(required=false)} field injection — proxy / eager-init quirks make
 *       the field variant brittle). When the bean is absent
 *       ({@link ObjectProvider#getIfAvailable()} returns {@code null}), zero mutation callbacks
 *       are appended. When present, its 4 {@code @Tool} methods become callbacks wrapped in the
 *       {@link MutationToolCallbackBoundaryDecorator} — they are NOT routed through the generic
 *       audit decorator because {@code BuiltInMutationTools} is the primary in-method audit
 *       owner (self-audits success/replay/blocked/error/commit-failed exactly once with
 *       mutation-specific outcomes). The boundary decorator is the fallback for pre-method
 *       binding/invocation failures.</li>
 * </ul>
 *
 * <p>D-09 callback counts:
 * <ul>
 *   <li>default (mutation off): 6 read + 2 link + 1 extraction = 9 callbacks.</li>
 *   <li>{@code ai-agent.tools.mutation.enabled=true}: 6 + 2 + 1 + 5 = 14 callbacks.</li>
 *   <li>NEVER: a {@code delete_record} callback under any property combination (D-07).</li>
 * </ul>
 */
@Component
public class AgentToolCallbacks {

    public static final String PREPARE_FORM_DRAFT_TOOL_NAME = "prepare_form_draft";
    private static final List<String> MUTATION_TOOL_NAMES = List.of(
            "create_record",
            "update_record",
            "add_related_record",
            "remove_related_record",
            "bulk_save_records");

    private final BuiltInDataTools builtIns;
    private final BuiltInLinkTools builtInLinkTools;
    private final ExtractionToolBridge extractionToolBridge;
    private final ActionProposalTool actionProposalTool;
    private final ObjectProvider<BuiltInMutationTools> mutationToolsProvider;
    private final List<ToolContributor> contributors;
    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;
    private final StreamingSinkHolder streamingSinkHolder;
    private final MutationArgumentSanitizer mutationArgumentSanitizer;

    public AgentToolCallbacks(BuiltInDataTools builtIns,
                              BuiltInLinkTools builtInLinkTools,
                              ExtractionToolBridge extractionToolBridge,
                              ActionProposalTool actionProposalTool,
                              ObjectProvider<BuiltInMutationTools> mutationToolsProvider,
                              List<ToolContributor> contributors,
                              AuditWriter auditWriter,
                              CurrentAuthentication currentAuthentication,
                              StreamingSinkHolder streamingSinkHolder,
                              MutationArgumentSanitizer mutationArgumentSanitizer) {
        this.builtIns = builtIns;
        this.builtInLinkTools = builtInLinkTools;
        this.extractionToolBridge = extractionToolBridge;
        this.actionProposalTool = actionProposalTool;
        this.mutationToolsProvider = mutationToolsProvider;
        this.contributors = contributors;
        this.auditWriter = auditWriter;
        this.currentAuthentication = currentAuthentication;
        this.streamingSinkHolder = streamingSinkHolder;
        this.mutationArgumentSanitizer = mutationArgumentSanitizer;
    }

    /**
     * Build a fresh {@link ToolCallback} array per request (AUD-04). Read + link + contributor
     * callbacks are wrapped in {@link ToolCallbackAuditDecorator} so each tool invocation produces
     * an audit row via the REQUIRES_NEW {@code AuditWriter} boundary — rows survive even when a
     * tool rolls back its own transaction. Mutation callbacks (when present) are wrapped instead
     * in {@link MutationToolCallbackBoundaryDecorator} so {@code BuiltInMutationTools} remains the
     * primary in-method audit owner for mutation outcomes (self-audits exactly once via
     * {@code safeWriteAudit}); the boundary decorator only audits if the delegate throws before
     * the tool method body runs (binding/invocation failures). Do NOT cache the returned array —
     * ToolContributor output and effective schema can change across invocations.
     */
    public ToolCallback[] forCurrentUser() {
        ToolCallback[] audited = auditedNonMutationCallbacks(true, true, true);
        ToolCallback[] mutationBoundaryWrapped = mutationCallbacks();
        ToolCallback[] out = Arrays.copyOf(audited, audited.length + mutationBoundaryWrapped.length);
        System.arraycopy(mutationBoundaryWrapped, 0, out, audited.length, mutationBoundaryWrapped.length);
        return out;
    }

    private ToolCallback[] auditedNonMutationCallbacks(boolean includeExtraction,
                                                       boolean includeActionProposal) {
        return auditedNonMutationCallbacks(includeExtraction, includeActionProposal, false);
    }

    private ToolCallback[] auditedNonMutationCallbacks(boolean includeExtraction,
                                                       boolean includeActionProposal,
                                                       boolean includeContributorMutationCallbacks) {
        List<ToolCallback> all = new ArrayList<>();
        Collections.addAll(all, fromBean(builtIns));
        // Always-on link tools (Plan 11-08): generic audit wrapping is correct here — link tools
        // do NOT self-audit; they emit a single SUCCESS/ERROR row through the generic decorator.
        Collections.addAll(all, fromBean(builtInLinkTools));
        if (includeExtraction) {
            Collections.addAll(all, fromBean(extractionToolBridge));
        }
        if (includeActionProposal) {
            Collections.addAll(all, fromBean(actionProposalTool));
        }
        for (ToolContributor tc : contributors) {
            List<Object> beans = tc.contribute();
            if (beans == null) {
                continue;
            }
            for (Object bean : beans) {
                ToolCallback[] contributedCallbacks = fromBean(bean);
                for (ToolCallback contributedCallback : contributedCallbacks) {
                    if (includeContributorMutationCallbacks
                            || !MUTATION_TOOL_NAMES.contains(contributedCallback.getToolDefinition().name())) {
                        all.add(contributedCallback);
                    }
                }
            }
        }
        ToolCallback[] audited = new ToolCallback[all.size()];
        for (int i = 0; i < all.size(); i++) {
            audited[i] = new ToolCallbackAuditDecorator(all.get(i), auditWriter, currentAuthentication, streamingSinkHolder);
        }
        return audited;
    }

    private ToolCallback[] mutationCallbacks() {
        // Conditional mutation tools (Plan 11-09 D-09). ObjectProvider.getIfAvailable() returns
        // null when @ConditionalOnProperty is OFF — RESEARCH Q5 forbids @Autowired(required=false)
        // field injection because of proxy / eager-init quirks. BuiltInMutationTools is the
        // primary in-method audit owner (self-audits via safeWriteAudit); raw callbacks are
        // wrapped in the mutation boundary decorator instead of the generic audit decorator to
        // avoid duplicate audit rows.
        BuiltInMutationTools mutationTools = mutationToolsProvider.getIfAvailable();
        if (mutationTools == null) {
            return new ToolCallback[0];
        }
        ToolCallback[] rawMutationCallbacks = fromBean(mutationTools);
        ToolCallback[] mutationBoundaryWrapped = new ToolCallback[rawMutationCallbacks.length];
        for (int i = 0; i < rawMutationCallbacks.length; i++) {
            mutationBoundaryWrapped[i] = new MutationToolCallbackBoundaryDecorator(
                    rawMutationCallbacks[i], streamingSinkHolder, auditWriter, currentAuthentication,
                    mutationArgumentSanitizer);
        }

        return mutationBoundaryWrapped;
    }

    /**
     * Per-request assembly used by {@code DefaultChatServiceImpl} (Plan 04-04 Task 3). The
     * {@code userId} and {@code conversationId} parameters are accepted for future per-user tool
     * filtering (Phase 5+); today the implementation delegates to {@link #forCurrentUser()}.
     */
    public ToolCallback[] callbacksFor(String userId, java.util.UUID conversationId) {
        return callbacksFor(userId, conversationId, null);
    }

    /**
     * Per-turn assembly used by chat. Auto/default turns retain the complete tool surface.
     * Named-intent turns fail closed to exactly the audited prepare_form_draft callback.
     */
    public ToolCallback[] callbacksFor(String userId, java.util.UUID conversationId, String intentId) {
        if (intentId == null || intentId.isBlank()) {
            return auditedNonMutationCallbacks(false, true);
        }
        String actionIntentId = ActionIntentId.fromSelectionParameter(intentId);
        if (ActionIntentId.CREATE_NOW.equals(actionIntentId)) {
            ToolCallback[] audited = auditedNonMutationCallbacks(false, false, true);
            ToolCallback[] mutationBoundaryWrapped = mutationCallbacks();
            ToolCallback[] out = Arrays.copyOf(audited, audited.length + mutationBoundaryWrapped.length);
            System.arraycopy(mutationBoundaryWrapped, 0, out, audited.length, mutationBoundaryWrapped.length);
            return out;
        }
        if (ActionIntentId.PREFILL_FORM.equals(actionIntentId)) {
            return singlePrepareFormDraftCallback();
        }
        return singlePrepareFormDraftCallback();
    }

    private ToolCallback[] singlePrepareFormDraftCallback() {
        ToolCallback[] callbacks = auditedNonMutationCallbacks(true, false, true);
        List<ToolCallback> matchingCallbacks = new ArrayList<>();
        for (ToolCallback callback : callbacks) {
            if (PREPARE_FORM_DRAFT_TOOL_NAME.equals(callback.getToolDefinition().name())) {
                matchingCallbacks.add(callback);
            }
        }
        if (matchingCallbacks.size() != 1) {
            throw new ToolConfigurationException(PREPARE_FORM_DRAFT_TOOL_NAME, matchingCallbacks.size());
        }
        return new ToolCallback[] { matchingCallbacks.get(0) };
    }

    /** Spring AI 1.1.4 replacement for the plan's {@code ToolCallbacks.from(bean)}. */
    private static ToolCallback[] fromBean(Object bean) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(bean)
                .build()
                .getToolCallbacks();
    }

    public static class ToolConfigurationException extends RuntimeException {

        public static final String CODE_PREPARE_FORM_DRAFT_MISCONFIGURED =
                "prepare_form_draft_misconfigured";

        private final String code;
        private final String toolName;
        private final int matchingCallbackCount;

        public ToolConfigurationException(String toolName, int matchingCallbackCount) {
            super("Required extraction tool callback is misconfigured");
            this.code = CODE_PREPARE_FORM_DRAFT_MISCONFIGURED;
            this.toolName = toolName;
            this.matchingCallbackCount = matchingCallbackCount;
        }

        public String getCode() {
            return code;
        }

        public String getToolName() {
            return toolName;
        }

        public int getMatchingCallbackCount() {
            return matchingCallbackCount;
        }
    }
}
