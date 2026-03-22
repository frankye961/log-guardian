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
- Unit tests for the CLI runner, aggregator, parser, counter, anomaly detector, and utility behavior

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

`docker.host`

- Docker daemon endpoint.

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

## Tests

The test suite covers the most failure-prone behaviors:

- [`CliRunnerServiceTest.java`](src/test/java/com/logguardian/runners/CliRunnerServiceTest.java)
- [`MultilineAggregatorTest.java`](src/test/java/com/logguardian/aggregator/MultilineAggregatorTest.java)
- [`StringParserTest.java`](src/test/java/com/logguardian/parser/string/StringParserTest.java)
- [`FingerPrintWindowCounterTest.java`](src/test/java/com/logguardian/fingerprint/window/FingerPrintWindowCounterTest.java)
- [`AnomalyDetectorTest.java`](src/test/java/com/logguardian/fingerprint/anomaly/AnomalyDetectorTest.java)
- [`UtilsTest.java`](src/test/java/com/logguardian/util/UtilsTest.java)

## Edge Cases

- JSON detection is heuristic. Only trimmed `{...}` payloads are treated as JSON.
- Naive timestamps in plain-text logs are interpreted in the local JVM timezone.
- Multiline grouping is heuristic and may merge formats that begin new events with indentation.
- `max-lines` is a safety valve, so very large stack traces can be split into multiple entries.
- Counting is bucketed, not sliding, so spikes near bucket boundaries can appear smaller.
- Only `ERROR` events can currently raise anomalies.
- AI summarization is best-effort and does not block the rest of the stream if the model call fails.

## Next Features

- Implementation support for Kubernetes
- Further cleanup
- Speed improvement
- Vulnerabilites detector
- Report file generation
  
