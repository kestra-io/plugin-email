# Kestra Email Plugin

## What

- Provides plugin components under `io.kestra.plugin.email`.
- Includes classes such as `MailExecution`, `RealTimeTrigger`, `MailReceivedTrigger`, `MailTemplate`.

## Why

- This plugin integrates Kestra with Email.
- It provides tasks that send email notifications.

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
