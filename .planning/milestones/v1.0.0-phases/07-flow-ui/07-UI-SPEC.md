---
phase: 7
slug: flow-ui
status: approved
shadcn_initialized: false
preset: not applicable (Vaadin Lumo / Jmix Flow UI)
created: 2026-04-21
reviewed_at: 2026-04-21
---

# Phase 7 — UI Design Contract

> Visual and interaction contract for the Jmix Flow UI layer of `ai-agent`. Stack is locked by Phase 1 D-01 and Phase 7 CONTEXT D-01..D-31: Vaadin Flow + Jmix Flow UI themed by Lumo. shadcn is not applicable. This spec constrains visual tokens, component choices, copy, and reusable-Fragment contracts so the planner and executor have one source of truth.

---

## Design System

| Property | Value |
|----------|-------|
| Tool | none (Jmix-shipped Vaadin Lumo theme) |
| Preset | not applicable — shadcn does not apply to Vaadin Flow |
| Component library | Jmix Flow UI 2.8 (`io.jmix.flowui:jmix-flowui-starter` + `jmix-flowui-themes`) over Vaadin Flow 24 |
| Icon library | Vaadin Icons (`com.vaadin.flow.component.icon.VaadinIcon`) — ships with Jmix, zero extra dep |
| Font | Lumo default (`--lumo-font-family`: Inter/system stack). Do not override. |
| Theme variant | Lumo **light** by default; respect user OS preference via existing Jmix theme toggle if host enables it. Do not hardcode `theme="dark"`. |
| Density | Lumo default. For the audit + KB grids, add `theme="compact"` on the grid only (high-density tabular data). |
| Markdown renderer | Flexmark (`com.vladsch.flexmark:flexmark` — new dep per CONTEXT D-07), output wrapped in `Html`, sanitized via Flexmark `HtmlSanitizer` or `jsoup` safelist (basic + links). |
| Push transport | `@Push(transport = Transport.WEBSOCKET_XHR)` — contributed via add-on `AppShellConfigurator` per CONTEXT D-02. |

**Source:** Phase 1 D-01 (2-module shape, views in `ai-agent`), Phase 7 CONTEXT D-02, D-07, D-15, D-17, D-20, D-22, D-27.

---

## Spacing Scale

All paddings, gaps, and margins **must** come from Lumo space tokens. Do not emit raw px in CSS or `style="..."`.

| Token | Lumo variable | Resolved px (default theme) | Usage |
|-------|---------------|-----------------------------|-------|
| xs | `--lumo-space-xs` | 4px | Icon gaps, badge padding, inline adjustments |
| sm | `--lumo-space-s` | 8px | Compact vertical gaps inside a message bubble, list-item padding |
| md | `--lumo-space-m` | 16px | Default control gaps, bubble-to-bubble gap, form row gap |
| lg | `--lumo-space-l` | 24px | View padding (outermost layout `setPadding(true)` → md; content frame → l), dialog padding |
| xl | `--lumo-space-xl` | 36px | Major section breaks in ParametersDetailView (between Form + YAML Preview tabs’ content sections) |

**Enforced rules:**
- `VerticalLayout` / `HorizontalLayout` use `.setSpacing(true)` + default Lumo gap; only override via `.getThemeList().add("spacing-s|spacing-m|spacing-l")` if stricter density is needed.
- **Chat message list:** vertical gap `sm` (8px) between consecutive same-role messages, `md` (16px) between role boundaries.
- **Bubble internal padding:** `md` (16px) horizontal, `sm` (8px) vertical.
- **Dialogs** (citation preview, audit detail, reingest confirm): `setWidth("var(--lumo-size-xl-plus)")` equivalent — use `Dialog` default + content padding `lg`.
- **Grids:** use `theme="compact"` (≈ row height `--lumo-size-s`) for audit + KB only; conversations list uses default row height.

**Exceptions:** `ChatView` input bar (`MessageInput`) spans full width with `padding="md"` top and bottom; the streaming indicator dot is `4px × 4px` (xs) — these are visual accents, not layout spacing.

