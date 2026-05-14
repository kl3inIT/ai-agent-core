package com.vn.agent.view.chat;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15 OBS-01 / OBS-02 locale gate (T-15-E3) — every NEW Phase-15 {@code msg://} key
 * (added by Plans 15-01 / 15-03 / 15-04) resolves to a non-blank value in BOTH
 * {@code messages_en.properties} AND {@code messages_vi.properties}. A key present in one
 * bundle but missing/blank in the other fails the test — so a user-visible label can never
 * ship in only one locale (an untranslated string leaking through).
 *
 * <p>Plain JUnit — loads each bundle via {@link Properties} (from the test classpath, with a
 * source-tree fallback). No Spring context needed.
 */
class ObservabilityMessagesCompletenessTest {

    /**
     * The authoritative list of NEW Phase-15 {@code msg://} keys, from the 15-01 / 15-03 / 15-04
     * SUMMARYs. Order: 15-01 (SIDEBAR enum label), 15-03 (sidebar aria-labels), 15-04 (status line
     * + per-turn tool-detail disclosure).
     */
    private static final List<String> PHASE_15_KEYS = List.of(
            // 15-01 — AiChatSurface.SIDEBAR enum label
            "com.vn.agent.entity/AiChatSurface.SIDEBAR",
            // 15-03 — sidebar toggle / closer aria-labels
            "chatSurfaceMounter.sidebarToggle.ariaLabel.open",
            "chatSurfaceMounter.sidebarToggle.ariaLabel.closed",
            "chatSurfaceMounter.sidebarCloser.ariaLabel",
            // 15-04 — ephemeral streaming-status line (OBS-01)
            "chatView.status.neutral",
            "chatView.status.chat",
            "chatView.status.tool",
            "chatView.status.retrieval");

    @Test
    void everyPhase15KeyResolvesNonBlankInEnglishBundle() {
        assertAllKeysNonBlank(loadBundle("messages_en.properties"), "messages_en.properties");
    }

    @Test
    void everyPhase15KeyResolvesNonBlankInVietnameseBundle() {
        assertAllKeysNonBlank(loadBundle("messages_vi.properties"), "messages_vi.properties");
    }

    @Test
    void noPhase15KeyIsPresentInOnlyOneBundle() {
        Properties en = loadBundle("messages_en.properties");
        Properties vi = loadBundle("messages_vi.properties");
        for (String key : PHASE_15_KEYS) {
            boolean inEn = isNonBlank(en.getProperty(key));
            boolean inVi = isNonBlank(vi.getProperty(key));
            assertThat(inEn)
                    .as("Phase-15 key '%s' present(en)=%s but present(vi)=%s — must be in BOTH", key, inEn, inVi)
                    .isEqualTo(inVi);
        }
    }

    // ---- helpers ------------------------------------------------------------

    private static void assertAllKeysNonBlank(Properties bundle, String bundleName) {
        for (String key : PHASE_15_KEYS) {
            String value = bundle.getProperty(key);
            assertThat(value)
                    .as("Phase-15 msg:// key '%s' must be present in %s", key, bundleName)
                    .isNotNull();
            assertThat(value.isBlank())
                    .as("Phase-15 msg:// key '%s' must be non-blank in %s", key, bundleName)
                    .isFalse();
        }
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Properties loadBundle(String fileName) {
        Properties props = new Properties();
        // 1) classpath (main resources are on the test classpath)
        try (InputStream in = ObservabilityMessagesCompletenessTest.class
                .getResourceAsStream("/com/vn/agent/" + fileName)) {
            if (in != null) {
                props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                return props;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load bundle from classpath: " + fileName, e);
        }
        // 2) source-tree fallback
        Path p = firstExisting(
                "ai-agent/ai-agent/src/main/resources/com/vn/agent/" + fileName,
                "src/main/resources/com/vn/agent/" + fileName);
        if (p == null) {
            throw new IllegalStateException("Could not locate " + fileName + " on classpath or in the source tree");
        }
        try (InputStream in = Files.newInputStream(p)) {
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load bundle from source tree: " + p, e);
        }
        return props;
    }

    private static Path firstExisting(String... relativePaths) {
        for (String rel : relativePaths) {
            for (Path candidate : new Path[]{
                    Path.of(rel),
                    Path.of(System.getProperty("user.dir")).resolve(rel).normalize()
            }) {
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
