# LogGuardian

LogGuardian is a Java 25 log-ingestion and anomaly-detection prototype for container logs. It can connect to Docker or Kubernetes, stream runtime logs, merge multiline entries such as stack traces, parse JSON and plain-text logs, fingerprint repeated failures, count them inside configurable windows, and optionally send anomalies to an LLM for a human-readable incident summary.

The active implementation includes both a CLI runtime and an optional web GUI. Persistence configuration is present, but persistence-backed incident storage is not part of the executable path yet.

## Current Scope

What works now:

- Docker-backed log streaming through `docker-java`
- Kubernetes-backed pod log streaming through Fabric8
- CLI commands for listing running sources and tailing one or all sources via `docker` or `kub`
- Interactive shell mode with background tail jobs
- Optional browser-based GUI for listing sources, starting/stopping tail jobs, viewing incidents, and changing incident event types
- GUI incident cards show raw container log errors instead of the AI summary
- Multiline aggregation for stack traces and continuation lines
- JSON and plain-text parsing
- Fingerprint generation for repeated log patterns
- Count-based anomaly detection on `ERROR` events
- Optional AI summarization through Spring AI and OpenAI
- Plain-text and HTML email notifications for detected anomalies
- Unit and integration tests for anomaly email delivery and formatting

What is present but not finished:

- MongoDB and Redis configuration is present, but persistence and cache-backed incident memory are not wired into the processing path yet
- Some dependencies still suggest future extensions beyond the current CLI runtime path

## Architecture

The runtime flow is linear:

1. CLI or GUI command dispatch
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

- `docker list`
- `docker tail-all`
- `docker tail-one <sourceId>`
- `kub list`
- `kub tail-all`
- `kub tail-one <sourceId>`
- `shell`
- `help`

The runner now separates command execution from process termination:

- `execute(...)` returns an exit code, which makes the behavior testable
- `tail-all` starts all subscriptions before blocking
- when `tail-one` or `tail-all` are launched from `shell`, they are registered as background jobs so the shell can continue accepting commands
- shutdown handling disposes active subscriptions explicitly

### GUI Layer

[`DashboardController.java`](src/main/java/com/logguardian/gui/DashboardController.java) and [`DashboardService.java`](src/main/java/com/logguardian/gui/DashboardService.java) provide an optional web dashboard when `logguardian.mode=gui`.

The dashboard currently supports:

- listing Docker and Kubernetes sources
- starting `tail-one` and `tail-all` jobs without blocking the UI
- stopping active tail jobs
- showing recent incidents from persistence when available
- selecting incidents and applying manual event-type changes such as `ACKNOWLEDGED`, `RESOLVED`, and `CLOSED`
- showing incident content from stored container log samples instead of the AI-generated summary
- collapsing repeated identical samples for the same incident so the same fingerprinted error is shown once
- live updates through server-sent events
- chunked source rendering for large runtime lists
- responsive card layouts that wrap long source IDs, titles, summaries, and notes instead of overflowing

### Docker Access

[`DockerConnectionConfiguration.java`](src/main/java/com/logguardian/configuration/DockerConnectionConfiguration.java) builds the Docker client from `docker.host`.

[`DockerContainerService.java`](src/main/java/com/logguardian/service/docker/DockerContainerService.java) is responsible for:

- listing running containers
- attaching to container log streams
- tracking active subscriptions
- preventing duplicate streams for the same container
- stopping all streams on shutdown

### Kubernetes Access

[`KubernetesConnectionConfiguration.java`](src/main/java/com/logguardian/configuration/KubernetesConnectionConfiguration.java) builds a lazy Fabric8 client.

[`KubernetesPodsService.java`](src/main/java/com/logguardian/service/kubernetes/KubernetesPodsService.java) is responsible for:

- listing running pods
- attaching to pod log streams
- tracking active subscriptions
- preventing duplicate streams for the same pod
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
- if email notifications are enabled, the anomaly and AI summary are rendered into a readable incident email and sent through Spring Mail

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