---

## Typography

Sized via Lumo font-size variables only. No hardcoded `font-size` values anywhere.

| Role | Lumo token | Resolved (default) | Weight | Line height | Usage |
|------|-----------|--------------------|--------|-------------|-------|
| Body | `--lumo-font-size-m` | 16px | 400 | 1.5 | Chat message text, form field values, grid cells |
| Label | `--lumo-font-size-s` | 14px | 500 | 1.4 | Form labels, tool-call-card metadata, tab labels, badge text |
| Heading | `--lumo-font-size-xl` | 22px | 600 | 1.25 | `H2` view titles (`Chat`, `Parameters`, etc.) |
| Small | `--lumo-font-size-xs` | 13px | 400 | 1.4 | Citation markers `[n]`, timestamps, auxiliary metadata (`tokens: 123`) |
| Monospace | `--lumo-font-family-monospace` via `theme="font-mono"` on `Pre`/`Code` | inherit | 400 | 1.4 | YAML preview, tool args JSON, structured tool result |

**Rules:**
- Exactly **2** weights: `400` (regular) and `500`/`600` (semibold via Lumo defaults).
- Exactly **4** declared roles above (Body, Label, Heading, Small) + Monospace utility. View-title-level `H2` is the only heading variant Phase 7 needs; do not introduce `H1`/`H3`.
- Markdown renderer must map `#` → Body size with weight 600 (treat chat markdown headings as emphasis, not page chrome), `##`/`###` → Body/Label sizes. Executor sets this via Flexmark config + one scoped CSS class, not via raw inline styles.

---

## Color

Driven entirely by Lumo semantic variables — never hex literals in view code. Theme-agnostic (works in light + dark).

| Role | Lumo token | Usage |
|------|-----------|-------|
| Dominant (60%) | `--lumo-base-color` | Primary view background, input backgrounds |
| Secondary (30%) | `--lumo-contrast-5pct` / `--lumo-contrast-10pct` | Card / bubble / dialog surfaces, side-panel, disabled field background |
| Accent (10%) | `--lumo-primary-color` / `--lumo-primary-text-color` | **Reserved list** — see below |
| Destructive | `--lumo-error-color` / `--lumo-error-text-color` | `Delete document`, `Reingest` confirm-dialog primary button, `FAILED` badges, hard-error inline validation |
| Text primary | `--lumo-body-text-color` | All readable content |
| Text secondary | `--lumo-secondary-text-color` | Timestamps, metadata rows, citation markers |
| Tertiary / muted | `--lumo-tertiary-text-color` | Placeholder text, disabled-state labels |

**Accent reserved for — and ONLY for:**
1. The single primary action button per view: `Send` in ChatView, `Save` in ParametersDetailView, `Upload files` in KnowledgeBaseView, `Export Excel` in ToolCallAuditListView (the primary export; `Export JSON` is a secondary button with no accent).
2. `Set active` action (list-row + detail button) — `theme="primary success"` on ParametersListView + Detail.
3. Assistant-role chat bubble left border (2px primary).
4. Active-profile row highlight in ParametersListView (left border 3px primary + `--lumo-primary-color-10pct` row tint).
5. Citation markers `[n]` (primary text color, underlined on hover).

Accent is **never** used for: all buttons, all links, all icons, all headings, navigation items, grid selection highlight (use Lumo default), filter chips, or tool-call-card chrome.

**Badge colour mapping (shared across KB, Audit, Chat tool cards):**

| State | Badge theme | Lumo variant |
|-------|-------------|--------------|
| Success / READY / SUCCESS | `success` | `--lumo-success-color` |
| Failure / FAILED / ERROR | `error` | `--lumo-error-color` |
| Warning / FLAGGED / DENIED | `warning` | `--lumo-warning-color` (tool-call-card outcome when guard vetoed) |
| Pending / PROCESSING / IN-PROGRESS | `contrast` | `--lumo-contrast` |
| Cancelled / TIMEOUT / neutral | `contrast tertiary` | muted contrast |

