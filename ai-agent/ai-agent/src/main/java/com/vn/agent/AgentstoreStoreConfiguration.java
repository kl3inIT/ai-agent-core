package com.vn.agent;

import io.jmix.autoconfigure.data.JmixLiquibaseCreator;
import io.jmix.core.JmixModules;
import io.jmix.core.Resources;
import io.jmix.data.impl.JmixEntityManagerFactoryBean;
import io.jmix.data.impl.JmixTransactionManager;
import io.jmix.data.persistence.DbmsSpecifics;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

@Configuration
public class AgentstoreStoreConfiguration {

    @Bean
    @ConfigurationProperties("agentstore.datasource")
    DataSourceProperties agentstoreDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "agentstore.datasource.hikari")
    DataSource agentstoreDataSource(
            @Qualifier("agentstoreDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    LocalContainerEntityManagerFactoryBean agentstoreEntityManagerFactory(
            @Qualifier("agentstoreDataSource") DataSource dataSource,
            JpaVendorAdapter jpaVendorAdapter,
            DbmsSpecifics dbmsSpecifics,
            JmixModules jmixModules,
            Resources resources) {
        return new JmixEntityManagerFactoryBean(
                "agentstore", dataSource, jpaVendorAdapter, dbmsSpecifics, jmixModules, resources);
    }

    @Bean
    JpaTransactionManager agentstoreTransactionManager(
            @Qualifier("agentstoreEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JmixTransactionManager("agentstore", entityManagerFactory);
    }

    @Bean("agentstoreLiquibaseProperties")
    @ConfigurationProperties(prefix = "agentstore.liquibase")
    public LiquibaseProperties agentstoreLiquibaseProperties() {
        return new LiquibaseProperties();
    }

    @Bean("agentstoreLiquibase")
    public SpringLiquibase agentstoreLiquibase(@Qualifier("agentstoreDataSource") DataSource dataSource,
                                               @Qualifier("agentstoreLiquibaseProperties") LiquibaseProperties liquibaseProperties) {
        return JmixLiquibaseCreator.create(dataSource, liquibaseProperties);
    }

    @Bean
    JdbcTemplate pgvectorJdbcTemplate(@Qualifier("agentstoreDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}

