# Pipeline Map

Use this map when tracing incident-generation problems.

## Flow

1. Source attachment from Docker or Kubernetes services
2. Raw line ingestion into `LogLine`
3. Multiline assembly in `src/main/java/com/logguardian/aggregator`
4. Parser selection in the parser layer
5. Parsed event creation in `src/main/java/com/logguardian/parser/json` or `src/main/java/com/logguardian/parser/string`
6. Fingerprint normalization and hashing in `src/main/java/com/logguardian/fingerprint`
7. Window counting and threshold checks in `src/main/java/com/logguardian/fingerprint/window`
8. Optional AI summary generation in `src/main/java/com/logguardian/ai`
9. Optional notification delivery in `src/main/java/com/logguardian/service/email`

## Configuration Knobs

Check these before changing code:

- `logguardian.ingest.multiline.idle-flush-ms`
- `logguardian.ingest.multiline.max-lines`
- `logguardian.detection.window-seconds`
- `logguardian.detection.min-count-threshold`
- `logguardian.ai.enabled`
- `logguardian.notifications.email.enabled`

## Fast Triage

- Incident never appears: inspect parsing, fingerprinting, and threshold logic
- Incident count looks wrong: inspect windowing and normalization
- Incident appears but wording is wrong: inspect AI prompt construction or summary handling
- Incident appears but no email arrives: inspect notification enablement, recipients, and SMTP handling
