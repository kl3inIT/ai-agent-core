---
created: 2026-04-24T10:41:14.466Z
title: Add explicit host override for tool fetch plans
area: general
files:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
---

## Problem

The current generic tool layer hardcodes `FetchPlan.BASE` for the main entity read paths and
`FetchPlan.INSTANCE_NAME` for the related-side projection in `get_related_records`. That is a
reasonable default for a generic add-on, but it is too rigid if a host application wants to supply
its own fetch plan for a specific entity or tool.

The important requirement is flexibility without losing control:

- the add-on should keep a safe generic default when no host customization exists
- the host app should be able to override that projection explicitly when needed
- the model should **not** choose fetch plans directly
- the add-on should **not** auto-discover and start using arbitrary shared plans from
  `fetch-plans.xml`, because a generic add-on cannot know which named plan is correct for
  `find_records`, `get_record`, or `get_related_records`

Without an explicit override point, hosts are forced either to accept the built-in projection or to
fork/replace tool logic for what should be an extension concern.

## Solution

TBD, but the intended direction is:

- keep `_base` / `_instance_name` as the add-on's default generic fallback
- add an explicit host-controlled override point for fetch plan resolution
- resolve by runtime metadata such as `toolName + MetaClass` rather than compile-time entity types
- prefer an SPI or mapping contract where the host app deliberately supplies a named fetch plan or
  a programmatically built `FetchPlan`
- make the override opt-in and explicit; do not automatically consume every shared plan defined in
  host `fetch-plans.xml`
- preserve the rule that fetch plan is a projection/performance concern, not a security boundary
- add tests that prove fallback behavior and host override behavior separately
