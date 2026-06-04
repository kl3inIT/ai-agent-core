---
status: testing
phase: 17-mutation-internals-hardening-phase-11-follow-up
source:
  - 17-01-SUMMARY.md
  - 17-02-SUMMARY.md
  - 17-03-SUMMARY.md
  - 17-04-SUMMARY.md
  - 17-05-SUMMARY.md
started: 2026-06-02T00:00:00Z
updated: 2026-06-02T00:00:00Z
---

## Current Test
<!-- OVERWRITE each test - shows where we are -->

number: 1
name: Single record create / update via the agent
expected: |
  Trong chat agent (http://localhost:8088), yêu cầu agent tạo hoặc cập nhật MỘT record
  mà user hiện tại được phép sửa (ví dụ tạo một Client / Order tuỳ entity được expose).
  Kỳ vọng: agent gọi create_record / update_record, record được lưu đúng và agent xác
  nhận lại id mới (hoặc cập nhật thành công). Hành vi, thông điệp, và id trả về GIỐNG
  HỆT như trước Phase 17 — không có lỗi, không có thay đổi quan sát được.
awaiting: user response

## Tests

### 1. Single record create / update via the agent
expected: Agent tạo/cập nhật một record được phép; lưu đúng, trả về id/confirm như trước. (MUT-15 gate chain, MUT-18 parity)
result: [pending]

### 2. Bulk save — all-or-nothing + no slowdown
expected: Yêu cầu agent tạo nhiều record cùng lúc (vd "tạo 20 X", nhiều dòng trỏ về cùng một parent FK). Tất cả lưu trong một transaction; nếu một dòng invalid thì TOÀN BỘ batch rollback (không commit một phần); tốc độ không xấu đi theo số dòng; phản hồi liệt kê id đã lưu. (MUT-16 batch FK O(1) + discardSaved, MUT-18)
result: [pending]

### 3. Add / remove related record
expected: Yêu cầu agent thêm rồi gỡ một child liên quan trên một parent. Quan hệ cập nhật đúng; cùng outcome/thông điệp như trước Phase 17. (MUT-15 related-write path, MUT-17 metadata resolve)
result: [pending]

### 4. Fail-closed gate — permission / validation error
expected: Yêu cầu agent sửa thứ user KHÔNG được phép, hoặc với field sai/không hợp lệ. Kỳ vọng: trả về lỗi có cấu trúc rõ ràng (access_denied / not_found / validation_failed) với reason; KHÔNG stack trace, KHÔNG commit một phần; gate chặn TRƯỚC khi save. (MUT-15 fail-closed, MUT-18 error parity)
result: [pending]

## Summary

total: 4
passed: 0
issues: 0
pending: 4
skipped: 0

## Gaps

[none yet]