Executor applies via `element.getThemeList().add("badge success")` — the shared `BadgeRenderer` helper component is a required deliverable.

---

## Copywriting Contract

All strings resolved through `msg://` keys in `messages.properties` (en, base) and `messages_vi.properties`. **Zero** hardcoded literals in view XML or view Java (enforced by Phase 7 coverage test per CONTEXT D-30). Keys below are authoritative; planner must include them verbatim in the message bundles for both locales.

### Global menu + view titles

| Key | EN | VI |
|-----|----|----|
| `com.vn.agent/menu.addon` | AI Agent | Trợ lý AI |
| `com.vn.agent/menu.chat` | Chat | Trò chuyện |
| `com.vn.agent/menu.conversations` | Conversations | Hội thoại |
| `com.vn.agent/menu.parameters` | Parameters | Tham số |
| `com.vn.agent/menu.knowledge` | Knowledge base | Cơ sở tri thức |
| `com.vn.agent/menu.audit` | Tool call audit | Nhật ký gọi công cụ |

### ChatView (UI-01, UI-02)

| Key | EN | VI |
|-----|----|----|
| `chatView.title` | Chat | Trò chuyện |
| `chatView.input.placeholder` | Ask something about your data… | Đặt câu hỏi về dữ liệu của bạn… |
| `chatView.action.send` | Send | Gửi |
| `chatView.action.newChat` | New chat | Cuộc trò chuyện mới |
| `chatView.action.stop` | Stop | Dừng |
| `chatView.empty.heading` | Start a conversation | Bắt đầu một cuộc trò chuyện |
| `chatView.empty.body` | Ask about entities, upload documents in Knowledge base, or try: “List the last 5 orders.” | Hỏi về dữ liệu, tải tài liệu lên Cơ sở tri thức, hoặc thử: “Liệt kê 5 đơn hàng gần nhất.” |
| `chatView.streaming.indicator` | Thinking… | Đang xử lý… |
| `chatView.toolCard.collapsed` | {0} ({1}) | {0} ({1}) |
| `chatView.toolCard.argsHeading` | Arguments | Tham số |
| `chatView.toolCard.resultHeading` | Result | Kết quả |
| `chatView.toolCard.outcome.success` | Success | Thành công |
| `chatView.toolCard.outcome.failed` | Failed | Thất bại |
| `chatView.toolCard.outcome.denied` | Denied | Từ chối |
| `chatView.citation.dialogTitle` | Source: {0} | Nguồn: {0} |
| `chatView.citation.openInKb` | Open in Knowledge base | Mở trong Cơ sở tri thức |
| `chatView.error.generic` | Something went wrong. Please try again. | Đã xảy ra lỗi. Vui lòng thử lại. |
| `chatView.error.rateLimited` | You’re sending messages too quickly. Please wait a moment. | Bạn đang gửi tin nhắn quá nhanh. Vui lòng chờ một chút. |
| `chatView.error.tokenBudget` | This conversation has reached its length limit. Start a new chat to continue. | Cuộc trò chuyện đã đạt giới hạn độ dài. Hãy bắt đầu cuộc trò chuyện mới. |
| `chatView.error.flagged` | The model response was flagged and withheld. | Phản hồi của mô hình đã bị gắn cờ và không được hiển thị. |
| `chatView.confirm.newChat.title` | Start a new chat? | Bắt đầu cuộc trò chuyện mới? |
| `chatView.confirm.newChat.body` | The current conversation will remain in your history. | Cuộc trò chuyện hiện tại vẫn được lưu trong lịch sử. |

### ConversationListView / DetailView (UI-03)

