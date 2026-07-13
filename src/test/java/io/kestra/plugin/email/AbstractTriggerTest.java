package io.kestra.plugin.email;

import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.Scheduler;

import jakarta.inject.Inject;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

@KestraTest(startRunner = true, startScheduler = true)
public abstract class AbstractTriggerTest extends AbstractEmailTest {

    @Inject
    protected Scheduler scheduler;

    // Bind greenmail in a class initializer so the IMAP/POP3 servers are reachable
    // before Micronaut's @KestraTest boots the runner/scheduler. With @RegisterExtension
    // greenmail would only start during JUnit beforeAll, which runs after the scheduler
    // already began evaluating triggers, leading to connection refused on slow CI hosts.
    protected static final GreenMail greenMail;
    static {
        greenMail = new GreenMail(new ServerSetup[] {
            new ServerSetup(3144, "127.0.0.1", ServerSetup.PROTOCOL_IMAP),
            new ServerSetup(3145, "127.0.0.1", ServerSetup.PROTOCOL_POP3)
        });
        greenMail.start();
        greenMail.setUser("test@localhost", "password");
        Runtime.getRuntime().addShutdownHook(new Thread(greenMail::stop));
    }

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
