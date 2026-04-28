---
phase: 11-mutation-capable-built-in-tools
plan: 07B
type: execute
wave: 7
depends_on:
  - 11-07A-PLAN.md
files_modified:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java
autonomous: true
requirements:
  - MUT-02
  - MUT-03
  - MUT-07
  - MUT-08
---

<objective>
Add the relationship metadata resolver and the two related-write tools after create/update core compiles. This isolates Jmix/JPA relationship handling from scalar mutation work.
</objective>

<tasks>
<task type="auto" tdd="false">
  <name>Task 1: Related-write metadata resolver helpers</name>
  <action>
Implement and compile helper methods only:
- `resolveSupportedRelatedWriteRelationship`
- child inverse lookup from `@OneToMany(mappedBy=...)`, cross-checked with `MetaProperty.getInverse()` when available
- `isCompositionOrDeleteCapable`
- `wireInverseReference`, `clearInverseReference`, `childBelongsToParent`

Support only parent collection `@OneToMany(mappedBy=...)` with a child-side single-valued `@ManyToOne`/`@OneToOne` inverse. Reject many-to-many, unidirectional relationships, collection-valued inverses, composition, orphanRemoval, required inverse clearing, and ambiguous metadata as `validation_failed` before any host save.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:compileJava</automated>
  </verify>
</task>

<task type="auto" tdd="false">
  <name>Task 2: add_related_record and remove_related_record</name>
  <action>
Add the two related-write tools using the helper support matrix. They must enforce parent update, relationship attribute modify, child LLM read/modify exposure, child read/update, inverse attribute modify, idempotency reservation, guard, transactional saveAll, and non-throwing audit.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:compileJava</automated>
  </verify>
</task>
</tasks>

<success_criteria>
- Related-write support compiles separately from create/update core.
- Unsupported metadata fails closed before `MutationSaveExecutor.save/saveAll`.
- Related writes never rewrite parent collections and never delete child rows in v1.1.
</success_criteria>
