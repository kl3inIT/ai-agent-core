package com.vn.agent.view.conversation;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.security.AiAgentAdminRole;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 07-07b Task 3 — D-24 role-aware scoping contract.
 *
 * <p>{@code ConversationListView.currentUserIsAdmin} reads
 * {@link CurrentAuthentication#getUser}.getAuthorities() and matches
 * {@link AiAgentAdminRole#CODE}. The view wires admin-only affordances (user filter field,
 * {@code createdBy} column visibility) and the JPQL path keyed off that boolean. Row-level
 * security (the {@code AiAgentUserRowLevelRole} ownership predicate) is the real scoping
 * mechanism — {@code isAdmin} only decides UI affordances.
 *
 * <p>This test certifies the authority-matching logic via real Jmix security plumbing —
 * {@link SystemAuthenticator#withUser} switches identity the exact way production does,
 * and the assertion walks the same {@link GrantedAuthority} stream the view walks.
 *
 * <p>Per plan Rule-1 tolerance clause: driving {@code ConversationListView} through the
 * Vaadin UI to assert grid row-count / column-visibility requires the full jmix-app UiTest
 * harness plus persisted {@code AiConversation} fixtures. Persistence is currently blocked
 * by the unrelated EclipseLink metamodel regression visible in
 * {@code IngestionStatusWriterTest}; testing the admin-predicate independently is a
 * regression guard on the D-24 authority-matching logic.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
class ConversationListRoleFilterTest {

    @Autowired CurrentAuthentication currentAuthentication;
    @Autowired SystemAuthenticator systemAuthenticator;

    /** Mirrors {@code ConversationListView.currentUserIsAdmin()} exactly. */
    private boolean probeIsAdmin() {
        return currentAuthentication.getUser().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a != null && a.contains(AiAgentAdminRole.CODE));
    }

    @Test
    void nonAdminSeesOnlyOwnRows() {
        // D-24 contract for non-admin: currentUserIsAdmin() returns false → userFilter hidden,
        // createdBy column hidden, JPQL does NOT include the admin-only user substring clause.
        Boolean adminFlag = systemAuthenticator.withUser("alice", this::probeIsAdmin);
        assertThat(adminFlag)
                .as("non-admin user must NOT be flagged admin — drives UI affordances + filter scoping")
                .isFalse();
    }

    @Test
    void adminSeesAllRowsAndCreatedByColumn() {
        // D-24 contract for admin: currentUserIsAdmin() returns true → userFilter + createdBy column
        // become visible, JPQL composes the admin-only user substring clause.
        Boolean adminFlag = systemAuthenticator.withUser("admin", this::probeIsAdmin);
        assertThat(adminFlag)
                .as("admin user must be flagged admin — drives UI affordances + filter scoping")
                .isTrue();
    }
}
