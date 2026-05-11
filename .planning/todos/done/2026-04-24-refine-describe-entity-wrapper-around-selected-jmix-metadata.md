---
created: 2026-04-24T17:40:33.6846930+07:00
title: Refine describe_entity wrapper around selected Jmix metadata
area: general
files:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultPayloads.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolResultFormatter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/metadata/CurrentUserSchemaAccess.java
---

## Problem

The current `describe_entity` payload is intentionally minimal, but after reviewing the real Jmix
metadata API in detail it is now clear that we are leaving out some metadata that matters for
enterprise-grade agent behavior.

Today the wrapper exposes only:

- entity name + label
- attribute name
- type
- nullable
- label
- enum values
- relationship target
- max length

That keeps token use low, but it misses several semantics that the team now cares about:

- the project uses `@Comment`, so entity and attribute comments are a real documentation source
- Jmix metadata distinguishes `attributeType` (`DATATYPE`, `ENUM`, `ASSOCIATION`, `COMPOSITION`,
  `EMBEDDED`) from the rendered type string
- relationship `cardinality` is available and useful
- `readOnly`, `persistent`, and `transient` can matter for extraction, explanation, and form
  prefill
- primary-key information is available and may help tool use

At the same time, we do **not** want to swing to the opposite extreme and serialize raw Jmix
`MetaClass` / `MetaProperty` objects or blindly copy the full REST metadata shape. That would add
framework noise, increase tokens, and couple the LLM contract too tightly to Jmix internals.

The desired direction is a deliberate wrapper evolution:

- use the Jmix REST metadata shape as a reference point
- keep an add-on-owned JSON contract for the agent
- add selected high-value metadata, especially `comment`
- avoid raw annotations, raw Java reflection objects, and low-signal framework internals

## Solution

TBD, but the intended direction is:

- evolve `describe_entity` toward a richer wrapper with selected fields such as:
  - entity: `entityName`, `label`, optional `comment`, optional `ancestor`
  - attribute: `name`, `label`, `comment`, `attributeType`, `type`, `cardinality`, `mandatory`,
    `readOnly`, `persistent`, `transient`, `isPrimaryKey`, `enumValues`, `relationshipTarget`,
    `maxLength`
- prefer `mandatory` over the current inverted `nullable` naming so the payload aligns more
  closely with Jmix concepts
- read comments via `MetadataTools.getMetaAnnotationValue(..., Comment.class)` instead of inventing
  a parallel description source
- keep the wrapper contract explicit and curated rather than exposing full raw metadata objects
- document clearly which Jmix metadata fields are intentionally excluded and why
- add/update tests so the richer wrapper is stable and so future refactors do not silently drop
  `@Comment` support
