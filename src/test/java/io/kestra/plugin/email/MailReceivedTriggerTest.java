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
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

class MailReceivedTriggerTest extends AbstractTriggerTest {

    @Test
    @LoadFlows({"flows/mail-received-trigger-pop3.yaml"})
    void MailReceivedTriggerWithPop3() throws Exception {
        runTrigger("mail-received-trigger-pop3");
    }

    @Test
    @LoadFlows({"flows/mail-received-trigger-imap.yaml"})
    void MailReceivedTriggerWithImap() throws Exception {
        runTrigger("mail-received-trigger-imap");
    }

    private void runTrigger(String flowId) throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).until(() -> scheduler.isActive());

        CountDownLatch queueCount = new CountDownLatch(1);
        AtomicReference<Execution> lastExecution = new AtomicReference<>();

        executionQueue.addListener(execution -> {
            if (execution.getFlowId().equals(flowId)) {
                lastExecution.set(execution);
                queueCount.countDown();
            }
        });

        sendTestEmail("First Email", "sender1@example.com", "First test email body");

        boolean await = queueCount.await(1, TimeUnit.MINUTES);
        assertThat(flowId + " trigger should execute", await, is(true));

        Execution execution = lastExecution.get();
        assertThat(execution, notNullValue());

        Map<String, Object> triggerVars = execution.getTrigger().getVariables();
        assertThat("Should have received emails", (Integer) triggerVars.get("total"), greaterThan(0));

        @SuppressWarnings("unchecked")
        Map<String, Object> latestEmail = (Map<String, Object>) triggerVars.get("latestEmail");
        assertThat("Latest email should have a subject", latestEmail.get("subject"), notNullValue());
        assertThat("Latest email should have a from address", latestEmail.get("from"), notNullValue());
        assertThat("Latest email subject should match", latestEmail.get("subject"), is("First Email"));
    }
}
