package com.vn.agent.spi;

import io.jmix.core.FetchPlan;
import io.jmix.core.metamodel.model.MetaClass;

import java.util.Optional;

/**
 * Host extension point for overriding the {@link FetchPlan} a built-in or host-contributed
 * tool uses when loading entity rows (TOOL-10, SPI-09).
 *
 * <p><b>Discovery:</b> injected as {@code List<ToolFetchPlanCustomizer>} at the consumer
 * (see {@code BuiltInDataTools} / {@code FetchPlanIntersector}, Plan 09-04). Customizers are
 * iterated in Spring bean order; the first non-empty {@code Optional} wins. The default
 * {@code @ConditionalOnMissingBean} bean in {@code SpiDefaultsAutoConfiguration} returns
 * {@link Optional#empty()} so the add-on falls back to {@link FetchPlan#BASE} when no host
 * customizer matches.
 *
 * <p><b>Scope (D-13):</b> this SPI overrides the <em>data</em> fetch plan only. The
 * {@code _instance_name} label projection convention (Phase 3 D-12) is <em>not</em> exposed
 * through this SPI in v1.1; hosts model alternative labels via Jmix
 * {@code @InstanceName} / instance-name configuration on the entity itself.
 *
 * <p><b>fetch plan is projection, not security.</b> The returned plan is intersected with
 * the current user's readable-attribute set (per {@code CurrentUserSchemaAccess}) BEFORE
 * {@code DataManager.load(...)} sees it. Properties the user cannot read are silently dropped
 * and an audit row with {@code outcome=PLAN_NARROWED} is emitted (D-12). Returning a wider
 * plan than the user's permissions is not a security violation; it is benign over-fetching
 * that the intersector prunes. Returning a narrower plan is a legitimate optimization /
 * shaping concern.
 *
 * <p><b>Example:</b>
 * <pre>{@code
 * @Component
 * class OrderShippingPlanCustomizer implements ToolFetchPlanCustomizer {
 *     private final FetchPlans fetchPlans;
 *     OrderShippingPlanCustomizer(FetchPlans fetchPlans) { this.fetchPlans = fetchPlans; }
 *
 *     @Override
 *     public Optional<FetchPlan> overrideFor(String toolName, MetaClass metaClass,
 *                                            FetchPlanContext context) {
 *         if (!"find_records".equals(toolName) || !"acme_Order".equals(metaClass.getName())) {
 *             return Optional.empty();
 *         }
 *         return Optional.of(fetchPlans.builder(metaClass.getJavaClass())
 *                 .addFetchPlan(FetchPlan.BASE)
 *                 .add("shippingAddress", b -> b.addFetchPlan(FetchPlan.INSTANCE_NAME))
 *                 .build());
 *     }
 * }
 * }</pre>
 *
 * @see FetchPlanContext
 */
public interface ToolFetchPlanCustomizer {

    /**
     * Optionally override the fetch plan for a built-in or host-contributed tool invocation.
     *
     * @param toolName  the {@code @Tool} method name (e.g. {@code "find_records"},
     *                  {@code "get_record"}, {@code "get_related_records"})
     * @param metaClass the resolved root entity {@link MetaClass} (already verified
     *                  read-permitted for the current user before this SPI is consulted)
     * @param context   per-request {@link FetchPlanContext} carrying run/conversation/retrieval/locale/user snapshot
     * @return {@link Optional#empty()} to fall through to the next customizer, or to the
     *         add-on default {@link FetchPlan#BASE} if no host returns a value
     */
    Optional<FetchPlan> overrideFor(String toolName, MetaClass metaClass, FetchPlanContext context);
}
