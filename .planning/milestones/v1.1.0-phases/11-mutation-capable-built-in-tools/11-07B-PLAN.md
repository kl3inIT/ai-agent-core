---
phase: 11-mutation-capable-built-in-tools
plan: 07B
type: execute
wave: 7
depends_on:
  - 11-07A-PLAN.md
files_modified:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/RelatedWriteMetadataResolver.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/AITestConfiguration.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationTestFixture.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationParentFixture.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationChildFixture.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationLinkedParentFixture.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationLinkedChildFixture.java
  - ai-agent/ai-agent/src/test/resources/com/vn/agent/test_liquibase/010-mutation-test-fixture.xml
  - ai-agent/ai-agent/src/test/resources/com/vn/agent/test_liquibase/test-main-changelog.xml
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/RelatedWriteMetadataResolverTest.java
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
    - "Related-write metadata logic lives in RelatedWriteMetadataResolver; BuiltInMutationTools calls the resolver and does not contain relationship annotation/metamodel traversal internals."
    - "11-07B is the sole owner of mutation fixture entity classes, their main-store test Liquibase changelog, and test Jmix module registration. Plans 11-10 and 11-11 reuse these fixtures read-only and must not redefine or edit them."
    - "The support matrix below is binding, but the concrete API calls are not assumed until Task 0 verifies them. If MetaProperty/annotation access differs in Jmix 2.8, update this plan before coding rather than forcing the reference snippet."
    - "Related-write @Tool descriptions explicitly say the tools support only non-composition parent @OneToMany(mappedBy) relationships with a child-side to-one inverse; unsupported relationships return validation_failed."
    - "Focused helper-level tests prove the related metadata support matrix before add_related_record/remove_related_record tool methods are implemented."
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
Before implementing helper logic, first create/register the shared mutation fixture package and main-store test Liquibase changelog that later plans reuse:
- `MutationTestFixture` for scalar create/update tests
- `MutationParentFixture` / `MutationChildFixture` for composition/orphanRemoval rejection
- `MutationLinkedParentFixture` / `MutationLinkedChildFixture` for supported non-composition related-write success
- `010-mutation-test-fixture.xml` plus `test-main-changelog.xml`
- `AITestConfiguration` registration so all five fixture classes are visible to Jmix metadata

Fixture shape is fixed here so later plans do not redefine it: every fixture has `@JmixEntity`, UUID `@JmixGeneratedValue`, `@Version`, and `@InstanceName`; `MutationTestFixture` has `name`, sensitive `secret`, and integer `priority`; `MutationParentFixture.children` is `@Composition` + `@OneToMany(mappedBy="parent", orphanRemoval=true)` with child inverse `MutationChildFixture.parent`; `MutationLinkedParentFixture.linkedChildren` is non-composition `@OneToMany(mappedBy="linkedParent")` with child inverse `MutationLinkedChildFixture.linkedParent`; the changelog creates all five host fixture tables and both parent-child foreign keys in the main store only.

Then inspect the actual Jmix 2.8 `MetaProperty` API and the project fixture annotations. Record the exact source of each fact in `11-07B-SUMMARY.md`: parent mapped-by ownership evidence, child inverse property lookup, whether `MetaProperty.getInverse()` exists and what it returns, composition detection, orphanRemoval/delete-capable detection, required inverse detection, and how to read `ManyToOne(optional)` / `JoinColumn(nullable)` or the Jmix equivalent. Add a summary table with columns `Resolver decision`, `Verified API/annotation`, `Source file/doc`, and `Fallback/fail-closed behavior`. If any API differs from the reference contract, stop and repair this plan before coding.
Use Context7 `/jmix-framework/jmix-context7`, local source/Javadocs, and the fixture entity annotations as evidence; do not rely on guessed method names for inverse metadata.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:compileTestJava</automated>
  </verify>
</task>

<task type="auto" tdd="false">
  <name>Task 1: Related-write metadata resolver helpers</name>
  <action>
