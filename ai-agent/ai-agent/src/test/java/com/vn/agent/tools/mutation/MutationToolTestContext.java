package com.vn.agent.tools.mutation;

import com.vn.agent.orchestration.RunContext;
import io.jmix.core.security.SystemAuthenticator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Plan 11-10 Task 0 — single helper used by every TEST-10..12 mutation-tool test to wrap a
 * direct tool invocation in BOTH a Jmix authentication context AND a deterministic
 * {@link RunContext}. The Phase-11 mutation tools require both: {@code BuiltInMutationTools}
 * reads {@link io.jmix.core.security.CurrentAuthentication#getUser} for {@code userUsername}
 * and reads {@link RunContext#getConversationId()} when reserving the dedup row. Tests that
 * forget either context surface as confusing NPE / "no current user" failures.
 *
 * <p>Wraps {@link SystemAuthenticator#withUser(String, java.util.concurrent.Callable)} and
 * sets a fresh {@link RunContext} (run id + conversation id + root audit id) before the
 * supplier runs, then clears the {@code RunContext} in a {@code finally}. Authentication is
 * cleared by {@code SystemAuthenticator} when its callback returns.
 *
 * <p>Does NOT manufacture authorities; relies on the test users seeded by
 * {@link MutationToolTestUsersConfiguration}.
 */
@Component
public class MutationToolTestContext {

    private final SystemAuthenticator systemAuthenticator;

    @Autowired
    public MutationToolTestContext(SystemAuthenticator systemAuthenticator) {
        this.systemAuthenticator = systemAuthenticator;
    }

    /**
     * Run {@code action} as {@code username} with a fresh {@link RunContext}.
     *
     * @param username one of {@code mutation-admin}, {@code mutation-user},
     *                 {@code mutation-no-marker}, {@code mutation-fake-marker}
     */
    public <T> T withMutationRun(String username, Supplier<T> action) {
        // SystemAuthenticator.withUser expects an AuthenticatedOperation<T> (a SAM interface
        // whose single method returns T). The lambda body sets/clears RunContext around the
        // supplied action. Returning action.get() from the lambda yields the operation result.
        return systemAuthenticator.withUser(username, () -> {
            UUID runId = UUID.randomUUID();
            UUID conversationId = UUID.randomUUID();
            UUID rootAuditId = UUID.randomUUID();
            RunContext.set(runId);
            RunContext.setConversationId(conversationId);
            RunContext.setRootAuditId(rootAuditId);
            try {
                return action.get();
            } finally {
                RunContext.clear();
            }
        });
    }
}
