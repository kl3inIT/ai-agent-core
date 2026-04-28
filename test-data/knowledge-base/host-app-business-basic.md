# Host App - Nghiệp vụ bán hàng cơ bản

## Mục đích tài liệu

Tài liệu này mô tả nghiệp vụ cơ bản của host app Jmix dùng để kiểm thử Cơ sở tri thức.
Nội dung tập trung vào mô hình quản lý khách hàng, sản phẩm và đơn hàng.

## Tổng quan nghiệp vụ

Host app là một ứng dụng quản lý bán hàng đơn giản.
Người dùng có thể quản lý khách hàng, danh mục sản phẩm, đơn hàng và các dòng hàng trong đơn.

Luồng nghiệp vụ chính là:

1. Tạo khách hàng.
2. Tạo sản phẩm với SKU và đơn giá.
3. Tạo đơn hàng cho một khách hàng.
4. Thêm nhiều dòng hàng vào đơn hàng.
5. Theo dõi trạng thái đơn hàng từ lúc mới tạo đến khi giao hàng hoặc hủy.

## Khách hàng

Khách hàng là người hoặc tổ chức mua sản phẩm.
Mỗi khách hàng có các thông tin chính:

- Tên khách hàng.
- Email.
- Số điện thoại.

Tên khách hàng là thông tin bắt buộc.
Email được dùng để liên hệ và có định dạng email hợp lệ.

## Sản phẩm

Sản phẩm là mặt hàng được bán trong hệ thống.
Mỗi sản phẩm có các thông tin chính:

- Tên sản phẩm.
- SKU.
- Đơn giá.

SKU là mã định danh duy nhất của sản phẩm.
Đơn giá không được âm.

## Đơn hàng

Đơn hàng ghi nhận giao dịch bán hàng với một khách hàng.
Mỗi đơn hàng có các thông tin chính:

- Số đơn hàng.
- Ngày đặt hàng.
- Khách hàng.
- Trạng thái.
- Danh sách dòng hàng.

Số đơn hàng là duy nhất.
Một đơn hàng bắt buộc phải thuộc về một khách hàng.
Khi xóa khách hàng đang có đơn hàng, hệ thống không nên cho xóa để tránh mất lịch sử giao dịch.

## Dòng hàng

Dòng hàng là từng sản phẩm cụ thể trong một đơn hàng.
Mỗi dòng hàng có các thông tin chính:

- Đơn hàng cha.
- Sản phẩm.
- Số lượng.

Số lượng phải lớn hơn 0.
Một đơn hàng có thể có nhiều dòng hàng.
Khi xóa đơn hàng, các dòng hàng của đơn đó được xóa theo.
Khi sản phẩm đã được dùng trong dòng hàng, hệ thống không nên cho xóa sản phẩm để tránh làm sai lịch sử đơn hàng.

## Tính tiền

Thành tiền của một dòng hàng được tính theo công thức:

`thành tiền dòng = đơn giá sản phẩm * số lượng`

Tổng tiền đơn hàng được tính bằng tổng thành tiền của tất cả dòng hàng trong đơn.

Ví dụ:

- Sản phẩm A có đơn giá 120000.
- Số lượng mua là 3.
- Thành tiền dòng hàng là 360000.

## Trạng thái đơn hàng

Đơn hàng có bốn trạng thái nghiệp vụ:

- `NEW`: đơn hàng mới tạo.
- `CONFIRMED`: đơn hàng đã được xác nhận.
- `SHIPPED`: đơn hàng đã được giao.
- `CANCELLED`: đơn hàng đã bị hủy.

Quy trình chuẩn là:

`NEW -> CONFIRMED -> SHIPPED`

Nếu đơn hàng không tiếp tục xử lý, trạng thái có thể chuyển sang `CANCELLED`.

## Quy tắc báo cáo

Khi người dùng hỏi về doanh thu, hệ thống nên dựa trên tổng tiền đơn hàng.
Khi người dùng hỏi về số lượng sản phẩm đã bán, hệ thống nên dựa trên tổng số lượng trong các dòng hàng.
Khi người dùng hỏi về khách hàng mua nhiều nhất, hệ thống nên nhóm đơn hàng theo khách hàng và cộng tổng tiền.

## Dữ kiện kiểm thử đặc biệt

Mã tài liệu nghiệp vụ host app là `HOST-BIZ-BASIC-2026`.

Tên luồng nghiệp vụ kiểm thử là `Order Sunrise Flow`.

Trạng thái kết thúc thành công của một đơn hàng là `SHIPPED`.

Quy tắc tính tổng tiền đơn hàng là cộng tất cả `lineAmount` của các dòng hàng.

## Câu hỏi gợi ý để kiểm thử AI

- Host app quản lý các đối tượng nghiệp vụ chính nào?
- SKU trong host app dùng để làm gì?
- Công thức tính thành tiền của một dòng hàng là gì?
- Quy trình trạng thái chuẩn của đơn hàng là gì?
- Mã tài liệu nghiệp vụ host app là gì?
- Tên luồng nghiệp vụ kiểm thử là gì?
- Trạng thái kết thúc thành công của đơn hàng là gì?
