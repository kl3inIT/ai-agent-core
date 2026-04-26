package com.vn.agent.performance;

import net.ttddyy.dsproxy.listener.SingleQueryCountHolder;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * R-03a (cross-AI review fix): wraps the EXISTING DataSource bean in-place via
 * BeanPostProcessor — does NOT register a new {@code @Primary} bean. The previous shape used
 * {@code @Bean @Primary DataSource queryCountingDataSource(...)} which would displace
 * whatever was previously primary in the test profile, potentially breaking the
 * agentstore datasource wiring (Jmix has TWO datasources). Wrapping in-place avoids
 * the displacement.
 *
 * <p><b>Phase 8 Plan 03 deviation (Rule 3 — recorded in 08-03-SUMMARY.md):</b> the wrapper
 * targets the {@code agentstoreDataSource} bean (NOT the main {@code dataSource}) because
 * every {@code ai_*} entity used by {@link com.vn.agent.tools.BuiltInDataTools} carries
 * {@code @Store(name = "agentstore")}. JDBC SELECTs issued by the six built-in tools route
 * to the agentstore HikariCP — wrapping the main DataSource would yield zero counts.</p>
 *
 * <p>Pattern: {@link BeanPostProcessor#postProcessAfterInitialization(Object, String)}
 * intercepts the bean named {@code "agentstoreDataSource"} (declared by
 * {@code AgentstoreStoreConfiguration#agentstoreDataSource}). The main {@code dataSource}
 * bean defined in {@code AITestConfiguration} is untouched.</p>
 *
 * <p>EclipseLink-friendly substitute for the {@code SessionFactory.getStatistics()} API
 * named in CONTEXT D-07 — that API is Hibernate-only.</p>
 */
@TestConfiguration
public class QueryCountingDataSourceConfiguration {

    /** Logical name registered into {@link net.ttddyy.dsproxy.QueryCountHolder}. */
    public static final String DATASOURCE_NAME = "counting-ds";

    /** Bean name of the agentstore DataSource declared by {@code AgentstoreStoreConfiguration}. */
    private static final String TARGET_BEAN_NAME = "agentstoreDataSource";

    @Bean
    public BeanPostProcessor queryCountingDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (TARGET_BEAN_NAME.equals(beanName) && bean instanceof DataSource delegate) {
                    return ProxyDataSourceBuilder.create(delegate)
                            .name(DATASOURCE_NAME)
                            .countQuery(new SingleQueryCountHolder())
                            .build();
                }
                return bean;
            }
        };
    }
}
