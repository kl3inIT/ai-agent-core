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

The add-on already filters the schema through `AccessManager` and blocks data reads through `DataManager`, but the LLM only sees the "allowed" surface through `list_entities` plus isolated errors such as `access_denied` and `unknown_entity`. It does not yet have a complete picture of the current user's permissions, so it cannot answer consistently in cases like:

- "you can read entity A but not attribute B"
- "you do not have permission for this entity"
- "this filter path is denied because an intermediate relationship hop is not readable"

Without a permission inventory, the security UX depends on the model inferring too much from tool errors. That makes answers less stable, harder to audit, and less likely to match the intended behavior when a user directly asks what they are or are not allowed to access.

## Solution

TBD — the implementation should be clarified in a dedicated follow-up task or phase:

- design a permission inventory for the LLM at entity and attribute level, computed per request for the current user
- make an explicit decision about whether denied entity names may be revealed to the model; this is the direct tradeoff against the goal that "if access is denied, the model should behave as if the entity does not exist"
- if revealing denied names is acceptable, add a structured tool or baseline context so the model learns the allowed/denied surface directly instead of inferring it from errors
- if revealing denied names is not acceptable, still build an internal inventory so the system can map tool errors to clear user-facing explanations without encouraging guessing
- add prompt- or integration-level tests for permission-aware responses so the behavior remains stable as the tool surface evolves
