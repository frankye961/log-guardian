# LogGuardian

LogGuardian is a Java 25 log-ingestion and anomaly-detection prototype for container logs. The current runtime path is a CLI Spring Boot application that connects to Docker, streams container output, merges multiline entries such as stack traces, parses JSON and plain-text logs, fingerprints repeated failures, counts them inside configurable windows, and optionally sends anomalies to an LLM for a human-readable incident summary.

The active implementation is Docker-based. Kubernetes, persistence, and web features are not part of the executable path in the current repository state.

## Current Scope

What works now:

- Docker-backed log streaming through `docker-java`
- CLI commands for listing running containers and tailing one or all containers
- Multiline aggregation for stack traces and continuation lines
- JSON and plain-text parsing
- Fingerprint generation for repeated log patterns
- Count-based anomaly detection on `ERROR` events
- Optional AI summarization through Spring AI and OpenAI
- Plain-text and HTML email notifications for detected anomalies
- Unit and integration tests for anomaly email delivery and formatting

What is present but not finished:

- [`KubernetesPodsService.java`](src/main/java/com/logguardian/service/kubernetes/KubernetesPodsService.java) is currently empty
- Some dependencies still suggest future extensions, but the current executable path is CLI + Docker only

## Architecture

The runtime flow is linear:

1. CLI command dispatch
2. Container discovery and stream attachment
3. Raw line ingestion
4. Multiline event assembly
5. Parser selection
6. Fingerprint normalization and hashing
7. Windowed counting
8. Threshold-based anomaly detection
9. Optional AI summary generation
10. Optional email notification delivery

### CLI Layer

[`CliRunnerService.java`](src/main/java/com/logguardian/runners/CliRunnerService.java) is the entry point when `logguardian.mode=cli`.

Supported commands:

- `list`
- `tail-all`
- `tail-one <containerId>`
- `shell`
- `help`

The runner now separates command execution from process termination:

- `execute(...)` returns an exit code, which makes the behavior testable
- `tail-all` starts all subscriptions before blocking
- shutdown handling disposes active subscriptions explicitly

### Docker Access

[`DockerConnectionConfiguration.java`](src/main/java/com/logguardian/configuration/DockerConnectionConfiguration.java) builds the Docker client from `docker.host`.

[`DockerContainerService.java`](src/main/java/com/logguardian/service/docker/DockerContainerService.java) is responsible for:

- listing running containers
- attaching to container log streams
- tracking active subscriptions
- preventing duplicate streams for the same container
- stopping all streams on shutdown

### Processing Pipeline

[`DockerLogPipelineService.java`](src/main/java/com/logguardian/service/docker/DockerLogPipelineService.java) owns the log-processing path.

For each incoming `LogLine`:

- lines are merged into logical entries by [`MultilineAggregator.java`](src/main/java/com/logguardian/aggregator/MultilineAggregator.java)
- parser selection happens through `Utils.checkIfJson(...)`
- JSON events go through [`JsonParser.java`](src/main/java/com/logguardian/parser/json/JsonParser.java)
- non-JSON events go through [`StringParser.java`](src/main/java/com/logguardian/parser/string/StringParser.java)
- failed JSON parsing falls back to the string parser
- fingerprints are generated from normalized messages
- counts are tracked inside a configured time bucket
- anomalies are emitted only for `ERROR` events above the configured threshold
- if AI is enabled, the summarizer builds a prompt from configuration and calls the model
- if email notifications are enabled, the anomaly and summary are rendered into a readable incident email and sent through Spring Mail

### Email Notifications

[`EmailSenderService.java`](src/main/java/com/logguardian/service/email/EmailSenderService.java) sends anomaly notifications as multipart emails.

Each email includes:

- a severity-aware subject line
- the anomaly source, fingerprint, log level, count, and detection time
- a human-readable incident explanation
- the probable cause and suggested investigation steps
- the original sample log line
- both plain-text and HTML bodies for better client compatibility

The email step is best-effort:

- notification delivery is skipped if email notifications are disabled
- delivery is skipped if no recipients are configured
- SMTP failures are logged and do not stop the log stream

## Data Model

Important records in the pipeline:

- [`LogLine.java`](src/main/java/com/logguardian/model/LogLine.java): one raw line from Docker
- [`LogEntry.java`](src/main/java/com/logguardian/model/LogEntry.java): one logical log event after multiline assembly
- [`LogEvent.java`](src/main/java/com/logguardian/parser/model/LogEvent.java): parsed event with timestamps, level, message, and fingerprint
- [`CountedLogEvent.java`](src/main/java/com/logguardian/fingerprint/window/CountedLogEvent.java): parsed event plus count in its current window
- [`IncidentSummaryRequest.java`](src/main/java/com/logguardian/ai/model/IncidentSummaryRequest.java): prompt input for the summarizer
- [`IncidentSummary.java`](src/main/java/com/logguardian/ai/model/IncidentSummary.java): summarizer output payload

## Configuration

The project now keeps active configuration directly in [`application.yml`](src/main/resources/application.yml). Unused placeholder properties were removed.

### Active Configuration

`spring.main.web-application-type`

- Set to `none`, so the application runs as a CLI process.

`spring.ai.openai.api-key`

- OpenAI API key used by Spring AI.

`spring.ai.openai.chat.options.model`

- Chat model used by the summarizer.

`spring.mail.host`

- SMTP host used for notification delivery.

`spring.mail.port`

- SMTP port used for notification delivery.

`spring.mail.username`

- SMTP username.

`spring.mail.password`

- SMTP password.

