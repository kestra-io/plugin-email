package io.kestra.plugin.email;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.kestra.core.models.executions.Execution;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContextLogger;
import io.kestra.core.storages.Storage;

import lombok.experimental.Delegate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

class MailServiceTest {
    private static final URI SOURCE = URI.create("kestra:///ns/flow/executions/trigger-exec/attachments/file.txt");
    private static final URI EXPECTED = URI.create("kestra:///ns/flow/executions/new-exec/attachments/file.txt");

    @Test
    void moveAttachmentsDeletesSourceAfterCopy() {
        var storage = new RecordingStorage(false, false);

        var result = move(storage);

        assertThat("Attachment should point to the copied file", result.getAttachments().getFirst().getUri(), is(EXPECTED));
        assertThat("Attachment should be copied once", storage.puts, contains(EXPECTED));
        assertThat("Source should be deleted after the copy", storage.deletes, contains(SOURCE));
    }

    @Test
    void moveAttachmentsKeepsNewUriWhenDeleteFails() {
        var storage = new RecordingStorage(false, true);

        var result = move(storage);

        assertThat("Attachment should point to the copied file", result.getAttachments().getFirst().getUri(), is(EXPECTED));
        assertThat("Attachment should be copied once", storage.puts, contains(EXPECTED));
        assertThat("Delete should have been attempted", storage.deletes, contains(SOURCE));
    }

    @Test
    void moveAttachmentsKeepsSourceUriWhenCopyFails() {
        var storage = new RecordingStorage(true, false);

        var result = move(storage);

        assertThat("Attachment should keep the original URI", result.getAttachments().getFirst().getUri(), is(SOURCE));
        assertThat("Nothing should be copied", storage.puts, is(empty()));
        assertThat("Source should not be deleted", storage.deletes, is(empty()));
    }

    private static MailService.EmailData move(Storage storage) {
        var runContext = new DefaultRunContext.Builder()
            .withStorage(storage)
            .withLogger(new RunContextLogger())
            .withTriggerExecutionId("trigger-exec")
            .build();

        var execution = Execution.builder().id("new-exec").build();
        var emailData = MailService.EmailData.builder()
            .attachments(List.of(MailService.AttachmentInfo.builder().filename("file.txt").uri(SOURCE).build()))
            .build();

        return MailService.moveAttachmentsToExecution(emailData, runContext, execution);
    }

    // Only the calls used by the move are implemented; any other call hits the null delegate.
    private static class RecordingStorage implements Storage {
        private final List<URI> puts = new ArrayList<>();
        private final List<URI> deletes = new ArrayList<>();
        private final boolean failGet;
        private final boolean failDelete;

        @Delegate(excludes = Overridden.class)
        private final Storage unsupported = null;

        RecordingStorage(boolean failGet, boolean failDelete) {
            this.failGet = failGet;
            this.failDelete = failDelete;
        }

        @Override
        public InputStream getFile(URI uri) throws IOException {
            if (failGet) {
                throw new IOException("get failed");
            }
            return new ByteArrayInputStream("content".getBytes());
        }

        @Override
        public URI putFile(InputStream inputStream, URI uri) throws IOException {
            // Drain the stream like a real storage would
            inputStream.readAllBytes();
            puts.add(uri);
            return uri;
        }

        @Override
        public boolean deleteFile(URI uri) throws IOException {
            deletes.add(uri);
            if (failDelete) {
                throw new IOException("delete failed");
            }
            return true;
        }

        private interface Overridden {
            InputStream getFile(URI uri) throws IOException;

            URI putFile(InputStream inputStream, URI uri) throws IOException;

            boolean deleteFile(URI uri) throws IOException;
        }
    }
}
