package com.vn.agent.tools.fetchplan;

import com.vn.agent.orchestration.RunContext;
import com.vn.agent.spi.FetchPlanContext;
import com.vn.agent.spi.ToolFetchPlanCustomizer;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlans;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.CurrentAuthentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Resolves the effective {@link FetchPlan} for a tool invocation by chaining host
 * {@link ToolFetchPlanCustomizer} beans (first non-empty {@link Optional} wins), falling back
 * to {@link FetchPlan#BASE} if no customizer returns a value, then ALWAYS piping the result
 * through {@link FetchPlanIntersector#intersectWithAcl(FetchPlan, MetaClass, String)} before
 * handing the plan to {@code DataManager.load(...)}.
 *
 * <p>Customizer order is the Spring bean order; hosts use {@link
 * org.springframework.core.annotation.Order} or implement {@link org.springframework.core.Ordered}
 * to control priority. The default no-op bean (in {@code SpiDefaultsAutoConfiguration})
 * returns {@link Optional#empty()} so it never short-circuits the chain.
 *
 * <p>Per D-10 review correction: {@link FetchPlanContext} is a concrete snapshot of static
 * {@link RunContext} accessors plus locale and a minimal user snapshot. The resolver reads
 * those accessors at the tool boundary and stores them in the record before host code runs;
 * {@code RunContext} itself is never embedded in the SPI parameter object.
 */
@Component
public class FetchPlanResolver {

    private final List<ToolFetchPlanCustomizer> customizers;
    private final FetchPlanIntersector intersector;
    private final FetchPlans fetchPlans;
    private final CurrentAuthentication currentAuthentication;

    public FetchPlanResolver(List<ToolFetchPlanCustomizer> customizers,
                             FetchPlanIntersector intersector,
                             FetchPlans fetchPlans,
                             CurrentAuthentication currentAuthentication) {
        this.customizers = customizers;
        this.intersector = intersector;
        this.fetchPlans = fetchPlans;
        this.currentAuthentication = currentAuthentication;
    }

    /**
     * Resolve the effective {@link FetchPlan} for a single tool invocation. First non-empty
     * customizer wins; otherwise falls back to {@link FetchPlan#BASE}. The result is ALWAYS
     * narrowed via {@link FetchPlanIntersector} before being returned.
     *
     * @param toolName  the {@code @Tool} method name
     * @param metaClass the resolved root entity {@link MetaClass} (already access-checked)
     * @return the ACL-narrowed {@link FetchPlan} ready to pass to {@code DataManager.load(...)}
     */
    public FetchPlan resolve(String toolName, MetaClass metaClass) {
        FetchPlanContext context = new FetchPlanContext(
                RunContext.get(),
                RunContext.getConversationId(),
                RunContext.getRetrievalTopK(),
                RunContext.getRetrievalSimilarityThreshold(),
                RunContext.getRetrievalFiltersJson(),
                currentLocaleOrRoot(),
                currentUserSnapshot());
        FetchPlan candidatePlan = null;
        for (ToolFetchPlanCustomizer customizer : customizers) {
            Optional<FetchPlan> overridden = customizer.overrideFor(toolName, metaClass, context);
            if (overridden.isPresent()) {
                candidatePlan = overridden.get();
                break;
            }
        }
        // Jmix 2.8 canonical construction: FetchPlan.BASE is a String name constant
        // (io.jmix.core.FetchPlan.BASE = "_base"); a FetchPlan *instance* is materialised
        // through FetchPlans.builder(...).addFetchPlan(BASE-name).build(). This is the same
        // shape used by BuiltInDataTools.java line 229-231 against Jmix 2.8.1.
        FetchPlan plan = candidatePlan != null
                ? candidatePlan
                : fetchPlans.builder(metaClass.getJavaClass())
                        .addFetchPlan(FetchPlan.BASE)
                        .build();
        return intersector.intersectWithAcl(plan, metaClass, toolName);
    }

    private FetchPlanContext.UserSnapshot currentUserSnapshot() {
        try {
            UserDetails user = currentAuthentication.getUser();
            if (user == null) {
                return new FetchPlanContext.UserSnapshot("", Set.of());
            }
            return new FetchPlanContext.UserSnapshot(user.getUsername(), rolesOf(user));
        } catch (RuntimeException anonymous) {
            return new FetchPlanContext.UserSnapshot("", Set.of());
        }
    }

    private static Set<String> rolesOf(UserDetails user) {
        return user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private Locale currentLocaleOrRoot() {
        try {
            Locale locale = currentAuthentication.getLocale();
            return locale == null ? Locale.ROOT : locale;
        } catch (RuntimeException anonymous) {
            return Locale.ROOT;
        }
    }
}
