package io.kestra.plugin.email;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;

import io.kestra.core.junit.annotations.KestraTest;

import jakarta.activation.DataHandler;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

@KestraTest
public abstract class AbstractTriggerTest extends AbstractEmailTest {

    protected static final String ATTACHMENT_FILENAME = "report.txt";
    protected static final String ATTACHMENT_CONTENT = "attachment content";
    protected static final String PDF_FILENAME = "scan.pdf";
    protected static final String ICS_CONTENT = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nSUMMARY:Caf\u00e9 meeting\r\nEND:VCALENDAR";
    protected static final byte[] PNG_CONTENT = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, (byte) 0xFF };
    protected static final byte[] PDF_CONTENT = "%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);

    protected static final String UNKNOWN_CHARSET_EMAIL = "From: bogus-charset@example.com\r\n"
        + "To: test@localhost\r\n"
        + "Subject: Bogus Charset\r\n"
        + "MIME-Version: 1.0\r\n"
        + "Content-Type: text/plain; charset=x-bogus\r\n"
        + "\r\n"
        + "Bogus charset body\r\n";

    protected static final String UNKNOWN_CHARSET_MULTIPART_EMAIL = "From: bogus-charset@example.com\r\n"
        + "To: test@localhost\r\n"
        + "Subject: Bogus Charset In Multipart\r\n"
        + "MIME-Version: 1.0\r\n"
        + "Content-Type: multipart/mixed; boundary=\"example-boundary\"\r\n"
        + "\r\n"
        + "--example-boundary\r\n"
        + "Content-Type: text/plain; charset=UTF-8\r\n"
        + "\r\n"
        + "Good part\r\n"
        + "--example-boundary\r\n"
        + "Content-Type: text/plain; charset=x-bogus\r\n"
        + "\r\n"
        + "Bogus part\r\n"
        + "--example-boundary--\r\n";

    protected static final String UNKNOWN_CHARSET_TEXT_ATTACHMENT_EMAIL = "From: bogus-charset@example.com\r\n"
        + "To: test@localhost\r\n"
        + "Subject: Unknown Charset Text Attachment\r\n"
        + "MIME-Version: 1.0\r\n"
        + "Content-Type: multipart/mixed; boundary=\"attachment-boundary\"\r\n"
        + "\r\n"
        + "--attachment-boundary\r\n"
        + "Content-Type: text/plain; charset=UTF-8\r\n"
        + "\r\n"
        + "Email body\r\n"
        + "--attachment-boundary\r\n"
        + "Content-Type: text/plain; charset=x-bogus; name=\"unknown.txt\"\r\n"
        + "Content-Disposition: attachment; filename=\"unknown.txt\"\r\n"
        + "\r\n"
        + "Attachment body\r\n"
        + "--attachment-boundary--\r\n";

    protected static final String MISSING_BOUNDARY_EMAIL = "From: broken-mime@example.com\r\n"
        + "To: test@localhost\r\n"
        + "Subject: Missing Boundary\r\n"
        + "MIME-Version: 1.0\r\n"
        + "Content-Type: multipart/mixed\r\n"
        + "\r\n"
        + "An email whose multipart declares no boundary\r\n";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(
        new ServerSetup[] {
            new ServerSetup(3144, "127.0.0.1", ServerSetup.PROTOCOL_IMAP),
            new ServerSetup(3145, "127.0.0.1", ServerSetup.PROTOCOL_POP3)
        }
    ).withConfiguration(GreenMailConfiguration.aConfig().withUser("test@localhost", "password"))
        .withPerMethodLifecycle(false);

    @BeforeEach
    void cleanupBeforeEach() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
    }

    protected void sendTestEmail(String subject, String from, String body) throws MessagingException {
        var props = new Properties();
        var session = Session.getDefaultInstance(props);

        var message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.addRecipient(Message.RecipientType.TO, new InternetAddress("test@localhost"));
        message.setSubject(subject);
        message.setText(body);
        greenMail.getUserManager().getUser("test@localhost").deliver(message);
    }

    // Keep malformed MIME payloads intact for parser tests.
    protected void sendRawTestEmail(String rawContent) throws MessagingException {
        var props = new Properties();
        var session = Session.getDefaultInstance(props);

        var message = new MimeMessage(session, new ByteArrayInputStream(rawContent.getBytes(StandardCharsets.UTF_8)));
        greenMail.getUserManager().getUser("test@localhost").deliver(message);
    }

    protected void sendSinglePartTestEmail(String subject, String from, String filename, byte[] content, String contentType)
        throws MessagingException {
        var props = new Properties();
        var session = Session.getDefaultInstance(props);

        var message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.addRecipient(Message.RecipientType.TO, new InternetAddress("test@localhost"));
        message.setSubject(subject);
        message.setDataHandler(new DataHandler(new ByteArrayDataSource(content, contentType)));
        if (filename != null) {
            message.setFileName(filename);
        }
        greenMail.getUserManager().getUser("test@localhost").deliver(message);
    }

    protected void sendTestEmailWithAttachment(String subject, String from, String body, String attachmentFilename,
        byte[] attachmentContent) throws Exception {
        sendTestEmailWithAttachments(subject, from, body, List.of(new Attachment(attachmentFilename, attachmentContent)));
    }

    protected void sendTestEmailWithAttachments(String subject, String from, String body, List<Attachment> attachments)
        throws Exception {
        var props = new Properties();
        var session = Session.getDefaultInstance(props);

        var message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.addRecipient(Message.RecipientType.TO, new InternetAddress("test@localhost"));
        message.setSubject(subject);

        var textPart = new MimeBodyPart();
        textPart.setText(body);

        var multipart = new MimeMultipart();
        multipart.addBodyPart(textPart);

        for (var attachment : attachments) {
            var attachmentPart = new MimeBodyPart();
            attachmentPart.setDataHandler(
                new jakarta.activation.DataHandler(
                    new ByteArrayDataSource(attachment.content(), "application/octet-stream")
                )
            );
            attachmentPart.setFileName(attachment.filename());
            attachmentPart.setDisposition(Part.ATTACHMENT);
            multipart.addBodyPart(attachmentPart);
        }

        message.setContent(multipart);

        greenMail.getUserManager().getUser("test@localhost").deliver(message);
    }

    protected record Attachment(String filename, byte[] content) {
    }
}
