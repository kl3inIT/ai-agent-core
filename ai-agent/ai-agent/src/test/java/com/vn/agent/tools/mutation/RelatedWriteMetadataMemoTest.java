package com.vn.agent.tools.mutation;

import com.vn.agent.tools.ToolUserError;
import io.jmix.core.metamodel.model.MetaClass;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Plan 17-01 Task 2 (MUT-17) — pure-JUnit walk-once call-count seam for
 * {@link RelatedWriteMetadataResolver}.
 *
 * <p><b>No Spring, no Mockito</b> (D-16/D-17). The test drives the package-private
 * {@code computeSupported(MetaClass, String)} metamodel-walk seam through a counting subclass
 * ({@link CountingResolver}) that overrides {@code computeSupported} to bump an
 * {@link AtomicInteger} and return a canned outcome. Counting at the seam boundary — rather than
 * walking a real Jmix metamodel — lets the test stay pure JUnit while still proving the
 * memoization contract Plan 02 will add:
 * <ul>
 *     <li>A repeated SUPPORTED {@code (entity, relationship)} key walks exactly ONCE.</li>
 *     <li>A repeated UNSUPPORTED key also walks exactly ONCE (the rejected outcome is cached),
 *         AND each call rethrows a FRESH {@link ToolUserError} — never the cached throwable
 *         instance (D-12). The two thrown instances are asserted distinct via {@code isNotSameAs}.</li>
 *     <li>Distinct keys each walk once.</li>
 * </ul>
 *
 * <p><b>Intentional RED until Plan 02.</b> Today {@link RelatedWriteMetadataResolver} performs NO
 * memoization — {@code resolveSupportedRelatedWriteRelationship} delegates straight to
 * {@code computeSupported} on every call. So a repeated key bumps {@code walkCount} to 2 and the
 * {@code walkCount == 1} assertions FAIL. Plan 02 wraps {@code computeSupported} in a
 * {@code (parentEntityName, relationshipName)}-keyed memo (immutable metamodel, no eviction,
 * security-independent per T-17-03), flipping these assertions GREEN.
 *
 * <p>The {@code MetaClass} arguments are never dereferenced by the counting seam, so the test
 * passes lightweight key-only stand-ins built from distinct entity-name strings — the memo key
 * Plan 02 derives is {@code (parentMetaClass.getName(), relationshipName)}, which the stand-ins
 * make distinguishable without a Spring metamodel.
 */
class RelatedWriteMetadataMemoTest {

    private static final String UNSUPPORTED_RELATIONSHIP = "doesNotExist";

    /**
     * Counting subclass: overrides the package-private metamodel-walk seam to bump
     * {@link #walkCount} on every invocation, then return a canned outcome (supported branch)
     * or throw a FRESH {@link ToolUserError} (unsupported branch — name contains
     * {@link #UNSUPPORTED_RELATIONSHIP}). Does NOT call {@code super.computeSupported} so the
     * test never needs a real Jmix metamodel.
     */
    private static final class CountingResolver extends RelatedWriteMetadataResolver {

        private final AtomicInteger walkCount = new AtomicInteger();

        @Override
        SupportedRelatedRelationship computeSupported(MetaClass parentMetaClass, String relationshipName) {
            walkCount.incrementAndGet();
            if (relationshipName == null || relationshipName.contains(UNSUPPORTED_RELATIONSHIP)) {
                // Fresh instance every call (D-12) — a memo must rethrow a NEW throwable, never
                // hand back the cached one.
                throw new ToolUserError("validation_failed",
                        "relationship is not supported by add/remove_related_record",
                        List.of("use describe_entity to inspect the entity's relationships"));
            }
            return new SupportedRelatedRelationship(null, null, null);
        }

        int walkCount() {
            return walkCount.get();
        }
    }

