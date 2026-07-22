package io.kestra.plugin.email;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.IdUtils;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.UtilityClass;

@UtilityClass
public class MailService {

    public enum Protocol {
        IMAP,
        POP3
    }

    @Builder
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Latest email received", description = "The most recent email that triggered this execution")
        private final EmailData latestEmail;

        @Schema(title = "Total number of new emails found", description = "Count of emails returned in this trigger cycle")
        private final Integer total;

        @Schema(title = "All new emails found", description = "List of every email fetched during this evaluation")
        private final List<EmailData> emails;
    }

    @Builder
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmailData implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Email subject", description = "Subject line of the received email")
        @PluginProperty(group = "advanced")
        private final String subject;

        @Schema(title = "Sender email address", description = "Address from the From header")
        @PluginProperty(group = "source")
        private final String from;

        @Schema(title = "Recipient email addresses", description = "Addresses from the To header")
        @PluginProperty(group = "destination")
        private final List<String> to;

        @Schema(title = "CC email addresses", description = "Addresses from the CC header")
        @PluginProperty(group = "advanced")
        private final List<String> cc;

        @Schema(title = "BCC email addresses", description = "Addresses from the BCC header when available")
        @PluginProperty(group = "advanced")
        private final List<String> bcc;

        @Schema(title = "Email date", description = "Message date as parsed from Received or Sent headers")
        @PluginProperty(group = "advanced")
        private final ZonedDateTime date;

        @Schema(title = "Email body content", description = "Text content extracted from the message parts")
        @PluginProperty(group = "advanced")
        private final String body;

        @Schema(title = "Message ID", description = "Unique message identifier from the email headers")
        @PluginProperty(group = "advanced")
        private final String messageId;

        @Schema(title = "Email attachments", description = "Metadata describing each attachment")
        @PluginProperty(group = "advanced")
        private final List<AttachmentInfo> attachments;
    }

    @Builder
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AttachmentInfo {
        @Schema(title = "Attachment filename", description = "Name as provided by the sender. Can be null when the sender did not provide one")
        @PluginProperty(group = "advanced")
        private final String filename;

        @Schema(title = "Content type", description = "Attachment MIME type reported by the sender")
        @PluginProperty(group = "advanced")
        private final String contentType;

        @Schema(title = "File size in bytes", description = "Attachment size when provided by the server")
        @PluginProperty(group = "advanced")
        private final Integer size;

        @Schema(title = "Attachment URI", description = "URI of the attachment stored in Kestra internal storage")
        @PluginProperty(group = "advanced")
        private final URI uri;
    }

    @Builder
    @Getter
    public static class MailConfiguration {
        public final String protocol;
        public final String host;
        public final Integer port;
        public final String username;
        public final String password;
        public final String folder;
        public final Boolean ssl;
        public final Boolean trustAllCertificates;
        public final Duration interval;
        public final Long maxAttachmentSize;
    }

    // CWE-532 / OWASP A09:2021: lines from the JavaMail protocol transcript that carry
    // authentication material and must never reach stdout or the application logger.
    private static final Pattern SENSITIVE_DEBUG_LINE = Pattern.compile(
        "^(AUTH|AUTHENTICATE)\\b.*|^(\\+OK|\\+|.*[Pp]assword).*|^[A-Za-z0-9+/=]{20,}$"
    );

    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");

    /**
     * Default cap applied to a single attachment before it is stored in internal storage: 25MB.
     */
    public static final long DEFAULT_MAX_ATTACHMENT_SIZE = 25L * 1024 * 1024;

    private static final int MAX_FILENAME_LENGTH = 200;
    private static final int COPY_BUFFER_SIZE = 8192;

    public static Properties setupMailProperties(String protocol, String host, Integer port, Boolean ssl,
        Boolean trustAllCertificates, RunContext runContext) {
        Properties props = new Properties();
        String protocolName = getProtocolName(protocol, ssl);

        props.put("mail.store.protocol", protocolName);
        props.put("mail." + protocolName + ".host", host);
        props.put("mail." + protocolName + ".port", port.toString());
        props.put("mail." + protocolName + ".auth", "true");

        if (ssl) {
            props.put("mail." + protocolName + ".ssl.enable", "true");
            props.put("mail." + protocolName + ".ssl.protocols", "TLSv1.2");
        }

        if (trustAllCertificates) {
            // CWE-295 / OWASP A02:2025: disabling certificate and hostname validation removes
            // all protection against a man-in-the-middle attack on this connection. This option
            // must only ever be used for local/testing setups against a known, trusted host.
            runContext.logger().warn(
                "trustAllCertificates is enabled for {}:{} — TLS certificate and hostname " +
                    "validation is disabled. This makes the connection vulnerable to " +
                    "man-in-the-middle attacks and must never be used in production.",
                host, port
            );
            props.put("mail." + protocolName + ".ssl.trust", "*");
            props.put("mail." + protocolName + ".ssl.checkserveridentity", "false");
        }

        // Never set mail.debug=true here: JavaMail writes the raw protocol transcript
        // (including AUTH PLAIN/LOGIN handshakes with base64-encoded, trivially decodable
        // credentials) directly to whatever debug stream is configured. Debug output is
        // instead enabled, when appropriate, via a redacting stream in applyDebugLogging().
        return props;
    }

    /**
     * Enables JavaMail debug output on the given session only when the run context logger is at
     * DEBUG level, and routes it through a redacting filter so credential material (AUTH/LOGIN
     * handshakes, base64 tokens) is never written to stdout or the application logger.
     */
    public static void applyDebugLogging(Session session, RunContext runContext) {
        if (runContext.logger().isDebugEnabled()) {
            session.setDebug(true);
            session.setDebugOut(new PrintStream(new RedactingOutputStream(runContext), true, StandardCharsets.UTF_8));
        }
    }

    /**
     * OutputStream that buffers JavaMail's debug output line-by-line and forwards each line to
     * the run context logger, redacting any line that looks like it may carry credentials before
     * it is ever logged.
     */
    private static final class RedactingOutputStream extends OutputStream {
        private final RunContext runContext;
        private final StringBuilder buffer = new StringBuilder();

        private RedactingOutputStream(RunContext runContext) {
            this.runContext = runContext;
        }

        @Override
        public void write(int b) {
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                buffer.append((char) b);
            }
        }

        private void flushLine() {
            String line = buffer.toString();
            buffer.setLength(0);
            if (line.isBlank()) {
                return;
            }
            if (SENSITIVE_DEBUG_LINE.matcher(line.trim()).matches()) {
                runContext.logger().debug("[JavaMail] <redacted: possible credential material>");
            } else {
                runContext.logger().debug("[JavaMail] {}", line);
            }
        }
    }

    public static String getProtocolName(String protocol, Boolean ssl) {
        if (protocol.equals("IMAP")) {
            return ssl ? "imaps" : "imap";
        }
        return ssl ? "pop3s" : "pop3";
    }

    public static void connectToStore(Store store, String host, Integer port, String username,
        String password, RunContext runContext) throws MessagingException {
        try {
            store.connect(host, port, username, password);
        } catch (MessagingException e) {
            store.connect(username, password);
        }
        runContext.logger().info("Connected to {}:{}", host, port);
    }

    public static Integer getDefaultPort(Protocol protocol, Boolean ssl) {
        return switch (protocol) {
            case IMAP -> ssl ? 993 : 143;
            case POP3 -> ssl ? 995 : 110;
        };
    }

    public static EmailData parseEmailData(MimeMessage message, RunContext runContext, long maxAttachmentSize)
        throws MessagingException, IOException {
        Date receivedDate = message.getReceivedDate() != null ? message.getReceivedDate() : message.getSentDate();
        ZonedDateTime date = receivedDate != null
            ? ZonedDateTime.ofInstant(receivedDate.toInstant(), ZonedDateTime.now().getZone())
            : ZonedDateTime.now();

        return EmailData.builder()
            .subject(message.getSubject())
            .from(getAddressString(message.getFrom()))
            .to(getAddressList(message.getRecipients(Message.RecipientType.TO)))
            .cc(getAddressList(message.getRecipients(Message.RecipientType.CC)))
            .bcc(getAddressList(message.getRecipients(Message.RecipientType.BCC)))
            .date(date)
            .body(extractTextContent(message))
            .messageId(message.getMessageID())
            .attachments(extractAttachments(message, runContext, maxAttachmentSize))
            .build();
    }

    private static String getAddressString(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return null;
        }
        return ((InternetAddress) addresses[0]).getAddress();
    }

    private static List<String> getAddressList(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return Collections.emptyList();
        }
        return Arrays.stream(addresses)
            .map(addr -> ((InternetAddress) addr).getAddress())
            .toList();
    }

    private static String extractTextContent(Message message) throws MessagingException, IOException {
        if (message.isMimeType("multipart/*")) {
            MimeMultipart multipart = (MimeMultipart) message.getContent();
            return extractTextFromMultipart(multipart);
        }
        return (String) message.getContent();
    }

    private static String extractTextFromMultipart(MimeMultipart multipart) throws MessagingException, IOException {
        StringBuilder result = new StringBuilder();
        int count = multipart.getCount();

        for (int i = 0; i < count; i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            if (bodyPart.isMimeType("multipart/*")) {
                result.append(extractTextFromMultipart((MimeMultipart) bodyPart.getContent()));
            } else {
                result.append(bodyPart.getContent().toString());
            }
        }

        return result.toString();
    }

    private static List<AttachmentInfo> extractAttachments(Message message, RunContext runContext, long maxAttachmentSize)
        throws MessagingException, IOException {
        List<AttachmentInfo> attachments = new ArrayList<>();

        if (message.isMimeType("multipart/*")) {
            MimeMultipart multipart = (MimeMultipart) message.getContent();
            extractAttachmentsFromMultipart(multipart, attachments, runContext, maxAttachmentSize);
        }

        return attachments;
    }

    private static void extractAttachmentsFromMultipart(MimeMultipart multipart, List<AttachmentInfo> attachments,
        RunContext runContext, long maxAttachmentSize) throws MessagingException, IOException {
        int count = multipart.getCount();

        for (int i = 0; i < count; i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);

            if (
                Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition()) ||
                    (bodyPart.getFileName() != null && !bodyPart.getFileName().isEmpty())
            ) {
                String filename = sanitizeFilename(bodyPart.getFileName(), attachments.size());

                AttachmentInfo.AttachmentInfoBuilder attachment = AttachmentInfo.builder()
                    .filename(bodyPart.getFileName())
                    .contentType(bodyPart.getContentType())
                    .size(bodyPart.getSize());

                try {
                    attachment.uri(storeAttachment(bodyPart, filename, runContext, maxAttachmentSize));
                } catch (IOException | MessagingException e) {
                    // A single unreadable/unstorable attachment must not fail the whole trigger evaluation.
                    runContext.logger().warn("Failed to store attachment '{}' in internal storage", filename, e);
                }

                attachments.add(attachment.build());
            } else if (bodyPart.isMimeType("multipart/*")) {
                extractAttachmentsFromMultipart((MimeMultipart) bodyPart.getContent(), attachments, runContext, maxAttachmentSize);
            }
        }
    }

    /**
     * Strips any path information from an attachment filename before it is used on the local
     * filesystem, and restricts it to a safe character set and length. Filenames come from
     * untrusted senders and could otherwise be crafted for a path traversal (CWE-22) or for
     * characters (#, ?, %) that get misinterpreted when the storage URI is built.
     */
    private static String sanitizeFilename(String filename, int index) {
        if (filename == null) {
            return "attachment-" + index;
        }

        String sanitized = filename.replace('\\', '/').replace("\0", "");
        int lastSlash = sanitized.lastIndexOf('/');
        if (lastSlash >= 0) {
            sanitized = sanitized.substring(lastSlash + 1);
        }
        sanitized = sanitized.trim();
        sanitized = UNSAFE_FILENAME_CHARS.matcher(sanitized).replaceAll("_");

        if (sanitized.isEmpty()) {
            return "attachment-" + index;
        }

        return sanitized.length() > MAX_FILENAME_LENGTH ? sanitized.substring(0, MAX_FILENAME_LENGTH) : sanitized;
    }

    private static URI storeAttachment(BodyPart bodyPart, String filename, RunContext runContext, long maxAttachmentSize)
        throws IOException, MessagingException {
        Path tempFile = runContext.workingDir().createTempFile();

        long copied;
        try {
            copied = copyBounded(bodyPart.getInputStream(), tempFile, maxAttachmentSize);
        } catch (IOException | MessagingException e) {
            // The temp file lives in the RunContext working directory, which for RealTimeTrigger spans the
            // whole IMAP IDLE connection: leaving it behind on failure would accumulate orphan files.
            Files.deleteIfExists(tempFile);
            throw e;
        }

        if (copied < 0) {
            Files.deleteIfExists(tempFile);
            runContext.logger().warn(
                "Attachment '{}' exceeds the maximum allowed size of {} bytes, keeping its metadata but not its content",
                filename, maxAttachmentSize
            );
            return null;
        }

        // The storage context is shared across the whole trigger evaluation, so two attachments with the same
        // filename (within one email, or across emails in one batch) would otherwise collide on the same URI.
        String storedName = IdUtils.create() + "-" + filename;
        return runContext.storage().putFile(tempFile.toFile(), storedName);
    }

    // bodyPart.getSize() is not trusted here: JavaMail can report -1 or an estimate that does not
    // match the actual stream length.
    private static long copyBounded(InputStream inputStream, Path target, long maxBytes) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0;

        try (inputStream; var outputStream = Files.newOutputStream(target)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    return -1;
                }
                outputStream.write(buffer, 0, read);
            }
        }

        return total;
    }

    public static List<EmailData> fetchNewEmails(RunContext runContext, String protocol, String host, Integer port,
        String username, String password, String folder, Boolean ssl, Boolean trustAllCertificates,
        Long maxAttachmentSize, ZonedDateTime lastCheckTime) throws MessagingException, IOException {

        Properties props = setupMailProperties(protocol, host, port, ssl, trustAllCertificates, runContext);
        String protocolName = getProtocolName(protocol, ssl);
        Session session = Session.getInstance(props, null);
        applyDebugLogging(session, runContext);
        Store store = session.getStore(protocolName);

        try {
            connectToStore(store, host, port, username, password, runContext);
            return processMessages(store, folder, lastCheckTime, runContext, maxAttachmentSize);
        } finally {
            if (store.isConnected()) {
                try {
                    store.close();
                } catch (MessagingException e) {
                    runContext.logger().warn("Failed to close mail store", e);
                }
            }
        }
    }

    private static List<EmailData> processMessages(Store store, String folder, ZonedDateTime lastCheckTime,
        RunContext runContext, long maxAttachmentSize) throws MessagingException, IOException {
        List<EmailData> newEmails = new ArrayList<>();
        Folder mailFolder = store.getFolder(folder);
        try {
            mailFolder.open(Folder.READ_ONLY);

            int messageCount = mailFolder.getMessageCount();
            if (messageCount == 0) {
                runContext.logger().info("No messages found in folder: {}", folder);
                return Collections.emptyList();
            }

            runContext.logger().info("Checking for emails newer than: {}", lastCheckTime);

            int messagesToCheck = Math.min(messageCount, 10);
            Message[] messages = mailFolder.getMessages(messageCount - messagesToCheck + 1, messageCount);

            runContext.logger().info("Checking {} messages out of {} total", messagesToCheck, messageCount);

            for (Message message : messages) {
                if (message instanceof MimeMessage mimeMessage) {
                    Date receivedDate = message.getReceivedDate() != null ? message.getReceivedDate()
                        : message.getSentDate();

                    if (receivedDate != null) {
                        ZonedDateTime messageDate = ZonedDateTime.ofInstant(
                            receivedDate.toInstant(),
                            lastCheckTime.getZone()
                        );

                        runContext.logger().debug(
                            "Message date: {}, Last check: {}, Is newer: {}",
                            messageDate, lastCheckTime, messageDate.isAfter(lastCheckTime)
                        );

                        if (messageDate.isAfter(lastCheckTime)) {
                            EmailData emailData = parseEmailData(mimeMessage, runContext, maxAttachmentSize);
                            if (emailData != null) {
                                newEmails.add(emailData);
                                runContext.logger().info(
                                    "New email - Subject: '{}', From: '{}', Body: '{}'",
                                    emailData.getSubject(), emailData.getFrom(),
                                    emailData.getBody().length() > 100 ? emailData.getBody().substring(0, 100) + "..."
                                        : emailData.getBody()
                                );
                            }
                        }
                    } else {
                        runContext.logger().debug("Message has no received date or sent date.");
                    }
                }
            }
        } finally {
            if (mailFolder.isOpen()) {
                try {
                    mailFolder.close(false);
                } catch (MessagingException e) {
                    runContext.logger().warn("Failed to close mail folder", e);
                }
            }
        }

        runContext.logger().info("Found {} new emails", newEmails.size());
        return newEmails;
    }
}
