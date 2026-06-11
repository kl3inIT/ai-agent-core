package com.company.crm;

import io.jmix.core.annotation.JmixModule;
import org.springframework.context.annotation.Configuration;

/**
 * Declares com.company.crm as a Jmix module so its @JmixEntity classes
 * are registered in the metamodel even though the SpringBootApplication
 * class lives in com.vn.dth.crm.
 */
@Configuration
@JmixModule(id = "com.company.crm")
public class CrmJmixModuleConfiguration {
}
