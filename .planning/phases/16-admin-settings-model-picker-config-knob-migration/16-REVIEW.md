---
phase: 16-admin-settings-model-picker-config-knob-migration
reviewed: 2026-05-13T15:33:13Z
depth: standard
files_reviewed: 18
files_reviewed_list:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiSettingsChangedEvent.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/KnobMetadata.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/ChatModelCatalog.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/ChatModelCatalogProperties.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AdminSecretPatternProperties.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiParametersEntityListener.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiUiSettingsEntityListener.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/KnobInventory.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiUiSettingsResolver.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatModelFallbackAppliedEvent.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersDetailView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/uisettings/AiUiSettingsDetailView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
  - ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/KnobInventoryScanner.java
  - ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/KnobInventoryAutoConfiguration.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/uisettings/ai-ui-settings-detail-view.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/120-ai-ui-settings-tier1-knobs.xml
findings:
  critical: 3
  warning: 7
  info: 3
  total: 13
status: issues_found
---

# Phase 16: Code Review Report

**Reviewed:** 2026-05-13T15:33:13Z
**Depth:** standard
**Files Reviewed:** 18+ source files (Phase 16 delta only — Phase 15 files in scope were excluded per scoping note)
**Status:** issues_found

## Summary

Phase 16 lands a substantial admin-settings overhaul: curated model `ComboBox` + custom-entry escape hatch, 12 Tier-1 nullable columns on `AiUiSettings`, three-layer knob inventory (`@KnobMetadata` + scanner + secret-pattern), single-publish-site entity listeners for `AiSettingsChangedEvent`, and the MODEL-02 bad-model catch + one-shot reissue inside the BLK-01 chokepoint. Architecture is precedent-driven and largely well-executed.

Three BLOCKER-class issues were found:

1. **SEC-08 defense-in-depth gap** — un-annotated host `@ConfigurationProperties` under an allowed prefix gets a Tier-2 `KnobRow` with the **raw value** in `resolvedValue`, which is rendered by the Boot Config grid. A host that adds `@ConfigurationProperties("ai-agent.host")` with an `apiKey` field but forgets `@KnobMetadata(TIER_3)` will leak the secret to the admin UI — the very scenario SPEC criterion 4 says must be masked.
2. **Unescaped JSON in audit row** — `writeModelValidationFailureAudit` builds `argumentsJson` via string concatenation: `"{\"model\":\"" + offendingModel + "\"}"`. The `modelField` ComboBox has `allowCustomValue=true` and accepts arbitrary admin input — a model id containing `"` or `\` produces malformed JSON in the audit table.
3. **Bean dual-registration risk on `KnobInventory`** — class is `@Component` AND imported by `KnobInventoryAutoConfiguration` via `@Import(KnobInventory.class)`. Spring Boot 3.x throws `BeanDefinitionOverrideException` by default. Same issue applies to `KnobInventoryScanner` (though that one is in a package the agent module's `@ComponentScan` likely does not visit, masking the issue).

Seven WARNINGs cover bean-validation discontinuous-range gaps, entity-listener edge cases, and other quality issues. Three INFO items capture minor concerns.

## Critical Issues

### CR-01: Un-annotated host config under allowed prefix leaks raw value to Tier-2 Boot Config grid

**File:** `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/KnobInventoryScanner.java:220-255`
**Issue:** `addComponent(...)` always populates `KnobRow.resolvedValue` via `safeReadValue(key)` (line 225). For un-annotated properties (no `@KnobMetadata`), it falls through to Tier-2 with `requiresRestart=true` (lines 232-234). The Boot Config XML grid (`ai-ui-settings-detail-view.xml:100`) binds `<column key="resolvedValue"/>` and renders that value verbatim to the DOM.

A host extension that adds `@ConfigurationProperties("ai-agent.host") record HostProps(String apiKey)` and forgets `@KnobMetadata(tier=TIER_3)` will produce a `KnobRow("ai-agent.host.api-key", "<SECRET_VALUE>", "...", true)` in the Tier-2 list. Both Tier-2 grid (raw value) and Tier-3 indicator (configured=yes) will show — the latter masked, the former NOT.

This directly contradicts SPEC criterion 4 ("a host forgetting to mark a new `*.api-key` field as `TIER_3` still gets masked") and the Tier-3 defense-in-depth invariant. The deferred runtime DOM-scan test (`noRawSecretValueReachesRenderedDom`, Plan 16-06 Deferred Issues) would have caught this; the static SEC-08 source-scan only covers XML editable-bindings, not Tier-2 value display.

**Fix:** Apply the Tier-3 pattern check inside `addComponent` (or `safeReadValue`) and either (a) reclassify matched keys as Tier-3 and skip the Tier-2 row, or (b) replace the value with a masked sentinel before storing in `KnobRow`. The patterns are already resolved via `secretPatterns.resolvedPatterns()`.

```java
private void addComponent(ConfigurationPropertyName name, Class<?> componentType,
                          KnobMetadata metadata, ...) {
    String key = name.toString();
    // Defense-in-depth: if the property name matches a Tier-3 glob, mask
    // regardless of annotation state. Mirrors the env-walk pass at
    // scanEnvironmentForSecrets and prevents un-annotated host config
    // from leaking secret values into the Tier-2 grid.
    if (matchesAny(key, compilePatterns(secretPatterns.resolvedPatterns()))) {
        return; // env-walk pass will emit the secret indicator row
    }
    String resolvedValue = safeReadValue(key);
    // ...
}
```

### CR-02: `writeModelValidationFailureAudit` builds JSON via unescaped string concatenation

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:1171`
**Issue:** The audit-row `argumentsJson` is built as:
```java
"{\"model\":\"" + offendingModel + "\"}"
```

