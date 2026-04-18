package com.vn.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ChatResponse} record — verifies the compact-constructor
 * null guards required by plan 01-02 Task 1.
 */
class ChatResponseTest {

    @Test
    void acceptsNonNullFields() {
        ChatResponse r = new ChatResponse("hi", Map.of("k", "v"));
        assertEquals("hi", r.content());
        assertEquals("v", r.metadata().get("k"));
    }

    @Test
    void acceptsEmptyMetadata() {
        ChatResponse r = new ChatResponse("", Map.of());
        assertEquals("", r.content());
        assertEquals(0, r.metadata().size());
    }

    @Test
    void rejectsNullContent() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChatResponse(null, Map.of()));
    }

    @Test
    void rejectsNullMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChatResponse("x", null));
    }
}
