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
must_haves:
  truths:
    - "Task 0 is mandatory: verify actual Jmix 2.8/project metadata APIs before implementing related-write helpers, then record findings and sources in 11-07B-SUMMARY.md."
    - "add_related_record and remove_related_record use the exact D-01 signatures and keep AiAgentMutationRole.CODE as the first gate inherited from 11-07A."
    - "Related-write @Tool descriptions explicitly say the tools support only non-composition parent @OneToMany(mappedBy) relationships with a child-side to-one inverse; unsupported relationships return validation_failed."
    - "Supported v1.1 related writes are only parent collection @OneToMany(mappedBy=...) with a child-side single-valued @ManyToOne/@OneToOne inverse."
    - "Reject many-to-many, unidirectional relationships, parent-side to-one properties, map-valued relationships, collection-valued inverses, composition, orphanRemoval/delete-capable relationships, required inverse clearing, and ambiguous metadata as validation_failed before save."
    - "Related writes never rewrite parent collections and never call remove/delete. They mutate only the verified child-side inverse and save through MutationSaveExecutor.saveAll."
    - "Both related tools enforce parent update, parent relationship attribute canModify, child LLM read+modify exposure, child Jmix read+update, and child inverse attribute canModify before host mutation."
    - "Parent id and relatedId are UUID tools: malformed ids return parameter_conversion_error; valid UUIDs with missing rows return not_found."
    - "remove_related_record verifies childBelongsToParent using the child-side inverse id; unverifiable or non-member children return not_found without save."
    - "MutationGuard and idempotency semantics remain identical to 11-07A/11-07 reference: no host mutation unless reserveOrReplay returns RESERVED, and same-key replay/violation/pending short-circuits."
    - "Audit rows for related writes include full hashed argumentsJson: entityName, id, relationship, relatedId, and idempotencyKey."
---

<objective>
Add the relationship metadata resolver and the two related-write tools after create/update core compiles. This isolates Jmix/JPA relationship handling from scalar mutation work.
</objective>

<tasks>
<task type="auto" tdd="false">
  <name>Task 0: Verify Jmix/JPA relationship metadata before coding helpers</name>
  <action>
Before implementing helper logic, inspect the actual Jmix 2.8 `MetaProperty` API and the project fixture annotations. Record the exact source of each fact in `11-07B-SUMMARY.md`: parent `@OneToMany(mappedBy)`, child inverse property lookup, `MetaProperty.getInverse()` behavior, `@Composition`, `orphanRemoval`, `@ManyToOne(optional)`, and `@JoinColumn(nullable)`. If any API differs from the reference contract, stop and repair this plan before coding.
Use Context7 `/jmix-framework/jmix-context7`, local source/Javadocs, and the fixture entity annotations as evidence; do not rely on guessed method names for inverse metadata.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:compileJava</automated>
  </verify>
</task>

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
Keep related-write result/error/audit behavior aligned with the 11-07 reference snippets, including `IDEMPOTENT_REPLAY`, `COMMIT_FAILED`, `not_found`, `parameter_conversion_error`, and full hashed argument envelopes.
Update both related-write tool descriptions so the LLM sees the narrow v1.1 scope and does not attempt composition, many-to-many, unidirectional, required-inverse, or delete-capable relationship writes.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:compileJava</automated>
  </verify>
</task>
</tasks>

<success_criteria>
- `11-07B-SUMMARY.md` records the verified Jmix/JPA metadata APIs used by the helpers.
- Related-write support compiles separately from create/update core.
- Unsupported metadata fails closed before `MutationSaveExecutor.save/saveAll`.
- Related writes never rewrite parent collections and never delete child rows in v1.1.
- Helper acceptance repeats the reference contract: require parent `@OneToMany(mappedBy)`, child single-valued `@ManyToOne/@OneToOne`, reject many-to-many/unidirectional/collection-inverse/composition/orphanRemoval/required-inverse/ambiguous metadata, and check child LLM read+modify exposure plus Jmix read/update and inverse attribute modify.
- `add_related_record` and `remove_related_record` are not considered complete until they have both a supported non-composition success path and fail-closed coverage for composition/orphanRemoval/unsupported metadata in Plans 11-10 and 11-11.
</success_criteria>