`offendingModel` originates from `AiParameters.bodyYaml.model`, which is admin-input via a ComboBox with `allowCustomValue="true"` (Plan 16-05). The custom-value escape hatch accepts arbitrary strings — a model id containing `"`, `\`, or newline produces malformed JSON. Examples that break:

- `bad"model` → `{"model":"bad"model"}` (invalid JSON)
- `path\with\backslash` → `{"model":"path\with\backslash"}` (invalid JSON — `\w` is not a valid escape)
- `multi\nline` → embedded newline breaks JSON parser

The audit pipeline does not validate the JSON shape at write time (audit rows are text-blob `argumentsJson`), so the malformed row persists silently. Downstream consumers (admin audit UI, log parsers) that attempt JSON parse will fail or skip the row, losing forensic context — the very purpose of the MODEL_VALIDATION_FAILURE row.

P-22 sanitization precedent (`MutationErrorTranslator`) demands proper escaping for any audit-row payload that crosses JSON boundaries.

**Fix:** Use a JSON library (Jackson is already on the classpath via Spring AI) to serialize the map:

```java
private final ObjectMapper auditObjectMapper = new ObjectMapper(); // or inject existing

private void writeModelValidationFailureAudit(...) {
    String argumentsJson;
    try {
        argumentsJson = auditObjectMapper.writeValueAsString(Map.of("model", offendingModel));
    } catch (JsonProcessingException e) {
        argumentsJson = "{}";
    }
    // ...
}
```

Or, equivalently, use Spring's `org.springframework.boot.json.JsonWriter` / `BasicJsonParser` round-trip.

### CR-03: `KnobInventory` dual-registered via `@Component` + autoconfig `@Import`

**File:** `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/KnobInventoryAutoConfiguration.java:23`
**Issue:** `KnobInventory` is annotated `@Component` (file: `KnobInventory.java:32`) and lives at `com.vn.agent.admin.config` — within the package tree scanned by `AIConfiguration`'s default `@ComponentScan` (`AIConfiguration.java:33`, scans `com.vn.agent`). The autoconfig `KnobInventoryAutoConfiguration` also `@Import({KnobInventory.class, KnobInventoryScanner.class})`.

Spring Boot 3.x defaults `spring.main.allow-bean-definition-overriding=false`. When both component-scan and `@Import` attempt to register the bean, Spring throws `BeanDefinitionOverrideException` at boot. The reason this likely escaped detection: the documented Phase 11/13 Spring-context boot regression blocks `@SpringBootTest` in the ai-agent module, so no Wave-0 test boots a full context to discover the duplicate.

A host application that successfully boots Spring against the starter (i.e., outside the test regression) will hit this in production — but the same boot regression that masks it in tests prevents proof in a sandbox here.

`KnobInventoryScanner` has the same `@Component` (line 60) + `@Import` (autoconfig line 23) shape, but lives in `com.vn.autoconfigure.agent` — likely outside `AIConfiguration.@ComponentScan`'s root (`com.vn.agent`). So that registration is only via `@Import`. Worth verifying.

**Fix:** Pick ONE registration path:
- (a) Remove `@Import(KnobInventory.class)` from `KnobInventoryAutoConfiguration` and rely on the agent module's `@ComponentScan` to pick up the `@Component`-annotated `KnobInventory`; or
- (b) Remove `@Component` from `KnobInventory` and rely solely on the autoconfig `@Import`.

Option (b) is cleaner — keeps the inventory holder strictly tied to the autoconfig that registers its scanner. The same change should be applied to `KnobInventoryScanner` (currently `@Component` AND `@Import`-ed) to avoid the same risk if `AIConfiguration`'s scan ever expands to the starter package.

```java
// KnobInventory.java — remove @Component
public class KnobInventory { ... }

