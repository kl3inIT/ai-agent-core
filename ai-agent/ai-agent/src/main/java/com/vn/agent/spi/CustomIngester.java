package com.vn.agent.spi;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Host extension point to plug in custom knowledge-base ingestion sources
 * (S3, Confluence, SharePoint, classpath markdown, etc.).
 * <p>Phase 5 wires {@code IngesterManager} which invokes every bean of this type;
 * Phase 2 ships the interface only.</p>
 *
 * <p><b>Contract:</b> {@link #getId()} must be stable across restarts (it is used as the
 * source key for re-ingest / delete operations). {@link #read()} is invoked on the
 * ingestion worker thread — implementations may block on I/O.</p>
 */
public interface CustomIngester {
    /** @return stable identifier, surfaced in admin UI. */
    String getId();

    /** @return human-readable label for admin UI. */
    String getDisplayName();

    /** Pull documents from the source; Phase 5 splits + embeds + writes to the vector store. */
    List<Document> read();
}
