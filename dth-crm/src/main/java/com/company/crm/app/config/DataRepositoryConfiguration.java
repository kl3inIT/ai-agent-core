package com.company.crm.app.config;

import io.jmix.core.repository.EnableJmixDataRepositories;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableJmixDataRepositories(basePackages = "com.company.crm")
public class DataRepositoryConfiguration {
}
