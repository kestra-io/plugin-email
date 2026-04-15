package io.kestra.plugin.email;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.repositories.LocalFlowRepositoryLoader;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;
import reactor.core.publisher.Flux;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest(startRunner = true, startScheduler = true)
class RealTimeTriggerTest extends AbstractTriggerTest {
    private static final Duration REALTIME_TRIGGER_STARTUP_WAIT = Duration.ofSeconds(5);

    @Inject
    private LocalFlowRepositoryLoader repositoryLoader;

    @Test
    void RealTimeTriggerWithPop3() throws Exception {
        var execution = runRealtimeTrigger(
            "flows/real-time-trigger-pop3.yaml",
            "real-time-trigger-pop3",
            Duration.ZERO
        );

        Map<String, Object> triggerVars = execution.getTrigger().getVariables();
        assertThat("Latest email subject should be present", triggerVars.get("subject"), notNullValue());
        assertThat("Latest email sender should be present", triggerVars.get("from"), notNullValue());
        assertThat("Latest email body should be present", triggerVars.get("body"), notNullValue());
    }

    @Test
    void RealTimeTriggerWithImap() throws Exception {
        var execution = runRealtimeTrigger(
            "flows/real-time-trigger-imap.yaml",
            "real-time-trigger-imap",
            REALTIME_TRIGGER_STARTUP_WAIT
        );

        Map<String, Object> triggerVars = execution.getTrigger().getVariables();
        assertThat("Latest email subject should be present", triggerVars.get("subject"), notNullValue());
        assertThat("Latest email sender should be present", triggerVars.get("from"), notNullValue());
        assertThat("Latest email body should be present", triggerVars.get("body"), notNullValue());
    }

    private Execution runRealtimeTrigger(String flowPath, String flowId, Duration startupWait) throws Exception {
        var queueCount = new CountDownLatch(1);

        Flux<Execution> receive = TestsUtils.receive(executionQueue, execution -> {
            if (execution.getLeft().getFlowId().equals(flowId)) {
                queueCount.countDown();
            }
        });

        repositoryLoader.load(Objects.requireNonNull(RealTimeTriggerTest.class.getClassLoader().getResource(flowPath)));

        if (!startupWait.isZero()) {
            // IMAP only sees emails after the listener is attached, so give the realtime listener time to subscribe.
            Thread.sleep(startupWait.toMillis());
        }

        sendTestEmail("Test Email", "sender@example.com", "Test email body");

        boolean await = queueCount.await(30, TimeUnit.SECONDS);
        assertThat(flowId + " should execute", await, is(true));

        var execution = receive.blockLast();
        assertThat("Execution should be captured", execution, notNullValue());
        return execution;
    }
}
