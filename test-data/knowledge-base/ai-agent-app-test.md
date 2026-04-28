# AI Agent Core - Tài liệu kiểm thử Cơ sở tri thức

## Mục đích

Tài liệu này dùng để kiểm thử chức năng Cơ sở tri thức của ứng dụng AI Agent Core.
Khi tài liệu được tải lên thành công, trợ lý AI phải có thể truy xuất nội dung này
và trả lời dựa trên các thông tin bên dưới.

## Tổng quan ứng dụng

AI Agent Core là một add-on Jmix giúp tích hợp trợ lý AI vào ứng dụng Jmix Flow UI.
Ứng dụng chạy với Java 17, Jmix 2.8, Spring Boot 3 và cơ sở dữ liệu quan hệ.

Trợ lý AI trong hệ thống được thiết kế để chạy trong security context của người dùng hiện tại.
Điều này có nghĩa là AI chỉ được xem dữ liệu, tài liệu và thực thể mà người dùng đang đăng nhập
có quyền truy cập theo Jmix security.

## Các chức năng chính

1. Chat với trợ lý AI trong giao diện Jmix.
2. Gọi tool đọc dữ liệu Jmix thông qua DataManager và quyền truy cập hiện tại.
3. Tải tài liệu lên Cơ sở tri thức để AI có thể truy xuất nội dung khi trả lời.
4. Phân quyền tài liệu theo role để chỉ người dùng phù hợp mới retrieval được tài liệu.
5. Gắn tài liệu với thực thể nguồn để tài liệu liên quan tới miền dữ liệu cụ thể.
6. Ghi audit cho tool call, retrieval và các trường hợp bị chặn.

## Quy tắc về Cơ sở tri thức

Tài liệu trong Cơ sở tri thức là context do ứng dụng cung cấp.
AI phải dùng tài liệu này theo system prompt và access policy của ứng dụng host.
Nếu tài liệu retrieval được nhưng không đủ thông tin để trả lời, AI phải nói rằng context hiện có chưa đủ.

## Dữ kiện kiểm thử đặc biệt

Mã kiểm thử của tài liệu này là `KB-APP-TEST-2026`.

Tên quy trình kiểm thử là `Blue Lantern Flow`.

Giới hạn upload mẫu được cấu hình trong ứng dụng là 100 MB.

Người dùng nên thấy tài liệu đã upload trong màn hình chi tiết Cơ sở tri thức,
bao gồm tên file, dung lượng, người tải lên, role được phép, thực thể nguồn và các vector chunk.

## Câu hỏi gợi ý để kiểm thử AI

- Mã kiểm thử trong tài liệu AI Agent Core là gì?
- Quy trình kiểm thử trong tài liệu có tên là gì?
- Ứng dụng giới hạn upload mẫu bao nhiêu MB?
- AI Agent Core dùng security context của ai khi đọc dữ liệu?
- Khi context không đủ để trả lời thì trợ lý phải phản hồi như thế nào?
