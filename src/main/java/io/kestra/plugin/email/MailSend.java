package io.kestra.plugin.email;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;
import org.slf4j.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.mail.util.ByteArrayDataSource;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import static io.kestra.core.utils.Rethrow.throwFunction;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Send email from a Flow task",
    description = "Builds and sends an email over SMTP/SMTPS/SMTP_TLS/SMTP_OAUTH2 with optional HTML, attachments, and embedded images. Defaults to SMTPS transport with a 10-second session timeout and server identity verification; provide credentials or an OAuth2 access token when required."
)
@Plugin(
    examples = {
        @Example(
            title = "Send an email on a failed flow execution.",
            full = true,
            code = """
                id: unreliable_flow
                namespace: company.team

                tasks:
                  - id: fail
                    type: io.kestra.plugin.scripts.shell.Commands
                    runner: PROCESS
                    commands:
                      - exit 1

                errors:
                  - id: send_email
                    type: io.kestra.plugin.email.MailSend
                    from: hello@kestra.io
                    to: hello@kestra.io
                    username: "{{ secret('EMAIL_USERNAME') }}"
                    password: "{{ secret('EMAIL_PASSWORD') }}"
                    host: mail.privateemail.com
                    port: 465 # or 587
                    subject: "Kestra workflow failed for the flow {{flow.id}} in the namespace {{flow.namespace}}"
                    htmlTextContent: "Failure alert for flow {{ flow.namespace }}.{{ flow.id }} with ID {{ execution.id }}"
                """
        ),
        @Example(
            title = "Send an email with attachments.",
            full = true,
            code = """
                id: send_email
                namespace: company.team

                inputs:
                  - id: attachments
                    type: ARRAY
                    itemType: JSON

                tasks:
                  - id: send_email
                    type: io.kestra.plugin.email.MailSend
                    from: hello@kestra.io
                    to: hello@kestra.io
                    attachments: "{{ inputs.attachments | toJson }}"
                """
        ),
        @Example(
            title = "Send an email with an embedded image.",
            full = true,
            code = """
                id: send_email
                namespace: company.team

                inputs:
                  - id: embedded_image_uri
                    type: STRING

                tasks:
                  - id: send_email
                    type: io.kestra.plugin.email.MailSend
                    from: hello@kestra.io
                    to: hello@kestra.io
                    embeddedImages:
                      - name: kestra.png
                        uri: "{{ inputs.embedded_image_uri }}"
                        contentType: image/png
                """
        ),
        @Example(
            title = "Export Kestra audit logs to a CSV file and send it by email.",
            full = true,
            code = """
                id: export_audit_logs_csv
                namespace: company.team

                tasks:
                  - id: ship_audit_logs
                    type: "io.kestra.plugin.ee.core.log.AuditLogShipper"
                    lookbackPeriod: P1D
                    logExporters:
                      - id: file
                        type: io.kestra.plugin.ee.core.log.FileLogExporter

                  - id: convert_to_csv
                    type: "io.kestra.plugin.serdes.csv.IonToCsv"
                    from: "{{ outputs.ship_audit_logs.outputs.file.uris | first }}"

                  - id: send_email
                    type: io.kestra.plugin.email.MailSend
                    from: hello@kestra.io
                    to: hello@kestra.io
                    username: "{{ secret('EMAIL_USERNAME') }}"
                    password: "{{ secret('EMAIL_PASSWORD') }}"
                    host: mail.privateemail.com
                    port: 465 # or 587
                    subject: "Weekly Kestra Audit Logs CSV Export"
                    htmlTextContent: "Weekly Kestra Audit Logs CSV Export"
                    attachments:
                      - name: audit_logs.csv
                        uri: "{{ outputs.convert_to_csv.uri }}"
                        contentType: text/csv

                triggers:
                  - id: schedule
                    type: io.kestra.plugin.core.trigger.Schedule
                    cron: 0 10 * * 5
                """
        ),
        @Example(
            title = "Send an email using an internal mail server with self-signed certificate and specific trusted hosts.",
            full = true,
            code = """
                id: send_email_internal
                namespace: company.team

                tasks:
                  - id: send_email
                    type: io.kestra.plugin.email.MailSend
                    from: noreply@company.local
                    to: admin@company.local
                    username: "{{ secret('INTERNAL_SMTP_USER') }}"
                    password: "{{ secret('INTERNAL_SMTP_PASSWORD') }}"
                    host: mail.company.local
                    port: 587
                    transportStrategy: SMTP_TLS
                    subject: "Internal notification"
                    htmlTextContent: "This email was sent from an internal mail server"
                    verifyServerIdentity: false
                    trustedHosts:
                      - mail.company.local
                      - smtp.company.local
                      - 192.168.1.100
                """
        )
    },
    aliases = "io.kestra.plugin.notifications.mail.MailSend"
)
public class MailSend extends Task implements RunnableTask<VoidOutput> {
    /* Server info */
    @Schema(
        title = "SMTP server host",
        description = "Hostname or IP of the SMTP relay used to send emails"
    )
    protected Property<String> host;