Implement and compile `RelatedWriteMetadataResolver` helper methods only:
- `resolveSupportedRelatedWriteRelationship`
- child inverse lookup from the verified mapped-by metadata source, cross-checked with `MetaProperty.getInverse()` only if Task 0 proves it is available and semantically reliable
- `isCompositionOrDeleteCapable`
- `wireInverseReference`, `clearInverseReference`, `childBelongsToParent`

Support only parent collection relationships with verified mapped-by ownership and a child-side single-valued inverse. Reject many-to-many, unidirectional relationships, collection-valued inverses, composition, orphanRemoval/delete-capable relationships, required inverse clearing, and ambiguous metadata as `validation_failed` before any host save.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:compileJava</automated>
  </verify>
</task>

<task type="auto" tdd="false">
  <name>Task 2: Related-write metadata helper tests</name>
  <action>
Before adding the two tool methods, create `RelatedWriteMetadataResolverTest` for the helper methods from Task 1. Use concrete Jmix metadata, not pure mocks:
- prove supported non-composition parent `@OneToMany(mappedBy)` + child to-one inverse is accepted
- prove `@Composition` and `orphanRemoval` are rejected
- prove many-to-many/unidirectional/collection-inverse/ambiguous metadata is rejected when fixtures or minimal test entities expose those shapes
- prove required inverse clearing is rejected for remove
- prove `wireInverseReference`, `clearInverseReference`, and `childBelongsToParent` operate on the child-side inverse and never rewrite parent collections

Do not defer fixture creation to Plan 11-10. This plan owns the fixture classes and changelog so helper tests and later mutation-tool tests share one source of truth. Do not proceed to the broad related-write tool methods until these focused helper tests pass.
  </action>
  <verify>
    <automated>./gradlew :ai-agent:test --tests "com.vn.agent.tools.mutation.RelatedWriteMetadataResolverTest"</automated>
  </verify>
</task>

<task type="auto" tdd="false">
  <name>Task 3: add_related_record and remove_related_record</name>
  <action>
Add the two related-write tools using `RelatedWriteMetadataResolver` as the only relationship-metadata authority. They must enforce parent update, relationship attribute modify, child LLM read/modify exposure, child read/update, inverse attribute modify, idempotency reservation, guard, transactional saveAll, and non-throwing audit.
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
- `11-07B-SUMMARY.md` includes the resolver decision table: `Resolver decision`, `Verified API/annotation`, `Source file/doc`, and `Fallback/fail-closed behavior`.
- `MutationTestFixture`, `MutationParentFixture`, `MutationChildFixture`, `MutationLinkedParentFixture`, and `MutationLinkedChildFixture` are created/registered here, with `010-mutation-test-fixture.xml` and `test-main-changelog.xml`; later plans must reuse them read-only.
- Related-write support compiles separately from create/update core.
- No related-write acceptance criterion depends on an unverified method name. `11-07B-SUMMARY.md` maps each resolver decision to a verified Jmix/JPA API or fixture annotation source.
- `BuiltInMutationTools` delegates all relationship metadata interpretation to `RelatedWriteMetadataResolver`.
- Unsupported metadata fails closed before `MutationSaveExecutor.save/saveAll`.
- `RelatedWriteMetadataResolverTest` passes before broad related-write tool tests are attempted.
- Related writes never rewrite parent collections and never delete child rows in v1.1.
- Helper acceptance repeats the reference contract: require parent `@OneToMany(mappedBy)`, child single-valued `@ManyToOne/@OneToOne`, reject many-to-many/unidirectional/collection-inverse/composition/orphanRemoval/required-inverse/ambiguous metadata, and check child LLM read+modify exposure plus Jmix read/update and inverse attribute modify.
- `add_related_record` and `remove_related_record` are not considered complete until they have both a supported non-composition success path and fail-closed coverage for composition/orphanRemoval/unsupported metadata in Plans 11-10 and 11-11.
</success_criteria>
