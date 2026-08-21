package io.kestra.plugin.email;

import java.util.Map;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.plugins.notifications.ExecutionInterface;
import io.kestra.core.plugins.notifications.ExecutionService;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import io.kestra.core.models.annotations.PluginProperty;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Email a Flow execution summary",
    description = "Sends a templated email that links to the execution page and includes the execution ID, namespace, flow name, start time, duration, final status, and the failing task when applicable. Uses built-in HTML and text templates with `executionId` defaulting to the current run. Use this in Flow-trigger alerting scenarios and prefer [MailSend](https://kestra.io/plugins/plugin-email/tasks/io.kestra.plugin.email.mailsend) for `errors` handlers; see [alerting docs](https://kestra.io/docs/administrator-guide/monitoring#alerting)."
)
@Plugin(
    examples = {
        @Example(
            title = "Send an email notification on a failed flow execution",
            full = true,
            code = """
                id: failure_alert
                namespace: company.team

                tasks:
                  - id: send_alert
                    type: io.kestra.plugin.email.MailExecution
                    to: hello@kestra.io
                    from: hello@kestra.io
                    subject: "The workflow execution {{trigger.executionId}} failed for the flow {{trigger.flowId}} in the namespace {{trigger.namespace}}"
                    host: mail.privateemail.com
                    port: 465
                    username: "{{ secret('EMAIL_USERNAME') }}"
                    password: "{{ secret('EMAIL_PASSWORD') }}"
                    executionId: "{{ trigger.executionId }}"

                triggers:
                  - id: failed_prod_workflows
                    type: io.kestra.plugin.core.trigger.Flow
                    conditions:
                      - type: io.kestra.plugin.core.condition.ExecutionStatus
                        in:
                          - FAILED
                          - WARNING
                      - type: io.kestra.plugin.core.condition.ExecutionNamespace
                        namespace: prod
                        prefix: true
                """
        )
    },
    aliases = "io.kestra.plugin.notifications.mail.MailExecution"
)
public class MailExecution extends MailTemplate implements ExecutionInterface {
    @Schema(
        title = "Execution ID to describe",
        description = "Execution identifier injected into the templates. Defaults to the current execution when left blank"
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private final Property<String> executionId = Property.ofExpression("{{ execution.id }}");

    @Schema(
        title = "Custom fields for the template",
        description = "Additional key-value map merged into the email templates alongside the execution data"
    )
    @PluginProperty(group = "destination")
    private Property<Map<String, Object>> customFields;

    @Schema(
        title = "Custom message prefix",
        description = "Optional freeform text appended to the rendered email body"
    )
    @PluginProperty(group = "destination")
    private Property<String> customMessage;

    @Schema(
        hidden = true
    )
    protected Property<String> htmlTextContent;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        this.templateUri = Property.ofValue("mail-template.hbs.peb");
        this.textTemplateUri = Property.ofValue("text-template.hbs.peb");
        this.templateRenderMap = Property.ofValue(ExecutionService.executionMap(runContext, this));

        return super.run(runContext);
    }
}
