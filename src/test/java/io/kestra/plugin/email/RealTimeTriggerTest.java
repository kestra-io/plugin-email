package io.kestra.plugin.email;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.repositories.LocalFlowRepositoryLoader;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;
import reactor.core.publisher.Flux;

import static io.kestra.core.tenant.TenantService.MAIN_TENANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@KestraTest(startRunner = true, startScheduler = true)
class RealTimeTriggerTest extends AbstractTriggerTest {
    private static final Duration REALTIME_TRIGGER_STARTUP_WAIT = Duration.ofSeconds(5);
    private static final String OVERSIZED_ATTACHMENT_FILENAME = "big-report.txt";
    private static final byte[] OVERSIZED_ATTACHMENT_CONTENT = "this content is larger than the configured limit".getBytes(StandardCharsets.UTF_8);

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

        var triggerVars = execution.getTrigger().getVariables();
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

        var triggerVars = execution.getTrigger().getVariables();
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

        var triggerVars = execution.getTrigger().getVariables();

        @SuppressWarnings("unchecked")
        var attachments = (List<Map<String, Object>>) triggerVars.get("attachments");
        assertThat("Should have exactly one attachment", attachments, hasSize(1));

        var attachment = attachments.getFirst();
        assertThat("Attachment filename should match", attachment.get("filename"), is(ATTACHMENT_FILENAME));
        assertThat("Attachment should have a storage uri", attachment.get("uri"), notNullValue());

