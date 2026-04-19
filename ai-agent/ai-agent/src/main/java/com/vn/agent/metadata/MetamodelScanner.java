package com.vn.agent.metadata;

import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.entity.annotation.SystemLevel;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * TOOL-01 startup scanner. Runs once on {@link ApplicationReadyEvent} (NOT
 * {@code @PostConstruct} — see 03-RESEARCH.md Pitfall 1) and caches a
 * {@link UserEditableStringIndex} — the only genuinely precomputed artifact we need
 * (attribute names derived from type + @SystemLevel + framework-managed filters).
 *
 * <p>Entity/attribute shape (typeLabel, enum values, captions, maxLength) is NOT cached — the
 * Jmix {@link MetaClass}/{@link MetaProperty} metamodel is already the authoritative source,
 * and {@code EffectiveSchemaComputer} + {@code ToolResultFormatter.describe} compute the
 * LLM-facing payload live per request (MessageTools is locale-sensitive anyway).</p>
 */
@Component
public class MetamodelScanner {

    /** Framework-managed attrs excluded from the user-editable-string index (D-13). */
    private static final Set<String> FRAMEWORK_MANAGED = Set.of(
            "id", "version",
            "createdBy", "createdDate",
            "lastModifiedBy", "lastModifiedDate",
            "deletedBy", "deletedDate"
    );

    private final Metadata metadata;
    private final MetadataTools metadataTools;

    private volatile UserEditableStringIndex userEditableStringIndex;

    // CLAUDE.md: constructor injection only.
    public MetamodelScanner(Metadata metadata, MetadataTools metadataTools) {
        this.metadata = metadata;
        this.metadataTools = metadataTools;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scan() {
        Map<String, Set<String>> uei = new LinkedHashMap<>();

        for (MetaClass mc : metadata.getSession().getClasses()) {
            if (mc.getJavaClass().isAnnotationPresent(SystemLevel.class)) {
                continue;
            }
            Set<String> userEditable = new LinkedHashSet<>();
            for (MetaProperty mp : mc.getProperties()) {
                if (isUserEditableString(mp)) {
                    userEditable.add(mp.getName());
                }
            }
            uei.put(mc.getName(), Set.copyOf(userEditable));
        }

        this.userEditableStringIndex = new UserEditableStringIndex(uei);
    }

    public UserEditableStringIndex getUserEditableStringIndex() {
        if (userEditableStringIndex == null) {
            throw new IllegalStateException("scan() not yet run");
        }
        return userEditableStringIndex;
    }

    /**
     * D-13: persistent + String-typed + not @SystemLevel + not framework-managed.
     * {@code MetadataTools.isJpa(mp)} covers the "persistent" check portably.
     */
    private boolean isUserEditableString(MetaProperty mp) {
        if (FRAMEWORK_MANAGED.contains(mp.getName())) {
            return false;
        }
        if (metadataTools.isSystemLevel(mp)) {
            return false;
        }
        if (metadataTools.isSystem(mp)) {
            return false;
        }
        if (!metadataTools.isJpa(mp)) {
            return false;
        }
        Range range = mp.getRange();
        if (!range.isDatatype()) {
            return false;
        }
        return range.asDatatype().getJavaClass() == String.class;
    }
}
