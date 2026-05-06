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
 * <p>Asserts that {@code messages_en.properties} (EN) and {@code messages_vi.properties} (VI)
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
                .as("Keys present in messages_en.properties but missing from messages_vi.properties")
                .isEmpty();
        assertThat(new TreeSet<>(viOnly))
                .as("Keys present in messages_vi.properties but missing from messages_en.properties")
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
                    .as("At least one key with prefix %s must exist in messages_en.properties", prefix)
                    .isTrue();
        }
    }

    @Test
    void allPhase12KeysPresentInBothBundles() throws IOException {
        Properties en = load("/com/vn/agent/messages_en.properties");
        Properties vi = load("/com/vn/agent/messages_vi.properties");

        List<String> requiredKeys = List.of(
                "com.vn.agent.entity/AiChatSurface",
                "com.vn.agent.entity/AiChatSurface.FULL_ROUTE",
                "com.vn.agent.entity/AiChatSurface.HEADER_BUTTON",
                "com.vn.agent.entity/AiUiSettings",
                "com.vn.agent.entity/AiUiSettings.enabledSurfaceIds",
                "com.vn.agent.entity/AiUiSettings.defaultSurface",
                "com.vn.agent/menu.uiSettings",
                "chatView.action.editTitle",
                "chatView.editTitle.dialog.header",
                "chatView.editTitle.field.title",
                "chatView.editTitle.validation.required",
                "chatView.fullRouteDisabled",
                "chatDialogView.title",
                "chatDialogView.close.label",
                "chatSurfaceMounter.headerButton.ariaLabel",
                "aiUiSettingsDetail.title",
                "aiUiSettingsDetail.field.enabledSurfaces",
                "aiUiSettingsDetail.field.enabledSurfaces.helper",
                "aiUiSettingsDetail.field.defaultSurface",
                "aiUiSettingsDetail.field.defaultSurface.helper",
                "aiUiSettingsDetail.action.save",
                "aiUiSettingsDetail.validation.enabledSurfacesRequired",
                "aiUiSettingsDetail.validation.defaultSurfaceRequired",
                "aiUiSettingsDetail.validation.defaultSurfaceEnabled");

        for (String key : requiredKeys) {
            assertThat(en.containsKey(key))
                    .as("Phase 12 key %s must exist in messages_en.properties", key)
                    .isTrue();
            assertThat(vi.containsKey(key))
                    .as("Phase 12 key %s must exist in messages_vi.properties", key)
                    .isTrue();
        }
    }

    /**
     * Phase 13.1 Plan 07 — REQ-9 / I18N-01 attachments-key parity gate.
     *
     * <p>Plan 13.1-04 added 13 new {@code chatView.attachments.*} keys (plus the
     * {@code AiMessageRole.NOTICE} enum caption from Plan 13.1-01) to the EN bundle
     * and the VI bundle. This test pins the 14 keys in BOTH bundles so a unilateral
     * key add (or a typo on one side) silently regressing one locale is caught.
     */
    @Test
    void allPhase13Dot1AttachmentsKeysPresentInBothBundles() throws IOException {
        Properties en = load("/com/vn/agent/messages_en.properties");
        Properties vi = load("/com/vn/agent/messages_vi.properties");

        List<String> requiredKeys = List.of(
                // Plan 13.1-04 — 13 chatView.attachments.* keys.
                "chatView.attachments.title",
                "chatView.attachments.uploadText",
                "chatView.attachments.dropLabel",
                "chatView.attachments.emptyState",
                "chatView.attachments.notice",
                "chatView.attachments.budgetExceeded",
                "chatView.attachments.action.download",
                "chatView.attachments.action.delete",
                "chatView.attachments.deleteConfirm.title",
                "chatView.attachments.deleteConfirm.message",
                "chatView.attachments.deleteConfirm.confirm",
                "chatView.attachments.deleteConfirm.cancel",
                "chatView.attachments.missingFileName",
                // Plan 13.1-01 — AiMessageRole.NOTICE enum caption.
                "com.vn.agent.entity/AiMessageRole.NOTICE");

        for (String key : requiredKeys) {
            assertThat(en.containsKey(key))
                    .as("Phase 13.1 key %s must exist in messages_en.properties", key)
                    .isTrue();
            assertThat(vi.containsKey(key))
                    .as("Phase 13.1 key %s must exist in messages_vi.properties", key)
                    .isTrue();

            String enValue = en.getProperty(key);
            String viValue = vi.getProperty(key);
            assertThat(enValue)
                    .as("Phase 13.1 key %s must have a non-blank EN value", key)
                    .isNotNull()
                    .isNotBlank();
            assertThat(viValue)
                    .as("Phase 13.1 key %s must have a non-blank VI value", key)
                    .isNotNull()
                    .isNotBlank();
        }
    }

    /**
     * Phase 13.1 Plan 07 — REQ-9 namespace-symmetry gate. The symmetric difference
     * between EN and VI key sets restricted to the {@code chatView.attachments.}
     * namespace MUST be empty. Other namespaces may legitimately differ for legacy
     * reasons (handled by {@link #bundlesHaveIdenticalKeySets}); this narrower gate
     * is the Phase 13.1 attachments-only invariant.
     */
    @Test
    void chatViewAttachmentsNamespaceHasIdenticalKeySetAcrossBundles() throws IOException {
        Properties en = load("/com/vn/agent/messages_en.properties");
        Properties vi = load("/com/vn/agent/messages_vi.properties");

        Set<Object> enAttachmentsKeys = en.keySet().stream()
                .filter(k -> ((String) k).startsWith("chatView.attachments."))
                .collect(java.util.stream.Collectors.toSet());
        Set<Object> viAttachmentsKeys = vi.keySet().stream()
                .filter(k -> ((String) k).startsWith("chatView.attachments."))
                .collect(java.util.stream.Collectors.toSet());

        Set<Object> enOnly = new HashSet<>(enAttachmentsKeys);
        enOnly.removeAll(viAttachmentsKeys);
        Set<Object> viOnly = new HashSet<>(viAttachmentsKeys);
        viOnly.removeAll(enAttachmentsKeys);

        assertThat(new TreeSet<>(enOnly))
                .as("chatView.attachments.* keys present in EN but missing from VI")
                .isEmpty();
        assertThat(new TreeSet<>(viOnly))
                .as("chatView.attachments.* keys present in VI but missing from EN")
                .isEmpty();
        assertThat(enAttachmentsKeys)
                .as("Phase 13.1 attachments namespace must be non-empty")
                .isNotEmpty();
    }
}
