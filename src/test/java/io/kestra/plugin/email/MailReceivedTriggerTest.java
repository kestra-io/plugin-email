package io.kestra.plugin.email;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

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

    private static final String OVERSIZED_ATTACHMENT_FILENAME = "big-report.txt";
    private static final String OVERSIZED_ATTACHMENT_CONTENT = "this content is larger than the configured limit";
    private static final int BURST_SIZE = 15;

    @BeforeEach
    void sendEmailForTest(TestInfo testInfo) throws Exception {
        // Each attachment test needs its own dedicated inbox content: mixing it with "First Email" would make
        // "latestEmail" selection depend on GreenMail's delivery timestamp ordering, which is not deterministic
        // when both messages are delivered within the same second.
        //
        // This dispatch cannot move into each test method body: @EvaluateTrigger resolves the Optional<Execution>
        // parameter (and therefore evaluates the trigger) before the test method body runs, so the email must
        // already be sitting in the mailbox by the time @BeforeEach returns.
        String testName = testInfo.getTestMethod().map(Method::getName).orElse("");

        if (testName.equals("attachmentIsStoredInInternalStorage")) {
            sendTestEmailWithAttachment(
                "Email With Attachment", "sender2@example.com", "Attachment email body",
                ATTACHMENT_FILENAME, ATTACHMENT_CONTENT.getBytes(StandardCharsets.UTF_8)
            );
        } else if (testName.equals("singlePartPdfEmailIsReturnedWithItsAttachment")) {
            sendTestEmail("Text Email 1", "sender6@example.com", "Text email body 1");
            sendSinglePartTestEmail("Scanned Invoice", "sender6@example.com", PDF_FILENAME, PDF_CONTENT, "application/pdf");
            sendTestEmail("Text Email 2", "sender6@example.com", "Text email body 2");
        } else if (testName.equals("singlePartCalendarEmailWithoutFilenameIsDecodedAndAttached")) {
            sendSinglePartTestEmail(
                "Team Invite", "sender7@example.com", null, ICS_CONTENT.getBytes(StandardCharsets.UTF_8),
                "text/calendar; charset=UTF-8; method=REQUEST"
            );
        } else if (testName.equals("singlePartInlineImageWithoutFilenameIsReturnedAsAttachment")) {
            sendSinglePartTestEmail("Inline Image", "sender8@example.com", null, PNG_CONTENT, "image/png");
        } else if (testName.equals("duplicateAttachmentFilenamesGetDistinctUris")) {
            sendTestEmailWithAttachments(
                "Email With Duplicate Attachment Names", "sender3@example.com", "Duplicate attachment names body",
                List.of(
                    new Attachment(ATTACHMENT_FILENAME, "content-1".getBytes(StandardCharsets.UTF_8)),
                    new Attachment(ATTACHMENT_FILENAME, "content-2".getBytes(StandardCharsets.UTF_8))
                )
            );
        } else if (testName.equals("oversizedAttachmentKeepsMetadataWithNullUri")) {
            sendTestEmailWithAttachment(
                "Email With Oversized Attachment", "sender4@example.com", "Oversized attachment body",
                OVERSIZED_ATTACHMENT_FILENAME, OVERSIZED_ATTACHMENT_CONTENT.getBytes(StandardCharsets.UTF_8)
            );
        } else if (testName.equals("everyEmailOfABurstIsReturned")) {
            for (int i = 1; i <= BURST_SIZE; i++) {
                sendTestEmail("Burst Email " + i, "sender5@example.com", "Burst email body " + i);
            }
        } else if (testName.equals("textAttachmentWithUnknownCharsetDoesNotDropEmail")) {
            sendRawTestEmail(UNKNOWN_CHARSET_TEXT_ATTACHMENT_EMAIL);
        } else if (testName.equals("emailsWithUnknownCharsetAreStillReturned")) {
            sendTestEmail("Good Email 1", "sender9@example.com", "Good email body 1");
            sendRawTestEmail(UNKNOWN_CHARSET_EMAIL);
            sendRawTestEmail(UNKNOWN_CHARSET_MULTIPART_EMAIL);
            sendTestEmail("Good Email 2", "sender9@example.com", "Good email body 2");
        } else if (testName.equals("malformedEmailIsSkippedWithoutBlockingTheOthers")) {
            sendTestEmail("Good Email 3", "sender10@example.com", "Good email body 3");
            sendRawTestEmail(MISSING_BOUNDARY_EMAIL);
            sendTestEmail("Good Email 4", "sender10@example.com", "Good email body 4");
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
    void singlePartPdfEmailIsReturnedWithItsAttachment(Optional<Execution> optionalExecution) throws Exception {
        assertThat(optionalExecution.isPresent(), is(true));

        var triggerVars = optionalExecution.get().getTrigger().getVariables();

        @SuppressWarnings("unchecked")
        var emails = (List<Map<String, Object>>) triggerVars.get("emails");
        assertThat(
            "Every email should be returned, including the one whose only part is a PDF",
            emails.stream().map(email -> email.get("subject")).toList(),
            contains("Text Email 1", "Scanned Invoice", "Text Email 2")
        );

        var pdfEmail = emails.get(1);
        assertThat("An email without a text part should have an empty body", pdfEmail.get("body"), is(""));

        @SuppressWarnings("unchecked")
        var attachments = (List<Map<String, Object>>) pdfEmail.get("attachments");
        assertThat("The PDF should be returned as the email's attachment", attachments, hasSize(1));

        var attachment = attachments.getFirst();
        assertThat("Attachment filename should match", attachment.get("filename"), is(PDF_FILENAME));
        assertThat("Attachment content type should be the PDF one", (String) attachment.get("contentType"), startsWithIgnoringCase("application/pdf"));
        assertThat("Attachment should have a storage uri", attachment.get("uri"), notNullValue());

        var uri = URI.create((String) attachment.get("uri"));
        var content = IOUtils.toByteArray(storageInterface.get(MAIN_TENANT, null, uri));
        assertThat("Stored attachment content should match the sent PDF", content, is(PDF_CONTENT));
    }

    @Test
    @EvaluateTrigger(flow = "flows/mail-received-trigger-imap.yaml", triggerId = "imap-mail-trigger")
    void singlePartCalendarEmailWithoutFilenameIsDecodedAndAttached(Optional<Execution> optionalExecution) throws Exception {
        assertThat(optionalExecution.isPresent(), is(true));

        var triggerVars = optionalExecution.get().getTrigger().getVariables();

        @SuppressWarnings("unchecked")
        var emails = (List<Map<String, Object>>) triggerVars.get("emails");
        assertThat(emails, hasSize(1));

        var invite = emails.getFirst();
        assertThat("The calendar text should be decoded as the body", invite.get("body"), is(ICS_CONTENT));

        @SuppressWarnings("unchecked")
        var attachments = (List<Map<String, Object>>) invite.get("attachments");
        assertThat("The invite should also be returned as an attachment", attachments, hasSize(1));

        var attachment = attachments.getFirst();
        assertThat("Attachment content type should be the calendar one", (String) attachment.get("contentType"), startsWithIgnoringCase("text/calendar"));
        assertThat("Attachment should have a storage uri", attachment.get("uri"), notNullValue());

        var uri = URI.create((String) attachment.get("uri"));
        var content = IOUtils.toByteArray(storageInterface.get(MAIN_TENANT, null, uri));
        assertThat("Stored attachment content should match the sent invite", content, is(ICS_CONTENT.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @EvaluateTrigger(flow = "flows/mail-received-trigger-imap.yaml", triggerId = "imap-mail-trigger")
    void singlePartInlineImageWithoutFilenameIsReturnedAsAttachment(Optional<Execution> optionalExecution) throws Exception {
        assertThat(optionalExecution.isPresent(), is(true));

        var triggerVars = optionalExecution.get().getTrigger().getVariables();

        @SuppressWarnings("unchecked")
        var emails = (List<Map<String, Object>>) triggerVars.get("emails");
        assertThat(emails, hasSize(1));

        var imageEmail = emails.getFirst();
        assertThat("An image-only email should have an empty body", imageEmail.get("body"), is(""));

        @SuppressWarnings("unchecked")
        var attachments = (List<Map<String, Object>>) imageEmail.get("attachments");
        assertThat("The image should be returned as the email's attachment", attachments, hasSize(1));

        var attachment = attachments.getFirst();
        assertThat("Attachment content type should be the image one", (String) attachment.get("contentType"), startsWithIgnoringCase("image/png"));
        assertThat("Attachment should have a storage uri", attachment.get("uri"), notNullValue());
        assertThat("Stored file should fall back to a generated name", ((String) attachment.get("uri")), containsString("attachment-0"));

        var uri = URI.create((String) attachment.get("uri"));
        var content = IOUtils.toByteArray(storageInterface.get(MAIN_TENANT, null, uri));
        assertThat("Stored attachment content should match the sent image", content, is(PNG_CONTENT));
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

    @Test
    @EvaluateTrigger(flow = "flows/mail-received-trigger-imap-max-attachment-size.yaml", triggerId = "imap-mail-trigger-max-attachment-size")
    void oversizedAttachmentKeepsMetadataWithNullUri(Optional<Execution> optionalExecution) {
        // @EvaluateTrigger only exercises the trigger evaluation, not a full flow run through the executor
        // (see RealTimeTriggerTest for that). A present execution here means the evaluation did not throw
        // despite the oversized attachment, which is what this test is guarding against.
        assertThat("Trigger evaluation should still produce an execution despite the oversized attachment", optionalExecution.isPresent(), is(true));

        var execution = optionalExecution.get();
        Map<String, Object> triggerVars = execution.getTrigger().getVariables();

        @SuppressWarnings("unchecked")
        var latestEmail = (Map<String, Object>) triggerVars.get("latestEmail");
        assertThat(
            "Latest email subject should match the sent oversized-attachment email",
            latestEmail.get("subject"),
            is("Email With Oversized Attachment")
        );

        @SuppressWarnings("unchecked")
        var attachments = (List<Map<String, Object>>) latestEmail.get("attachments");
        assertThat("Should have exactly one attachment", attachments, hasSize(1));

        var attachment = attachments.getFirst();
        assertThat("Attachment filename should still be reported", attachment.get("filename"), is(OVERSIZED_ATTACHMENT_FILENAME));
        assertThat("Attachment content type should still be reported", attachment.get("contentType"), notNullValue());
        assertThat("Attachment size should still be reported", attachment.get("size"), notNullValue());
        assertThat("Attachment uri should be null because it exceeds maxAttachmentSize", attachment.get("uri"), nullValue());
    }

    @Test
    @EvaluateTrigger(flow = "flows/mail-received-trigger-imap.yaml", triggerId = "imap-mail-trigger")
    void everyEmailOfABurstIsReturned(Optional<Execution> optionalExecution) {
        assertThat(optionalExecution.isPresent(), is(true));

        Map<String, Object> triggerVars = optionalExecution.get().getTrigger().getVariables();
        assertThat("Every email of the burst should be returned", triggerVars.get("total"), is(BURST_SIZE));

        @SuppressWarnings("unchecked")
        var emails = (List<Map<String, Object>>) triggerVars.get("emails");
        assertThat(
            "Emails should be returned oldest first",
            emails.stream().map(email -> email.get("subject")).toList(),
            is(IntStream.rangeClosed(1, BURST_SIZE).mapToObj(i -> "Burst Email " + i).toList())
        );
    }

    @Test
    @EvaluateTrigger(flow = "flows/mail-received-trigger-imap.yaml", triggerId = "imap-mail-trigger")
    void emailsWithUnknownCharsetAreStillReturned(Optional<Execution> optionalExecution) {
        assertThat(optionalExecution.isPresent(), is(true));

        Map<String, Object> triggerVars = optionalExecution.get().getTrigger().getVariables();
        assertThat(
            "The poll should not fail on an email whose charset is unknown to the JVM",
            triggerVars.get("total"),
            is(4)
        );

        @SuppressWarnings("unchecked")
        var emails = (List<Map<String, Object>>) triggerVars.get("emails");
        assertThat(
            "Every email should be returned, including the ones with an unknown charset",
            emails.stream().map(email -> email.get("subject")).toList(),
            contains("Good Email 1", "Bogus Charset", "Bogus Charset In Multipart", "Good Email 2")
        );

        var topLevel = emails.get(1);
        assertThat("A top-level unknown charset must not lose the body", topLevel.get("body"), is("Bogus charset body"));

        var withMultipart = emails.get(2);
        assertThat(
            "An unknown charset multipart part must not lose itself or the other parts",
            withMultipart.get("body"),
            is("Good partBogus part")
        );
    }

    @Test
    @EvaluateTrigger(flow = "flows/mail-received-trigger-imap.yaml", triggerId = "imap-mail-trigger")
    void textAttachmentWithUnknownCharsetDoesNotDropEmail(Optional<Execution> optionalExecution) {
        assertThat(optionalExecution.isPresent(), is(true));

        Map<String, Object> triggerVars = optionalExecution.get().getTrigger().getVariables();
        assertThat("The email with an unknown-charset text attachment should be returned", triggerVars.get("total"), is(1));

        @SuppressWarnings("unchecked")
        var emails = (List<Map<String, Object>>) triggerVars.get("emails");
        Map<String, Object> email = emails.getFirst();
        assertThat(email.get("subject"), is("Unknown Charset Text Attachment"));
        assertThat(email.get("body"), is("Email body"));

        @SuppressWarnings("unchecked")
        var attachments = (List<Map<String, Object>>) email.get("attachments");
        assertThat("The undecodable text attachment should still be listed", attachments, hasSize(1));
        assertThat(attachments.getFirst().get("filename"), is("unknown.txt"));
        assertThat(attachments.getFirst().get("uri"), notNullValue());
    }

    @Test
    @EvaluateTrigger(flow = "flows/mail-received-trigger-imap.yaml", triggerId = "imap-mail-trigger")
    void malformedEmailIsSkippedWithoutBlockingTheOthers(Optional<Execution> optionalExecution) {
        assertThat(optionalExecution.isPresent(), is(true));

        Map<String, Object> triggerVars = optionalExecution.get().getTrigger().getVariables();
        assertThat(
            "The poll should not fail on a malformed email",
            triggerVars.get("total"),
            is(2)
        );

        @SuppressWarnings("unchecked")
        var emails = (List<Map<String, Object>>) triggerVars.get("emails");
        assertThat(
            "Every well-formed email should be returned despite the malformed one being skipped",
            emails.stream().map(email -> email.get("subject")).toList(),
            contains("Good Email 3", "Good Email 4")
        );
    }
}
