package io.kestra.plugin.email;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@KestraTest
class MailSendTlsTest {
    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTPS);

    private static final String SMTPS_TRUST = "mail.smtps.ssl.trust";

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeEach
    void purge() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
    }

    private MailSend.MailSendBuilder<?, ?> task(TransportStrategy strategy) {
        return MailSend.builder()
            .host(Property.ofValue("localhost"))
            .port(Property.ofValue(greenMail.getSmtps().getPort()))
            .from(Property.ofValue("from@mail.com"))
            .to(Property.ofValue("to@mail.com"))
            .subject(Property.ofValue("TLS subject"))
            .plainTextContent(Property.ofValue("TLS body"))
            .transportStrategy(Property.ofValue(strategy));
    }

    private static String causeChain(Throwable throwable) {
        var sb = new StringBuilder();
        for (var t = throwable; t != null; t = t.getCause()) {
            sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void withoutTrustedHostsSelfSignedCertificateFails() {
        var mailSend = task(TransportStrategy.SMTPS).build();

        var thrown = catchThrowable(() -> mailSend.run(runContextFactory.of()));

        assertThat(causeChain(thrown)).contains("PKIX path building failed");
    }

    @Test
    void listedHostIsTrusted() throws Exception {
        var mailSend = task(TransportStrategy.SMTPS)
            .verifyServerIdentity(Property.ofValue(false))
            .trustedHosts(Property.ofValue(List.of("localhost")))
            .build();

        mailSend.run(runContextFactory.of());

        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    @Test
    void listedHostIsTrustedIgnoringCase() throws Exception {
        var mailSend = task(TransportStrategy.SMTPS)
            .verifyServerIdentity(Property.ofValue(false))
            .trustedHosts(Property.ofValue(List.of(" LOCALHOST ")))
            .build();

        mailSend.run(runContextFactory.of());

        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    @Test
    void unlistedHostFallsBackToTruststore() {
        var mailSend = task(TransportStrategy.SMTPS)
            .verifyServerIdentity(Property.ofValue(false))
            .trustedHosts(Property.ofValue(List.of("other.example")))
            .build();

        assertThatThrownBy(() -> mailSend.run(runContextFactory.of()))
            .satisfies(e -> assertThat(causeChain(e))
                .contains("PKIX path building failed")
                .doesNotContain("Server is not trusted"));
    }

    @Test
    void sslTrustAbsentWhenHostNotListed() throws Exception {
        var mailSend = task(TransportStrategy.SMTPS)
            .trustedHosts(Property.ofValue(List.of("other.example")))
            .build();

        try (var mailer = mailSend.buildMailer(runContextFactory.of())) {
            assertThat(mailer.getSession().getProperties()).doesNotContainKey(SMTPS_TRUST);
        }
    }

    @Test
    void sslTrustAbsentWhenTrustedHostsUnset() throws Exception {
        try (var mailer = task(TransportStrategy.SMTPS).build().buildMailer(runContextFactory.of())) {
            assertThat(mailer.getSession().getProperties()).doesNotContainKey(SMTPS_TRUST);
        }
    }

    @Test
    void sslTrustIsRenderedHostWhenOnlyCaseDiffers() throws Exception {
        var mailSend = task(TransportStrategy.SMTPS)
            .trustedHosts(Property.ofValue(List.of("LOCALHOST")))
            .build();

        try (var mailer = mailSend.buildMailer(runContextFactory.of())) {
            assertThat(mailer.getSession().getProperties()).containsEntry(SMTPS_TRUST, "localhost");
        }
    }

    @Test
    void sslTrustIsWildcardWhenListContainsStar() throws Exception {
        var mailSend = task(TransportStrategy.SMTPS)
            .trustedHosts(Property.ofValue(List.of("other.example", "*")))
            .build();

        try (var mailer = mailSend.buildMailer(runContextFactory.of())) {
            assertThat(mailer.getSession().getProperties()).containsEntry(SMTPS_TRUST, "*");
        }
    }

    @Test
    void startTlsUsesSmtpSslTrust() throws Exception {
        var listed = task(TransportStrategy.SMTP_TLS)
            .trustedHosts(Property.ofValue(List.of("LOCALHOST")))
            .build();
        var unlisted = task(TransportStrategy.SMTP_TLS)
            .trustedHosts(Property.ofValue(List.of("other.example")))
            .build();

        try (var mailer = listed.buildMailer(runContextFactory.of())) {
            assertThat(mailer.getSession().getProperties()).containsEntry("mail.smtp.ssl.trust", "localhost");
        }
        try (var mailer = unlisted.buildMailer(runContextFactory.of())) {
            assertThat(mailer.getSession().getProperties()).doesNotContainKey("mail.smtp.ssl.trust");
        }
    }
}