    @Schema(
        title = "SMTP server port",
        description = "Override the SMTP port. Defaults come from the transport strategy (SMTPS often 465, TLS 587)"
    )
    private Property<Integer> port;

    @Schema(
        title = "SMTP username",
        description = "Username for authenticating to the SMTP server if required"
    )
    protected Property<String> username;

    @Schema(
        title = "SMTP password",
        description = "Password or secret used for SMTP authentication"
    )
    protected Property<String> password;

    @Schema(
        title = "SMTP transport strategy",
        description = "Protocol used to send the email. Defaults to SMTPS; can be SMTP_TLS, SMTP, or SMTP_OAUTH2"
    )
    @Builder.Default
    private final Property<TransportStrategy> transportStrategy = Property.ofValue(TransportStrategy.SMTPS);

    @Schema(
        title = "Session timeout (ms)",
        description = "Maximum socket timeout while sending emails. Defaults to 10000 ms (10 seconds)"
    )
    @Builder.Default
    private final Property<Integer> sessionTimeout = Property.ofValue(10000);

    @Schema(
        title = "Verify server identity",
        description = "Performs TLS server identity checks. Defaults to true; disable only for self-signed or internal servers"
    )
    @Builder.Default
    private final Property<Boolean> verifyServerIdentity = Property.ofValue(true);

    @Schema(
        title = "Trusted SSL/TLS hosts",
        description = "Restrict TLS trust to the specified hosts when working with internal or self-signed servers"
    )
    private Property<List<String>> trustedHosts;

    /* Mail info */
    @Schema(
        title = "Sender address",
        description = "RFC2822 From address presented to recipients"
    )
    protected Property<String> from;

    @Schema(
        title = "Recipients (To)",
        description = "Semicolon-delimited list of RFC2822 addresses for primary recipients"
    )
    protected Property<String> to;

    @Schema(
        title = "CC recipients",
        description = "Optional semicolon-delimited RFC2822 addresses for carbon copy recipients"
    )
    protected Property<String> cc;

    @Schema(
        title = "Email subject",
        description = "Optional subject line; template expressions are allowed"
    )
    protected Property<String> subject;

    @Schema(
        title = "HTML body",
        description = "HTML version of the message body. Can be paired with plainTextContent; most clients prefer HTML over plain text"
    )
    protected Property<String> htmlTextContent;

    @Schema(
        title = "Plain text body",
        description = "Plain-text alternative used when HTML is not supported"
    )
    protected Property<String> plainTextContent;

    @Schema(
        title = "Attachments",
        description = "Attachments to include, provided as a list or JSON string. Shown as separate files; some clients preview common types inline",
        anyOf = { List.class, String.class } // Can be a List<Attachment> or a String like "{{ inputs.attachments | toJson }})"
    )
    private Property<Object> attachments;

    @Schema(
        title = "Embedded images",
        description = "Images referenced from the HTML body via content IDs; accepts a list or JSON and expects common image MIME types",
        anyOf = { List.class, String.class } // Can be a List<Attachment> or a String like "{{ inputs.attachments | toJson }})"
    )
    private Property<Object> embeddedImages;

    @Schema(
        title = "OAuth2 access token",
        description = "Used when transportStrategy is SMTP_OAUTH2. Overrides password when provided; otherwise password is treated as the token"
    )
    protected Property<String> accessToken;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

        Logger logger = runContext.logger();

        logger.debug("Sending an email to {}", to);

        final String htmlContent = runContext.render(this.htmlTextContent).as(String.class).orElse(null);
        final String textContent = runContext.render(this.plainTextContent).as(String.class)
            .orElse("Please view this email in a modern email client");

        EmailPopulatingBuilder builder = EmailBuilder.startingBlank()
            .to(runContext.render(to).as(String.class).orElseThrow())
            .from(runContext.render(from).as(String.class).orElseThrow())
            .withSubject(runContext.render(subject).as(String.class).orElse(null))
            .withHTMLText(htmlContent)
            .withPlainText(textContent)
            .withReturnReceiptTo();

        var renderedAttachments = runContext.render(attachments).as(Object.class).orElse("");
        var attachmentsList = getAttachments(renderedAttachments);

        if (!attachmentsList.isEmpty()) {
            builder.withAttachments(this.attachmentResources(attachmentsList, runContext));
        }

        var renderedEmbeddedImages = runContext.render(embeddedImages).as(Object.class).orElse("");
        var embeddedImagesList = getAttachments(renderedEmbeddedImages);

        if (!embeddedImagesList.isEmpty()) {
            builder.withEmbeddedImages(this.attachmentResources(embeddedImagesList, runContext));
        }

        runContext.render(cc).as(String.class).ifPresent(builder::cc);

        Email email = builder.buildEmail();

