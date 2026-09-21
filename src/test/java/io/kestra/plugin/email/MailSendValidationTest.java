package io.kestra.plugin.email;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.validations.ModelValidator;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@KestraTest
class MailSendValidationTest {
    @Inject
    private ModelValidator modelValidator;

    @Test
    void shouldRejectMissingFrom() {
        MailSend task = MailSend.builder()
            .id("send_email")
            .type("io.kestra.plugin.email.MailSend")
            .to(Property.ofValue("to@mail.com"))
            .build();

        var violation = modelValidator.isValid(task);
        assertThat(violation).isPresent();
        assertThat(violation.get().getConstraintViolations())
            .extracting(v -> v.getPropertyPath().toString())
            .contains("from");
    }

    @Test
    void shouldRejectMissingTo() {
        MailSend task = MailSend.builder()
            .id("send_email")
            .type("io.kestra.plugin.email.MailSend")
            .from(Property.ofValue("from@mail.com"))
            .build();

        var violation = modelValidator.isValid(task);
        assertThat(violation).isPresent();
        assertThat(violation.get().getConstraintViolations())
            .extracting(v -> v.getPropertyPath().toString())
            .contains("to");
    }

    @Test
    void shouldAcceptFromAndTo() {
        MailSend task = MailSend.builder()
            .id("send_email")
            .type("io.kestra.plugin.email.MailSend")
            .from(Property.ofValue("from@mail.com"))
            .to(Property.ofValue("to@mail.com"))
            .build();

        assertThat(modelValidator.isValid(task)).isEmpty();
    }
}
