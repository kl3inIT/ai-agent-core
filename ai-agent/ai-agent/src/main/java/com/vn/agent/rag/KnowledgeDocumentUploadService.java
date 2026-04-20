package com.vn.agent.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiKnowledgeDocumentStatus;
import com.vn.agent.rag.config.AiAgentEmbeddingProperties;
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

    private final Metadata metadata;
    private final DataManager dataManager;
    private final ResourceRoleRepository roleRepository;
    private final AsyncIngestionWorker asyncIngestionWorker;
    private final AiAgentEmbeddingProperties embeddingProperties;

    public KnowledgeDocumentUploadService(Metadata metadata,
                                          DataManager dataManager,
                                          ResourceRoleRepository roleRepository,
                                          AsyncIngestionWorker asyncIngestionWorker,
                                          AiAgentEmbeddingProperties embeddingProperties) {
        this.metadata = metadata;
        this.dataManager = dataManager;
        this.roleRepository = roleRepository;
        this.asyncIngestionWorker = asyncIngestionWorker;
        this.embeddingProperties = embeddingProperties;
    }

    /**
     * Validates role codes, persists a PENDING {@link AiKnowledgeDocument}, and
     * schedules async ingestion after commit.
     *
     * @param sourceUri    Spring {@code ResourceLoader}-resolvable URI (see class Javadoc)
     * @param sourceKind   free-form label (MIME type or synthetic marker); nullable
     * @param allowedRoles Jmix role codes granting retrieval visibility; nullable (treated as empty)
     * @return the persisted document (with {@link AiKnowledgeDocument#getId()} populated)
     * @throws UnknownRoleCodeException if any supplied code is not defined in {@link ResourceRoleRepository}
     */
    @Transactional
    public AiKnowledgeDocument upload(String sourceUri, String sourceKind, Collection<String> allowedRoles) {
        Objects.requireNonNull(sourceUri, "sourceUri must not be null");

        List<String> roles = allowedRoles == null ? List.of() : new ArrayList<>(allowedRoles);

        // D-07 fail-closed role validation — every code MUST resolve, unknown codes abort.
        for (String code : roles) {
            ResourceRole role = roleRepository.findRoleByCode(code);
            if (role == null) {
                throw new UnknownRoleCodeException(code);
            }
        }

        // PATTERNS: entity instantiation via Metadata.create (constructor path is forbidden).
        AiKnowledgeDocument document = metadata.create(AiKnowledgeDocument.class);
        document.setFileName(sourceUri);
        document.setMimeType(sourceKind);
        document.setAllowedRolesJson(writeRolesJson(roles));
        document.setStatus(AiKnowledgeDocumentStatus.PENDING);
        // Note: embeddingModel is not a column on the Phase 2 entity — it is carried on each
        // chunk via ChunkMetadata.EMBEDDING_MODEL during enrichment in AsyncIngestionWorker.
        // The call to embeddingProperties.resolvedModel() below is kept for future use and to
        // document the coupling; it is intentionally not persisted on the entity.
        embeddingProperties.resolvedModel();

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

    private static String writeRolesJson(List<String> roles) {
        try {
            return JSON.writeValueAsString(roles);
        } catch (JsonProcessingException e) {
            // List<String> is trivially serializable; an exception here is a JVM-level bug, not a caller problem.
            throw new IllegalStateException("Failed to serialise allowedRoles to JSON", e);
        }
    }
}
