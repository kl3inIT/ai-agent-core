package com.vn.agent.tools.mutation;

import com.vn.agent.AIConfiguration;
import io.jmix.core.annotation.JmixModule;
import io.jmix.gridexportflowui.GridExportFlowuiConfiguration;
import io.jmix.securitydata.SecurityDataConfiguration;
import io.jmix.securityflowui.SecurityFlowuiConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Test-only Jmix module that contributes a main-store persistence unit for the mutation
 * fixture entities while preserving jmix-security-data mappings.
 */
@Configuration
@JmixModule(id = "com.vn.agent.mutation.test", dependsOn = {
        AIConfiguration.class,
        GridExportFlowuiConfiguration.class,
        SecurityDataConfiguration.class,
        SecurityFlowuiConfiguration.class
})
public class MutationFixturePersistenceTestConfiguration {
}
