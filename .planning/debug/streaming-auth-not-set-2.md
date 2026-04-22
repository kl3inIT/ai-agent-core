---
slug: streaming-auth-not-set-2
status: investigating
trigger: |
  <!-- DATA_START -->
  streaming-auth-not-set
  <!-- DATA_END -->
created: 2026-04-22T00:00:00Z
updated: 2026-04-22T00:00:00Z
---

# Debug Session: streaming-auth-not-set-2

## Symptoms

- **Expected:** Streaming assistant responses should complete end-to-end.
- **Actual:** Streaming flow appears to fail due to authentication context not being available.
- **Error:** `java.lang.IllegalStateException: Authentication is not set` (assumed from issue label and prior session context).
- **Timeline:** Recurrence suspected after a previously resolved streaming authentication issue.
- **Reproduction:** Trigger a streaming chat request in the AI chat UI and observe runtime failure.

## Current Focus

hypothesis: pending.
test: pending.
expecting: isolate the exact thread/context boundary where authentication is lost.
next_action: gather initial evidence

## Evidence

- timestamp: 2026-04-22 — session initialized from `$gsd-debug streaming-auth-not-set`.

## Eliminated

- hypothesis: none yet.

## Resolution

**Root cause:** pending.

**Fix:** pending.

**Verification plan:**
1. Reproduce consistently.
2. Validate root cause with code and runtime evidence.
3. Apply focused fix.
4. Re-run targeted tests and manual verification.
