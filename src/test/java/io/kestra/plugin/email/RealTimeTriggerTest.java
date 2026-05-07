package io.kestra.plugin.email;

import io.kestra.core.junit.annotations.LoadFlows;
import io.kestra.core.models.executions.Execution;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

class RealTimeTriggerTest extends AbstractTriggerTest {
    private static final Duration IMAP_TRIGGER_STARTUP_WAIT = Duration.ofSeconds(5);

    @Test
    @LoadFlows({"flows/real-time-trigger-pop3.yaml"})
    void RealTimeTriggerWithPop3() throws Exception {
        runRealtimeTrigger("real-time-trigger-pop3", Duration.ZERO);
    }

    @Test
    @LoadFlows({"flows/real-time-trigger-imap.yaml"})
    void RealTimeTriggerWithImap() throws Exception {
        runRealtimeTrigger("real-time-trigger-imap", IMAP_TRIGGER_STARTUP_WAIT);
    }

    private void runRealtimeTrigger(String flowId, Duration startupWait) throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).until(() -> scheduler.isActive());

        CountDownLatch queueCount = new CountDownLatch(1);
        AtomicReference<Execution> lastExecution = new AtomicReference<>();

        executionQueue.addListener(execution -> {
            if (execution.getFlowId().equals(flowId)) {
                lastExecution.set(execution);
                queueCount.countDown();
            }
        });

        if (!startupWait.isZero()) {
            Thread.sleep(startupWait.toMillis());
        }

        sendTestEmail("Test Email", "sender@example.com", "Test email body");

        boolean await = queueCount.await(2, TimeUnit.MINUTES);
        assertThat(flowId + " should execute", await, is(true));

        Execution execution = lastExecution.get();
        assertThat(execution, notNullValue());

        Map<String, Object> triggerVars = execution.getTrigger().getVariables();
        assertThat("Latest email subject should be present", triggerVars.get("subject"), notNullValue());
        assertThat("Latest email sender should be present", triggerVars.get("from"), notNullValue());
        assertThat("Latest email body should be present", triggerVars.get("body"), notNullValue());
    }
}
