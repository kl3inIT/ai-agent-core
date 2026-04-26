package com.vn.agent.spi;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FetchPlanContextTest {

    @Test
    void userSnapshotSortsAndCopiesRoles() {
        Set<String> roles = new LinkedHashSet<>();
        roles.add("ROLE_B");
        roles.add("ROLE_A");

        FetchPlanContext.UserSnapshot snapshot = new FetchPlanContext.UserSnapshot("alice", roles);
        roles.add("ROLE_C");

        assertThat(snapshot.username()).isEqualTo("alice");
        assertThat(snapshot.roles()).containsExactly("ROLE_A", "ROLE_B");
        assertThatThrownBy(() -> snapshot.roles().add("ROLE_D"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void contextDefaultsNullLocaleAndUserToSafeValues() {
        FetchPlanContext context = new FetchPlanContext(
                UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, null);

        assertThat(context.locale()).isEqualTo(Locale.ROOT);
        assertThat(context.user().username()).isEmpty();
        assertThat(context.user().roles()).isEmpty();
    }
}