// KnobInventoryScanner.java — remove @Component
public class KnobInventoryScanner { ... }

// KnobInventoryAutoConfiguration.java — already has @Import; no change needed
```

## Warnings

### WR-01: Bean-validation bounds allow values in the documented "invalid gap" range for sentinel knobs

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java:58-71`
**Issue:** SPEC criterion 3 specifies `ttlSeconds ∈ {-1} ∪ [60, 604_800]` (discontinuous range — `-1` sentinel OR a value ≥ 60). The implementation uses `@Min(-1L) @Max(604_800L)` which accepts every integer in `[-1, 604800]` including `0, 1, ..., 59`. Setting `taskFileTtlSeconds = 5` validates clean but makes uploaded files expire 5 seconds after creation, defeating the upload feature.

Same gap applies to `taskFilePerTurnMaxFiles` (`@Min(-1) @Max(100)` — accepts `0`, no per-turn upload allowed), `taskFilePerTurnMaxTotalBytes` (accepts `0`-byte budget), and any other sentinel-bearing column.

`ChatPanelFragment.java:2063-2066` consumes `ttlSeconds`: if `== -1` use NON_EXPIRING; else `now().plusSeconds(ttlSeconds)`. With `ttlSeconds=0` files expire immediately on upload.

**Fix:** Add a class-level `@AssertTrue` cross-field validator OR use a custom `@TaskFileTtl` annotation that enforces the discontinuous range. Example:

```java
@AssertTrue(message = "{aiUiSettings.validation.taskFileTtl.range}")
public boolean isTaskFileTtlValid() {
    if (taskFileTtlSeconds == null) return true;
    long v = taskFileTtlSeconds;
    return v == -1L || (v >= 60L && v <= 604_800L);
}
```

### WR-02: `AiParametersEntityListener` may not publish on DELETED when Jmix doesn't carry attribute values for delete events

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiParametersEntityListener.java:70-71`
**Issue:**
```java
case DELETED:
    Boolean oldActiveDeleted = changes.getOldValue(ACTIVE_ATTRIBUTE);
    return Boolean.TRUE.equals(oldActiveDeleted);
