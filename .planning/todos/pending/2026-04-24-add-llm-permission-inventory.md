---
created: 2026-04-24T10:15:32.259Z
title: Add LLM permission inventory
area: general
files:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/filter/StructuredFilterConditionMapper.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
---

## Problem

Hiện tại add-on đã lọc schema theo `AccessManager` và chặn query bằng `DataManager`, nhưng LLM chỉ nhìn thấy phần "được phép" qua `list_entities` và các lỗi rời rạc như `access_denied` / `unknown_entity`. Nó chưa có một bức tranh đầy đủ về quyền của user hiện tại để trả lời nhất quán kiểu:

- "bạn có quyền đọc entity A nhưng không được xem attribute B"
- "bạn không có quyền với entity này"
- "filter path này bị cấm vì hop giữa quan hệ không readable"

Thiếu permission inventory làm cho UX security phụ thuộc vào việc model tự suy luận từ lỗi tool. Điều đó khiến câu trả lời thiếu ổn định, khó audit, và khó đạt đúng behavior mong muốn khi user hỏi trực tiếp về quyền hiện tại của mình.

## Solution

TBD — hướng triển khai cần làm rõ trong một phase/task riêng:

- thiết kế một permission inventory cho LLM ở mức entity + attribute, tính theo current user trên mỗi request
- quyết định rõ có lộ denied-entity names cho model hay không; đây là tradeoff trực tiếp với mục tiêu "không có quyền thì coi như không biết entity tồn tại"
- nếu chấp nhận lộ denied list, thêm tool hoặc baseline context có cấu trúc để model biết allowed/denied surface thay vì suy ra từ lỗi
- nếu không chấp nhận lộ denied list, vẫn cần inventory nội bộ để map error → user-facing explanation mà không làm model đoán bừa
- thêm test contract cho các câu trả lời permission-aware để tránh hồi quy khi thay đổi prompt/tool surface
