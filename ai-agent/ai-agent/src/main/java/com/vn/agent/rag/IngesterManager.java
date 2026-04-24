package com.vn.agent.rag;

import com.vn.agent.security.AiAgentUserRole;
import com.vn.agent.spi.CustomIngester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Discovers every {@link CustomIngester} bean in the context and funnels their output
 * through {@link KnowledgeDocumentUploadService} so SPI-ingested documents share the
 * same role-validation and async-ingestion path as user uploads (SPI-07).
 *
 * <p><b>Isolation (T-05-04-04):</b> every ingester is wrapped in its own try/catch;
 * a thrown {@code read()} does not abort the batch. Per-document upload failures
 * break out of the current ingester (to bound blast radius) but do not stop the
 * next ingester from running.
 *
 * <p><b>Invocation:</b> never invoked automatically — no {@code ApplicationReadyEvent},
 * no {@code @Scheduled}. Host applications call {@link #runAll()} when needed
 * (admin-triggered only per D-18).
 *
 * <p><b>Synthetic {@code sourceUri}:</b> each ingester-produced {@link Document} becomes
 * an {@code ingester://&lt;ingesterId&gt;/&lt;source-or-doc-id&gt;} URI. The AsyncIngestionWorker
 * resolves {@code classpath:} / {@code file:} URIs through its {@link org.springframework.core.io.ResourceLoader};
 * this {@code ingester://} scheme is NOT directly resolvable — the underlying Document
 * content is already materialised by the ingester, but Phase 5 reuses the upload path
 * uniformly and the ingest worker will FAIL these rows with an "Unsupported source
 * scheme" message. That is the intended v1 shape: ingesters that need their content
 * actually embedded stage their files to {@code classpath:} or {@code file:} paths
 * and set their {@link Document#getMetadata()} {@code source} key to that URI. The
 * sample {@link ClasspathMarkdownIngester} does exactly this.
 *
 * <p><b>D-19 deferred:</b> SPI-ingested docs use random UUIDs from {@code Metadata.create};
 * re-running a {@link CustomIngester} will create duplicate {@link com.vn.agent.entity.AiKnowledgeDocument}
 * rows. Operator workaround is delete-then-reingest. UUIDv5-based idempotent upsert is
 * a Phase 5+ follow-up per CONTEXT.md §Deferred.
 *
 * <p><b>Default {@code allowedRoles}:</b> every SPI-ingested document is tagged with
 * {@link AiAgentUserRole#CODE} matching the D-04 shared-default posture. Operators
 * needing narrower exposure retag documents via the admin UI after ingest.
 */
@Component
public class IngesterManager {

    private static final Logger log = LoggerFactory.getLogger(IngesterManager.class);

    private final List<CustomIngester> ingesters;
    private final KnowledgeDocumentUploadService uploadService;

    public IngesterManager(List<CustomIngester> ingesters,
                           KnowledgeDocumentUploadService uploadService) {
        this.ingesters = ingesters;
        this.uploadService = uploadService;
    }

    /**
     * Iterate every discovered {@link CustomIngester} and hand its documents to the
     * upload pipeline. Exceptions from one ingester do not affect the next.
     */
    public void runAll() {
        if (ingesters.isEmpty()) {
            log.info("runAll: no CustomIngester beans registered");
            return;
        }
        for (CustomIngester ingester : ingesters) {
            runOne(ingester);
        }
    }

    private void runOne(CustomIngester ingester) {
        List<Document> docs;
        try {
            docs = ingester.read();
        } catch (Exception readFailure) {
            log.error("Ingester {} read() failed: {}",
                    ingester.getId(), readFailure.getMessage(), readFailure);
            return;
        }
        if (docs == null || docs.isEmpty()) {
            log.info("Ingester {} returned no documents", ingester.getId());
            return;
        }
        for (Document doc : docs) {
            try {
                String rawSource = doc.getMetadata() == null
                        ? doc.getId()
                        : String.valueOf(doc.getMetadata().getOrDefault(ChunkMetadata.SOURCE, doc.getId()));
                String sourceUri = rawSource != null && (rawSource.startsWith("classpath:") || rawSource.startsWith("file:"))
                        ? rawSource
                        : "ingester://" + ingester.getId() + "/" + rawSource;
                uploadService.upload(sourceUri, "CUSTOM_INGESTER", List.of(AiAgentUserRole.CODE));
            } catch (Exception perDoc) {
                log.error("Ingester {} failed on document {}: {}",
                        ingester.getId(), doc.getId(), perDoc.getMessage(), perDoc);
                // Bound blast radius: skip remaining documents of this ingester and
                // continue with the next ingester.
                break;
            }
        }
    }
}