`spring.mail.properties.mail.smtp.auth`

- Enables or disables SMTP authentication.

`spring.mail.properties.mail.smtp.starttls.enable`

- Enables STARTTLS when supported by the mail server.

`logguardian.mode`

- Runtime mode selector. `cli` is the implemented mode.

`logguardian.ingest.multiline.idle-flush-ms`

- Maximum idle time before a partial multiline event is flushed.

`logguardian.ingest.multiline.max-lines`

- Maximum number of physical lines allowed inside one logical entry before a forced flush.

`logguardian.detection.window-seconds`

- Bucket size for fingerprint counting.

`logguardian.detection.min-count-threshold`

- Minimum count required before an `ERROR` fingerprint becomes an anomaly.

`logguardian.ai.enabled`

- Enables or disables the summarization step.

`logguardian.ai.prompt-template`

- Prompt template used by [`AiIncidentSummarizer.java`](src/main/java/com/logguardian/ai/AiIncidentSummarizer.java).
- Supported placeholders:
  `{fingerprint}`, `{level}`, `{count}`, `{sourceId}`, `{sourceName}`, `{samples}`

`logguardian.notifications.email.enabled`

- Enables or disables email notification delivery.

`logguardian.notifications.email.from`

- Optional sender address for incident emails.

`logguardian.notifications.email.to`

- Comma-separated recipient list for incident emails.

`logguardian.notifications.email.reply-to`

- Optional reply-to address for incident emails.

`logguardian.notifications.email.subject-prefix`

- Prefix prepended to email subjects.

`docker.host`

- Docker daemon endpoint.

### Example Email Configuration

Mailtrap:

```bash
export MAIL_HOST=sandbox.smtp.mailtrap.io
export MAIL_PORT=587
export MAIL_USERNAME=your-mailtrap-username
export MAIL_PASSWORD=your-mailtrap-password
export MAIL_SMTP_AUTH=true
export MAIL_SMTP_STARTTLS_ENABLE=true
export LOGGUARDIAN_EMAIL_ENABLED=true
export LOGGUARDIAN_EMAIL_FROM=alerts@example.com
export LOGGUARDIAN_EMAIL_TO=dev@example.com
```

smtp4dev running in Docker and exposed on host port `2525`:

```bash
export MAIL_HOST=localhost
export MAIL_PORT=2525
export MAIL_USERNAME=
export MAIL_PASSWORD=
export MAIL_SMTP_AUTH=false
export MAIL_SMTP_STARTTLS_ENABLE=false
export LOGGUARDIAN_EMAIL_ENABLED=true
export LOGGUARDIAN_EMAIL_FROM=alerts@example.com
export LOGGUARDIAN_EMAIL_TO=dev@example.com
```

If LogGuardian runs inside Docker too, `localhost` will not reach the SMTP container. Use the SMTP container or service name instead.

## Running

Build and test:

```bash
mvn test
mvn package
```

List containers:

```bash
java -jar target/logguardian-0.1.0-SNAPSHOT.jar list
```

Tail one container:

```bash
java -jar target/logguardian-0.1.0-SNAPSHOT.jar tail-one <containerId>
```

Tail all running containers:

```bash
java -jar target/logguardian-0.1.0-SNAPSHOT.jar tail-all
```

Interactive shell:

```bash
java -jar target/logguardian-0.1.0-SNAPSHOT.jar shell
```

For a quick anomaly-email test, lower the threshold temporarily:

```bash
export LOGGUARDIAN_MIN_COUNT_THRESHOLD=0
```

Then emit repeated lines containing `ERROR` from the tailed container.

## Tests

The test suite covers the most failure-prone behaviors:

- [`CliRunnerServiceTest.java`](src/test/java/com/logguardian/runners/CliRunnerServiceTest.java)
- [`MultilineAggregatorTest.java`](src/test/java/com/logguardian/aggregator/MultilineAggregatorTest.java)
- [`StringParserTest.java`](src/test/java/com/logguardian/parser/string/StringParserTest.java)
- [`FingerPrintWindowCounterTest.java`](src/test/java/com/logguardian/fingerprint/window/FingerPrintWindowCounterTest.java)
- [`AnomalyDetectorTest.java`](src/test/java/com/logguardian/fingerprint/anomaly/AnomalyDetectorTest.java)
- [`UtilsTest.java`](src/test/java/com/logguardian/util/UtilsTest.java)
- [`EmailSenderServiceTest.java`](src/test/java/com/logguardian/service/email/EmailSenderServiceTest.java)
- [`DockerLogPipelineEmailIntegrationTest.java`](src/test/java/com/logguardian/service/docker/DockerLogPipelineEmailIntegrationTest.java)

## Edge Cases

- JSON detection is heuristic. Only trimmed `{...}` payloads are treated as JSON.
- Naive timestamps in plain-text logs are interpreted in the local JVM timezone.
- Multiline grouping is heuristic and may merge formats that begin new events with indentation.
- `max-lines` is a safety valve, so very large stack traces can be split into multiple entries.
- Counting is bucketed, not sliding, so spikes near bucket boundaries can appear smaller.
- Only `ERROR` events can currently raise anomalies.
- AI summarization is best-effort and does not block the rest of the stream if the model call fails.
- Email delivery happens only after an anomaly is detected, so normal container tailing alone will not send notifications.
- By default, anomalies require more than `logguardian.detection.min-count-threshold` matching `ERROR` events inside the current window.

## Next Features

- Implementation support for Kubernetes
- Further cleanup
- Speed improvement
- Vulnerabilites detector
- Report file generation
  
