package com.vn.agent.admin.config;

import com.vn.agent.orchestration.AiAgentDefaultsProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Phase 16 D-06 — curated multi-vendor chat-model catalog, validated at boot.
 *
 * <p>The ai-agent is shipped as an addon to Jmix host applications with mixed
 * deployment constraints: some host apps run fully offline (Qwen self-host),
 * others run online with managed providers via OpenRouter (Claude). The catalog
 * mixes both flavors — the host app's deployment determines which entries are
 * actually reachable at runtime; the addon does not gate by license.</p>
 *
 * <p>Consumed by:</p>
 * <ul>
 *   <li>Plan 16-05 — {@code parameters-detail-view} {@code modelField} ComboBox
 *       populated via {@link #entries()} with default-marked rendering via
 *       {@code setItemLabelGenerator}.</li>
 *   <li>{@code ChatModelCatalogAllowlistTest} (TEST-20, Plan 16-03 Task 3) —
 *       catalog ⊆ {@link #CURATED_MODEL_ALLOWLIST}, exactly-one-default, drift
 *       gate against {@code default-params.yaml.model}.</li>
 * </ul>
 *
 * <p>Boot validation ({@link #validate()}) fails fast on:</p>
 * <ul>
 *   <li>Empty / null catalog ({@code module.properties} always seeds the curated set).</li>
 *   <li>Any entry whose {@code id} is outside the allowlist.</li>
 *   <li>Default-entry count not equal to 1.</li>
 *   <li>Drift between the marked-default id and {@code default-params.yaml.model}
 *       (consulted via {@link AiAgentDefaultsProperties#model()}).</li>
 * </ul>
 *
 * <p>The drift gate exists at both boot ({@link #validate()}) and build time
 * (TEST-20). If a future phase bumps {@code default-params.yaml.model} without
 * updating {@code is-default=true} on the matching catalog row, BOTH the test
 * and the application boot fail with a clear pointer.</p>
 */
@Component
public class ChatModelCatalog {

    // Curated multi-vendor allowlist. The addon is deployed to both offline
    // (Qwen self-host) and online (Claude via OpenRouter) host apps; the
    // catalog mixes both flavors. Models outside this set are reachable only
    // via the ComboBox's allow-custom-value escape hatch (SPEC criterion 4).
    public static final Set<String> CURATED_MODEL_ALLOWLIST = Set.of(
            "qwen/qwen3.6-35b-a3b",
            "qwen/qwen3-32b",
            "anthropic/claude-sonnet-4-6",
            "anthropic/claude-opus-4-7"
    );

    private final ChatModelCatalogProperties properties;
    private final AiAgentDefaultsProperties defaults;

    // WR-05: volatile + List.copyOf at assignment point. Spring's singleton publication
    // typically provides happens-before publication for the bean reference, but if a
    // future change ever re-invokes validate() (reload hook, custom @EventListener),
    // unsynchronised readers iterating `entries` in findById(...) would see torn state.
    // volatile ensures safe publication of subsequent assignments; List.copyOf at the
    // assignment point yields an immutable snapshot.
    private volatile List<Entry> entries;
    private volatile Entry defaultEntry;

    public ChatModelCatalog(ChatModelCatalogProperties properties,
                            AiAgentDefaultsProperties defaults) {
        this.properties = properties;
        this.defaults = defaults;
    }

    @PostConstruct
    void validate() {
        List<ChatModelCatalogProperties.Entry> raw = properties.catalog();
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException(
                    "ChatModelCatalog requires at least one entry under " +
                            "jmix.ai-agent.models.catalog[*] — module.properties seeds the default 4.");
        }

        for (ChatModelCatalogProperties.Entry entry : raw) {
            if (entry.id() == null || entry.id().isBlank()) {
                throw new IllegalStateException(
                        "ChatModelCatalog entry has null or blank id");
            }
            if (!CURATED_MODEL_ALLOWLIST.contains(entry.id())) {
                throw new IllegalStateException(
                        "ChatModelCatalog entry '" + entry.id() + "' is not in " +
                                "CURATED_MODEL_ALLOWLIST. Models outside the curated set " +
                                "are reachable only via the ComboBox allow-custom-value " +
                                "escape hatch in the admin UI.");
            }
        }

        long defaultCount = raw.stream()
                .filter(e -> Boolean.TRUE.equals(e.isDefault()))
                .count();
        if (defaultCount != 1) {
            throw new IllegalStateException(
                    "ChatModelCatalog must have exactly one default entry; found N=" + defaultCount);
        }

        ChatModelCatalogProperties.Entry markedDefault = raw.stream()
                .filter(e -> Boolean.TRUE.equals(e.isDefault()))
                .findFirst()
                .orElseThrow(); // unreachable — count check above guarantees presence.

        String defaultParamsModel = defaults.model();
        if (defaultParamsModel == null || !defaultParamsModel.equals(markedDefault.id())) {
            throw new IllegalStateException(
                    "ChatModelCatalog default '" + markedDefault.id() +
                            "' must equal default-params.yaml.model '" + defaultParamsModel +
                            "' (drift gate — SPEC criterion 4 / RESEARCH §7).");
        }

        // WR-05 — List.copyOf wraps the stream result so the field publishes an immutable
        // snapshot. Set defaultEntry BEFORE entries to keep readers consistent: a reader
        // that observes the new entries list also observes a matching defaultEntry, never
        // a stale one pointing into the previous list.
        List<Entry> nextEntries = List.copyOf(raw.stream()
                .map(e -> new Entry(e.id(), e.labelMessageKey(), Boolean.TRUE.equals(e.isDefault())))
                .toList());
        this.defaultEntry = nextEntries.stream()
                .filter(Entry::isDefault)
                .findFirst()
                .orElseThrow();
        this.entries = nextEntries;
    }

    /** Immutable, ordered list of catalog rows. Source ordering preserved from properties. */
    public List<Entry> entries() {
        return entries;
    }

    /** The single entry whose {@code is-default=true}. Never {@code null} after boot. */
    public Entry defaultEntry() {
        return defaultEntry;
    }

    /**
     * Lookup by id. Returns {@code null} for unknown ids — Plan 16-05's ComboBox
     * label generator renders custom-entered (allow-custom-value) ids verbatim
     * when this returns {@code null}.
     */
    public Entry findById(String id) {
        if (id == null) {
            return null;
        }
        for (Entry entry : entries) {
            if (id.equals(entry.id())) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Internal resolved-view of a catalog row — primitive {@code boolean}
     * because validation has already rejected null. {@code labelMessageKey}
     * may be {@code null} when a host configures an entry without a label
     * key (Plan 16-05 falls back to rendering the id verbatim).
     */
    public record Entry(String id, String labelMessageKey, boolean isDefault) {
    }
}