| Key | EN | VI |
|-----|----|----|
| `conversationList.title` | Conversations | Hội thoại |
| `conversationList.column.title` | Title | Tiêu đề |
| `conversationList.column.createdDate` | Started | Bắt đầu |
| `conversationList.column.messageCount` | Messages | Số tin nhắn |
| `conversationList.column.user` | User | Người dùng |
| `conversationList.empty.heading` | No conversations yet | Chưa có hội thoại |
| `conversationList.empty.body` | Conversations appear here after you chat with the AI Agent. | Hội thoại sẽ xuất hiện sau khi bạn trò chuyện với Trợ lý AI. |
| `conversationDetail.title` | {0} | {0} |
| `conversationDetail.action.continueInChat` | Continue in chat | Tiếp tục trò chuyện |
| `conversationDetail.banner.readOnly` | Read-only replay | Chế độ xem lại |

### ParametersListView / DetailView (UI-04)

| Key | EN | VI |
|-----|----|----|
| `parametersList.title` | Parameters | Tham số |
| `parametersList.column.name` | Profile | Hồ sơ |
| `parametersList.column.model` | Model | Mô hình |
| `parametersList.column.active` | Active | Đang dùng |
| `parametersList.action.setActive` | Set active | Đặt làm hiện hành |
| `parametersList.badge.active` | Active | Đang dùng |
| `parametersDetail.title.new` | New parameters profile | Hồ sơ tham số mới |
| `parametersDetail.title.edit` | Edit parameters | Chỉnh sửa tham số |
| `parametersDetail.tab.form` | Form | Biểu mẫu |
| `parametersDetail.tab.yaml` | YAML preview | Xem trước YAML |
| `parametersDetail.yaml.banner` | Read-only preview. Edit fields in the Form tab. | Xem trước chỉ đọc. Chỉnh sửa ở tab Biểu mẫu. |
| `parametersDetail.field.model` | Model | Mô hình |
| `parametersDetail.field.temperature` | Temperature | Nhiệt độ |
| `parametersDetail.field.topP` | Top-P | Top-P |
| `parametersDetail.field.maxTokens` | Max tokens | Token tối đa |
| `parametersDetail.field.systemPrompt` | System prompt | Lời nhắc hệ thống |
| `parametersDetail.field.enabledTools` | Enabled tools | Công cụ khả dụng |
| `parametersDetail.field.rateLimit` | Rate limit (req/min per user) | Giới hạn tần suất (req/phút/người dùng) |
| `parametersDetail.field.tokenBudget` | Token budget per conversation | Ngân sách token mỗi cuộc trò chuyện |
| `parametersDetail.field.iterationCap` | Tool iteration cap | Giới hạn lặp công cụ |
| `parametersDetail.field.outputScannerPatterns` | Output scanner patterns | Mẫu quét đầu ra |
| `parametersDetail.action.save` | Save | Lưu |
| `parametersDetail.action.setActive` | Set active | Đặt làm hiện hành |
| `parametersDetail.validation.temperature.range` | Temperature must be between 0.0 and 2.0. | Nhiệt độ phải trong khoảng 0.0 đến 2.0. |
| `parametersDetail.validation.topP.range` | Top-P must be between 0.0 and 1.0. | Top-P phải trong khoảng 0.0 đến 1.0. |
| `parametersDetail.validation.modelRequired` | Model id is required. | Bắt buộc chọn Mô hình. |
| `parametersDetail.validation.unknownTool` | Unknown tool: {0} | Công cụ không xác định: {0} |

### KnowledgeBaseView (UI-05)

