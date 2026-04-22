package io.kestra.plugin.email;

import java.time.Duration;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import io.kestra.core.models.annotations.PluginProperty;

@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class AbstractMailTrigger extends AbstractTrigger {

    public Duration getInterval() {
        return Duration.ofSeconds(60);
    }

    @Schema(title = "Choose mail protocol", description = "Protocol used to connect to the mailbox (IMAP or POP3). Defaults to IMAP")
    @Builder.Default
    protected final Property<MailService.Protocol> protocol = Property.ofValue(MailService.Protocol.IMAP);

    @Schema(title = "Mail server host", description = "Hostname or IP address for the mailbox connection")
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> host;

    @Schema(title = "Mail server port", description = "Override the connection port. Defaults: IMAP 993/143, POP3 995/110 depending on SSL")
    @PluginProperty(group = "connection")
    protected Property<Integer> port;

    @Schema(title = "Username", description = "Username used to authenticate against the mailbox")
    @NotNull
    @PluginProperty(group = "main", secret = true)
    protected Property<String> username;

    @Schema(title = "Password", description = "Password or secret for mailbox authentication")
    @NotNull
    @PluginProperty(group = "main", secret = true)
    protected Property<String> password;

    @Schema(title = "Mail folder", description = "Folder to monitor (IMAP only). Defaults to INBOX")
    @Builder.Default
    @PluginProperty(group = "advanced")
    protected final Property<String> folder = Property.ofValue("INBOX");

    @Schema(title = "Use SSL/TLS", description = "Enable TLS for the mailbox connection. Defaults to true")
    @Builder.Default
    @PluginProperty(group = "connection")
    protected final Property<Boolean> ssl = Property.ofValue(true);

    @Schema(title = "Trust all certificates", description = "Skip TLS certificate validation (testing only). Defaults to false")
    @Builder.Default
    @PluginProperty(group = "advanced")
    protected final Property<Boolean> trustAllCertificates = Property.ofValue(false);

    @Schema(title = "Check interval", description = "Frequency for polling-based checks as an ISO-8601 duration. Default PT60S")
    @Builder.Default
    @PluginProperty(group = "execution")
    protected final Property<Duration> interval = Property.ofValue(Duration.ofSeconds(60));

    protected MailService.MailConfiguration renderMailConfiguration(RunContext runContext) throws Exception {
        String rProtocol = String.valueOf(runContext.render(this.protocol).as(MailService.Protocol.class).orElseThrow());
        String rHost = runContext.render(this.host).as(String.class).orElseThrow();
        String rUsername = runContext.render(this.username).as(String.class).orElseThrow();
        String rPassword = runContext.render(this.password).as(String.class).orElseThrow();
        String rFolder = runContext.render(this.folder).as(String.class).orElse("INBOX");
        Boolean rSsl = runContext.render(this.ssl).as(Boolean.class).orElse(true);
        Boolean rTrustAllCertificates = runContext.render(this.trustAllCertificates).as(Boolean.class).orElse(false);
        Duration rInterval = runContext.render(this.interval).as(Duration.class).orElse(getInterval());

        Integer rPort = runContext.render(this.port).as(Integer.class)
            .orElse(MailService.getDefaultPort(MailService.Protocol.valueOf(rProtocol), rSsl));

        return new MailService.MailConfiguration(rProtocol, rHost, rPort, rUsername, rPassword, rFolder, rSsl, rTrustAllCertificates, rInterval);
    }
}
