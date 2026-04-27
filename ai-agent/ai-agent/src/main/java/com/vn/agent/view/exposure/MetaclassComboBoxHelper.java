package com.vn.agent.view.exposure;

import io.jmix.core.MessageTools;
import io.jmix.core.Metadata;
import io.jmix.core.entity.annotation.SystemLevel;
import io.jmix.core.metamodel.model.MetaClass;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared helper that produces the filtered, sorted list of {@link MetaClass} for the
 * entity-name ComboBox in {@code AiExposureRuleDetailView} and
 * {@code KnowledgeBaseView} upload/edit forms (CONTEXT.md D-11).
 *
 * <p>Excludes:
 * <ul>
 *     <li>{@code @SystemLevel} entities — matches the
 *         {@code CurrentUserSchemaAccess.isSystemLevelEntity} exclusion rule.</li>
 *     <li>AI-* internal entities — denylisting the governance UI itself would brick
 *         admin access to the very rules controlling exposure.</li>
 * </ul>
 *
 * <p>Items are sorted alphabetically by locale-resolved entity caption so the
 * dropdown reads well in both EN and VI bundles. Callers must set the item-label
 * generator to mirror the contract from the UI-SPEC:
 * <pre>{@code
 *     comboBox.setItemLabelGenerator(mc ->
 *             messageTools.getEntityCaption(mc) + " (" + mc.getName() + ")");
 * }</pre>
 */
@Component
public class MetaclassComboBoxHelper {

    /**
     * AI-* internal entity names (the {@code @Entity(name=...)} values, not Java class
     * simple names). Excluded from the dropdown to keep the governance UI usable.
     */
    private static final Set<String> AI_INTERNAL_ENTITY_NAMES = Set.of(
            "ai_AiAuditEvent",
            "ai_AiConversation",
            "ai_AiMessage",
            "ai_AiKnowledgeDocument",
            "ai_AiParameters",
            "aiExposure_AiExposureRule"
    );

    private final Metadata metadata;
    private final MessageTools messageTools;

    public MetaclassComboBoxHelper(Metadata metadata, MessageTools messageTools) {
        this.metadata = metadata;
        this.messageTools = messageTools;
    }

    /**
     * Returns a sorted list of {@link MetaClass} suitable for the entity-name ComboBox.
     * Excludes {@code @SystemLevel} entities and AI-* internals. Sorted alphabetically
     * by locale-resolved entity caption.
     */
    public List<MetaClass> buildFilteredList() {
        return metadata.getSession().getClasses().stream()
                .filter(mc -> !mc.getJavaClass().isAnnotationPresent(SystemLevel.class))
                .filter(mc -> !AI_INTERNAL_ENTITY_NAMES.contains(mc.getName()))
                .sorted(Comparator.comparing(messageTools::getEntityCaption))
                .collect(Collectors.toList());
    }
}