| Key | EN | VI |
|-----|----|----|
| `knowledgeBase.title` | Knowledge base | Cơ sở tri thức |
| `knowledgeBase.upload.placeholder` | Drop files here or click to upload | Kéo thả tệp hoặc bấm để tải lên |
| `knowledgeBase.upload.button` | Upload files | Tải lên |
| `knowledgeBase.column.filename` | File | Tệp |
| `knowledgeBase.column.mimeType` | Type | Loại |
| `knowledgeBase.column.status` | Status | Trạng thái |
| `knowledgeBase.column.uploadedDate` | Uploaded | Tải lên lúc |
| `knowledgeBase.column.roles` | Roles | Vai trò |
| `knowledgeBase.status.pending` | Pending | Đang chờ |
| `knowledgeBase.status.processing` | Processing | Đang xử lý |
| `knowledgeBase.status.ready` | Ready | Sẵn sàng |
| `knowledgeBase.status.failed` | Failed | Thất bại |
| `knowledgeBase.status.cancelled` | Cancelled | Đã huỷ |
| `knowledgeBase.action.reingest` | Reingest | Nạp lại |
| `knowledgeBase.action.delete` | Delete | Xoá |
| `knowledgeBase.confirm.reingest.title` | Reingest {0}? | Nạp lại {0}? |
| `knowledgeBase.confirm.reingest.body` | Existing chunks will be replaced. | Các đoạn hiện tại sẽ bị thay thế. |
| `knowledgeBase.confirm.delete.title` | Delete {0}? | Xoá {0}? |
| `knowledgeBase.confirm.delete.body` | The document and all its embedded chunks will be removed. This cannot be undone. | Tài liệu và toàn bộ đoạn nhúng sẽ bị xoá. Không thể hoàn tác. |
| `knowledgeBase.empty.heading` | No documents yet | Chưa có tài liệu |
| `knowledgeBase.empty.body` | Upload PDF, Markdown, TXT or HTML to let the AI Agent answer from your content. | Tải lên tệp PDF, Markdown, TXT hoặc HTML để Trợ lý AI có thể trả lời dựa trên nội dung của bạn. |
| `knowledgeBase.upload.rejected` | {0} is not a supported file type. | {0} không phải loại tệp được hỗ trợ. |
| `knowledgeBase.toast.uploadStarted` | Upload started: {0} | Đã bắt đầu tải lên: {0} |

### ToolCallAuditListView (UI-06)

| Key | EN | VI |
|-----|----|----|
| `auditList.title` | Tool call audit | Nhật ký gọi công cụ |
| `auditList.filter.user` | User | Người dùng |
| `auditList.filter.tool` | Tool | Công cụ |
| `auditList.filter.outcome` | Outcome | Kết quả |
| `auditList.filter.dateRange` | Date range | Khoảng thời gian |
| `auditList.action.exportExcel` | Export Excel | Xuất Excel |
| `auditList.action.exportJson` | Export JSON | Xuất JSON |
| `auditList.column.createdDate` | Time | Thời điểm |
| `auditList.column.user` | User | Người dùng |
| `auditList.column.tool` | Tool | Công cụ |
| `auditList.column.phase` | Phase | Giai đoạn |
| `auditList.column.outcome` | Outcome | Kết quả |
| `auditList.column.latencyMs` | Latency (ms) | Độ trễ (ms) |
| `auditList.outcome.success` | Success | Thành công |
| `auditList.outcome.failed` | Failed | Thất bại |
| `auditList.outcome.denied` | Denied | Từ chối |
| `auditList.outcome.timeout` | Timeout | Hết thời gian |
| `auditList.outcome.cancelled` | Cancelled | Đã huỷ |
| `auditList.detail.title` | Tool call details | Chi tiết gọi công cụ |
| `auditList.detail.runId` | Run id | Mã phiên |
| `auditList.detail.argsJson` | Arguments | Tham số |
| `auditList.detail.resultSummary` | Result | Kết quả |
| `auditList.detail.errorClass` | Error class | Loại lỗi |
| `auditList.detail.denialReason` | Denial reason | Lý do từ chối |
| `auditList.detail.flagged` | Flagged | Đã gắn cờ |
| `auditList.empty.heading` | No audit records | Chưa có bản ghi |
| `auditList.empty.body` | Tool calls and denials appear here once users interact with the AI Agent. | Các lượt gọi công cụ và từ chối sẽ hiển thị ở đây sau khi người dùng sử dụng Trợ lý AI. |

### Destructive actions summary (CONTEXT D-18, KB delete)

