package io.kestra.plugin.email;

import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;

import io.kestra.core.junit.annotations.KestraTest;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

@KestraTest
public abstract class AbstractTriggerTest extends AbstractEmailTest {

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
