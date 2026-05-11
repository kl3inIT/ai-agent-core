---
created: 2026-04-24T17:19:04.0048787+07:00
title: Add intent-driven extraction to prefilled Jmix forms
area: ui
files:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatResponseDto.java
---

## Problem

We want a new chat workflow where the user chooses an intent first, provides input for that intent,
and the system extracts a structured draft that can open a real Jmix form with prefilled data after
user confirmation.

This is not the same as generic chat, generic tool use, or KB upload. It is a bounded workflow:

1. the user selects an intent up front
2. the system processes the provided input according to that intent
3. chat shows a read-only extracted draft
4. the user confirms
5. the UI navigates to the target Jmix form and pre-populates it

For v1, the concrete case is file-only input: for example, the user uploads a PDF containing
customer information, the system extracts the fields for the chosen intent, shows the draft in
chat, and after confirmation opens the target create/edit form with those fields prefilled.

This flow needs its own contract because the assistant should not directly manipulate navigation.
The AI side should only produce a structured draft plus the intended target, while the Jmix UI
layer remains responsible for confirmation, `ViewNavigators`, and form initialization.

## Solution

TBD, but the intended direction is:

- model this as an intent-first workflow instead of free-form routing
- start with one file-only path such as PDF-to-customer-draft
- return a structured, read-only draft in chat rather than immediately navigating
- add a confirmation action in chat that triggers UI-side navigation only after explicit user
  approval
- use Jmix Flow UI navigation and prefill patterns from the controller/view layer, not from the
  LLM/tool layer
- keep the transport from chat to destination form explicit and testable, likely via a draft id or
  another server-side handoff instead of making the model own UI state