- Defaults to `none`, so the application runs as a CLI process by default.
- Set it to `reactive` to enable the GUI.

`spring.autoconfigure.exclude`

- Disables OpenAI model auto-configuration by default so the application can start without an API key.
- Override it if you want Spring AI OpenAI auto-configuration back.

`spring.ai.chat.client.enabled`

- Defaults to `false` so the GUI and CLI can start without a configured `ChatModel`.
- Set it to `true` when AI chat summarization is fully configured.

`spring.ai.openai.api-key`

- OpenAI API key used by Spring AI.

`spring.ai.openai.chat.options.model`

- Chat model used by the summarizer.
- Configurable via `OPENAI_MODEL` and defaults to `gpt-5-mini`.

`spring.data.mongodb.uri`

- MongoDB connection URI for persistent incident storage.

`spring.data.mongodb.database`

- MongoDB database name used by the application.

`spring.data.mongodb.auto-index-creation`

- Enables automatic index creation for MongoDB documents.

`spring.data.redis.host`

- Redis host used for in-memory caching and deduplication.

`spring.data.redis.port`

- Redis port used for in-memory caching and deduplication.

`spring.data.redis.username`

- Optional Redis username.

`spring.data.redis.password`

- Optional Redis password.

`spring.data.redis.database`

- Redis logical database number.

`spring.data.redis.timeout`

- Redis command timeout.

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

- Runtime mode selector.
- Defaults to `cli`.
- Set it to `gui` to enable the web dashboard.

`docker.host`

- Docker daemon host used by the Docker runtime.

`kubernetes.namespace`

- Default namespace used when the `kub` runtime lists or tails pods.

`kubernetes.all-namespaces`

- When enabled, the `kub` runtime lists running pods from all namespaces and `tail-one` expects `<namespace>/<podName>`.

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

CLI mode is the default. Example commands:

List Docker containers:

```bash
java -jar target/logguardian-0.1.0-SNAPSHOT.jar docker list
```

Tail one Docker container:

```bash
java -jar target/logguardian-0.1.0-SNAPSHOT.jar docker tail-one <containerId>
```

Tail all running Docker containers:

```bash
java -jar target/logguardian-0.1.0-SNAPSHOT.jar docker tail-all
```

Interactive shell:

```bash
java -jar target/logguardian-0.1.0-SNAPSHOT.jar shell
```

GUI mode is opt-in and runs in parallel to the CLI, not instead of it:

```bash
LOGGUARDIAN_MODE=gui SPRING_MAIN_WEB_APPLICATION_TYPE=reactive mvn spring-boot:run
```

Then open `http://localhost:8080/`.

The GUI:

- loads live updates over `/api/dashboard/stream`
- starts tail jobs in background
- renders source lists in batches to avoid large first paints
- lets operators select incidents and change the incident event type from the dashboard
- shows log-first incident cards built from stored sample errors
- collapses duplicate repeated samples for the same incident
- wraps long text fields so cards remain usable on smaller screens and with long source IDs

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
- If AI is not configured, the application still starts and returns an "AI unavailable" style summary fallback.
- The GUI incident cards are intentionally log-first. AI summaries are preserved for email delivery and persisted metadata, but the dashboard emphasizes the raw container error samples.
- Email delivery happens only after an anomaly is detected, so normal container tailing alone will not send notifications.
- The first GUI snapshot now uses cached runtime data immediately and refreshes Docker/Kubernetes source discovery in the background. A completely cold cache can still show an initially sparse dashboard until discovery completes.
- By default, anomalies require more than `logguardian.detection.min-count-threshold` matching `ERROR` events inside the current window.

## Next Features

- Cache implementation
- Further cleanup
- Speed improvement
- Database support for long term memory implementation
- Vulnerabilities detector
  
