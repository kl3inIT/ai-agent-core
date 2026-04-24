---
created: 2026-04-24T10:13:40.975Z
title: Enforce unknown_entity retry contract
area: general
files:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolErrorDto.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolUserError.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent-starter/src/main/resources/default-params.yaml
---

## Problem

Khi tool trả về `unknown_entity`, model hiện vẫn có xu hướng tự đoán sang entity khác thay vì quay lại nguồn sự thật (`list_entities`) hoặc hỏi lại user. Về mặt code, `BuiltInDataTools.resolveReadableEntityOrThrow(...)` đã trả lỗi có cấu trúc (`unknown_entity`, `reason = no entity named ...`), nhưng orchestration/prompt hiện chưa ràng buộc hành vi retry của LLM.

Hệ quả:

- tool contract không deterministic: cùng một lỗi nhưng model có thể tự suy diễn khác nhau
- user thấy agent "bịa" entity gần nghĩa thay vì nói rõ không có entity đó
- sau này nếu muốn phân biệt rõ `unknown_entity` với `access_denied`, hành vi suy đoán tự do sẽ làm security UX khó kiểm soát

## Solution

TBD — hướng nên chốt trong một thay đổi riêng:

- bổ sung contract rõ cho tool-calling: gặp `unknown_entity` thì bắt buộc quay lại `list_entities` đúng một lần, không được tự đoán entity khác
- nếu `list_entities` không cho ra match rõ ràng thì model phải hỏi lại user hoặc trả lời thẳng là không có entity phù hợp
- cân nhắc đưa hint hành động tiếp theo vào `ToolErrorDto.expected` cho `unknown_entity`
- bổ sung system prompt/tool guidance để cấm "semantic guessing" khi tên entity không có trong tool surface hiện tại
- khoá behavior bằng test integration hoặc prompt-contract test để tránh hồi quy
