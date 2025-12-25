package io.kestra.plugin.email;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.flows.State;
import io.kestra.core.repositories.LocalFlowRepositoryLoader;
import io.kestra.core.runners.TestRunner;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Objects;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

@KestraTest
class MailExecutionTest extends AbstractEmailTest {
    @Inject
    protected TestRunner runner;

    @Inject
    protected LocalFlowRepositoryLoader repositoryLoader;

    @BeforeEach
    void init() throws IOException, URISyntaxException {
        repositoryLoader.load(Objects.requireNonNull(MailExecutionTest.class.getClassLoader().getResource("flows")));
        this.runner.run();
    }

    @Test
    void testFlow() throws Exception {
        var failedExecution = runAndCaptureExecution(
            "main-flow-that-fails",
            "mail"
        );

        assertThat(failedExecution.getTaskRunList(), hasSize(1));
        assertThat(failedExecution.getTaskRunList().getFirst().getState().getCurrent(), is(State.Type.FAILED));
    }
}
