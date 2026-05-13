package com.vn.agent.admin.config;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 16 D-02 — immutable holder for the three-tier knob inventory populated at
 * {@code ApplicationReadyEvent} by {@code KnobInventoryScanner} (Pitfall 4 — beans
 * are not eagerly initialised before the ApplicationReady gate, so the scan runs
 * once after full context refresh).
 *
 * <p>Consumers (Plan 16-06 {@code AiUiSettingsDetailView}):</p>
 * <ul>
 *   <li>{@link #tier1()} — runtime-editable knobs, surfaced as form fields bound to
 *       the {@code AiUiSettings} singleton (the UI ignores this list directly; it
 *       reads the singleton via the existing data container — the list exists for
 *       completeness / inspection tooling).</li>
 *   <li>{@link #tier2()} — boot-time knobs, surfaced read-only on the
 *       {@code bootConfigTab} {@code dataGrid} with a {@code requiresRestart}
 *       badge column.</li>
 *   <li>{@link #tier3()} — secret material; surfaced on the {@code secretsTab}
 *       {@code dataGrid} as a {@code configured: yes/no} indicator only —
 *       the {@code SecretIndicatorRow} record intentionally does NOT carry a
 *       value field (defense-in-depth — SEC-08).</li>
 * </ul>
 *
 * <p>All three accessors return immutable empty lists before the scanner has run
 * (so a view rendered before {@code ApplicationReady} does not NPE).</p>
 */
@Component
public class KnobInventory {

    /**
     * One Tier-1 / Tier-2 knob row. {@code resolvedValue} is the value resolved
     * from {@code Environment} at scan time (snapshot — operator edits via the
     * Tier-1 form do NOT change this until the next boot, matching CFG-02 boot-
     * config semantics).
     */
    public record KnobRow(String key, String resolvedValue, String displayMessageKey, boolean requiresRestart) {
    }

    /**
     * One Tier-3 secret-indicator row. Carries the {@link #configured} boolean
     * only — never the raw secret value (SEC-08 invariant — see Plan 06 Task 3
     * {@code SecretRedactionInvariantsTest#noRawSecretValueReachesRenderedDom}).
     */
    public record SecretIndicatorRow(String key, String displayMessageKey, boolean configured) {
    }

    /** Internal snapshot state — atomically swapped by the scanner. */
    public record State(List<KnobRow> tier1, List<KnobRow> tier2, List<SecretIndicatorRow> tier3) {
        public State {
            tier1 = tier1 == null ? List.of() : List.copyOf(tier1);
            tier2 = tier2 == null ? List.of() : List.copyOf(tier2);
            tier3 = tier3 == null ? List.of() : List.copyOf(tier3);
        }
    }

    private static final State EMPTY = new State(List.of(), List.of(), List.of());

    private final AtomicReference<State> state = new AtomicReference<>(EMPTY);

    public List<KnobRow> tier1() {
        return state.get().tier1();
    }

    public List<KnobRow> tier2() {
        return state.get().tier2();
    }

    public List<SecretIndicatorRow> tier3() {
        return state.get().tier3();
    }

    /**
     * Replaces the inventory snapshot. Called once at {@code ApplicationReadyEvent}
     * by {@code KnobInventoryScanner}; visible-for-test so unit tests can stub the
     * scan path. Production callers MUST be the scanner.
     */
    public void setState(State next) {
        state.set(next == null ? EMPTY : next);
    }
}
