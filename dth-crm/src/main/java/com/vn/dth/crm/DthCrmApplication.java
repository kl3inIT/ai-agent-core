package com.vn.dth.crm;

import com.google.common.base.Strings;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.server.PWA;
import com.vaadin.flow.theme.Theme;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

@Push
@Theme(value = "crm")
@PWA(name = "DTH CRM", shortName = "DTH CRM", offline = false)
@com.vaadin.flow.component.dependency.JsModule("./src/theme/color-scheme-switching-support.js")
@com.vaadin.flow.component.dependency.JsModule("@vaadin/vaadin-lumo-styles/presets/compact.js")
@SpringBootApplication
@ComponentScan(basePackages = {"com.vn.dth.crm", "com.company.crm"})
public class DthCrmApplication implements AppShellConfigurator {

    @Autowired
    private Environment environment;

    public static void main(String[] args) {
        SpringApplication.run(DthCrmApplication.class, args);
    }

    @Bean
    @Primary
    @ConfigurationProperties("main.datasource")
    DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("main.datasource.hikari")
    DataSource dataSource(final DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().build();
    }

    // ===== agentstore datasource (required by ai-agent add-on) =====
    @Bean
    @ConfigurationProperties("agentstore.datasource")
    DataSourceProperties agentstoreDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("agentstore.datasource.hikari")
    DataSource agentstoreDataSource(@org.springframework.beans.factory.annotation.Qualifier("agentstoreDataSourceProperties")
                                    DataSourceProperties agentstoreDataSourceProperties) {
        return agentstoreDataSourceProperties.initializeDataSourceBuilder().build();
    }

    @EventListener
    public void printApplicationUrl(final ApplicationStartedEvent event) {
        LoggerFactory.getLogger(DthCrmApplication.class).info("Application started at "
                + "http://localhost:"
                + environment.getProperty("local.server.port")
                + Strings.nullToEmpty(environment.getProperty("server.servlet.context-path")));
    }
}
