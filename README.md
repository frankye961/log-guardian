# LogGuardian - V1.0.0 ALPHA

LogGuardian is a Spring Boot/WebFlux service that tails Docker container logs in real time, groups multiline stack traces, parses events (JSON or plaintext), computes normalized fingerprints, detects error spikes, and produces AI-assisted incident summaries.

## 1. What the system does end-to-end

At runtime, the pipeline is:

1. **Client starts tailing** via `POST /api/tailing/start` with a `ContainerRulesetRequest`.
2. **Container matching** happens inside `DockerContainerService` using rule semantics:
   - `CONTAINS`: partial ID match
   - `EQUAL`: exact ID match
   - `ALL`: tail every running container
3. **Docker streaming** attaches to each selected container using Docker Java `logContainerCmd(...).withFollowStream(true).withTailAll()`.
4. **Raw frame mapping** converts Docker frames into internal `LogLine` records.
5. **Multiline aggregation** combines stacktrace-like continuation lines into a single `LogEntry`.
6. **Parsing** routes each entry:
   - JSON payloads -> `JsonParser`
   - Plain text -> `StringParser`
7. **Fingerprint generation** normalizes message content and hashes it, creating a stable signature for repeated incidents.
8. **Window counting** tracks per-fingerprint frequency in minute buckets.
9. **Anomaly detection** currently flags events when:
   - level is `ERROR`, and
   - count in the active minute window is above threshold.
10. **AI summarization** sends anomaly context (fingerprint, count, samples) to a chat model and logs a human-readable incident summary.
11. **Lifecycle management** keeps one active subscription per tailed container and disposes it when stopping.

## 2. Runtime components and responsibilities

### REST API layer
`DockerController` exposes operations:

- `GET /api/running/containers` → returns currently running Docker containers.
- `POST /api/tailing/start` → starts one or more reactive tail streams.
- `POST /api/tailing/stop` → stops a specific stream or all streams.

### Stream orchestration
`DockerContainerService` is the central coordinator:

- stores active stream subscriptions in `ConcurrentHashMap<String, Disposable>` (`containerId -> stream subscription`)
- builds and runs the reactive processing graph
- encapsulates Docker client interactions

### Parsing and normalization
- `MultilineAggregator` groups contiguous lines into one logical event.
- `StringParser` extracts timestamp and severity hints from unstructured logs.
- `JsonParser` parses structured logs.
- `FingerPrintGenerator` normalizes and hashes message text.

### Detection and AI
- `FingerPrintWindowCounter` counts occurrences per minute window.
- `AnomalyDetector` decides if a counted event is anomalous.
- `AiIncidentSummarizer` transforms anomalies into natural-language incident insights.

## 3. Detailed processing sequence (reactive stream internals)

For each selected container, `DockerContainerService.startStream(containerId)` wires this flow:

```text
streamLogs(containerId)
  -> multilineAggregator.transform(...)
  -> chooseParser(entry)
  -> fingerprintGenerator.generateFingerprint(event)
  -> counter.countFingerprint(event)
  -> detector.detectAnomaly(countedEvent)
  -> AiSummerizerMapper.toIncidentSummaryRequest(...)
  -> summarizer.summarize(request) [boundedElastic]
  -> log.warn("AI INCIDENT SUMMARY: ...")
```

Important behavior details:

- **Backpressure/concurrency**: AI calls are wrapped in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` to avoid blocking reactive worker threads.
- **Termination cleanup**: `doFinally(...)` removes container IDs from `activeContainers`.
- **Failure visibility**: per-container stream failures are logged through `doOnError(...)`.

## 4. API contract

### `GET /api/running/containers`
Returns Docker `Container` metadata list.

- Success: `200 OK`
- Failure: `400 Bad Request`

### `POST /api/tailing/start`
Request body (`ContainerRulesetRequest`):

```json
{
  "containerId": "abc123",
  "label": ["optional", "labels"],
  "rule": "CONTAINS"
}
```

Rules:
- `CONTAINS` matches partial IDs.
- `EQUAL` matches one exact ID.
- `ALL` starts streams for all running containers.

Response:
- Success: `200 OK`
- Failure: `400 Bad Request` with error message

### `POST /api/tailing/stop`
Uses the same request model.

- For specific stop, `containerId` is removed from active map and disposed.
- For `ALL`, every active container stream is disposed.

Response:
- Success: `200 OK`
- Failure: `400 Bad Request`

## 5. Configuration

Main runtime settings are in `src/main/resources/application.yml`:

- `server.port`: HTTP port (default `8087`)
- `docker.host`: Docker daemon endpoint (`DOCKER_HOST`)
- `spring.ai.openai.api-key`: model API key (`OPENAPI_KEY`)
- Mongo/observability sections are present for broader platform support

## 6. Local development workflow

### Prerequisites
- Java 25
- Maven 3.9+
- reachable Docker daemon (`DOCKER_HOST`)
- OpenAI API key (if AI summarization is enabled)

### Build and run

```bash
mvn clean test
mvn spring-boot:run
```

### Typical manual verification

1. Start service.
2. Call `GET /api/running/containers`.
3. Start tailing one container via `POST /api/tailing/start`.
4. Trigger synthetic errors in the target container.
5. Observe logs for anomaly and AI summary output.
6. Stop tailing via `POST /api/tailing/stop`.

## 7. Test strategy in this repository

The project now includes:

- **Service-level unit tests** for container selection and stream start behavior.
- **Controller integration tests** that validate REST endpoints through `WebTestClient` against the real controller (mocked service dependency).

Run all tests:

```bash
mvn test
```

## 8. Current limitations and implementation notes

- Some configuration blocks in `application.yml` describe broader Kubernetes-oriented goals; current concrete code path is Docker-focused.
- AI summaries are currently logged, not persisted.
- The anomaly rule is intentionally simple (`ERROR` + count threshold) and can be extended with baseline/novelty models.
- `ContainerRulesetRequest.label` exists in the model but is not currently used in selection logic.

---

If you want, the next iteration can include a strict OpenAPI contract section with concrete request/response schemas and example curl commands for each endpoint.
