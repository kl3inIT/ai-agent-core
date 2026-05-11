---
created: 2026-04-24T17:19:04.0048787+07:00
title: Add dedicated chat speech and file task input
area: ui
files:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/chat-view.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml
---

## Problem

The current chat surface is text-first and the existing upload flow in the add-on is knowledge-base
upload, which serves a completely different purpose. We now need a dedicated task-input capability
inside chat so users can provide non-text input for an active task:

- speech-to-text as an alternative to typing
- file attachments used as task input, not as knowledge-base ingestion

These inputs must stay separate from the KB upload path. A PDF, image, or office document attached
to a chat task should be treated as transient task input for the current interaction, not as
persistent RAG content.

Without this split, the UI and backend semantics stay muddy: "upload a file for the current task"
and "ingest a document into the knowledge base" look similar to users but have very different
security, lifecycle, and processing requirements.

## Solution

TBD, but the intended direction is:

- add a dedicated chat input surface for microphone capture / speech-to-text and task-scoped file
  attachments
- keep this capability separate from `KnowledgeBaseView` and `KnowledgeDocumentUploadService`
- define task-input storage/lifecycle rules clearly: likely ephemeral or conversation-scoped, not
  persistent KB ingestion
- make the chat UI explicit about the difference between:
  - sending plain text
  - attaching a file for the current task
  - uploading a document to the knowledge base
- design the backend contract so downstream intent-specific workflows can consume these inputs
  safely without overloading the RAG ingestion pipeline
