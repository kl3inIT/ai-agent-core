package com.vn.agent.rag;

import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiKnowledgeDocumentStatus;
import com.vn.agent.rag.config.AiAgentEmbeddingProperties;
import com.vn.agent.rag.config.AiAgentRagProperties;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.security.model.ResourceRole;
import io.jmix.security.role.ResourceRoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito-only unit test for {@link KnowledgeDocumentUploadService}. No Spring context — we
 * manually bootstrap {@link TransactionSynchronizationManager} so afterCommit ordering can be
 * observed without a real {@code PlatformTransactionManager}.
 */
class KnowledgeDocumentUploadServiceTest {

    private Metadata metadata;
    private DataManager dataManager;
    private ResourceRoleRepository roleRepository;
    private AsyncIngestionWorker asyncIngestionWorker;
    private AiAgentEmbeddingProperties embeddingProperties;

    private KnowledgeDocumentUploadService service;

    @BeforeEach
    void setUp() {
        metadata = mock(Metadata.class);
        dataManager = mock(DataManager.class);
        roleRepository = mock(ResourceRoleRepository.class);
        asyncIngestionWorker = mock(AsyncIngestionWorker.class);
        embeddingProperties = new AiAgentEmbeddingProperties(
                "qwen/qwen3-embedding-4b", 2000, null);

        // Metadata.create returns a fresh AiKnowledgeDocument (bypasses @JmixGeneratedValue).
        when(metadata.create(AiKnowledgeDocument.class)).thenAnswer(inv -> new AiKnowledgeDocument());

        // DataManager.save assigns an id and returns the saved instance.
        when(dataManager.save(any(AiKnowledgeDocument.class))).thenAnswer(inv -> {
            AiKnowledgeDocument doc = inv.getArgument(0);
            if (doc.getId() == null) {
                setField(doc, "id", UUID.randomUUID());
            }
            return doc;
        });

        // Simulate a live transaction so registerSynchronization works.
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        // CR-01: AiAgentRagProperties is required so the allowlist validator can resolve
        // the default classpath:ai-kb/ prefix used by every fixture URI in this suite.
        AiAgentRagProperties ragProperties = new AiAgentRagProperties(
                null, null, null, null, null, null, null, null, null);

        service = new KnowledgeDocumentUploadService(
                metadata, dataManager, roleRepository, asyncIngestionWorker, embeddingProperties, ragProperties);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    // --- Helpers -------------------------------------------------------------

    private void stubRoleKnown(String code) {
        ResourceRole role = mock(ResourceRole.class);
        lenient().when(roleRepository.findRoleByCode(code)).thenReturn(role);
    }

    private void stubRoleUnknown(String code) {
        lenient().when(roleRepository.findRoleByCode(code)).thenReturn(null);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static TransactionSynchronization captureOnlySynchronization() {
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);
        return syncs.get(0);
    }

    // --- Tests ---------------------------------------------------------------

    @Test
    void happy_path_persists_PENDING_and_schedules_worker_afterCommit() {
        stubRoleKnown("ai-agent-user");

        AiKnowledgeDocument saved = service.upload(
                "classpath:ai-kb/fixture-alpha.md", "text/markdown", List.of("ai-agent-user"));

        ArgumentCaptor<AiKnowledgeDocument> captor = ArgumentCaptor.forClass(AiKnowledgeDocument.class);
        verify(dataManager).save(captor.capture());
        AiKnowledgeDocument persisted = captor.getValue();
        assertThat(persisted.getFileName()).isEqualTo("classpath:ai-kb/fixture-alpha.md");
        assertThat(persisted.getMimeType()).isEqualTo("text/markdown");
        assertThat(persisted.getStatus()).isEqualTo(AiKnowledgeDocumentStatus.PENDING);
        assertThat(persisted.getAllowedRolesJson()).contains("ai-agent-user");
        assertThat(saved.getId()).isNotNull();

        // worker NOT invoked yet — only afterCommit schedules it.
        verify(asyncIngestionWorker, never()).ingest(any());
        captureOnlySynchronization().afterCommit();
        verify(asyncIngestionWorker, times(1)).ingest(saved.getId());
    }

    @Test
    void unknown_role_code_throws_and_does_not_persist_or_schedule() {
        stubRoleUnknown("phantom-role");

        assertThatThrownBy(() -> service.upload(
                "classpath:ai-kb/fixture.md", "text/markdown", List.of("phantom-role")))
                .isInstanceOf(UnknownRoleCodeException.class)
                .matches(e -> "phantom-role".equals(((UnknownRoleCodeException) e).getCode()));

        verify(dataManager, never()).save(any(AiKnowledgeDocument.class));
        verify(asyncIngestionWorker, never()).ingest(any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void empty_allowedRoles_succeeds_and_persists_empty_list() {
        AiKnowledgeDocument saved = service.upload(
                "classpath:ai-kb/fixture.md", "text/markdown", List.of());

        assertThat(saved.getAllowedRolesJson()).isEqualTo("[]");
        verify(roleRepository, never()).findRoleByCode(any());
        verify(dataManager).save(any(AiKnowledgeDocument.class));
    }

    @Test
    void null_allowedRoles_is_treated_as_empty() {
        AiKnowledgeDocument saved = service.upload(
                "classpath:ai-kb/fixture.md", "text/markdown", null);

        assertThat(saved.getAllowedRolesJson()).isEqualTo("[]");
        verify(roleRepository, never()).findRoleByCode(any());
    }

    @Test
    void worker_not_invoked_before_afterCommit_hook_runs() {
        stubRoleKnown("ai-agent-user");

        service.upload("classpath:ai-kb/a.md", "text/markdown", List.of("ai-agent-user"));

        verify(asyncIngestionWorker, never()).ingest(any());
        // Now run the registered synchronization — this is the moment the worker should fire.
        captureOnlySynchronization().afterCommit();
        verify(asyncIngestionWorker, times(1)).ingest(any());
    }

    // --- CR-01 sourceUri allowlist tests --------------------------------------

    @Test
    void rejects_file_uri_pointing_outside_staging_root_by_default() {
        // No staging root configured → all file: URIs must be rejected before persistence.
        assertThatThrownBy(() -> service.upload(
                "file:/etc/passwd", "text/plain", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(dataManager, never()).save(any(AiKnowledgeDocument.class));
    }

    @Test
    void rejects_file_uri_even_to_application_properties_form() {
        assertThatThrownBy(() -> service.upload(
                "file:/tmp/application.properties", "text/plain", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(dataManager, never()).save(any(AiKnowledgeDocument.class));
    }

    @Test
    void rejects_classpath_uri_outside_default_ai_kb_allowlist() {
        // An ai-kb-adjacent secret / internal resource must NOT be ingestible.
        assertThatThrownBy(() -> service.upload(
                "classpath:application.properties", "text/plain", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(dataManager, never()).save(any(AiKnowledgeDocument.class));
    }

    @Test
    void rejects_other_schemes() {
        assertThatThrownBy(() -> service.upload(
                "ftp://example.com/foo.md", "text/markdown", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upload(
                "http://example.com/foo.md", "text/markdown", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(dataManager, never()).save(any(AiKnowledgeDocument.class));
    }

    @Test
    void admin_code_is_validated_via_RoleRepository_not_allowlisted() {
        // Even the admin role code must resolve via the repository — no hardcoded allowlist.
        stubRoleUnknown("ai-agent-admin");

        assertThatThrownBy(() -> service.upload(
                "classpath:ai-kb/a.md", "text/markdown", List.of("ai-agent-admin")))
                .isInstanceOf(UnknownRoleCodeException.class);

        verify(roleRepository).findRoleByCode("ai-agent-admin");
        verify(dataManager, never()).save(any(AiKnowledgeDocument.class));
    }
}
