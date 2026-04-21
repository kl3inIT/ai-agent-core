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
 * <p>Asserts that {@code messages.properties} (EN) and {@code messages_vi.properties} (VI)
 * bundles carry identical key sets — any drift would silently fall back to EN for
 * Vietnamese users on a Phase 7 view (UI-08 / UI-09).
 *
 * <p>Also sanity-checks that the Phase 7 UI prefixes (chatView., conversationList., …)
 * are present in the EN bundle so a rename there does not quietly dissolve a whole view's
 * i18n. The VI side is covered by the identical-key-set assertion.
 */
class LocaleParityTest {

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
        Properties en = load("/com/vn/agent/messages_en.properties");
        Properties vi = load("/com/vn/agent/messages_vi.properties");

        Set<Object> enKeys = new HashSet<>(en.keySet());
        enKeys.removeIf(LocaleParityTest::isLocaleSpecificKey);
        Set<Object> viKeys = new HashSet<>(vi.keySet());
        viKeys.removeIf(LocaleParityTest::isLocaleSpecificKey);

        Set<Object> enOnly = new HashSet<>(enKeys);
        enOnly.removeAll(viKeys);
        Set<Object> viOnly = new HashSet<>(viKeys);
        viOnly.removeAll(enKeys);

        assertThat(new TreeSet<>(enOnly))
                .as("Keys present in messages.properties but missing from messages_vi.properties")
                .isEmpty();
        assertThat(new TreeSet<>(viOnly))
                .as("Keys present in messages_vi.properties but missing from messages.properties")
                .isEmpty();
        assertThat(enKeys).as("EN bundle must have non-locale-display keys").isNotEmpty();
    }

    @Test
    void allPhase7KeysPresent() throws IOException {
        Properties en = load("/com/vn/agent/messages_en.properties");

        List<String> requiredPrefixes = List.of(
                "chatView.",
                "conversationList.",
                "conversationDetail.",
                "parametersList.",
                "parametersDetail.",
                "knowledgeBase.",
                "auditList.");

        for (String prefix : requiredPrefixes) {
            boolean hasKey = en.keySet().stream()
                    .anyMatch(k -> ((String) k).startsWith(prefix));
            assertThat(hasKey)
                    .as("At least one key with prefix %s must exist in messages.properties", prefix)
                    .isTrue();
        }
    }
}