```

This depends on Jmix's `AttributeChanges` carrying the pre-delete value of the `active` attribute. Per Jmix docs, `AttributeChanges` for a DELETED event MAY only carry the entity id depending on which Jmix listener phase is subscribed. If `getOldValue("active")` returns `null` for DELETE events in this codebase's Jmix version, `Boolean.TRUE.equals(null)` is `false` and the listener NEVER publishes for DELETEs — silently breaking the "DELETED row whose previous active=true" invariant locked in 16-SPEC criterion 5.

The Wave-0 test `AiSettingsChangedEventListenerInvariantTest` uses a Mockito-fabricated `AttributeChanges`, not real Jmix behavior, so the test passes whether or not Jmix actually fills `getOldValue` for DELETE.

**Fix:** Verify in an integration test (post-boot-regression) that `AttributeChanges.getOldValue` returns the pre-delete `active` value. If Jmix does not populate it for DELETE, switch to using `event.getDeletedEntityValue()` or load via a different mechanism (e.g., read from soft-delete `DELETED_DATE`-aware lookup, or rely on a `BeforeDeleteEntityEvent` to snapshot the value before deletion). At minimum, document the dependency on Jmix's `AttributeChanges` shape so a future Jmix upgrade does not silently regress this.

### WR-03: `AiParametersEntityListener` re-reads the row via constrained `DataManager` — system-internal saves with no auth context may silently swallow events

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/AiParametersEntityListener.java:76-99`
**Issue:** The CREATED case and UPDATED CASE 3a/3b paths re-read the saved `AiParameters` row via constrained `DataManager.load(...).optional().orElse(null)`. If the load returns null (no user authentication, RLS denies, or row deleted post-event), the listener treats this as "do not publish".

For admin-driven saves via `ParametersDetailView`, the current user is admin and the load succeeds. But:
- System-internal saves (e.g., a future Liquibase migration that flips `active=true` via Java, a system seeder bean) have no `Authentication` in the security context; constrained `DataManager.load` returns null → event suppressed.
- A user whose role grants WRITE but not READ on `AiParameters` (unlikely with `AiAgentAdminRole` but possible with custom roles) would write successfully but the re-read returns null → silent suppression.

The `AiExposureRuleEntityListener` precedent uses `UnconstrainedDataManager` for the same re-read pattern. Adopting that here would be safer.

**Fix:** Inject `UnconstrainedDataManager` instead of `DataManager` for the re-read path:

```java
private final UnconstrainedDataManager unconstrainedDataManager;

AiParameters current = unconstrainedDataManager.load(AiParameters.class)
        .id(event.getEntityId().getValue())
        .optional()
        .orElse(null);
```

The publish decision is system-internal and should not be gated by the current user's read access to `AiParameters`.

