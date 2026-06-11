# Plan — Bỏ pane Attachments bên phải, đưa file inline trong chat (jmix-crm style)

> Scope chốt với user: "Gọn — inline + composer upload", card inline **có download** giống jmix-crm.
> Không qua GSD; plan thủ công. Ràng buộc: **fix hết, đảm bảo build + test xanh** (breaking OK nếu đã cập nhật test).

## Bối cảnh / đính chính

- **`AiTaskFile` = file user upload duy nhất.** Không có `origin`, không có "report AI sinh ra"
  (đó là khái niệm của jmix-crm: `AiConversationAttachment` + `AiAttachmentOrigin`). File được nạp
  làm media/document input cho model qua `AiTaskFileMediaResolver.resolveActive(convId)`.
- **NOTICE `AiMessage` là load-bearing cho MODEL, không chỉ UI.** `ProjectingChatMemoryRepository`
  (D-A1) giữ NOTICE qua các lượt làm "upload ledger" trong chat memory. ⇒ **Vẫn ghi NOTICE**.
  Tách bạch: NOTICE phục vụ model-memory; UI render card từ `AiTaskFile`.
- `ChatSurfaceMounter` **không** bind `attachmentsPanel` (chỉ bind host view) ⇒ rủi ro contract nhẹ.

## Trạng thái hiện tại

- `chat-panel-fragment.xml`: `<split splitterPosition=68>` → trái = chat (68%), phải =
  `attachmentsPanel` (title + emptyState + `gridLayout` card + `<upload>` dropzone). Pane luôn hiển thị.
- Upload đã ghi NOTICE inline (text) + đổ file vào grid phải.
- `AiTaskFileCardFragmentRenderer` + `ai-task-file-card-fragment.xml` = card grid (download + delete).

## Thiết kế đích (jmix-crm style)

- Chat **full-width**, không split.
- Upload = nút đính kèm (paperclip `JmixUpload`) trong thanh input, cạnh `MessageInput`.
- File hiện **inline trong timeline** dạng **card đính kèm có nút download + delete**, neo theo thời điểm.
- NOTICE `AiMessage` **vẫn ghi** (cho model memory) nhưng **UI không render NOTICE text nữa** — thay bằng
  card dựng từ `AiTaskFile` (tránh phải map NOTICE↔AiTaskFile).

## Thay đổi chi tiết

### 1. XML — `chat-panel-fragment.xml`
- Bỏ `<split id="conversationSplit">`; `chatPanel` lên làm content full-width (giữ id `rootLayout`).
- Bỏ vbox `attachmentsPanel` (attachmentsTitle, attachmentsEmptyState, attachmentsGridLayout).
- **Giữ** `<data>` `taskFilesDc`/`taskFilesDl` (cần load file để replay render card).
- Chuyển `<upload id="taskFileUpload">` xuống khu input: hbox bọc `messageInputSlot` + nút upload
  (paperclip, `dropAllowed=false`, giữ accepted types + maxFiles). Hoặc đặt upload là 1 slot nhỏ
  phía trên `messageInputSlot`.

### 2. Component card inline — MỚI
- `AiTaskFileInlineCard` (port `AiAttachmentCard` của jmix-crm): `JmixCard` LUMO_OUTLINED, icon theo
  ext (tái dùng bảng icon của `AiTaskFileCardFragmentRenderer`), filename, size humanize, nút
  **download** (Jmix `Downloader` trên `storageRef`), nút **delete** (option dialog → xóa blob → xóa row
  → `AiTaskFileDeletedUiEvent`). Dựng bằng `UiComponents`/Java thuần (không cần fragment XML).
- Bỏ `AiTaskFileCardFragmentRenderer` + `ai-task-file-card-fragment.xml` (logic download/delete/icon/size
  chuyển vào card mới hoặc 1 helper dùng chung).

### 3. `ChatPanelFragment.java`
- Bỏ field: `attachmentsPanel`, `attachmentsEmptyState`, `attachmentsGridLayout`; bỏ `refreshTaskFiles()`,
  `onTaskFilesPostLoad`. **Giữ** `taskFilesDc`/`taskFilesDl`, `taskFileUpload`, upload handler + validation.
- **Render card inline:** thêm `appendTaskFileCard(AiTaskFile)` dùng `anchorExtra(...)` (hạ tầng anchor có sẵn)
  để neo card vào cuối lượt hiện tại. Wrap trong `.ai-agent-attachment-card` (đổi từ
  `.ai-agent-attachment-notice` text).
- **Replay** (`setConversationIdInternal`): sau khi load `AiMessage`, **time-merge** thêm `AiTaskFile`
  (theo `createdDate`) → render card inline đúng vị trí thời gian. Bỏ nhánh render NOTICE-as-text
  (NOTICE vẫn nằm trong DB cho memory, chỉ không vẽ).
- **Live upload** (`handleUploadedFile`): vẫn ghi NOTICE `AiMessage` (memory); thay
  `appendNoticeRow(text)` bằng `appendTaskFileCard(saved)`; bỏ `taskFilesDl.load()` đổ grid
  (không còn grid) — chỉ append card.
- `onTaskFileDeleted`: xóa card inline tương ứng khỏi `turnExtras` thay vì reload grid.

### 4. Messages
- Tái dùng `chatView.attachments.*` đã có (download/delete/deleteConfirm/missingFileName/upload.*).
- Thêm nếu cần: `chatView.attachments.uploadButton.ariaLabel` (nút paperclip) — cả 2 locale.

### 5. CSS — `ai-agent-chat.css`
- Bỏ rule layout pane phải nếu có; thêm `.ai-agent-attachment-card` (card inline gọn trong timeline).

### 6. Tests (sửa cho xanh — breaking OK)
- `CrmStyleLayoutTest` → viết lại: assert full-width + upload ở input + card inline (bỏ split/pane).
  (cân nhắc đổi tên `InlineAttachmentsLayoutTest`).
- `SurfaceMountingTest` → bỏ assert `attachmentsPanel`/grid.
- `ChatPanelFragmentConversationIdTest` → cập nhật kỳ vọng loader/khu vực upload.
- `AttachmentReviewFixContractTest` → giữ kiểm tra validation MIME/size; trỏ tới vị trí upload mới.
- Thêm test: replay time-merge render đúng số card; live upload append card; delete gỡ card.

## Bất biến phải giữ

- NOTICE vẫn ghi mỗi upload (ledger model memory — `ProjectingChatMemoryRepository`).
- Validation server-side MIME + size + TTL/`expiresAt` sentinel `-1` giữ nguyên (REVIEWS HIGH-5).
- `UploadHandler.toFile` (không quay lại `setReceiver` deprecated — memory).
- Ownership check khi switch conversation; `UnconstrainedDataManager` chỉ cho read có `where userUsername`.
- Streaming/observability (status row, turnExtras anchoring) không đụng.

## Verify
- `get_file_problems` từng file sửa (JetBrains MCP).
- `./gradlew :ai-agent:test` (đặc biệt nhóm `view.chat.*`).
- UI verify thủ công khi app chạy (port 8088): upload từ input → card inline có download; reload
  conversation → card replay đúng; delete card.

## Không làm (defer)
- Context overlay panel (entity references / "chat about this") — phương án parity, để phase riêng.
- Khái niệm AI-generated attachment (không tồn tại trong addon).
