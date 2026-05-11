package com.vn.agent.extraction;

import org.springframework.ai.content.Media;

import java.util.List;
import java.util.UUID;

/**
 * Input passed to a host {@code IntentExtractor}.
 *
 * @param intentId       selected named intent
 * @param conversationId source conversation for the extraction turn
 * @param userMessage    nullable user text submitted with the turn
 * @param taskFileIds    task-file ids considered for the turn; empty when no files are active
 * @param taskFileMedia  Spring AI media resolved for active task files; empty when no media is active
 * @param sourceTexts    textual evidence extracted from source files; empty when no text is active
 */
public record ExtractionInput(String intentId,
                              UUID conversationId,
                              String userMessage,
                              List<UUID> taskFileIds,
                              List<Media> taskFileMedia,
                              List<ExtractionSourceText> sourceTexts) {

    public ExtractionInput(String intentId,
                           UUID conversationId,
                           String userMessage,
                           List<UUID> taskFileIds,
                           List<Media> taskFileMedia) {
        this(intentId, conversationId, userMessage, taskFileIds, taskFileMedia, List.of());
    }

    public ExtractionInput {
        taskFileIds = taskFileIds == null ? List.of() : List.copyOf(taskFileIds);
        taskFileMedia = taskFileMedia == null ? List.of() : List.copyOf(taskFileMedia);
        sourceTexts = sourceTexts == null ? List.of() : List.copyOf(sourceTexts);
    }
}
