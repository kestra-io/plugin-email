package io.kestra.plugin.email;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.repositories.LocalFlowRepositoryLoader;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;
import reactor.core.publisher.Flux;

import static io.kestra.core.tenant.TenantService.MAIN_TENANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest(startRunner = true, startScheduler = true)
class RealTimeTriggerTest extends AbstractTriggerTest {
    private static final Duration REALTIME_TRIGGER_STARTUP_WAIT = Duration.ofSeconds(5);
    private static final String ATTACHMENT_FILENAME = "report.txt";
    private static final String ATTACHMENT_CONTENT = "attachment content";

    @Inject
    private LocalFlowRepositoryLoader repositoryLoader;

    @Inject
    private StorageInterface storageInterface;

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

    @Test
    void RealTimeTriggerAttachmentIsStoredInInternalStorage() throws Exception {
        var execution = runRealtimeTrigger(
            "flows/real-time-trigger-imap-attachment.yaml",
            "real-time-trigger-imap-attachment",
            REALTIME_TRIGGER_STARTUP_WAIT,
            () -> sendTestEmailWithAttachment(
                "Email With Attachment", "sender@example.com", "Attachment email body",
                ATTACHMENT_FILENAME, ATTACHMENT_CONTENT.getBytes(StandardCharsets.UTF_8)
            )
        );

        Map<String, Object> triggerVars = execution.getTrigger().getVariables();

        @SuppressWarnings("unchecked")
        var attachments = (List<Map<String, Object>>) triggerVars.get("attachments");
        assertThat("Should have exactly one attachment", attachments, hasSize(1));

        var attachment = attachments.getFirst();
        assertThat("Attachment filename should match", attachment.get("filename"), is(ATTACHMENT_FILENAME));
        assertThat("Attachment should have a storage uri", attachment.get("uri"), notNullValue());

        URI uri = URI.create((String) attachment.get("uri"));
        String content = IOUtils.toString(storageInterface.get(MAIN_TENANT, null, uri), StandardCharsets.UTF_8);
        assertThat("Stored attachment content should match the sent attachment", content, is(ATTACHMENT_CONTENT));
    }

    private Execution runRealtimeTrigger(String flowPath, String flowId, Duration startupWait) throws Exception {
        return runRealtimeTrigger(
            flowPath, flowId, startupWait,
            () -> sendTestEmail("Test Email", "sender@example.com", "Test email body")
        );
    }

    private Execution runRealtimeTrigger(String flowPath, String flowId, Duration startupWait, ThrowingRunnable sendEmail)
        throws Exception {
        var queueCount = new CountDownLatch(1);

        Flux<Execution> receive = TestsUtils.receive(executionQueue, execution ->
        {
            if (execution.getLeft().getFlowId().equals(flowId)) {
                queueCount.countDown();
            }
        });

        repositoryLoader.load(Objects.requireNonNull(RealTimeTriggerTest.class.getClassLoader().getResource(flowPath)));

        if (!startupWait.isZero()) {
            Thread.sleep(startupWait.toMillis());
        }

        sendEmail.run();

        boolean await = queueCount.await(30, TimeUnit.SECONDS);
        assertThat(flowId + " should execute", await, is(true));

        var execution = receive.blockLast();
        assertThat("Execution should be captured", execution, notNullValue());
        return execution;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