| Action | Confirmation dialog | Primary button theme |
|--------|---------------------|----------------------|
| Delete knowledge document | Title + body above | `primary error` |
| Reingest knowledge document | Title + body above | `primary` (not destructive — replaces chunks but restores on success) |
| Stop streaming chat | No dialog — single click, idempotent | `tertiary` |
| Start new chat with unfinished stream | Confirm dialog above | `primary` |
| Set active parameters profile | **No dialog** per CONTEXT D-14 — immediate commit | `primary success` |

---

## Interaction Contracts (Phase 7 specific)

### Chat streaming + cancel (CONTEXT D-01..D-05)
- Send-button click → disabled + replaced by `Stop` button for the duration of the stream.
- Each `UI.access()` tick replaces the in-flight assistant bubble content; do not append-and-reparse per token (perf + flicker). Debounce to max 20 Hz.
- On `Stop`: button disables immediately (optimistic), `CancellationRegistry.cancel(runId)` fires, bubble freezes with existing buffered tokens, a muted `— stopped` suffix appears in Small type.
- Graceful fallback (non-streaming model, D-04): single bubble appears when `Flux` emits its terminal chunk; `Stop` button is hidden (not disabled) for the whole request.

### Tool-call card (CONTEXT D-08, D-25 — reusable)
- Shared `ToolCallCard` component in `com.vn.agent.view.chat.fragment` — consumed by both ChatView and ConversationDetailView in read-only mode.
- Collapsed height: 32px (one `Badge` row). Expanded: auto, args + result blocks in monospace, outcome `Badge` bottom-right.
- Expand/collapse via `Details` Vaadin component — keyboard accessible.

### Citation markers (CONTEXT D-09)
- Inline superscript `[n]` rendered after the sentence they attribute to (Flexmark post-processor injects spans with data-citation-index).
- Click opens `Dialog` with chunk text + document name + "Open in Knowledge base" button that navigates to `KnowledgeBaseView` with the document filter pre-applied (`view.navigate(KnowledgeBaseView.class, QueryParameters.of("documentId", uuid))`).

### Parameters form ↔ YAML preview (CONTEXT D-10..D-13)
- Tab layout: `Tabs` at top (`Form` | `YAML preview`). Content host is a single `Div`; on tab change, swap children (no route sub-navigation).
- Form uses Jmix Flow UI data binding with field-level validators declared in XML. Validation failures block `Save`; a global validation summary appears above the form action bar.
- YAML preview regenerates on every `valueChangeEvent` from any form field → `AiParametersBodyYamlMapper.writeAsYaml(buildBodyFromForm())` → set `CodeBlock` (monospace, read-only) contents.

### Knowledge base upload + status (CONTEXT D-15..D-18)
- Jmix `<upload>` component with `acceptedFileTypes="application/pdf,text/markdown,text/plain,text/html"`, `maxFileSize` from a new config property `jmix.ai-agent.ui.kb.maxUploadBytes` (default 20 MB).
- Each successful file upload → dispatches to `KnowledgeDocumentUploadService` → row inserted with `PENDING` → grid refreshes locally + subscribes for status events.
- Status events arrive via Spring `ApplicationEventPublisher` → UI-layer listener → per-UI `UI.access` refresh of just the affected row (Grid `dataProvider.refreshItem(item)`).
- Grid row action buttons (`Reingest`, `Delete`) revealed inline on hover (desktop) or always-visible on mobile widths.

### Audit list filter + export (CONTEXT D-19..D-22)
- Above the grid: typed filter row (4 fields, `HorizontalLayout`, wraps on narrow widths).
- Below: collapsible Jmix `genericFilter` element for ad-hoc.
- Two export buttons bound to grid actions via Jmix `gridexport` add-on (`io.jmix.gridexport:jmix-gridexport-flowui-starter`):
  - `Export Excel` (primary theme) → `<action id="excelExport" type="grdexp_excelExport"/>` on the audit `dataGrid`; filename pattern `audit-{yyyyMMdd-HHmmss}.xlsx`.
  - `Export JSON` (no theme accent, secondary) → `<action id="jsonExport" type="grdexp_jsonExport"/>` on the audit `dataGrid`; filename pattern `audit-{yyyyMMdd-HHmmss}.json`.
  - Both honor current filter + sort automatically; audit `dataGrid` uses `selectionMode="MULTI"` so admins can optionally export a selected subset. CSV export is intentionally not shipped (add-on does not provide one; see CONTEXT D-20).
