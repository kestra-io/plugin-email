# Kestra Email Plugin

## What

- Provides plugin components under `io.kestra.plugin.email`.
- Includes classes such as `MailExecution`, `RealTimeTrigger`, `MailReceivedTrigger`, `MailTemplate`.

## Why

- What user problem does this solve? Teams need to send email notifications from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps Email steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on Email.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `email`

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.email.MailExecution`
- `io.kestra.plugin.email.MailReceivedTrigger`
- `io.kestra.plugin.email.MailSend`
- `io.kestra.plugin.email.RealTimeTrigger`

### Project Structure

```
plugin-email/
├── src/main/java/io/kestra/plugin/email/
├── src/test/java/io/kestra/plugin/email/
├── build.gradle
└── README.md
```

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
