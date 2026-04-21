package com.vn.agent.i18n;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E-11: Locale parity for every Phase-6 Jmix message key.
 *
 * <p>The add-on ships two bundles — {@code messages.properties} (EN) and {@code messages_vi.properties}
 * (VI). Any {@code ai-agent.guard.*} or {@code ai-agent.parameters.*} key present in EN MUST also
 * exist in VI with a non-blank value and vice versa. Drift here would mean a Vietnamese host
 * silently falling back to English for guard-denial messages (T-06-02 opacity regression).
 *
 * <p>Also asserts the {@code AiToolCallOutcome.FLAGGED} key lands in both bundles (Plan 06-01).
 */
@Tag("eval")
class I18nParityTest {

    private static final String BUNDLE_BASENAME = "com/vn/agent/messages";

    @Test
    void everyPhase6KeyExistsInBothLocales() {
        ResourceBundle en = ResourceBundle.getBundle(BUNDLE_BASENAME, Locale.ENGLISH);
        ResourceBundle vi = ResourceBundle.getBundle(BUNDLE_BASENAME, new Locale("vi"));

        Set<String> enPhase6 = keysMatching(en, "ai-agent.guard.", "ai-agent.parameters.");
        Set<String> viPhase6 = keysMatching(vi, "ai-agent.guard.", "ai-agent.parameters.");

        assertThat(enPhase6)
                .as("Phase 6 message keys drift between EN and VI: %s", symmetricDiff(enPhase6, viPhase6))
                .isEqualTo(viPhase6);
        assertThat(enPhase6).as("Phase 6 key set must be non-empty").isNotEmpty();

        for (String key : enPhase6) {
            String enValue = en.getString(key);
            String viValue = vi.getString(key);
            assertThat(enValue).as("EN value for %s must be non-blank", key).isNotBlank();
            assertThat(viValue).as("VI value for %s must be non-blank", key).isNotBlank();
        }
    }

    @Test
    void flaggedEnumKeyExistsInBothLocales() {
        ResourceBundle en = ResourceBundle.getBundle(BUNDLE_BASENAME, Locale.ENGLISH);
        ResourceBundle vi = ResourceBundle.getBundle(BUNDLE_BASENAME, new Locale("vi"));
        String key = "com.vn.agent.entity/AiToolCallOutcome.FLAGGED";
        assertThat(en.getString(key)).isNotBlank();
        assertThat(vi.getString(key)).isNotBlank();
    }

    private static Set<String> keysMatching(ResourceBundle bundle, String... prefixes) {
        return bundle.keySet().stream()
                .filter(k -> {
                    for (String p : prefixes) {
                        if (k.startsWith(p)) {
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> symmetricDiff(Set<String> a, Set<String> b) {
        Set<String> diff = new TreeSet<>(a);
        diff.removeAll(b);
        Set<String> otherWay = new TreeSet<>(b);
        otherWay.removeAll(a);
        diff.addAll(otherWay);
        return diff;
    }
}
