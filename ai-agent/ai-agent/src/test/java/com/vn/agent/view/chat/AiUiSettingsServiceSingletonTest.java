package com.vn.agent.view.chat;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiChatSurface;
import com.vn.agent.entity.AiUiSettings;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AITestConfiguration.class)
class AiUiSettingsServiceSingletonTest {

    @Autowired
    AiUiSettingsService service;

    @Autowired
    UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    SystemAuthenticator systemAuthenticator;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        unconstrainedDataManager.load(AiUiSettings.class)
                .all()
                .list()
                .forEach(unconstrainedDataManager::remove);
    }

    @Test
    void firstLoadCreatesSingletonWithDefaults() {
        AiUiSettings settings = loadAsAdmin();

        assertThat(settings.getId()).isEqualTo(AiUiSettings.SINGLETON_ID);
        assertThat(settings.getDefaultSurface()).isEqualTo(AiChatSurface.FULL_ROUTE);
        assertThat(settings.getEnabledSurfaceSet())
                .containsExactly(AiChatSurface.FULL_ROUTE, AiChatSurface.HEADER_BUTTON);
        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void repeatedLoadReturnsSameSingletonWithoutDuplicates() {
        UUID firstId = loadAsAdmin().getId();
        UUID secondId = loadAsAdmin().getId();

        assertThat(secondId).isEqualTo(firstId).isEqualTo(AiUiSettings.SINGLETON_ID);
        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void concurrentFirstLoadRaceCreatesExactlyOneSingleton() throws Exception {
        int callerCount = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AiUiSettings>> futures = new ArrayList<>();
        try (ExecutorService executorService = Executors.newFixedThreadPool(callerCount)) {
            for (int i = 0; i < callerCount; i++) {
                futures.add(executorService.submit(loadAfter(start)));
            }

            start.countDown();

            for (Future<AiUiSettings> future : futures) {
                AiUiSettings settings = future.get(10, TimeUnit.SECONDS);
                assertThat(settings.getId()).isEqualTo(AiUiSettings.SINGLETON_ID);
            }
        }

        assertThat(countRows()).isEqualTo(1);
    }

    private Callable<AiUiSettings> loadAfter(CountDownLatch start) {
        return () -> {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return loadAsAdmin();
        };
    }

    private AiUiSettings loadAsAdmin() {
        return systemAuthenticator.withUser("admin", () -> service.loadCurrent());
    }

    private int countRows() {
        return unconstrainedDataManager.load(AiUiSettings.class)
                .all()
                .list()
                .size();
    }
}