        var rTrustedHosts = runContext.render(trustedHosts).asList(String.class);

        TransportStrategy rStrategy = runContext.render(transportStrategy).as(TransportStrategy.class).orElse(TransportStrategy.SMTPS);
        String rPassword = runContext.render(this.password).as(String.class).orElse(null);
        String rAccessToken = runContext.render(this.accessToken).as(String.class).orElse(null);

        String credential;

        if (rStrategy == TransportStrategy.SMTP_OAUTH2) {
            credential = rAccessToken != null ? rAccessToken : rPassword;

            if (credential == null) {
                throw new IllegalArgumentException(
                    "When using SMTP_OAUTH2, either 'accessToken' or 'password' must be provided"
                );
            }
        } else {
            credential = rPassword;
        }

        var mailerBuilder = MailerBuilder
            .withSMTPServer(
                runContext.render(this.host).as(String.class).orElse(null),
                runContext.render(this.port).as(Integer.class).orElse(null),
                runContext.render(this.username).as(String.class).orElse(null),
                credential
            )
            .withTransportStrategy(rStrategy)
            .withSessionTimeout(runContext.render(sessionTimeout).as(Integer.class).orElse(10000))
            .verifyingServerIdentity(runContext.render(verifyServerIdentity).as(Boolean.class).orElse(true));

        if (!rTrustedHosts.isEmpty()) {
            mailerBuilder = mailerBuilder
                .trustingAllHosts(false)
                .trustingSSLHosts(rTrustedHosts.toArray(new String[0]));
        }

        try (Mailer mailer = mailerBuilder.buildMailer()) {
            mailer.sendMail(email);
        }

        return null;
    }

    private List<AttachmentResource> attachmentResources(List<Attachment> attachments, RunContext runContext) throws Exception {
        return attachments
            .stream()
            .map(throwFunction(attachment ->
            {
                InputStream inputStream = runContext.storage()
                    .getFile(URI.create(runContext.render(attachment.getUri()).as(String.class).orElseThrow()));

                return new AttachmentResource(
                    runContext.render(attachment.getName()).as(String.class).orElseThrow(),
                    new ByteArrayDataSource(inputStream, runContext.render(attachment.getContentType()).as(String.class).orElseThrow())
                );
            }))
            .toList();
    }

    private List<Attachment> getAttachments(Object attachments) throws JsonProcessingException {
        switch (attachments) {
            case null -> {
                return List.of();
            }

            case List<?> list -> {
                if (list.isEmpty())
                    return List.of();

                if (list.getFirst() instanceof Attachment) {
                    @SuppressWarnings("unchecked")
                    List<Attachment> typed = (List<Attachment>) list;
                    return typed;
                } else {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> items = (List<Map<String, Object>>) list;
                    return toAttachments(items);
                }
            }

            case String content -> {
                String trimmed = content.trim();
                if (trimmed.isEmpty())
                    return List.of();

                if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                    return parseJsonAttachmentString(trimmed);
                }

                String innerJson = JacksonMapper.ofJson().readValue(trimmed, String.class);
                return parseJsonAttachmentString(innerJson);
            }
            default -> {
            }
        }

        throw new IllegalArgumentException("The `attachments` attribute must be a String or a List");
    }

    private List<Attachment> parseJsonAttachmentString(String json) throws JsonProcessingException {
        String t = json.trim();
        if (t.startsWith("[")) {
            List<Map<String, Object>> items = JacksonMapper.ofJson().readValue(t, new TypeReference<>() {
            });
            return toAttachments(items);
        } else if (t.startsWith("{")) {
            Map<String, Object> item = JacksonMapper.ofJson().readValue(t, new TypeReference<>() {
            });
            return toAttachments(List.of(item));
        } else {
            return List.of();
        }
    }

    private static List<Attachment> toAttachments(List<Map<String, Object>> items) {
        return items.stream()
            .map(
                item -> Attachment.builder()
                    .name(Property.ofValue((String) item.get("name")))
                    .uri(Property.ofValue((String) item.get("uri")))
                    .contentType(Property.ofValue((String) item.getOrDefault("contentType", "application/octet-stream")))
                    .build()
            )
            .toList();
    }

    @Getter
    @Builder
    public static class Attachment {
        @Schema(
            title = "Attachment URI in internal storage",
            description = "URI (for example, `kestra://...`) pointing to the attachment content in Kestra internal storage"
        )
        @NotNull
        private Property<String> uri;

        @Schema(
            title = "Attachment filename",
            description = "Name presented to recipients, for example `report.csv`"
        )
        @NotNull
        private Property<String> name;

        @Schema(
            title = "Attachment content type",
            description = "MIME type such as `text/plain`, `image/png`, `application/pdf`, or `text/csv`. Defaults to `application/octet-stream`"
        )
        @NotNull
        @Builder.Default
        private Property<String> contentType = Property.ofValue("application/octet-stream");
    }
}