        URI uri = URI.create((String) attachment.get("uri"));
        assertThat(
            "Attachment should be scoped to the started execution",
            uri.getPath(),
            is(containsString("/executions/" + execution.getId() + "/"))
        );
        String content = IOUtils.toString(storageInterface.get(MAIN_TENANT, null, uri), StandardCharsets.UTF_8);
        assertThat("Stored attachment content should match the sent attachment", content, is(ATTACHMENT_CONTENT));
    }

    @Test
    void RealTimeTriggerSinglePartPdfEmailIsReturnedWithItsAttachment() throws Exception {
        var execution = runRealtimeTrigger(
            "flows/real-time-trigger-imap-pdf.yaml",
            "real-time-trigger-imap-pdf",
            REALTIME_TRIGGER_STARTUP_WAIT,
            () -> sendSinglePartTestEmail("Scanned Invoice", "sender@example.com", PDF_FILENAME, PDF_CONTENT, "application/pdf")
        );

        var triggerVars = execution.getTrigger().getVariables();
        assertThat("Email subject should match", triggerVars.get("subject"), is("Scanned Invoice"));
        assertThat("An email without a text part should have an empty body", triggerVars.get("body"), is(""));

        @SuppressWarnings("unchecked")
        var attachments = (List<Map<String, Object>>) triggerVars.get("attachments");
        assertThat("The PDF should be returned as the email's attachment", attachments, hasSize(1));

        var attachment = attachments.getFirst();
        assertThat("Attachment filename should match", attachment.get("filename"), is(PDF_FILENAME));
        assertThat("Attachment should have a storage uri", attachment.get("uri"), notNullValue());

        URI uri = URI.create((String) attachment.get("uri"));
        byte[] content = IOUtils.toByteArray(storageInterface.get(MAIN_TENANT, null, uri));
        assertThat("Stored attachment content should match the sent PDF", content, is(PDF_CONTENT));
    }

    @Test
    void RealTimeTriggerUnknownCharsetDoesNotDropTheOtherEmails() throws Exception {
        var executions = runRealtimeTrigger(
            "flows/real-time-trigger-imap-bad-charset.yaml",
            "real-time-trigger-imap-bad-charset",
            REALTIME_TRIGGER_STARTUP_WAIT,
            3,
            () ->
            {
                sendTestEmail("Realtime Good 1", "bogus-charset@example.com", "Realtime good body 1");
                sendRawTestEmail(UNKNOWN_CHARSET_EMAIL);
                sendTestEmail("Realtime Good 2", "bogus-charset@example.com", "Realtime good body 2");
            }
        );

        assertThat("Every email should trigger, there should not be duplicates", executions, hasSize(3));
        assertThat(
            "Every email should trigger despite the unknown charset",
            executions.stream().map(execution -> execution.getTrigger().getVariables().get("subject")).toList(),
            containsInAnyOrder("Realtime Good 1", "Bogus Charset", "Realtime Good 2")
        );

        var bogusCharsetExecution = executions.stream()
            .filter(execution -> "Bogus Charset".equals(execution.getTrigger().getVariables().get("subject")))
            .findFirst()
            .orElseThrow();
        assertThat(
            "An email whose charset is unknown to the JVM should still have its body decoded",
            bogusCharsetExecution.getTrigger().getVariables().get("body"),
            is("Bogus charset body")
        );
    }

    @Test
    void RealTimeTriggerOversizedAttachmentDoesNotPreventExecution() throws Exception {
        var execution = runRealtimeTrigger(
            "flows/real-time-trigger-imap-max-attachment-size.yaml",
            "real-time-trigger-imap-max-attachment-size",
            REALTIME_TRIGGER_STARTUP_WAIT,
            () -> sendTestEmailWithAttachment(
                "Email With Oversized Attachment", "sender@example.com", "Oversized attachment email body",
                OVERSIZED_ATTACHMENT_FILENAME, OVERSIZED_ATTACHMENT_CONTENT
            )
        );

        var triggerVars = execution.getTrigger().getVariables();
        assertThat("Execution should contain the oversized email", triggerVars.get("subject"), is("Email With Oversized Attachment"));

        @SuppressWarnings("unchecked")
        var attachments = (List<Map<String, Object>>) triggerVars.get("attachments");
        assertThat("Should have exactly one attachment", attachments, hasSize(1));
        assertThat("Oversized attachment should keep a null URI", attachments.getFirst().get("uri"), nullValue());
    }

    private Execution runRealtimeTrigger(String flowPath, String flowId, Duration startupWait) throws Exception {
        return runRealtimeTrigger(
            flowPath, flowId, startupWait,
            () -> sendTestEmail("Test Email", "sender@example.com", "Test email body")
        );
    }

    private Execution runRealtimeTrigger(String flowPath, String flowId, Duration startupWait, Executable sendEmail)
        throws Exception {
        var executions = runRealtimeTrigger(flowPath, flowId, startupWait, 1, sendEmail);

        var execution = executions.getFirst();
        assertThat("Execution should be captured", execution, notNullValue());
        return execution;
    }

    // Await multiple executions to cover emails arriving in one IDLE notification.
    private List<Execution> runRealtimeTrigger(String flowPath, String flowId, Duration startupWait, int expectedExecutions,
        Executable sendEmail) throws Exception {
        var queueCount = new CountDownLatch(expectedExecutions);
        var executions = new ConcurrentHashMap<String, Execution>();

        Flux<Execution> receive = TestsUtils.receive(executionQueue, execution ->
        {
            if (execution.getLeft().getFlowId().equals(flowId)) {
                if (executions.putIfAbsent(execution.getLeft().getId(), execution.getLeft()) == null) {
                    queueCount.countDown();
                }
            }
        });

        repositoryLoader.load(Objects.requireNonNull(RealTimeTriggerTest.class.getClassLoader().getResource(flowPath)));

        if (!startupWait.isZero()) {
            Thread.sleep(startupWait.toMillis());
        }

        try {
            sendEmail.execute();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }

        boolean await = queueCount.await(30, TimeUnit.SECONDS);
        assertThat(flowId + " should execute " + expectedExecutions + " time(s)", await, is(true));

        var execution = receive.blockLast();
        assertThat("Executions should be captured", execution, notNullValue());

        return List.copyOf(executions.values());
    }
}