- Row click → modal `Dialog`, width `var(--lumo-size-xl-plus)` (≈ 960px), close button top-right, `Esc` closes.

### Admin gating (CONTEXT D-28, UI-10)
- `AiAgentAdminRole` gains `@ViewPolicy` + `@MenuPolicy` annotations for `ParametersListView`, `ParametersDetailView`, `KnowledgeBaseView`, `ToolCallAuditListView`.
- Each admin view class also carries `@ViewAccessChecker` (or Jmix 2.8 equivalent) for direct-URL protection.
- `ChatView` + `ConversationListView` + `ConversationDetailView` are accessible to any authenticated Jmix user (`AiAgentUserRole`).

### Routing contract (CONTEXT D-26)
- `ChatView` accepts optional `conversationId` query param: `/ai-agent/chat` (new) vs `/ai-agent/chat?conversationId={uuid}` (continue). Route alias registered via `@Route(value="ai-agent/chat")`.
- `ConversationDetailView.continueInChat()` navigates with the current conversation id.
- Invalid/non-owned `conversationId` → fall through to new chat with a Notification ("Conversation not available").

### Reusable Fragment boundary (CONTEXT D-29)
- `ChatPanelFragment` (`com.vn.agent.view.chat.fragment.ChatPanelFragment`) — owns: message list + input + streaming driver.
- Does **not** own: page title, navigation, user-auth lookup (injected via constructor).
- `ChatView` composes `ChatPanelFragment` + breadcrumb + `New chat` button only. v2 floating/embedded surfaces will compose the same Fragment without modification.

---

## Registry Safety

| Registry | Blocks Used | Safety Gate |
|----------|-------------|-------------|
| shadcn official | not applicable — Jmix add-on | not required |
| Jmix Flow UI 2.8 (`io.jmix.flowui:jmix-flowui-starter`, `jmix-flowui-themes`) | all — already a first-class dep of `ai-agent` | vendored by Jmix upstream, governed by Jmix release cycle; no per-block vetting required |
| Vaadin Flow 24 (transitively via Jmix) | `VerticalLayout`, `HorizontalLayout`, `Scroller`, `MessageInput`, `Tabs`, `Dialog`, `Upload`, `Grid`, `Html`, `Details`, `Icon`, `Badge` (theme), `Notification`, `ConfirmDialog` | transitive through Jmix — no per-block vetting |
| Jmix `gridexport` add-on (new — CONTEXT D-20; `io.jmix.gridexport:jmix-gridexport-flowui-starter`) | `grdexp_excelExport` + `grdexp_jsonExport` actions on `ToolCallAuditListView` dataGrid | first-party Jmix add-on; governed by Jmix release cycle; no per-block vetting required |
| Flexmark (`com.vladsch.flexmark:flexmark` — new, CONTEXT D-07) | core parser + HTML renderer + sanitizer | Apache 2.0; widely adopted server-side markdown; executor must enable sanitizer (strip `<script>`, `javascript:`, `data:` URIs) before rendering into `Html` |
| Third-party registries | **none declared** | not applicable — registry vetting gate skipped per CONTEXT D-27 boundaries |

---

## Checker Sign-Off

- [ ] Dimension 1 Copywriting: PASS
- [ ] Dimension 2 Visuals: PASS
- [ ] Dimension 3 Color: PASS
- [ ] Dimension 4 Typography: PASS
- [ ] Dimension 5 Spacing: PASS
- [ ] Dimension 6 Registry Safety: PASS

**Approval:** pending
