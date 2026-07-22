package io.kestra.plugin.email;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import io.kestra.core.junit.annotations.EvaluateTrigger;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.storages.StorageInterface;

import jakarta.inject.Inject;

import static io.kestra.core.tenant.TenantService.MAIN_TENANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class MailReceivedTriggerTest extends AbstractTriggerTest {

    @Inject
    private StorageInterface storageInterface;

    private static final String ATTACHMENT_FILENAME = "report.txt";
    private static final String ATTACHMENT_CONTENT = "attachment content";

    @BeforeEach
    void sendEmailForTest(TestInfo testInfo) throws Exception {
        // Each attachment test needs its own dedicated inbox content: mixing it with "First Email" would make
        // "latestEmail" selection depend on GreenMail's delivery timestamp ordering, which is not deterministic
        // when both messages are delivered within the same second.
        String testName = testInfo.getTestMethod().map(Method::getName).orElse("");

        if (testName.equals("attachmentIsStoredInInternalStorage")) {
            sendTestEmailWithAttachment(
                "Email With Attachment", "sender2@example.com", "Attachment email body",
                ATTACHMENT_FILENAME, ATTACHMENT_CONTENT.getBytes(StandardCharsets.UTF_8)
            );
        } else if (testName.equals("duplicateAttachmentFilenamesGetDistinctUris")) {
            sendTestEmailWithAttachments(
                "Email With Duplicate Attachment Names", "sender3@example.com", "Duplicate attachment names body",
                List.of(
                    new Attachment(ATTACHMENT_FILENAME, "content-1".getBytes(StandardCharsets.UTF_8)),
                    new Attachment(ATTACHMENT_FILENAME, "content-2".getBytes(StandardCharsets.UTF_8))
                )
            );
        } else {
            sendTestEmail("First Email", "sender1@example.com", "First test email body");
        }
    }

    @Test
    @EvaluateTrigger(flow = "flows/mail-received-trigger-pop3.yaml", triggerId = "pop3-mail-trigger")
    void MailReceivedTriggerWithPop3(Optional<Execution> optionalExecution) {
        assertThat(optionalExecution.isPresent(), is(true));

        var execution = optionalExecution.get();
        Map<String, Object> triggerVars = execution.getTrigger().getVariables();

        assertThat("Should have received emails", (Integer) triggerVars.get("total"), greaterThan(0));

        @SuppressWarnings("unchecked")
        var latestEmail = (Map<String, Object>) triggerVars.get("latestEmail");
        assertThat("Latest email should have a subject", latestEmail.get("subject"), notNullValue());
        assertThat("Latest email should have a from address", latestEmail.get("from"), notNullValue());
        assertThat(
            "Latest email subject should be one of the sent emails",
            latestEmail.get("subject"),
            is("First Email")
        );
    }

    @Test
    @EvaluateTrigger(flow = "flows/mail-received-trigger-imap.yaml", triggerId = "imap-mail-trigger")
    void MailReceivedTriggerWithImap(Optional<Execution> optionalExecution) {
        assertThat(optionalExecution.isPresent(), is(true));

        var execution = optionalExecution.get();
        Map<String, Object> triggerVars = execution.getTrigger().getVariables();

        assertThat("Should have received emails", (Integer) triggerVars.get("total"), greaterThan(0));

        @SuppressWarnings("unchecked")
        var latestEmail = (Map<String, Object>) triggerVars.get("latestEmail");
        assertThat("Latest email should have a subject", latestEmail.get("subject"), notNullValue());
        assertThat("Latest email should have a from address", latestEmail.get("from"), notNullValue());
        assertThat(
            "Latest email subject should be one of the sent emails",
            latestEmail.get("subject"),
            is("First Email")
        );
    }

    @Test
    @EvaluateTrigger(flow = "flows/mail-received-trigger-imap.yaml", triggerId = "imap-mail-trigger")
    void attachmentIsStoredInInternalStorage(Optional<Execution> optionalExecution) throws Exception {
        assertThat(optionalExecution.isPresent(), is(true));

        var execution = optionalExecution.get();
        Map<String, Object> triggerVars = execution.getTrigger().getVariables();

        @SuppressWarnings("unchecked")
        var latestEmail = (Map<String, Object>) triggerVars.get("latestEmail");
        assertThat("Latest email subject should match the sent attachment email", latestEmail.get("subject"), is("Email With Attachment"));

        @SuppressWarnings("unchecked")
        var attachments = (List<Map<String, Object>>) latestEmail.get("attachments");
        assertThat("Should have exactly one attachment", attachments, hasSize(1));

        var attachment = attachments.getFirst();
        assertThat("Attachment filename should match", attachment.get("filename"), is(ATTACHMENT_FILENAME));
        assertThat("Attachment should have a storage uri", attachment.get("uri"), notNullValue());

        URI uri = URI.create((String) attachment.get("uri"));
        String content = IOUtils.toString(storageInterface.get(MAIN_TENANT, null, uri), StandardCharsets.UTF_8);
        assertThat("Stored attachment content should match the sent attachment", content, is(ATTACHMENT_CONTENT));
    }

    @Test
    @EvaluateTrigger(flow = "flows/mail-received-trigger-imap.yaml", triggerId = "imap-mail-trigger")
    void duplicateAttachmentFilenamesGetDistinctUris(Optional<Execution> optionalExecution) throws Exception {
        assertThat(optionalExecution.isPresent(), is(true));

        var execution = optionalExecution.get();
        Map<String, Object> triggerVars = execution.getTrigger().getVariables();

        @SuppressWarnings("unchecked")
        var latestEmail = (Map<String, Object>) triggerVars.get("latestEmail");
        assertThat(
            "Latest email subject should match the sent duplicate-attachment email",
            latestEmail.get("subject"),
            is("Email With Duplicate Attachment Names")
        );

        @SuppressWarnings("unchecked")
        var attachments = (List<Map<String, Object>>) latestEmail.get("attachments");
        assertThat("Should have exactly two attachments", attachments, hasSize(2));

        var firstAttachment = attachments.get(0);
        var secondAttachment = attachments.get(1);

        assertThat("Both attachments should keep their original filename", firstAttachment.get("filename"), is(ATTACHMENT_FILENAME));
        assertThat("Both attachments should keep their original filename", secondAttachment.get("filename"), is(ATTACHMENT_FILENAME));

        Object firstUri = firstAttachment.get("uri");
        Object secondUri = secondAttachment.get("uri");
        assertThat("First attachment should have a storage uri", firstUri, notNullValue());
        assertThat("Second attachment should have a storage uri", secondUri, notNullValue());
        assertThat("Same-named attachments must not collide on the same storage uri", firstUri, is(not(secondUri)));

        String firstContent = IOUtils.toString(
            storageInterface.get(MAIN_TENANT, null, URI.create((String) firstUri)), StandardCharsets.UTF_8
        );
        String secondContent = IOUtils.toString(
            storageInterface.get(MAIN_TENANT, null, URI.create((String) secondUri)), StandardCharsets.UTF_8
        );

        assertThat("First attachment content should match what was sent", firstContent, is("content-1"));
        assertThat("Second attachment content should match what was sent", secondContent, is("content-2"));
    }
}
