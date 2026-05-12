package com.vn.agent.view.chat;

import com.vaadin.flow.shared.Registration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatSessionStateTest {

    @Test
    void setterAndGetterTrackCurrentConversationId() {
        AiChatSessionState state = new AiChatSessionState();
        UUID conversationId = UUID.randomUUID();

        state.setCurrentConversationId(conversationId);

        assertThat(state.getCurrentConversationId()).isEqualTo(conversationId);
    }

    @Test
    void multipleListenersReceiveUpdates() {
        AiChatSessionState state = new AiChatSessionState();
        List<UUID> firstListenerUpdates = new ArrayList<>();
        List<UUID> secondListenerUpdates = new ArrayList<>();
        UUID conversationId = UUID.randomUUID();

        state.addConversationIdChangeListener(firstListenerUpdates::add);
        state.addConversationIdChangeListener(secondListenerUpdates::add);

        state.setCurrentConversationId(conversationId);

        assertThat(firstListenerUpdates).containsExactly(conversationId);
        assertThat(secondListenerUpdates).containsExactly(conversationId);
    }

    @Test
    void unregisterPreventsLaterCallbacks() {
        AiChatSessionState state = new AiChatSessionState();
        List<UUID> updates = new ArrayList<>();
        UUID firstConversationId = UUID.randomUUID();
        UUID secondConversationId = UUID.randomUUID();

        Registration registration = state.addConversationIdChangeListener(updates::add);

        state.setCurrentConversationId(firstConversationId);
        registration.remove();
        state.setCurrentConversationId(secondConversationId);

        assertThat(updates).containsExactly(firstConversationId);
    }

    @Test
    void clearingWithNullNotifiesListeners() {
        AiChatSessionState state = new AiChatSessionState();
        List<UUID> updates = new ArrayList<>();

        state.addConversationIdChangeListener(updates::add);
        state.setCurrentConversationId(UUID.randomUUID());
        state.setCurrentConversationId(null);

        assertThat(updates).hasSize(2);
        assertThat(updates.get(1)).isNull();
        assertThat(state.getCurrentConversationId()).isNull();
    }

    // ---- Phase 15 OBS-04: no per-turn accumulation in session/UI-scoped state (15-REVIEWS #10) ----

    /**
     * {@code AiChatSessionState} must still hold exactly {@code {currentConversationId,
     * listenerRegistrations}} — Phase 15 added no field and no per-turn-growing collection. The
     * per-turn step list ({@code liveTurnSteps}, capped 50) lives on the {@code ChatPanelFragment}
     * INSTANCE, not here.
     */
    @Test
    void sessionStateHoldsExactlyConversationIdAndListeners_noPerTurnAccumulation() {
        Set<String> nonStaticFields = Arrays.stream(AiChatSessionState.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());
        assertThat(nonStaticFields)
                .as("AiChatSessionState must still hold exactly {currentConversationId, listenerRegistrations}")
                .containsExactlyInAnyOrder("currentConversationId", "listenerRegistrations");

        // The only collection (listenerRegistrations) is a listener registry — it grows/shrinks
        // with subscribers, NOT with chat turns. No additional collection-typed field exists.
        long collectionFieldCount = Arrays.stream(AiChatSessionState.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> Collection.class.isAssignableFrom(f.getType())
                        || Map.class.isAssignableFrom(f.getType())
                        || f.getType().isArray())
                .count();
        assertThat(collectionFieldCount)
                .as("AiChatSessionState must have exactly one collection field (the listener registry)")
                .isEqualTo(1);
    }

    /**
     * The sidebar open-state holder lives on the per-UI {@code ChatSurfaceMounter.MountedChatSurfaceState}
     * (Plan 03 — NOT {@code AiChatSessionState}, NOT {@code AiChatUIState}). It must declare only the
     * fixed sidebar fields ({@code sidebarToggleButton}, {@code sidebarPanelDiv}, {@code sidebarHostView},
     * {@code sidebarOpen}, plus the pre-existing {@code chatButton}/{@code missingAppLayoutWarned}) —
     * NO {@code List}/{@code Collection}/{@code Map}/array field that could grow per chat turn.
     */
    @Test
    void sidebarOpenStateHolderHasOnlyFixedFields_noPerTurnCollection() throws Exception {
        Class<?> mountedState = Class.forName(
                "com.vn.agent.view.chat.ChatSurfaceMounter$MountedChatSurfaceState");
        Field[] declared = mountedState.getDeclaredFields();
        for (Field f : declared) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            assertThat(Collection.class.isAssignableFrom(f.getType())
                    || Map.class.isAssignableFrom(f.getType())
                    || f.getType().isArray())
                    .as("MountedChatSurfaceState.%s is %s — the sidebar open-state holder must not gain "
                            + "a per-turn-growing collection (OBS-04)", f.getName(), f.getType())
                    .isFalse();
        }
        // The sidebar fields Plan 03 added are present (and only those, plus the pre-existing two).
        Set<String> fieldNames = Arrays.stream(declared)
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());
        assertThat(fieldNames).contains(
                "sidebarToggleButton", "sidebarPanelDiv", "sidebarHostView", "sidebarOpen");
        assertThat(fieldNames)
                .as("MountedChatSurfaceState must not gain fields beyond the chat button + the fixed "
                        + "sidebar fields")
                .containsExactlyInAnyOrder(
                        "chatButton", "missingAppLayoutWarned",
                        "sidebarToggleButton", "sidebarPanelDiv", "sidebarHostView", "sidebarOpen");
    }
}
