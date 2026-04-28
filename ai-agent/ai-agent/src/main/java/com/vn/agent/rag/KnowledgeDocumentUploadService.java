package com.vn.agent.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiKnowledgeDocumentStatus;
import com.vn.agent.rag.config.AiAgentEmbeddingProperties;
import com.vn.agent.rag.config.AiAgentRagProperties;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.security.model.ResourceRole;
import io.jmix.security.role.ResourceRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Creates {@link AiKnowledgeDocument} rows for user / SPI uploads and schedules the
 * async ingestion worker (RAG-06).
 *
 * <p><b>Role validation (D-07 fail-closed):</b> every submitted role code is resolved
 * against Jmix's {@link ResourceRoleRepository}; an unknown code aborts the save with
 * {@link UnknownRoleCodeException}. Empty {@code allowedRoles} is accepted (the document
 * just becomes invisible to non-admins per D-05 fail-closed retrieval) — validation
 * only rejects codes that exist but do not resolve to a role.</p>
 *
 * <p><b>{@code sourceUri} contract:</b> the URI must be resolvable by Spring's
 * {@code ResourceLoader} — typically {@code classpath:} (for shipped / sample content)
 * or {@code file:} (for admin-uploaded content staged to a temp directory). RAG-01
 * requires PDF / MD / TXT / HTML formats to work; the actual file-format dispatch lives
 * in {@link AsyncIngestionWorker} which delegates to Tika. Phase 7 Flow UI owns the
 * stream-to-temp-file staging step; this service only consumes already-resolvable
 * URIs. Phase 2 {@code AiKnowledgeDocument} entity stores the URI in the {@code fileName}
 * column (see {@code AsyncIngestionWorker} source-resolution rules).</p>
 *
 * <p><b>{@code sourceKind} contract:</b> a free-form label persisted into the
 * {@code mimeType} column — {@code application/pdf}, {@code text/markdown}, or
 * synthetic labels like {@code CUSTOM_INGESTER} are all acceptable. Phase 5 does not
 * parse this field; it is metadata for the admin UI and operators.</p>
 *
 * <p><b>Async dispatch (Pitfall #7 avoidance):</b> the async worker is invoked only
 * AFTER the upload transaction commits. This uses
 * {@link TransactionSynchronizationManager#registerSynchronization} so the worker
 * never observes a document in PENDING state that has not yet been committed — a
 * concurrent read could otherwise see a phantom row.</p>
 */
@Service
public class KnowledgeDocumentUploadService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentUploadService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String FILE_PREFIX = "file:";
    /** Default classpath allowlist — fixtures & shipped sample ingester root. */
    private static final List<String> DEFAULT_CLASSPATH_ALLOWED_PREFIXES =
            List.of("classpath:ai-kb/");

    private final Metadata metadata;
    private final DataManager dataManager;
    private final ResourceRoleRepository roleRepository;
    private final AsyncIngestionWorker asyncIngestionWorker;
    private final AiAgentEmbeddingProperties embeddingProperties;
    private final AiAgentRagProperties ragProperties;

    public KnowledgeDocumentUploadService(Metadata metadata,
                                          DataManager dataManager,
                                          ResourceRoleRepository roleRepository,
                                          AsyncIngestionWorker asyncIngestionWorker,
                                          AiAgentEmbeddingProperties embeddingProperties,
                                          AiAgentRagProperties ragProperties) {
        this.metadata = metadata;
        this.dataManager = dataManager;
        this.roleRepository = roleRepository;
        this.asyncIngestionWorker = asyncIngestionWorker;
        this.embeddingProperties = embeddingProperties;
        this.ragProperties = ragProperties;
    }

    /**
     * Validates role codes, persists a PENDING {@link AiKnowledgeDocument}, and
     * schedules async ingestion after commit.
     *
     * <p>Convenience overload that delegates to
     * {@link #upload(String, String, Collection, String)} with a {@code null}
     * {@code sourceEntityName} (no entity link). Existing callers remain
     * source-compatible.
     *
     * @param sourceUri    Spring {@code ResourceLoader}-resolvable URI (see class Javadoc)
     * @param sourceKind   free-form label (MIME type or synthetic marker); nullable
     * @param allowedRoles Jmix role codes granting retrieval visibility; nullable (treated as empty)
     * @return the persisted document (with {@link AiKnowledgeDocument#getId()} populated)
     * @throws UnknownRoleCodeException if any supplied code is not defined in {@link ResourceRoleRepository}
     */
    @Transactional
    public AiKnowledgeDocument upload(String sourceUri, String sourceKind, Collection<String> allowedRoles) {
        return upload(sourceUri, sourceKind, allowedRoles, null);
    }

    /**
     * Extended upload that captures an optional Jmix entity link
     * ({@code sourceEntityName}) so it is persisted on the document BEFORE
     * {@code dataManager.save()} runs and BEFORE the async ingester schedules.
     * Required by Plan 10-08 / Phase 10 D-07: chunk metadata
     * ({@link com.vn.agent.rag.ChunkMetadata#SOURCE_ENTITY}) is mirrored from the
     * document row at ingest time, so the field MUST already be persisted when
     * {@link AsyncIngestionWorker#ingest(java.util.UUID)} reloads the row.
     *
     * @param sourceUri        Spring {@code ResourceLoader}-resolvable URI
     * @param sourceKind       free-form label (MIME type or synthetic marker); nullable
     * @param allowedRoles     Jmix role codes granting retrieval visibility; nullable (treated as empty)
     * @param sourceEntityName Jmix entity name (MetaClass#getName) to link the document to;
     *                         nullable (no link → chunks ingest without {@code source_entity}
     *                         metadata; legacy-doc contract per D-06)
     */
    @Transactional
    public AiKnowledgeDocument upload(String sourceUri,
                                      String sourceKind,
                                      Collection<String> allowedRoles,
                                      String sourceEntityName) {
        Objects.requireNonNull(sourceUri, "sourceUri must not be null");

        // CR-01: reject arbitrary server-side URIs (threat T-05-03-05 hardening). Only
        // accept classpath: values inside the configured allowlist, and file: values
        // inside the configured staging root. Anything else — including raw classpath:
        // or file: targeting application config, secrets, or arbitrary filesystem paths —
        // is refused before persistence.
        validateSourceUri(sourceUri);

        List<String> roles = allowedRoles == null ? List.of() : new ArrayList<>(allowedRoles);

        // D-07 fail-closed role validation — every code MUST resolve, unknown codes abort.
        for (String code : roles) {
            ResourceRole role = resolveRole(code);
            if (role == null) {
                throw new UnknownRoleCodeException(code);
            }
        }

        // PATTERNS: entity instantiation via Metadata.create (constructor path is forbidden).
        AiKnowledgeDocument document = metadata.create(AiKnowledgeDocument.class);
        document.setFileName(sourceUri);
        document.setMimeType(sourceKind);
        document.setAllowedRolesJson(writeRolesJson(roles));
        // Plan 10-08 / D-07: persist sourceEntityName BEFORE dataManager.save so the
        // async ingester sees the field when it reloads the row. Null clears the field.
        document.setSourceEntityName(sourceEntityName);
        document.setStatus(AiKnowledgeDocumentStatus.PENDING);
        // Note: embeddingModel is not a column on the Phase 2 entity — it is carried on each
        // chunk via ChunkMetadata.EMBEDDING_MODEL by AsyncIngestionWorker.enrich() during
        // ingest. WARNING-05: the previous side-effect-free call to
        // embeddingProperties.resolvedModel() was removed — a discarded result does not
        // protect against rename/removal of the property and only created a misleading
        // signal that the upload service consumed it.

        AiKnowledgeDocument saved = dataManager.save(document);

        // Schedule async ingestion ONLY after the enclosing transaction commits so the worker
        // observes the row (Pitfall #7 + D-14). If no transaction is active (e.g., called from
        // a context that bypassed @Transactional), dispatch immediately — the save has already
        // flushed in that case.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    asyncIngestionWorker.ingest(saved.getId());
                }
            });
        } else {
            log.warn("upload: no active transaction synchronization; dispatching ingest inline for {}",
                    saved.getId());
            asyncIngestionWorker.ingest(saved.getId());
        }

        return saved;
    }

    private ResourceRole resolveRole(String code) {
        try {
            return roleRepository.findRoleByCode(code);
        } catch (RuntimeException e) {
            // Jmix may consult the runtime role store after design-time role lookup misses.
            // If that store is unavailable or not present in a lightweight test/host schema,
            // keep the public contract fail-closed: the caller supplied an unusable role code.
            throw new UnknownRoleCodeException(code, e);
        }
    }

    /**
     * CR-01 allowlist validator. Only {@code classpath:} URIs matching a configured
     * prefix and {@code file:} URIs resolving inside the configured staging root are
     * accepted. Anything else — including raw {@code file:} with no staging root
     * configured, other schemes, or classpath resources outside the allowlist — is
     * rejected as an {@link IllegalArgumentException}. Bare filenames (no scheme)
     * are treated as an implicit {@code classpath:ai-kb/<name>} which must also fall
     * inside the allowlist to succeed.
     */
    private void validateSourceUri(String sourceUri) {
        String trimmed = sourceUri.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("sourceUri must not be blank");
        }
        if (trimmed.startsWith(CLASSPATH_PREFIX)) {
            List<String> prefixes = effectiveClasspathAllowedPrefixes();
            for (String prefix : prefixes) {
                if (trimmed.startsWith(prefix)) {
                    return;
                }
            }
            throw new IllegalArgumentException(
                    "sourceUri classpath location is not allowed: " + trimmed
                            + " (allowed prefixes: " + prefixes + ")");
        }
        if (trimmed.startsWith(FILE_PREFIX)) {
            String stagingRoot = effectiveFileStagingRoot();
            if (stagingRoot == null || stagingRoot.isBlank()) {
                throw new IllegalArgumentException(
                        "file: sourceUri is not accepted — no jmix.ai-agent.rag.upload.file-staging-root configured");
            }
            File staging;
            File target;
            try {
                staging = new File(stagingRoot).getCanonicalFile();
                target = new File(new URI(trimmed)).getCanonicalFile();
            } catch (URISyntaxException | IOException | IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "file: sourceUri is not a valid URI or cannot be resolved: " + trimmed, e);
            }
            if (!isUnder(staging, target)) {
                throw new IllegalArgumentException(
                        "file: sourceUri must resolve inside configured staging root (" + stagingRoot + "): " + trimmed);
            }
            return;
        }
        if (trimmed.contains("://")) {
            throw new IllegalArgumentException("Unsupported sourceUri scheme: " + trimmed);
        }
        // Bare filename — defer to AsyncIngestionWorker's default classpath:ai-kb/ root
        // which must also be in the allowlist for this call to succeed.
        String implied = "classpath:ai-kb/" + trimmed;
        List<String> prefixes = effectiveClasspathAllowedPrefixes();
        for (String prefix : prefixes) {
            if (implied.startsWith(prefix)) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "sourceUri bare filename resolves outside allowed classpath prefixes: " + trimmed);
    }

    private List<String> effectiveClasspathAllowedPrefixes() {
        if (ragProperties == null || ragProperties.upload() == null
                || ragProperties.upload().classpathAllowedPrefixes() == null
                || ragProperties.upload().classpathAllowedPrefixes().isEmpty()) {
            return DEFAULT_CLASSPATH_ALLOWED_PREFIXES;
        }
        return ragProperties.upload().classpathAllowedPrefixes();
    }

    private String effectiveFileStagingRoot() {
        if (ragProperties == null || ragProperties.upload() == null) {
            return null;
        }
        return ragProperties.upload().fileStagingRoot();
    }

    private static boolean isUnder(File root, File candidate) {
        File cur = candidate;
        while (cur != null) {
            if (cur.equals(root)) {
                return true;
            }
            cur = cur.getParentFile();
        }
        return false;
    }

    private static String writeRolesJson(List<String> roles) {
        try {
            return JSON.writeValueAsString(roles);
        } catch (JsonProcessingException e) {
            // List<String> is trivially serializable; an exception here is a JVM-level bug, not a caller problem.
            throw new IllegalStateException("Failed to serialise allowedRoles to JSON", e);
        }
    }
}
