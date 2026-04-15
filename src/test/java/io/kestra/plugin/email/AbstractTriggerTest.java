package io.kestra.plugin.email;

import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;

import io.kestra.core.junit.annotations.KestraTest;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

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
}
