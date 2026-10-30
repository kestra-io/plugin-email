package io.kestra.plugin.email;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.kestra.core.models.executions.Execution;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContextLogger;
import io.kestra.core.storages.Storage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

class MailServiceTest {
    @Test
    void moveAttachmentsKeepsNewUriWhenDeleteFails() {
        var source = URI.create("kestra:///ns/flow/executions/trigger-exec/attachments/file.txt");
        var expected = URI.create("kestra:///ns/flow/executions/new-exec/attachments/file.txt");

        // Plain JDK proxy: only the three storage calls used by the move are supported
        var puts = new ArrayList<URI>();
        var storage = (Storage) Proxy.newProxyInstance(
            Storage.class.getClassLoader(),
            new Class<?>[]{Storage.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getFile" -> new ByteArrayInputStream("content".getBytes());
                case "putFile" -> {
                    // Drain the stream like a real storage would
                    ((InputStream) args[0]).readAllBytes();
                    puts.add((URI) args[1]);
                    yield args[1];
                }
                case "deleteFile" -> throw new IOException("delete failed");
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
        var runContext = new DefaultRunContext.Builder()
            .withStorage(storage)
            .withLogger(new RunContextLogger())
            .withTriggerExecutionId("trigger-exec")
            .build();

        var execution = Execution.builder().id("new-exec").build();
        var emailData = MailService.EmailData.builder()
            .attachments(List.of(MailService.AttachmentInfo.builder().filename("file.txt").uri(source).build()))
            .build();

        var result = MailService.moveAttachmentsToExecution(emailData, runContext, execution);

        assertThat("Attachment should point to the copied file", result.getAttachments().getFirst().getUri(), is(expected));
        assertThat("Attachment should be copied once", puts, contains(expected));
    }
}
