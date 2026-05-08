package com.vn.agent.view.chat.intent;

import com.vn.agent.tools.mutation.fixture.MutationTestFixture;
import io.jmix.flowui.view.StandardDetailView;
import org.springframework.context.ApplicationContext;
import org.springframework.lang.NonNull;

class TestMutationDetailView extends StandardDetailView<MutationTestFixture> {

    private final MutationTestFixture editedEntity = new MutationTestFixture();

    TestMutationDetailView() {
        setId("TestMutation.detail");
    }

    void setTestApplicationContext(ApplicationContext applicationContext) {
        setApplicationContext(applicationContext);
    }

    @Override
    @NonNull
    public MutationTestFixture getEditedEntity() {
        return editedEntity;
    }
}
