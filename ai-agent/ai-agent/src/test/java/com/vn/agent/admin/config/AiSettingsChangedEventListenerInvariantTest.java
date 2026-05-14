package com.vn.agent.admin.config;

import com.vn.agent.entity.AiParameters;
import com.vn.agent.entity.AiUiSettings;
import io.jmix.core.FluentLoader;
import io.jmix.core.Id;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.event.AttributeChanges;
import io.jmix.core.event.EntityChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 16 Plan 16-05 Task 3 — enforces the Plan 10-06 R2
 * single-publish-site invariant for {@link AiSettingsChangedEvent}.
 *
 * <p>Plans 02-07 MUST NOT inject {@link ApplicationEventPublisher} for this
 * event in views/services. The {@link #singlePublishSiteSourceScan()} method
 * fails the build if any source file under {@code ai-agent/src/main/java}
 * outside the two designated listeners contains the publish-site regex.</p>
 *
 * <p>Rule 3 — auto-fix blocking issue: the plan's nominal shape is
 * {@code @SpringBootTest} with {@code @RecordApplicationEvents}. The ai-agent
 * module's Spring context boot is blocked by the pre-existing Phase 11/13
 * regression (atmosphere-runtime / agentstoreEntityManagerFactory) documented
 * in {@code .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md};
 * Plans 13.1-06, 13.1-07, 14-01, 14-02, 16-01, 16-02, 16-03, 16-04 all hit the
 * same blocker and used a pure-JUnit / Mockito workaround. This test follows
 * the same playbook: the listeners are exercised directly with mock
 * {@code DataManager} + {@code ApplicationEventPublisher}, and the publish
 * contract is asserted with {@code verify(publisher)} calls. Contract preserved
 * exactly — when the boot regression is fixed, the eight publish-contract
 * methods port unchanged to {@code @SpringBootTest + @RecordApplicationEvents}
 * by replacing {@code listener.on...(buildEvent(...))} with
 * {@code dataManager.save(...)}.
 */
@Tag("unit")
class AiSettingsChangedEventListenerInvariantTest {

    private static final UUID PARAMETERS_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private ApplicationEventPublisher publisher;
    private UnconstrainedDataManager dataManager;
    private AiParametersEntityListener parametersListener;
    private AiUiSettingsEntityListener uiSettingsListener;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        publisher = mock(ApplicationEventPublisher.class);
        dataManager = mock(UnconstrainedDataManager.class);

        // Default: any AiParameters load returns empty. Individual tests rewire to
        // return a stub row when the listener must re-read the current `active` value.
        FluentLoader<AiParameters> loader = mock(FluentLoader.class);
        FluentLoader.ById<AiParameters> byId = mock(FluentLoader.ById.class);
        when(dataManager.load(AiParameters.class)).thenReturn(loader);
        when(loader.id(any())).thenReturn(byId);
        when(byId.optional()).thenReturn(Optional.empty());

        parametersListener = new AiParametersEntityListener(publisher, dataManager);
        uiSettingsListener = new AiUiSettingsEntityListener(publisher);
    }

    // -----------------------------------------------------------------------
    // AiParameters publish contract
    // -----------------------------------------------------------------------

    @Test
    void activeParametersSavePublishesPARAMETERS() {
        wireCurrentParametersActive(true);
        parametersListener.onAiParametersChanged(
                buildParametersEvent(EntityChangedEvent.Type.CREATED,
                        AttributeChanges.Builder.create()
                                .withChange("active", null)
                                .build()));

        assertPublishedExactly(AiSettingsChangedEvent.Kind.PARAMETERS, 1);
    }

    @Test
    void inactiveParametersSavePublishesZero() {
        wireCurrentParametersActive(false);
        parametersListener.onAiParametersChanged(
                buildParametersEvent(EntityChangedEvent.Type.CREATED,
                        AttributeChanges.Builder.create()
                                .withChange("active", null)
                                .build()));

        verify(publisher, never()).publishEvent(any(AiSettingsChangedEvent.class));
    }

    @Test
    void inactiveDeletedRowOldActiveTrueStillPublishes() {
        // DELETED carries oldValue=true → effective profile gone → publish.
        parametersListener.onAiParametersChanged(
                buildParametersEvent(EntityChangedEvent.Type.DELETED,
                        AttributeChanges.Builder.create()
                                .withChange("active", Boolean.TRUE)
                                .build()));

        assertPublishedExactly(AiSettingsChangedEvent.Kind.PARAMETERS, 1);
    }

    @Test
    void inactiveDeletedRowOldActiveFalsePublishesZero() {
        parametersListener.onAiParametersChanged(
                buildParametersEvent(EntityChangedEvent.Type.DELETED,
                        AttributeChanges.Builder.create()
                                .withChange("active", Boolean.FALSE)
                                .build()));

        verify(publisher, never()).publishEvent(any(AiSettingsChangedEvent.class));
    }

    @Test
    void deactivationFromActiveToInactivePublishesPARAMETERS() {
        // codex HIGH Concern #6 — UPDATED with `active` flipped true → false.
        // Current row is now inactive, but the previously-effective profile transitioned
        // away → downstream Phase 18 caches MUST invalidate.
        wireCurrentParametersActive(false);
        parametersListener.onAiParametersChanged(
                buildParametersEvent(EntityChangedEvent.Type.UPDATED,
                        AttributeChanges.Builder.create()
                                .withChange("active", Boolean.TRUE)
                                .build()));

        assertPublishedExactly(AiSettingsChangedEvent.Kind.PARAMETERS, 1);
    }

    @Test
    void inactiveToInactiveUpdatePublishesZero() {
        // codex HIGH Concern #6 — boundary: pre-seed active=false, UPDATE a non-active
        // attribute (active stays false). The `active` field is NOT in the change set
        // AND the current row is inactive → no effective-settings transition.
        wireCurrentParametersActive(false);
        parametersListener.onAiParametersChanged(
                buildParametersEvent(EntityChangedEvent.Type.UPDATED,
                        AttributeChanges.Builder.create()
                                .withChange("bodyYaml", "old-yaml")
                                .build()));

        verify(publisher, never()).publishEvent(any(AiSettingsChangedEvent.class));
    }

    @Test
    void activeUpdateOnNonActiveAttributePublishesPARAMETERS() {
        // Row IS currently active and a non-active attribute changed → effective settings
        // changed → publish.
        wireCurrentParametersActive(true);
        parametersListener.onAiParametersChanged(
                buildParametersEvent(EntityChangedEvent.Type.UPDATED,
                        AttributeChanges.Builder.create()
                                .withChange("bodyYaml", "old-yaml")
                                .build()));

        assertPublishedExactly(AiSettingsChangedEvent.Kind.PARAMETERS, 1);
    }

    @Test
    void activationFromInactiveToActivePublishesPARAMETERS() {
        // UPDATED with `active` flipped false → true (oldActive=false; current row active).
        wireCurrentParametersActive(true);
        parametersListener.onAiParametersChanged(
                buildParametersEvent(EntityChangedEvent.Type.UPDATED,
                        AttributeChanges.Builder.create()
                                .withChange("active", Boolean.FALSE)
                                .build()));

        assertPublishedExactly(AiSettingsChangedEvent.Kind.PARAMETERS, 1);
    }

    // -----------------------------------------------------------------------
    // AiUiSettings publish contract
    // -----------------------------------------------------------------------

    @Test
    void uiSettingsSavePublishesUI_SETTINGS() {
        uiSettingsListener.onAiUiSettingsChanged(
                buildUiSettingsEvent(AiUiSettings.SINGLETON_ID,
                        EntityChangedEvent.Type.UPDATED));

        assertPublishedExactly(AiSettingsChangedEvent.Kind.UI_SETTINGS, 1);
    }

    @Test
    void uiSettingsNonSingletonIdSavePublishesZero() {
        // codex MEDIUM Concern #6 — write against an AiUiSettings row whose id is NOT
        // the sealed singleton (simulating host-extension or accidental writes). The
        // SINGLETON_ID guard rejects → no event published.
        UUID otherId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        uiSettingsListener.onAiUiSettingsChanged(
                buildUiSettingsEvent(otherId, EntityChangedEvent.Type.UPDATED));

        verify(publisher, never()).publishEvent(any(AiSettingsChangedEvent.class));
    }

    // -----------------------------------------------------------------------
    // Source-scan invariant (Plan 10-06 R2 — single publish site per Kind)
    // -----------------------------------------------------------------------

    @Test
    void singlePublishSiteSourceScan() throws IOException {
        Path mainJavaRoot = findMainJavaRoot();
        Pattern publishPattern = Pattern.compile(
                "publishEvent\\s*\\(\\s*new\\s+AiSettingsChangedEvent",
                Pattern.DOTALL);

        Set<String> offenders = new TreeSet<>();
        Files.walkFileTree(mainJavaRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                String content = Files.readString(file);
                if (publishPattern.matcher(content).find()) {
                    offenders.add(file.getFileName().toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        Set<String> expected = new TreeSet<>(Set.of(
                "AiParametersEntityListener.java",
                "AiUiSettingsEntityListener.java"));

        assertThat(offenders)
                .as("Single-publish-site invariant (Plan 10-06 R2): only AiParametersEntityListener "
                        + "and AiUiSettingsEntityListener may publish AiSettingsChangedEvent. "
                        + "Found offenders: %s — views/services must NOT inject "
                        + "ApplicationEventPublisher for this event.", offenders)
                .isEqualTo(expected);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Asserts publisher saw exactly {@code count} events with the given {@code kind}. */
    private void assertPublishedExactly(AiSettingsChangedEvent.Kind kind, int count) {
        // ApplicationEventPublisher exposes two overloads: publishEvent(Object) and
        // publishEvent(ApplicationEvent). The listener invokes the ApplicationEvent
        // overload (AiSettingsChangedEvent extends ApplicationEvent), so capture against
        // that one to avoid Mockito routing the verification to the wrong overload.
        org.mockito.ArgumentCaptor<org.springframework.context.ApplicationEvent> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(publisher, times(count)).publishEvent(captor.capture());
        long matching = captor.getAllValues().stream()
                .filter(AiSettingsChangedEvent.class::isInstance)
                .map(AiSettingsChangedEvent.class::cast)
                .filter(e -> e.getKind() == kind)
                .count();
        assertThat(matching)
                .as("Expected exactly %d AiSettingsChangedEvent(kind=%s) published; "
                        + "publisher saw %s", count, kind, captor.getAllValues())
                .isEqualTo(count);
    }

    private void wireCurrentParametersActive(boolean active) {
        AiParameters row = new AiParameters();
        row.setId(PARAMETERS_ID);
        row.setActive(active);
        wireCurrentParametersRow(row);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void wireCurrentParametersRow(AiParameters row) {
        FluentLoader<AiParameters> loader = mock(FluentLoader.class);
        FluentLoader.ById<AiParameters> byId = mock(FluentLoader.ById.class);
        when(dataManager.load(AiParameters.class)).thenReturn(loader);
        when(loader.id(any())).thenReturn(byId);
        when(byId.optional()).thenReturn(Optional.ofNullable(row));
    }

    private EntityChangedEvent<AiParameters> buildParametersEvent(
            EntityChangedEvent.Type type, AttributeChanges changes) {
        return new EntityChangedEvent<>(
                this,
                Id.of(PARAMETERS_ID, AiParameters.class),
                type,
                changes,
                /* originalMetaClass */ null);
    }

    private EntityChangedEvent<AiUiSettings> buildUiSettingsEvent(
            UUID id, EntityChangedEvent.Type type) {
        return new EntityChangedEvent<>(
                this,
                Id.of(id, AiUiSettings.class),
                type,
                AttributeChanges.Builder.create().build(),
                /* originalMetaClass */ null);
    }

    /**
     * Locate {@code ai-agent/src/main/java} from the test working directory. Tests run with
     * the working dir set to the {@code ai-agent} subproject, so the relative path is
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