### WR-04: `extractBadModelStatus` ignores deeper RCRE inside `NonTransientAiException.getCause()` if outer chain has none

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:1139-1147`
**Issue:** `isBadModelException` walks the outer chain for Case A (direct RCRE) AND descends into `NonTransientAiException.getCause()` for Case B (wrapped RCRE). But `extractBadModelStatus` only does the linear outer walk:

```java
private static int extractBadModelStatus(Throwable cause) {
    Throwable cursor = cause;
    for (int depth = 0; cursor != null && depth < 5; depth++, cursor = cursor.getCause()) {
        if (cursor instanceof RestClientResponseException rcre) {
            return rcre.getStatusCode().value();
        }
    }
    return -1;
}
```

For a chain like `RuntimeException -> NonTransientAiException -> RestClientResponseException`, this walks `RuntimeException.getCause() → NonTransientAiException → NonTransientAiException.getCause() → RestClientResponseException` — finds it at depth 2. OK in that specific shape.

But if `NonTransientAiException` carries the RCRE in a non-getCause linkage (some Spring AI versions wrap into a `Throwable[] suppressed` array or a typed field), `extractBadModelStatus` returns `-1` while `isBadModelException` returned true (Case B used different traversal logic). The audit row then logs `status=-1`, which is misleading forensic data and a subtle drift between the classifier and the status extractor.

**Fix:** Mirror the classifier's traversal in the status extractor — or, better, share a single private helper `findCausalRcre(Throwable)` returning `RestClientResponseException` (or null) that BOTH `isBadModelException` and `extractBadModelStatus` call. Eliminates the dual-walk divergence:

```java
private static RestClientResponseException findCausalRcre(Throwable t) {
    Throwable cursor = t;
    for (int depth = 0; cursor != null && depth < 5; depth++, cursor = cursor.getCause()) {
        if (cursor instanceof RestClientResponseException rcre) return rcre;
        if (cursor instanceof NonTransientAiException) {
            Throwable inner = cursor.getCause();
            int innerDepth = 0;
            while (inner != null && innerDepth < 5) {
                if (inner instanceof RestClientResponseException rcre) return rcre;
                inner = inner.getCause();
                innerDepth++;
            }
        }
    }
    return null;
}
```

Then `isBadModelException` becomes `findCausalRcre(t) != null && matchesBadModelShape(rcre)` and `extractBadModelStatus` becomes `var r = findCausalRcre(t); return r != null ? r.getStatusCode().value() : -1`.

### WR-05: `ChatModelCatalog` non-final mutable state initialized post-construction without volatile / immutable wrapping

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/admin/config/ChatModelCatalog.java:56-57, 110-117`
**Issue:**
```java
private List<Entry> entries;
private Entry defaultEntry;
```

These fields are non-final, non-volatile, and assigned only in `validate()` (a `@PostConstruct` method). After `validate()` completes Spring's bean lifecycle, `getBean(ChatModelCatalog.class)` is published safely. But subsequent reads from any thread accessing `entries()` after the bean is published are safe ONLY if Spring's internal singleton publication uses a happens-before barrier. In Spring 6.x this is typically true (the singleton map is `ConcurrentHashMap` plus the `getSingleton` synchronized block), so practical risk is low.

However, the `findById(...)` iteration over `entries` (lines 138-143) is unsynchronized — if a future change ever re-invokes `validate()` (e.g., on a hypothetical reload hook), readers in flight see torn state.