    /**
     * Key-only {@link MetaClass} stand-in. The counting seam never dereferences the MetaClass,
     * and Plan 02's memo key only reads {@code getName()}, so a {@link Proxy} that answers
     * {@code getName()} (and {@code toString}/{@code equals}/{@code hashCode} for safety) is
     * sufficient — no Mockito, no full interface implementation.
     */
    private static MetaClass metaClassNamed(String name) {
        return (MetaClass) Proxy.newProxyInstance(
                RelatedWriteMetadataMemoTest.class.getClassLoader(),
                new Class<?>[]{MetaClass.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "toString" -> "MetaClass[" + name + "]";
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> throw new UnsupportedOperationException(
                            "key-only MetaClass stand-in does not implement " + method.getName());
                });
    }

    @Test
    void walkRunsOnceForRepeatedSupportedKey() {
        CountingResolver resolver = new CountingResolver();
        MetaClass parent = metaClassNamed("mutationTest_MutationLinkedParentFixture");

        RelatedWriteMetadataResolver.SupportedRelatedRelationship first =
                resolver.resolveSupportedRelatedWriteRelationship(parent, "linkedChildren");
        RelatedWriteMetadataResolver.SupportedRelatedRelationship second =
                resolver.resolveSupportedRelatedWriteRelationship(parent, "linkedChildren");

        assertThat(first).as("supported key must resolve to a non-null relationship").isNotNull();
        assertThat(second).as("repeated supported key must resolve to a non-null relationship").isNotNull();
        assertThat(resolver.walkCount())
                .as("MUT-17: repeated SUPPORTED (entity, relationship) key must walk the metamodel "
                        + "EXACTLY ONCE — memoized in Plan 02 (intentional RED until then)")
                .isEqualTo(1);
    }

    @Test
    void walkRunsOnceForRepeatedUnsupportedKey() {
        CountingResolver resolver = new CountingResolver();
        MetaClass parent = metaClassNamed("mutationTest_MutationLinkedParentFixture");

        Throwable firstThrown = catchThrowable(() ->
                resolver.resolveSupportedRelatedWriteRelationship(parent, UNSUPPORTED_RELATIONSHIP));
        Throwable secondThrown = catchThrowable(() ->
                resolver.resolveSupportedRelatedWriteRelationship(parent, UNSUPPORTED_RELATIONSHIP));

        assertThat(firstThrown)
                .isInstanceOf(ToolUserError.class)
                .extracting(t -> ((ToolUserError) t).toDto().error())
                .isEqualTo("validation_failed");
        assertThat(secondThrown)
                .isInstanceOf(ToolUserError.class)
                .extracting(t -> ((ToolUserError) t).toDto().error())
                .isEqualTo("validation_failed");

        // D-12: the rejected outcome may be cached, but each call must rethrow a FRESH instance —
        // never the cached throwable. This holds both pre- and post-memoization.
        assertThat(secondThrown)
                .as("MUT-17 / D-12: a memoized rejection must rethrow a FRESH ToolUserError, "
                        + "never the cached throwable instance")
                .isNotSameAs(firstThrown);

        assertThat(resolver.walkCount())
                .as("MUT-17: repeated UNSUPPORTED key must walk the metamodel EXACTLY ONCE — the "
                        + "rejected outcome is cached in Plan 02 (intentional RED until then)")
                .isEqualTo(1);
    }

    @Test
    void distinctKeysEachWalkOnce() {
        CountingResolver resolver = new CountingResolver();
        MetaClass parent = metaClassNamed("mutationTest_MutationLinkedParentFixture");

        // One supported key + one unsupported key = two distinct (entity, relationship) keys.
        resolver.resolveSupportedRelatedWriteRelationship(parent, "linkedChildren");
        assertThatThrownBy(() ->
                resolver.resolveSupportedRelatedWriteRelationship(parent, UNSUPPORTED_RELATIONSHIP))
                .isInstanceOf(ToolUserError.class);

        assertThat(resolver.walkCount())
                .as("MUT-17: two DISTINCT keys must each walk exactly once → 2 total walks")
                .isEqualTo(2);
    }
}
