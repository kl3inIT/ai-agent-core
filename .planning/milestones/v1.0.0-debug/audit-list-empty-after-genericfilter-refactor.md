---
slug: audit-list-empty-after-genericfilter-refactor
status: resolved
root_cause: |
  <column property="outcome" key="outcome"> trên dataGrid kích hoạt metamodel binding với property "outcome".
  Entity AiAuditEvent có dual-typing: JPA field `private String outcome` nhưng getter `public AiToolCallOutcome getOutcome()`.
  Jmix metamodel (qua JavaBeans introspection) đăng ký property "outcome" kiểu enum AiToolCallOutcome.
  Khi grid bind property + có @Supply renderer, default value-provider gọi getOutcome() → AiToolCallOutcome,
  pipeline format/render crash silently khi build cell, làm body grid không render row nào dù loader trả 53 row.
  Phiên bản trước (chỉ <column key="outcome"> không có property=) né vấn đề vì tạo column tổng hợp,
  để @Supply renderer hoàn toàn quyết định nội dung cell — không động vào metamodel.
fix: |
  Revert XML: <column property="outcome" key="outcome"> -> <column key="outcome">.
  Giữ nguyên @Supply(to="auditsDataGrid.outcome", subject="renderer") trong AiAuditEventListView.
verification: User reload /ai-agent/audit, xác nhận 53 root rows hiển thị + outcome badge render đúng.
files_changed:
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/audit/ai-audit-event-list-view.xml
trigger: |
  DATA_START
  Người dùng mở /ai-agent/audit thì grid trống. UI render đúng (filter bar + header), không có row nào hiển thị. Vừa refactor view audit theo các thay đổi trong session hiện tại:
    1. Bỏ UnconstrainedDataManager + @Install loadDelegate, dùng DataManager chuẩn.
    2. Thay <hbox filterBar> + rebuildQuery() bằng <genericFilter> + 5 <propertyFilter>.
    3. Date filter giờ là 2 propertyFilter (GREATER_OR_EQUAL + LESS_OR_EQUAL) trên cùng property startedAt.
    4. Bỏ row-click listener trùng action column.
    5. <column key="outcome"> -> <column property="outcome" key="outcome">.
  Screenshot từ user: filter "Bộ lọc *" (có dấu * = modified), 5 propertyFilter render đúng, nút Refresh + Add search condition + Excel/JSON export hiển thị, treeDataGrid header có đủ cột nhưng body rỗng.
  DATA_END
created: 2026-04-25T09:49:53Z
updated: 2026-04-25T10:35:00Z
---

# Debug Session: audit-list-empty-after-genericfilter-refactor

## Symptoms

- **Expected:** treeDataGrid #auditsDataGrid hiển thị các root CHAT events (e.parent IS NULL) sau khi mở /ai-agent/audit, mỗi root expand được để xem TOOL/RETRIEVAL children.
- **Actual:** Grid trống. Filter bar + header hiển thị bình thường. Tiêu đề filter có dấu `*` ("Bộ lọc *").
- **Errors:** Không có error message hiện trong UI. Chưa kiểm tra console/server log.
- **Timeline:** Bắt đầu sau khi áp loạt refactor trong cùng session — đặc biệt sau khi (a) bỏ UnconstrainedDataManager loadDelegate và (b) thay filter bar + rebuildQuery() bằng genericFilter.
- **Reproduction:** Login admin/admin → mở /ai-agent/audit → grid trống. Chưa thử bấm Refresh.

## Recent Changes (in this session)

- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/AiAuditEventListView.java` — gỡ ~150 dòng: bỏ DataManager/UnconstrainedDataManager loadDelegate, bỏ rebuildQuery, bỏ 5 filter handler, bỏ loadDistinctEventNames. Giờ chỉ còn 2 renderer (actions, outcome) + openDetailDialog.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/audit/ai-audit-event-list-view.xml` — thay `<hbox filterBar>` bằng `<genericFilter>` chứa `<configuration default="true">` với 5 propertyFilter (userUsername CONTAINS, eventName EQUAL, outcome EQUAL, startedAt GREATER_OR_EQUAL + LESS_OR_EQUAL).
- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java` — thêm `.store("agentstore")` vào findChatRootId loadValue (đã fix runtime exception trước đó).

## Pre-Investigation Findings

- `AiAgentAdminRole` đã cấp `EntityPolicyAction.ALL` trên `AiAuditEvent` (security/AiAgentAdminRole.java:27).
- `AiAgentUserRowLevelRole` chỉ có policy cho `AiConversation` và `AiMessage`, KHÔNG có cho `AiAuditEvent` → không có narrowing predicate cho admin.
- Loader query: `select e from ai_AiAuditEvent e where e.parent is null order by e.startedAt desc` (XML).
- `<facets><dataLoadCoordinator auto="true"/></facets>` vẫn còn → theo Jmix docs sample `generic-filter-add-condition.md` thì pattern này auto-load.
- `AiAuditEvent` ở `@Store(name = "agentstore")` — loader bind qua `<collection class="...">` nên store auto-inferred (an toàn).

## Hypotheses (priority)

1. ~~**DB không có row root CHAT nào (parent IS NULL).**~~ **ELIMINATED** — JDBC probe (10.123.123.174:5555/agentstore) cho thấy `total=61, roots=53, chat_roots=24`. Data tồn tại, recent CHAT roots từ 2026-04-25 16:53 có sẵn.
2. ~~**Loader bị `propertyFilter` design-time đang ở trạng thái "modified" (`Bộ lọc *`) chặn auto-apply lần đầu.**~~ **ELIMINATED** — đọc Jmix 2.8.0 source `GenericFilter.java:95,759`: dấu ` *` là `GLOBAL_CONFIGURATION_NAME_POSTFIX` áp khi `configuration.isAvailableForAllUsers()` (i.e. design-time global config). KHÔNG phải dirty marker.
3. **Loader silently throw exception** trong giai đoạn view-init — fetch plan có `<property name="children" fetchPlan="_base"/>` (composition); nếu Jmix cấm composition trong fetch plan của tree-only-roots query, có thể throw caught error.
4. **Cấu hình OUTCOME column đổi từ `key=` sang `property="outcome" key="outcome"`** triggered metamodel resolution issue — entity có field `String outcome` (JPA reads field) nhưng getter trả `AiToolCallOutcome` (JavaBeans → metamodel sees enum). `AbstractGridLoader.loadColumn` line 401-402 gọi `getMetaDataTools().resolveMetaPropertyPathOrNull(metaClass, "outcome")` rồi `addColumn(key, metaPropertyPath)` — nếu binding hoặc value-provider crash khi enum-typed property, có thể chặn render row body. (Khả năng cao nhất theo evidence hiện tại — version trước ăn `<column key="outcome">` không có `property` thì hoạt động.)
5. **dataLoadCoordinator auto + genericFilter conflict riêng cho treeDataGrid** (vs flat dataGrid trong docs sample). Đọc `AbstractDataLoadCoordinator.configureAutomatically(loader)`: query không có :param → add `BeforeShowEvent` default trigger → `loader.load()`. Filter condition hiện rỗng → query không bị thêm WHERE. Khả năng thấp.

## Source-code analysis (jmix-flowui 2.8.0)

- `GenericFilterLoader.loadConfigurationComponents` (line 198-213): iterate ALL children của `<configuration>`, mỗi element được add vào `rootLogicalFilterComponent` của configuration (groupFilter ngầm). 5 propertyFilter siblings hợp lệ về mặt code mặc dù XSD thấy mỗi loại tối đa 1 (XSD lỏng do dom4j parse free).
- `GenericFilterLoader.applyFilterIfNeeded` (line 263-291): nếu DataLoadCoordinator có triggers thì SKIP — KHÔNG gọi `apply()` thủ công. Initial load delegated cho coordinator.
- `AbstractDataLoadCoordinator.configureAutomatically(loader)` (line 131-162): query không có `:foo` param → `queryParameters.isEmpty()` true → add `BeforeShowEvent` default trigger → `loader.load()` chạy 1 lần khi view open.
- `GenericFilter.updateDataLoaderCondition` (line 765-783): tạo condition kết hợp `initialDataLoaderCondition` + filter root condition. Nếu filter values rỗng → filter root condition là empty LogicalCondition → query loader không bị thêm WHERE.

→ Theo source, initial load **PHẢI** chạy với query `where e.parent is null order by e.startedAt desc` trên 53 row. Việc grid trống chỉ có thể do (a) loader throw exception silently caught ở `BeforeShowEvent` listener, hoặc (b) load chạy nhưng grid binding crash khi build cell value cho 1 column → vô hiệu hoá render body.

## Current Focus

- hypothesis: **H4 (column outcome property/key conflict gây metamodel/binding error)** — likely; H3 (fetch plan exception trên view-init) — secondary.
- next_action: 
  - (a) Cần xem server log lúc mở view (user terminal đang chạy bootRun) — tìm exception/stack trace.
  - (b) Đề xuất fix: hoàn nguyên `<column property="outcome" key="outcome">` về `<column key="outcome">` (giữ @Supply renderer làm cell content). Đây là pattern đã prove hoạt động trước refactor.
  - (c) Nếu fix (b) không đủ, đơn giản hoá `<genericFilter>` về dạng working trong project (customer/user/order/product list views — empty body, chỉ `<properties include=".*"/>`).

## Evidence

- 2026-04-25T09:49:53Z: Screenshot xác nhận UI render hợp lệ, filter bar có dấu `*`, body grid rỗng.
- 2026-04-25T09:49:53Z: Security config kiểm tra OK — admin có ALL policy, không có row-level narrowing trên AiAuditEvent.
- 2026-04-25T10:30:00Z: JDBC probe agentstore DB → `total=61 roots=53 chat_roots=24`, recent CHAT root có outcome=SUCCESS, started_at=2026-04-25 16:53. **DB có data, query loader phải trả về 53 row.**
- 2026-04-25T10:30:30Z: Đọc `jmix-flowui-2.8.0-sources.jar` — xác nhận:
  - `GLOBAL_CONFIGURATION_NAME_POSTFIX = " *"` áp dụng cho mọi design-time configuration (`isAvailableForAllUsers()=true`). **Dấu `*` là cosmetic, KHÔNG phải dirty marker.**
  - `applyFilterIfNeeded` SKIP nếu DataLoadCoordinator có triggers.
  - `configureAutomatically` thêm `BeforeShowEvent` default trigger khi query không có :param.
  - 5 sibling propertyFilter trong `<configuration>` được loader iterate hết và add vào root group — hợp lệ về code.
- 2026-04-25T10:31:00Z: `git diff HEAD` view xml: thay đổi chính so với committed version `751f8c3` là (a) thêm `<configuration default="true">` block, (b) đổi `<column key="outcome">` → `<column property="outcome" key="outcome">`. Trước refactor, view hoạt động được.

## Eliminated

- H1: DB trống. **Eliminated** bằng JDBC probe — 53 root rows.
- H2: Filter `*` = dirty state chặn auto-load. **Eliminated** bằng đọc Jmix source — `*` là cosmetic GLOBAL_CONFIGURATION_NAME_POSTFIX cho design-time config.
