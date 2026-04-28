package com.vn.agent.exposure;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.Metadata;
import io.jmix.core.UnconstrainedDataManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class AiExposureRuleAdminServiceIntegrationTest {

    @Autowired
    private AiExposureRuleAdminService service;
    @Autowired
    private UnconstrainedDataManager dataManager;
    @Autowired
    private Metadata metadata;

    private final Set<String> cleanupPrefixes = new LinkedHashSet<>();

    @AfterEach
    void cleanUp() {
        for (String prefix : cleanupPrefixes) {
            dataManager.load(AiExposureRule.class)
                    .query("select r from aiExposure_AiExposureRule r where r.entityName like :prefix")
                    .parameter("prefix", prefix + "%")
                    .list()
                    .forEach(dataManager::remove);
        }
        cleanupPrefixes.clear();
    }

    @Test
    void replaceHiddenEntityNamesCreatesAndDisablesRulesForSelectableEntities() {
        String prefix = testPrefix();
        String customer = prefix + "Customer";
        String product = prefix + "Product";

        service.replaceHiddenEntityNames(Set.of(customer, product), Set.of(customer, product));
        assertThat(service.findHiddenEntityNames()).contains(customer, product);

        service.replaceHiddenEntityNames(Set.of(product), Set.of(customer, product));

        assertThat(service.findHiddenEntityNames())
                .contains(product)
                .doesNotContain(customer);
        AiExposureRule customerRule = dataManager.load(AiExposureRule.class)
                .query("select r from aiExposure_AiExposureRule r where r.entityName = :entityName")
                .parameter("entityName", customer)
                .one();
        assertThat(customerRule.getEnabled()).isFalse();
    }

    @Test
    void replaceHiddenEntityNamesPreservesRulesOutsideSelectableSet() {
        String prefix = testPrefix();
        String legacyEntity = prefix + "Legacy";
        String selectableEntity = prefix + "Selectable";

        AiExposureRule rule = metadata.create(AiExposureRule.class);
        rule.setEntityName(legacyEntity);
        rule.setMode(AiExposureRuleMode.EXCLUDE);
        rule.setEnabled(true);
        dataManager.save(rule);

        service.replaceHiddenEntityNames(Set.of(), Set.of(selectableEntity));

        assertThat(service.findHiddenEntityNames()).contains(legacyEntity);
    }

    private String testPrefix() {
        String prefix = "test_AiExposureRuleAdminService_" + UUID.randomUUID() + "_";
        cleanupPrefixes.add(prefix);
        return prefix;
    }
}
