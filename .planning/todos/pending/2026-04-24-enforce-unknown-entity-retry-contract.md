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

When a tool returns `unknown_entity`, the model still tends to guess a different entity instead of going back to the source of truth (`list_entities`) or asking the user to clarify. At the code level, `BuiltInDataTools.resolveReadableEntityOrThrow(...)` already returns a structured error (`unknown_entity`, `reason = no entity named ...`), but the orchestration/prompt layer does not yet constrain the LLM's retry behavior.

Consequences:

- the tool contract is not deterministic: the same error can lead to different model guesses
- the user sees the agent invent a nearby entity instead of stating clearly that the entity does not exist
- if we later want a strict distinction between `unknown_entity` and `access_denied`, unconstrained guessing will make the security UX harder to control

## Solution

TBD — likely to be addressed in a dedicated follow-up change:

- add an explicit tool-calling contract: on `unknown_entity`, the model must go back to `list_entities` exactly once and must not guess a different entity
- if `list_entities` does not produce a clear match, the model should ask the user to clarify or state that no matching entity exists
- consider putting the next valid action into `ToolErrorDto.expected` for `unknown_entity`
- extend the system prompt/tool guidance to forbid semantic guessing when an entity name is not present in the current tool surface
- lock the behavior with an integration test or prompt-contract test so it does not regress
