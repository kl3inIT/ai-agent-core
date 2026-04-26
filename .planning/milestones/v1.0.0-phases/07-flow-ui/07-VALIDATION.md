---
phase: 07
phase-slug: flow-ui
date: 2026-04-21
---

# Phase 07 Validation Plan

Extracted verbatim from `07-RESEARCH.md` (sections: Validation Architecture, Phase Requirements → Test Map, Wave 0 Gaps).

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5.x (Spring Boot test starter) + Mockito + Jmix `@UiTest` |
| Config file | `ai-agent/ai-agent.gradle` test block |
| Quick run command | `./gradlew :ai-agent:ai-agent:test` |
| Full suite command | `./gradlew :ai-agent:ai-agent:check` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| UI-01 | Streaming chat Flux subscribe + UI.access dispatch | unit (Mockito stub ChatService returning Flux.just) + `@UiTest` | `./gradlew :ai-agent:ai-agent:test --tests "*ChatViewStreamTest"` | ❌ Wave 0 |
| UI-02 | Stop disposes active stream + calls CancellationRegistry | unit | `./gradlew ... --tests "*ChatViewStopTest"` | ❌ Wave 0 |
| UI-03 | ConversationListView role-aware filter | integration `@SpringBootTest` | `... --tests "*ConversationListRoleFilterTest"` | ❌ Wave 0 |
| UI-04 | Form → YAML preview live sync | `@UiTest` | `... --tests "*ParametersDetailYamlPreviewTest"` | ❌ Wave 0 |
| UI-05 | Upload succeeded triggers upload service + grid refresh | `@UiTest` + event-broker mock | `... --tests "*KnowledgeBaseUploadTest"` | ❌ Wave 0 |
| UI-05 status push | Status event via publisher → UI.access on attached UIs | integration | `... --tests "*DocumentStatusPushTest"` | ❌ Wave 0 |
| UI-06 | Audit filter + Excel + JSON export | `@UiTest` | `... --tests "*ToolCallAuditListViewTest"` | ❌ Wave 0 |
| UI-09 | No hardcoded strings in view XML | unit classpath scan | `... --tests "*LocaleParityTest"` | ❌ Wave 0 |
| UI-09 | en ↔ vi parity | unit | `... --tests "LocaleParityTest#enAndViHaveIdenticalKeys"` | ❌ Wave 0 |
| UI-10 | Admin views reject non-admin user | integration `@SpringBootTest` with test-user fixture | `... --tests "*AdminViewAccessTest"` | ❌ Wave 0 |
| D-07 XSS | Flexmark + sanitizer strips `<script>` | unit | `... --tests "MarkdownRendererXssTest"` | ❌ Wave 0 |
| D-02 Push | AppShell @Push class present or documented snippet | unit classpath probe | `... --tests "*PushAutoConfigTest"` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew :ai-agent:ai-agent:test` (excludes `live`, `rag-it`, `eval`)
- **Per wave merge:** `./gradlew :ai-agent:ai-agent:check`
- **Phase gate:** Full suite green + manual `./gradlew :jmix-app:bootRun` click-through of Chat/Conv/Params/KB/Audit for success criteria #1–#5.

### Wave 0 Gaps
- [ ] `src/test/java/com/vn/agent/view/chat/ChatViewStreamTest.java`
- [ ] `src/test/java/com/vn/agent/view/chat/ChatViewStopTest.java`
- [ ] `src/test/java/com/vn/agent/view/chat/MarkdownRendererXssTest.java`
- [ ] `src/test/java/com/vn/agent/view/knowledge/KnowledgeBaseUploadTest.java`
- [ ] `src/test/java/com/vn/agent/view/knowledge/DocumentStatusPushTest.java`
- [ ] `src/test/java/com/vn/agent/view/parameters/ParametersDetailYamlPreviewTest.java`
- [ ] `src/test/java/com/vn/agent/view/audit/ToolCallAuditListViewTest.java`
- [ ] `src/test/java/com/vn/agent/view/conversation/ConversationListRoleFilterTest.java`
- [ ] `src/test/java/com/vn/agent/security/AdminViewAccessTest.java`
- [ ] `src/test/java/com/vn/agent/i18n/LocaleParityTest.java` (locale parity + hardcoded-string scan)
- [ ] `src/test/java/com/vn/agent/push/PushAutoConfigTest.java`
- [ ] Framework install: none (JUnit + Spring Boot test + `@UiTest` already on classpath)

---

**Note:** Full validation plan lives in 07-07a (Wave 0 test skeletons) and 07-07b (green-fill).
