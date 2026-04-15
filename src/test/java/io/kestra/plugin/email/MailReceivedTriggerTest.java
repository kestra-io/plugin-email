package io.kestra.plugin.email;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.EvaluateTrigger;
import io.kestra.core.models.executions.Execution;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class MailReceivedTriggerTest extends AbstractTriggerTest {

    @BeforeEach
    void sendEmailForTest() throws Exception {
        sendTestEmail("First Email", "sender1@example.com", "First test email body");
    }

    @Test
    @EvaluateTrigger(flow = "flows/mail-received-trigger-pop3.yaml", triggerId = "pop3-mail-trigger")
    void MailReceivedTriggerWithPop3(Optional<Execution> optionalExecution) {
        assertThat(optionalExecution.isPresent(), is(true));

        var execution = optionalExecution.get();
        Map<String, Object> triggerVars = execution.getTrigger().getVariables();

        assertThat("Should have received emails", (Integer) triggerVars.get("total"), greaterThan(0));

        @SuppressWarnings("unchecked")
        var latestEmail = (Map<String, Object>) triggerVars.get("latestEmail");
        assertThat("Latest email should have a subject", latestEmail.get("subject"), notNullValue());
        assertThat("Latest email should have a from address", latestEmail.get("from"), notNullValue());
        assertThat(
            "Latest email subject should be one of the sent emails",
            latestEmail.get("subject"),
            is("First Email")
        );
    }

    @Test
    @EvaluateTrigger(flow = "flows/mail-received-trigger-imap.yaml", triggerId = "imap-mail-trigger")
    void MailReceivedTriggerWithImap(Optional<Execution> optionalExecution) {
        assertThat(optionalExecution.isPresent(), is(true));

        var execution = optionalExecution.get();
        Map<String, Object> triggerVars = execution.getTrigger().getVariables();

        assertThat("Should have received emails", (Integer) triggerVars.get("total"), greaterThan(0));

        @SuppressWarnings("unchecked")
        var latestEmail = (Map<String, Object>) triggerVars.get("latestEmail");
        assertThat("Latest email should have a subject", latestEmail.get("subject"), notNullValue());
        assertThat("Latest email should have a from address", latestEmail.get("from"), notNullValue());
        assertThat(
            "Latest email subject should be one of the sent emails",
            latestEmail.get("subject"),
            is("First Email")
        );
    }
}
