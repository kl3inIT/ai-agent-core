package com.vn.agent.exposure;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PERF-02 / T-18-02 source-scan invariant: every exposure-derived in-bean cache inside
 * {@link LlmExposurePolicy} MUST subscribe to {@link LlmExposureChangedEvent} for eviction.
 *
 * <p>Adapted from {@code AiSettingsChangedEventListenerInvariantTest.singlePublishSiteSourceScan}
 * (reuses the {@code findMainJavaRoot()} resolution playbook). A silently-never-evicting cache is
 * the exact information-disclosure threat D-07 forbids; this test fails the build if a
 * {@code ConcurrentHashMap} cache field is added to {@code LlmExposurePolicy} without a matching
 * {@code @EventListener(LlmExposureChangedEvent} eviction consumer in the same file.</p>
 */
@Tag("unit")
class ExposureCacheEventSubscriptionInvariantTest {

    @Test
    void everyExposureCacheSubscribesToLlmExposureChangedEvent() throws IOException {
        Path source = findMainJavaRoot()
                .resolve(Paths.get("com", "vn", "agent", "exposure", "LlmExposurePolicy.java"));
        assertThat(Files.isRegularFile(source))
                .as("LlmExposurePolicy.java must exist at %s", source)
                .isTrue();

        String content = Files.readString(source);

        Pattern cacheFieldPattern = Pattern.compile("new\\s+ConcurrentHashMap\\s*<");
        Pattern eventListenerPattern = Pattern.compile("@EventListener\\s*\\(\\s*LlmExposureChangedEvent");

        boolean hasCache = cacheFieldPattern.matcher(content).find();
        boolean subscribesToEvent = eventListenerPattern.matcher(content).find();

        assertThat(hasCache)
                .as("LlmExposurePolicy is expected to hold an exposure-derived ConcurrentHashMap "
                        + "denylist memo (PERF-02)")
                .isTrue();

        assertThat(subscribesToEvent)
                .as("Every exposure-derived cache in LlmExposurePolicy MUST subscribe to "
                        + "LlmExposureChangedEvent for eviction (D-07/T-18-02). A cache field is "
                        + "present but no @EventListener(LlmExposureChangedEvent...) consumer was "
                        + "found in the same file — a never-evicting denylist would leak a denied "
                        + "entity past an admin edit.")
                .isTrue();
    }

    /**
     * Locate {@code ai-agent/src/main/java} from the test working directory. Tests run with the
     * working dir set to the {@code ai-agent} subproject, so the relative path is
     * {@code src/main/java}. Fall back to walking up if the working dir is the root.
     */
    private Path findMainJavaRoot() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get("src", "main", "java"));
        candidates.add(Paths.get("ai-agent", "src", "main", "java"));
        candidates.add(Paths.get("..", "src", "main", "java"));
        candidates.add(Paths.get("ai-agent", "ai-agent", "src", "main", "java"));
        for (Path c : candidates) {
            if (Files.isDirectory(c)) {
                return c;
            }
        }
        throw new IllegalStateException(
                "Could not locate ai-agent/src/main/java from working dir "
                        + Paths.get("").toAbsolutePath()
                        + "; tried: "
                        + candidates.stream().map(Path::toString).collect(Collectors.joining(", ")));
    }
}
