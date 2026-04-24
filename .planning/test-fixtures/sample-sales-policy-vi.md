# Chính sách bán hàng và vận hành đơn hàng (Sales & Order Operations Policy)

Phiên bản: 1.0 — hiệu lực từ 2026-01-01. Tài liệu này dùng làm dữ liệu mẫu để kiểm thử luồng ingest vào vector store của ai-agent addon. Nội dung mô phỏng tài liệu nội bộ cho ứng dụng Jmix quản lý bán hàng (Customer / Product / Order / OrderLine).

---

## 1. Trạng thái đơn hàng (Order status lifecycle)

Hệ thống quản lý đơn hàng theo 4 trạng thái tuần tự:

1. **NEW** — Đơn hàng vừa được nhân viên kinh doanh tạo. Ở trạng thái này, đơn hàng có thể chỉnh sửa tự do: thêm/bớt `OrderLine`, đổi `Customer`, đổi `orderDate`. Đơn NEW chưa trừ tồn kho.
2. **CONFIRMED** — Đơn đã được duyệt bởi trưởng phòng kinh doanh. Từ thời điểm này, kho sẽ được thông báo và số lượng `Product` tương ứng được reserve. Không thể thêm dòng mới; chỉ được phép sửa `quantity` với biên độ tối đa 10%.
3. **SHIPPED** — Đơn đã xuất kho và bàn giao cho đơn vị vận chuyển. Không còn chỉnh sửa được. Nếu khách hàng từ chối nhận, phải tạo đơn trả hàng riêng (out of scope phiên bản hiện tại).
4. **CANCELLED** — Đơn bị hủy. Có thể chuyển từ NEW hoặc CONFIRMED sang CANCELLED. Không thể chuyển từ SHIPPED sang CANCELLED — trường hợp đó phải dùng quy trình hoàn trả riêng.

### Quy tắc chuyển trạng thái (State transitions)

| Từ trạng thái | Sang trạng thái | Điều kiện |
|---|---|---|
| NEW | CONFIRMED | Tổng giá trị đơn > 0 và tất cả `OrderLine` có `quantity > 0` |
| NEW | CANCELLED | Người tạo hoặc quản lý bán hàng xác nhận |
| CONFIRMED | SHIPPED | Kho đã đóng gói và tạo waybill |
| CONFIRMED | CANCELLED | Chỉ trưởng phòng bán hàng hoặc cao hơn được phép; kho sẽ hoàn lại số reserve |
| SHIPPED | — | Terminal — không chuyển được sang trạng thái khác |
| CANCELLED | — | Terminal |

---

## 2. Quản lý khách hàng (Customer management)

Entity `Customer` lưu 3 trường bắt buộc khi tạo mới: `name`, `email`, `phone`. Trong đó `name` là bắt buộc cứng (NOT NULL ở DB), còn `email` và `phone` có thể để trống nhưng phải có ít nhất một trong hai.

### Chính sách trùng lặp

- Không cho phép tạo 2 khách hàng có cùng `email` (nếu email không rỗng). Khi phát hiện trùng, hệ thống gợi ý merge vào record đã tồn tại.
- `phone` không bị check unique vì nhiều khách hàng chia sẻ số điện thoại chung của công ty.

### Phân loại khách hàng

Phiên bản hiện tại chưa có trường `customerType` — mọi khách hàng được đối xử như nhau. Phân nhóm VIP / Regular / New dựa trên tổng giá trị đơn CONFIRMED+SHIPPED trong 12 tháng gần nhất, tính toán ngoài hệ thống (báo cáo BI).

---

## 3. Sản phẩm và giá (Product pricing)

Entity `Product` gồm `name` (bắt buộc), `sku` (bắt buộc, tối đa 64 ký tự), và `price` (BigDecimal, precision 19, scale 2).

### Quy tắc định giá

- Giá niêm yết trên `Product.price` là giá VND đã bao gồm VAT 10%.
- Không cho phép `price < 0`. Giá bằng 0 hợp lệ cho sản phẩm khuyến mãi kèm, nhưng phải có ghi chú ở `OrderLine`.
- SKU format khuyến nghị: `<NHÓM>-<MÃ>` (ví dụ `ELEC-0001`, `FOOD-1234`). Hệ thống không enforce format; tuân thủ là trách nhiệm của người nhập liệu.

### Khi nào được đổi giá

- Đổi `Product.price` **không** ảnh hưởng đến các `OrderLine` đã tồn tại — snapshot giá tại thời điểm confirm đơn đã được ghi ở mức tính toán `lineAmount`.
- Khi thay đổi giá cho campaign, nên tạo `Product` mới với SKU khác thay vì chỉnh `price` của sản phẩm đang bán.

---

## 4. Dòng đơn hàng (OrderLine rules)

Một `OrderLine` liên kết 1 `Order` với 1 `Product` và có `quantity > 0`. `lineAmount` được tính từ `Product.price × quantity` tại thời điểm đơn chuyển sang CONFIRMED.

