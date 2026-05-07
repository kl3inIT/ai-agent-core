/**
 * Phase 13 task-file pathway — attached chat files surfaced to the LLM as
 * Spring AI {@link org.springframework.ai.content.Media} for images and bounded
 * extracted text blocks for documents.
 *
 * <p><b>STRUCTURAL INVARIANT (TEST-16):</b> No source file in this package may
 * reference any of the following — task-file content NEVER reaches the RAG
 * pathway. <b>DO NOT REFERENCE:</b>
 * <ul>
 *   <li>{@code IngesterManager}</li>
 *   <li>{@code VectorStore}</li>
 *   <li>{@code RetrievalAugmentationAdvisor}</li>
 *   <li>{@code TokenTextSplitter}</li>
 *   <li>Anything from {@code com.vn.agent.rag.**}</li>
 * </ul>
 * Enforced by {@code TaskFileNoVectorStoreSourceScannerTest} (Wave 4). The
 * scanner strips this JavaDoc DO-NOT-REFERENCE block via the same allowlist
 * helper that the per-plan grep gates exclude (REVIEWS HIGH-8) — only this
 * {@code package-info.java} may name the forbidden tokens, and only inside
 * JavaDoc.
 *
 * <p>The task-file pathway is structurally disjoint from KB ingestion: a chat
 * file lives only as bytes in {@link io.jmix.core.FileStorage} plus a metadata
 * row in {@code AI_TASK_FILE} (agentstore); the resolver streams image bytes
 * straight into a multimodal {@code Media} payload and extracts bounded text
 * for non-image documents on EVERY user turn for the conversation (Phase 13.1
 * RES-01 per-turn-all), subject to the {@code perTurnMaxFiles} /
 * {@code perTurnMaxTotalBytes} budget caps in
 * {@link com.vn.agent.taskfile.AiTaskFileProperties}. The document extraction
 * path remains local to this package and must not route through KB ingestion,
 * vector storage, chunking, or retrieval advisors.
 */
package com.vn.agent.taskfile;
