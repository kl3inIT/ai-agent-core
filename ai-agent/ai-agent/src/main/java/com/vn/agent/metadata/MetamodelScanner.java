package com.vn.agent.metadata;

import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.entity.annotation.SystemLevel;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TOOL-01 raw-inventory scanner. Runs once on {@link ApplicationReadyEvent} (NOT
 * {@code @PostConstruct} — see 03-RESEARCH.md Pitfall 1) and caches an immutable {@link AiSchema}
 * plus a {@link UserEditableStringIndex}. Never consulted directly by LLM-facing paths; those go
 * through {@link EffectiveSchemaComputer}.
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

    private volatile AiSchema rawSchema;
    private volatile UserEditableStringIndex userEditableStringIndex;

    // CLAUDE.md: constructor injection only.
    public MetamodelScanner(Metadata metadata, MetadataTools metadataTools) {
        this.metadata = metadata;
        this.metadataTools = metadataTools;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scan() {
        Map<String, AiEntityInfo> entities = new LinkedHashMap<>();
        Map<String, Set<String>> uei = new LinkedHashMap<>();

        for (MetaClass mc : metadata.getSession().getClasses()) {
            if (mc.getJavaClass().isAnnotationPresent(SystemLevel.class)) {
                continue;
            }

            List<AiAttributeInfo> attrs = new ArrayList<>();
            Set<String> userEditable = new LinkedHashSet<>();

            for (MetaProperty mp : mc.getProperties()) {
                attrs.add(buildAttributeInfo(mp));
                if (isUserEditableString(mp)) {
                    userEditable.add(mp.getName());
                }
            }

            entities.put(mc.getName(),
                    new AiEntityInfo(mc, mc.getName(), null, attrs));
            uei.put(mc.getName(), Set.copyOf(userEditable));
        }

        this.rawSchema = new AiSchema(entities);
        this.userEditableStringIndex = new UserEditableStringIndex(uei);
    }

    public AiSchema getRawSchema() {
        if (rawSchema == null) {
            throw new IllegalStateException("scan() not yet run");
        }
        return rawSchema;
    }

    public UserEditableStringIndex getUserEditableStringIndex() {
        if (userEditableStringIndex == null) {
            throw new IllegalStateException("scan() not yet run");
        }
        return userEditableStringIndex;
    }

    // ---- internals (per D-02) ----

    private AiAttributeInfo buildAttributeInfo(MetaProperty mp) {
        Range range = mp.getRange();

        String typeLabel;
        List<String> enumValues = null;
        String relationshipTarget = null;

        if (range.isEnum()) {
            Class<?> javaEnum = range.asEnumeration().getJavaClass();
            typeLabel = "enum:" + javaEnum.getSimpleName();
            Object[] constants = javaEnum.getEnumConstants();
            if (constants != null) {
                List<String> names = new ArrayList<>(constants.length);
                for (Object c : constants) {
                    names.add(((Enum<?>) c).name());
                }
                enumValues = names;
            } else {
                enumValues = List.of();
            }
        } else if (range.isClass()) {
            MetaClass target = range.asClass();
            typeLabel = "ref:" + target.getName();
            relationshipTarget = target.getName();
        } else if (range.isDatatype()) {
            typeLabel = range.asDatatype().getJavaClass().getSimpleName();
        } else {
            typeLabel = "unknown";
        }

        boolean nullable = !mp.isMandatory();
        List<String> constraints = collectValidationConstraints(mp);

        return new AiAttributeInfo(
                mp.getName(),
                typeLabel,
                nullable,
                enumValues,
                relationshipTarget,
                constraints,
                null
        );
    }

    private List<String> collectValidationConstraints(MetaProperty mp) {
        AnnotatedElement el = mp.getAnnotatedElement();
        if (el == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        NotNull notNull = el.getAnnotation(NotNull.class);
        if (notNull != null) {
            out.add("NotNull");
        }
        Size size = el.getAnnotation(Size.class);
        if (size != null) {
            out.add("Size(min=" + size.min() + ",max=" + size.max() + ")");
        }
        Pattern pattern = el.getAnnotation(Pattern.class);
        if (pattern != null) {
            out.add("Pattern(regexp=" + pattern.regexp() + ")");
        }
        return out;
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
