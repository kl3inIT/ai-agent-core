package com.vn.agent.orchestration;

import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class BaselineContextProviderTest {

    @Test
    void compose_populates_all_baseline_keys() {
        CurrentAuthentication ca = Mockito.mock(CurrentAuthentication.class);
        Mockito.when(ca.getUser()).thenReturn(new User("alice", "x", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        Mockito.when(ca.getLocale()).thenReturn(Locale.US);

        UUID convId = UUID.randomUUID();
        Map<String, Object> ctx = new BaselineContextProvider(ca).compose(convId);

        assertThat(ctx).containsKeys("agent.userId", "agent.username", "agent.roles", "agent.locale", "agent.conversationId");
        assertThat(ctx.get("agent.username")).isEqualTo("alice");
        assertThat(ctx.get("agent.roles")).isEqualTo(Set.of("ROLE_USER"));
        assertThat(ctx.get("agent.locale")).isEqualTo("en_US");
        assertThat(ctx.get("agent.conversationId")).isEqualTo(convId.toString());
    }

    @Test
    void compose_handles_anonymous_user_gracefully() {
        CurrentAuthentication ca = Mockito.mock(CurrentAuthentication.class);
        Mockito.when(ca.getUser()).thenReturn(null);
        Mockito.when(ca.getLocale()).thenReturn(null);

        Map<String, Object> ctx = new BaselineContextProvider(ca).compose(null);
        assertThat(ctx.get("agent.username")).isEqualTo("");
        assertThat(ctx.get("agent.roles")).isEqualTo(Set.of());
        assertThat(ctx.get("agent.locale")).isEqualTo(Locale.ROOT.toString());
        assertThat(ctx.get("agent.conversationId")).isNull();
    }

    @Test
    void composeRendersAsTextWithSortedAgentKeys() {
        // Verifies renderAsText() sorts keys alphabetically and joins with \n.
        // Consumed by DefaultChatServiceImpl (Plan 04-04) - byte-stable output is required.
        CurrentAuthentication ca = Mockito.mock(CurrentAuthentication.class);
        Mockito.when(ca.getUser()).thenReturn(new User("bob", "x", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        Mockito.when(ca.getLocale()).thenReturn(Locale.US);

        UUID convId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String text = new BaselineContextProvider(ca).renderAsText(convId);

        // Every line begins with "agent." prefix
        for (String line : text.split("\n")) {
            assertThat(line).startsWith("agent.");
        }
        // Deterministic alphabetical key order: conversationId, locale, roles, userId, username
        String[] lines = text.split("\n");
        assertThat(lines).hasSize(5);
        assertThat(lines[0]).startsWith("agent.conversationId=");
        assertThat(lines[1]).startsWith("agent.locale=");
        assertThat(lines[2]).startsWith("agent.roles=");
        assertThat(lines[3]).startsWith("agent.userId=");
        assertThat(lines[4]).startsWith("agent.username=");
        // Sample value sanity
        assertThat(text).contains("agent.username=bob");
        assertThat(text).contains("agent.conversationId=00000000-0000-0000-0000-000000000001");
    }
}
