package com.vn.agent.parameters;

import com.vn.agent.entity.AiParameters;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * CRUD + lifecycle operations for {@link AiParameters} profiles (PARAM-01 / PARAM-03).
 *
 * <p>Strict YAML validation on every write path (Phase 6 D-05) via
 * {@link AiParametersBodyYamlMapper}: unknown top-level keys and invalid value ranges are
 * rejected with {@link ParametersValidationException} before persistence; the canonical
 * YAML round-trip (read → record → writeAsYaml) is what lands in {@code bodyYaml} so the
 * stored text always satisfies the {@link AiParametersBody} schema.</p>
 *
 * <p>{@link #setActive(UUID)} enforces the exactly-one-active invariant atomically within a
 * single REQUIRED transaction (D-06): all currently active rows are flipped to inactive,
 * then the target row is set active. Concurrent activations resolve via {@code @Version}
 * optimistic locking on {@link AiParameters} — the losing transaction surfaces an
 * {@code OptimisticLockingFailureException} which is an admin-surface error (standard Jmix
 * handler renders the dialog); the chat request path does not observe this race because it
 * reads whatever row is active at {@code ask()}-entry time.</p>
 */
@Service
public class ParametersService {

    private static final Logger log = LoggerFactory.getLogger(ParametersService.class);

    private final DataManager dataManager;
    private final Metadata metadata;
    private final AiParametersBodyYamlMapper yamlMapper;

    public ParametersService(DataManager dataManager,
                             Metadata metadata,
                             AiParametersBodyYamlMapper yamlMapper) {
        this.dataManager = dataManager;
        this.metadata = metadata;
        this.yamlMapper = yamlMapper;
    }

    @Transactional
    public AiParameters create(String profileName, String bodyYaml, boolean active) {
        // Validate the YAML body up front — strict on write (D-05).
        AiParametersBody body = yamlMapper.readValue(bodyYaml);
        String canonical = yamlMapper.writeAsYaml(body);

        AiParameters row = metadata.create(AiParameters.class);
        row.setProfileName(profileName);
        row.setBodyYaml(canonical);
        row.setActive(Boolean.FALSE); // start inactive; setActive() handles the flip.
        AiParameters saved = dataManager.save(row);
        if (active) {
            setActive(saved.getId());
            // WR-02: setActive() mutates the row but the local `saved` reference is now stale
            // (still reports active=false). Re-load so callers receive the post-activation state.
            return loadOrThrow(saved.getId());
        }
        return saved;
    }

    @Transactional
    public AiParameters update(UUID id, String newBodyYaml) {
        AiParameters row = loadOrThrow(id);
        AiParametersBody body = yamlMapper.readValue(newBodyYaml);
        row.setBodyYaml(yamlMapper.writeAsYaml(body));
        return dataManager.save(row);
    }

    @Transactional
    public void delete(UUID id) {
        AiParameters row = loadOrThrow(id);
        if (Boolean.TRUE.equals(row.getActive())) {
            throw new IllegalStateException(
                    "Cannot delete the active profile — activate another profile first.");
        }
        dataManager.remove(row);
    }

    /**
     * Flip all currently active rows to inactive, then set the target active — in a single
     * REQUIRED transaction (D-06). Optimistic-locking race (two concurrent activations) is
     * resolved by {@code @Version} on {@link AiParameters}; one transaction will throw
     * {@code OptimisticLockingFailureException} and the caller retries.
     *
     * <p>NOTE: {@code setActive} is an ADMIN-ONLY surface (settings UI / REST admin path)
     * and is NOT reachable from the chat request path. The optimistic-locking exception
     * therefore surfaces as an admin error, NOT as any {@code ChatResponseDto} error_code.</p>
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void setActive(UUID targetId) {
        List<AiParameters> active = dataManager.load(AiParameters.class)
                .query("select e from ai_AiParameters e where e.active = true")
                .list();
        for (AiParameters p : active) {
            p.setActive(Boolean.FALSE);
        }
        if (!active.isEmpty()) {
            dataManager.save(active.toArray());
        }
        AiParameters target = loadOrThrow(targetId);
        target.setActive(Boolean.TRUE);
        dataManager.save(target);
        log.info("Activated parameter profile {} (flipped {} prior active row(s))",
                target.getProfileName(), active.size());
    }

    @Transactional(readOnly = true)
    public AiParameters loadById(UUID id) {
        return loadOrThrow(id);
    }

    @Transactional(readOnly = true)
    public List<AiParameters> listAll() {
        return dataManager.load(AiParameters.class)
                .query("select e from ai_AiParameters e order by e.profileName")
                .list();
    }

    private AiParameters loadOrThrow(UUID id) {
        return dataManager.load(AiParameters.class).id(id).optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "AiParameters not found: " + id));
    }
}
