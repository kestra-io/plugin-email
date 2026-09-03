# How to use the Email plugin

Send email from flows and trigger flows from incoming email using standard SMTP and IMAP connections.

## Authentication

Set `host`, `port`, `username`, and `password` on each task. Store credentials in [secrets](https://kestra.io/docs/concepts/secret). Control encryption via `transportStrategy`: the default `SMTPS` uses implicit TLS on port 465; set it to `SMTP_TLS` for STARTTLS on port 587; or `SMTP` for unencrypted connections.

## Tasks

`MailSend` sends an email as a step within a flow — set `to`, `subject`, and `htmlTextContent` (or `plainTextContent` for plain-text only). Use it for ad hoc notifications and alerts where you control the message body.

`MailExecution` sends a structured execution summary including status, duration, and an execution link, and is designed to be used with a [Flow trigger](https://kestra.io/docs/workflow-components/triggers) in a dedicated monitoring namespace that watches other namespaces for failures — the same pattern as `SlackExecution`.

For inbound email, `MailReceivedTrigger` polls an IMAP inbox on a schedule and starts one execution per batch of new messages; `RealTimeTrigger` starts one execution per message as it arrives. Use `MailReceivedTrigger` for controlled batch processing and `RealTimeTrigger` when you need low-latency response to incoming email.

Both triggers store each attachment in Kestra's internal storage and expose its URI so it can be passed to downstream tasks: `{{ trigger.latestEmail.attachments[0].uri }}` on `MailReceivedTrigger`, `{{ trigger.attachments[0].uri }}` on `RealTimeTrigger`. If an attachment fails to store, its metadata (filename, contentType, size) is still returned with a null `uri`.
