package com.vn.agent.extraction;

/**
 * Textual evidence extracted from a source attached to an extraction turn.
 *
 * @param name      source display name, usually a task-file filename
 * @param text      extracted text body
 * @param truncated whether the source text was truncated before model input
 */
public record ExtractionSourceText(String name, String text, boolean truncated) {
}
