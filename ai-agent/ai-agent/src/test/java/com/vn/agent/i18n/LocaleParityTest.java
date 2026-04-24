package com.vn.agent.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 07-07b Task 1 — pure-unit locale-parity contract.
 *
 * <p>Asserts that EN and VI bundles carry identical key sets in every location —
 * root package and each per-view package bundle. Drift between locales would silently
 * fall back to EN for Vietnamese users on a Phase 7 view (UI-08 / UI-09).
 *
 * <p>Also sanity-checks that the Phase 7 UI prefixes are present in at least one
 * bundle so a rename there does not quietly dissolve a whole view's i18n.
 */
class LocaleParityTest {

    /** Every (en, vi) bundle pair that must agree on key sets. */
    private static final List<BundlePair> BUNDLES = List.of(
            new BundlePair("root", "/com/vn/agent/messages_en.properties", "/com/vn/agent/messages_vi.properties"),
            new BundlePair("view/knowledge",
                    "/com/vn/agent/view/knowledge/messages_en.properties",
                    "/com/vn/agent/view/knowledge/messages_vi.properties")
    );

    private record BundlePair(String name, String enResource, String viResource) {
    }

    private static Properties load(String resource) throws IOException {
        Properties props = new Properties();
        try (InputStream in = LocaleParityTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("bundle %s must exist on test classpath", resource).isNotNull();
            // Both bundles are stored as UTF-8 on this project; Properties.load(Reader) is safe.
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }

    /**
     * Jmix convention reserves {@code localeDisplayName.<locale>} for the locale's own
     * display name — by design each bundle carries only its own key (EN carries
     * {@code localeDisplayName.en}, VI carries {@code localeDisplayName.vi}). Excluded
     * from parity to avoid asserting against that intentional asymmetry.
     */
    private static boolean isLocaleSpecificKey(Object key) {
        return ((String) key).startsWith("localeDisplayName.");
    }

    @Test
    void bundlesHaveIdenticalKeySets() throws IOException {
        for (BundlePair pair : BUNDLES) {
            Properties en = load(pair.enResource());
            Properties vi = load(pair.viResource());

            Set<Object> enKeys = new HashSet<>(en.keySet());
            enKeys.removeIf(LocaleParityTest::isLocaleSpecificKey);
            Set<Object> viKeys = new HashSet<>(vi.keySet());
            viKeys.removeIf(LocaleParityTest::isLocaleSpecificKey);

            Set<Object> enOnly = new HashSet<>(enKeys);
            enOnly.removeAll(viKeys);
            Set<Object> viOnly = new HashSet<>(viKeys);
            viOnly.removeAll(enKeys);

            assertThat(new TreeSet<>(enOnly))
                    .as("[%s] keys present in EN but missing from VI", pair.name())
                    .isEmpty();
            assertThat(new TreeSet<>(viOnly))
                    .as("[%s] keys present in VI but missing from EN", pair.name())
                    .isEmpty();
            assertThat(enKeys).as("[%s] EN bundle must have non-locale-display keys", pair.name()).isNotEmpty();
        }
    }

    @Test
    void allPhase7PrefixesPresent() throws IOException {
        // Each entry: the EN resource + a prefix expected to exist in it. Prefixes are
        // checked against the bundle they belong to after the per-view migration.
        List<PrefixCheck> checks = List.of(
                new PrefixCheck("/com/vn/agent/messages_en.properties", "chatView."),
                new PrefixCheck("/com/vn/agent/messages_en.properties", "conversationList."),
                new PrefixCheck("/com/vn/agent/messages_en.properties", "conversationDetail."),
                new PrefixCheck("/com/vn/agent/messages_en.properties", "parametersList."),
                new PrefixCheck("/com/vn/agent/messages_en.properties", "parametersDetail."),
                new PrefixCheck("/com/vn/agent/messages_en.properties", "auditList."),
                // Knowledge base migrated to per-view bundle — prefix lookup adjusted.
                new PrefixCheck("/com/vn/agent/view/knowledge/messages_en.properties", "confirm.")
        );

        for (PrefixCheck check : checks) {
            Properties en = load(check.resource());
            boolean hasKey = en.keySet().stream()
                    .anyMatch(k -> ((String) k).startsWith(check.prefix()));
            assertThat(hasKey)
                    .as("At least one key with prefix '%s' must exist in %s", check.prefix(), check.resource())
                    .isTrue();
        }
    }

    private record PrefixCheck(String resource, String prefix) {
    }
}