**Fix:** Make `entries` and `defaultEntry` `final` by computing them in the constructor — or wrap them as `List.copyOf` immutable lists held in a single `final AtomicReference<State>` (mirroring `KnobInventory`'s pattern, which is already used elsewhere in Phase 16). At minimum mark as `volatile`:

```java
private volatile List<Entry> entries;
private volatile Entry defaultEntry;
```

The cleanest fix is to remove the field-init separation entirely and do `Collections.unmodifiableList(...)` at the assignment point.

### WR-06: `ParametersDetailView.populateFormFromBody` writes empty string to ComboBox when body.model is null

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersDetailView.java:334`
**Issue:**
```java
modelField.setValue(nullToBlank(body.model()));
```

For a fresh AiParameters with `body.model() == null` (e.g., a newly-created profile), `nullToBlank` returns `""`. ComboBox with `allowCustomValue=true` treats `""` as a custom value; the value-change handler fires with empty string; YAML preview shows `model: ''`. The `required=true` validation will reject the save, but the UX shows an empty selected entry rather than no selection.

Most ComboBox patterns use `setValue(null)` for "no selection". The `nullToBlank` helper is used elsewhere for text fields, where empty string is OK.

**Fix:** Pass the raw `body.model()` (which may be null) to `modelField.setValue(...)`. ComboBox handles null cleanly as "no selection":

```java
modelField.setValue(body.model());
```

### WR-07: ChatPanelFragment `onChatModelFallbackApplied` is `@EventListener` on a Vaadin prototype-scoped fragment — risks event-listener leak / unintended fan-out

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:417-429`
**Issue:** `ChatPanelFragment extends Fragment<VerticalLayout>` — Jmix fragments are instantiated per UI mount. Each instance has an `@EventListener` for `ChatModelFallbackAppliedEvent`. Spring registers each instance's listener with the application event multicaster. If fragments are not properly de-registered on detach, listeners accumulate across navigations (the multicaster holds references → memory leak + duplicate notifications fired on subsequent events).

Vaadin's `Fragment` lifecycle does not auto-remove Spring `@EventListener` registrations. The conversation-id filter at line 420-423 prevents UI-level duplication, but Spring still invokes the listener method on every accumulated instance for every event — wasted work that scales with mounted-then-detached fragments.

**Fix:** Either:
- (a) Implement `ApplicationListener<ChatModelFallbackAppliedEvent>` via a singleton bridge bean that dispatches to currently-attached fragments through a UI-thread access registry; or
- (b) Manually register/unregister the listener using `ApplicationContext.addApplicationListener` / `removeApplicationListener` in fragment `onAttach`/`onDetach` lifecycle hooks.

The Phase 15 sidebar chat surface uses a similar pattern (`onAiTaskFileDeleted`) — verify the same pattern is applied consistently. If it isn't, this is a pre-existing condition; document and defer.

## Info

### IN-01: `AiUiSettingsResolver.loadSingleton()` called on every resolve method invocation — 8+ DB reads per chat turn

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiUiSettingsResolver.java:78-90, 97-240`
**Issue:** Each `resolveXxx()` method calls `loadSingleton()` fresh, producing 8+ identical singleton reads per chat turn (one for each cluster consulted: task-file cleanup, media resolver, mutation idempotency, mutation bulk-max, prompt entity inventory, tools max-filter-depth, title max-context, title min-trigger, etc.).

This is documented as "Phase 18 owns memoization" in 16-CONTEXT.md, so it is by design — but the comment in the resolver class Javadoc says only "Read fresh per turn" without specifying that "per turn" actually means "per consumer call". An admin who reads the resolver class might assume one load per turn and rely on that for invariants.

**Fix:** Sharpen the Javadoc to say "Per call (Phase 18 will add per-turn memoization)". Out of v1 perf scope, but worth a docstring tightening for the next reviewer.

### IN-02: `KnobInventoryScanner.scanEnvironmentForSecrets` sanitises key for `displayMessageKey` but uses raw key elsewhere

**File:** `ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/KnobInventoryScanner.java:294-298`
**Issue:**
```java
String sanitised = name.replaceAll("[^a-zA-Z0-9]", "_");
rows.add(new KnobInventory.SecretIndicatorRow(
        name,                                       // raw key
        "bootConfig.knob.secret." + sanitised,      // sanitised for message key
        configured));
```

The `name` field is rendered to the DOM as the column "key" in the secrets grid. It's not a secret value — just the property name — but a malicious host config could craft a key that contains HTML-special characters (`<script>`-shaped keys are unlikely but theoretically possible). Vaadin's `Span.setText` HTML-escapes by default, so XSS risk is minimal. Just worth noting that raw key contents reach the DOM even though the value is masked.

**Fix:** None required — Vaadin Span handles escaping. Documented for awareness.

### IN-03: Sentinel `-1` for `taskFilePerTurnMaxFiles` and `taskFilePerTurnMaxTotalBytes` is documented but not enforced as the only "disable" value

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java:63-71`
**Issue:** The entity comment block says "sentinel `-1` disables", but bean validation accepts any value in `[-1, 100]` (files) or `[-1, 524288000]` (bytes). A value of `0` is accepted — meaning "zero files per turn" — which silently breaks the upload feature without an error. The fix is the same as WR-01; this item just calls out the duplicate cluster.

**Fix:** See WR-01.

---

_Reviewed: 2026-05-13T15:33:13Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
