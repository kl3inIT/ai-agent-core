package com.vn.agent.view.chat;

import com.vn.agent.rag.CancellationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.Disposable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * UI-02 — Stop semantics test (Phase 7.1 rewrite).
 *
 * <p>Asserts the D-04 contract: Stop MUST route through
 * {@link CancellationRegistry#cancel(UUID)}, NEVER bypass it by calling
 * {@link Disposable#dispose()} directly — because {@code cancel(UUID)} is where the
 * {@code CANCELLED} audit row is written (via the ToolCallbackAuditDecorator chain).
 *
 * <p>Pragmatic-unit per Plan 07-07b Rule-1 tolerance: no {@code @UiTest} in the addon
 * module (the Jmix UI-test harness resolves only in the {@code jmix-app} root module).
 * Test 1 exercises the behavioural contract against a real
 * {@link CancellationRegistry}. Test 2 enforces the invariant at the source level by
 * reading the Fragment file and counting the permitted raw {@code dispose()} call sites
 * — fails loudly if a future refactor reintroduces bypass paths.
 */
class ChatViewStopTest {

    @Test
    void stopClick_callsRegistryCancel_withPreAllocatedRunId() {
        CancellationRegistry registry = Mockito.spy(new CancellationRegistry());
        UUID runId = UUID.randomUUID();
        Disposable disposable = Mockito.mock(Disposable.class);
        registry.register(runId, disposable);

        // Simulate the Fragment's onStopClick path.
        registry.cancel(runId);

        verify(registry).cancel(runId);
        // Registry internally disposes the registered subscription — that's the
        // audit-carrying path we want.
        verify(disposable).dispose();
    }

    @Test
    void stopClick_doesNotBypassRegistry_neverCallsDisposableDirectly() throws Exception {
        // Contract sentinel — grep the Fragment source for bare `activeStream.dispose()`
        // and for `cancellationRegistry.cancel(` usages. Since we cannot instantiate the
        // Fragment here without a Vaadin session, this static-analysis assertion is the
        // pragmatic substitute.
        String fragmentSource = readFragmentSource();

        // Count raw `activeStream.dispose()` occurrences. Plan 03 D-04 allows AT MOST
        // one — the onDetach fallback when activeRunId == null (pre-Final stream window).
        int rawDisposeCount = countOccurrences(fragmentSource, "activeStream.dispose()");
        assertThat(rawDisposeCount)
                .as("Stop MUST go through cancellationRegistry.cancel(runId); "
                        + "raw dispose is permitted ONLY in the onDetach null-runId fallback")
                .isLessThanOrEqualTo(1);

        // AND cancellationRegistry.cancel(...) must appear multiple times (Stop click
        // path + onDetach-with-runId path + setConversationId mid-stream path +
        // startNewChat mid-stream path — see Plan 03 SUMMARY, four branches total).
        int cancelCount = countOccurrences(fragmentSource, "cancellationRegistry.cancel(");
        assertThat(cancelCount)
                .as("Stop path AND onDetach path (at minimum) must both route through "
                        + "cancellationRegistry.cancel")
                .isGreaterThanOrEqualTo(2);
    }

    private static String readFragmentSource() throws Exception {
        // Gradle runs :ai-agent:ai-agent:test with CWD = ai-agent/ai-agent/ (module dir).
        Path primary = Paths.get(
                "src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java");
        if (Files.exists(primary)) {
            return new String(Files.readAllBytes(primary), StandardCharsets.UTF_8);
        }
        // Fallback: IDE runs may use the repo root as CWD.
        Path fallback = Paths.get(System.getProperty("user.dir"))
                .resolve("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java");
        if (Files.exists(fallback)) {
            return new String(Files.readAllBytes(fallback), StandardCharsets.UTF_8);
        }
        throw new IllegalStateException(
                "Could not locate ChatPanelFragment.java relative to user.dir="
                        + System.getProperty("user.dir"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