### Giới hạn

- Tối đa 200 `OrderLine` trên 1 `Order`. Đơn nhiều hơn 200 dòng nên tách thành nhiều đơn.
- Không cho phép 2 `OrderLine` cùng một `Product` trong một `Order` — cộng dồn `quantity` vào dòng hiện có thay vì tạo dòng mới.

---

## 5. Phân quyền (Role-based access)

| Role | Customer | Product | Order (NEW) | Order (CONFIRMED+) |
|---|---|---|---|---|
| `sales-rep` | Read + Create + Update | Read | Full CRUD | Read + limited Update |
| `sales-manager` | Full CRUD | Read | Full CRUD | Full CRUD |
| `warehouse` | Read | Read | Read | Update status → SHIPPED |
| `admin` | Full CRUD | Full CRUD | Full CRUD | Full CRUD |

Bất kỳ thao tác không khớp bảng trên đều bị Jmix `AccessManager` từ chối ở tầng `DataManager` — view XML chỉ là hint UX, không phải ranh giới bảo mật.

---

## 6. Quy trình tạo đơn mẫu (Sample order creation flow)

Kịch bản điển hình:

1. Nhân viên bán hàng chọn `Customer` hoặc tạo mới (điền `name` + `email`).
2. Tạo `Order` với `orderDate = today`, chọn `customer` vừa chọn.
3. Thêm từng `OrderLine`: chọn `Product`, nhập `quantity`.
4. Kiểm tra `totalAmount` ở phần footer (tính gộp từ tất cả `lineAmount`).
5. Bấm "Confirm" để chuyển trạng thái NEW → CONFIRMED.
6. Chờ kho cập nhật SHIPPED khi đã đóng gói xong.

### Lỗi thường gặp

- **"Customer is required"** — chưa chọn khách hàng; đơn hàng bắt buộc có `customer` (NOT NULL).
- **"Order must have at least one line"** — đơn không có `OrderLine` nào, không confirm được.
- **"Duplicate product in order"** — đã có dòng cho sản phẩm này; cập nhật dòng cũ thay vì thêm mới.

---

## 7. Báo cáo và KPI (Reporting)

Các báo cáo standard được chạy hàng ngày lúc 02:00 giờ Việt Nam:

- **Daily sales report** — tổng `totalAmount` của các đơn chuyển sang CONFIRMED trong ngày, gom theo `sales-rep`.
- **Inventory reservation** — tổng `quantity` reserved (đơn CONFIRMED chưa SHIPPED) theo từng `Product`.
- **Customer lifetime value** — tổng `totalAmount` của tất cả đơn SHIPPED theo từng `Customer`, 12 tháng gần nhất.

Các báo cáo này chưa có view UI — truy xuất qua SQL trực tiếp trên store `main.datasource`.

---

## 8. Liên hệ hỗ trợ (Support contacts)

| Vấn đề | Liên hệ |
|---|---|
| Không truy cập được view | IT helpdesk, số máy lẻ 1001 |
| Báo cáo sai số | Phòng BI, email `bi@example.local` |
| Đơn bị stuck ở CONFIRMED quá 48h | Warehouse lead, số máy lẻ 2301 |
| Lỗi hệ thống / crash | Dev on-call, kênh Slack `#ai-agent-oncall` |

---

## 9. English summary (for EN-locale retrieval testing)

This document describes the sales and order operations policy for the Jmix sample app. Key facts for retrieval tests:

- The system uses four order statuses: NEW, CONFIRMED, SHIPPED, CANCELLED.
- Orders can move NEW → CONFIRMED → SHIPPED, or be cancelled from NEW/CONFIRMED (but not from SHIPPED).
- Customers require a name and at least one of email or phone. Emails must be unique.
- Products have name, sku (≤64 chars), and price (VND, VAT-inclusive).
- Each order line must have quantity > 0; a product may appear at most once per order.
- Role `sales-rep` can create orders but not confirm or ship; `warehouse` can only mark SHIPPED; `sales-manager` has full CRUD.
- Daily sales reports run at 02:00 Vietnam time.

---

## 10. Ghi chú kiểm thử (Test notes — not part of policy)

Tài liệu này được thiết kế để:

- Sinh ra đủ chunk khi chạy qua `TokenTextSplitter` (mặc định ~800 tokens/chunk) — document này ước lượng ~2500 tokens, tương đương 3-4 chunk.
- Kiểm thử retrieval với câu hỏi tiếng Việt như: "Khi nào được hủy đơn?", "Quy tắc giá sản phẩm?", "Ai được chuyển sang SHIPPED?".
- Kiểm thử retrieval tiếng Anh: "What statuses can an order have?", "Who can ship an order?".
- Kiểm thử cite source: câu trả lời nên cite lại filename `sample-sales-policy-vi.md`.

Khi xóa qua UI Knowledge Base, toàn bộ chunk phải được xóa khỏi vector store (RAG-03 atomicity).
