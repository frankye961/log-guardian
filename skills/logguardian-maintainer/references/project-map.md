# Project Map

Use this file to choose the correct edit location before changing code.

## Entry Points

- `src/main/java/com/logguardian/runners`: CLI command parsing and shell behavior
- `src/main/java/com/logguardian/gui`: dashboard endpoints, orchestration, SSE-facing behavior
- `src/main/resources/application.yml`: active runtime defaults and environment-variable mapping

## Runtime Integrations

- `src/main/java/com/logguardian/service/docker`: Docker source listing, log attachment, subscription management
- `src/main/java/com/logguardian/service/kubernetes`: Kubernetes pod listing and log attachment
- `src/main/java/com/logguardian/service/runtime`: runtime job coordination
- `src/main/java/com/logguardian/configuration`: Docker, Kubernetes, and supporting bean configuration

## Processing Pipeline

- `src/main/java/com/logguardian/aggregator`: multiline event assembly
- `src/main/java/com/logguardian/parser`: parser selection and event parsing
- `src/main/java/com/logguardian/fingerprint`: normalization, hashing, anomaly windows, thresholds
- `src/main/java/com/logguardian/ai`: AI summarization request/response handling
- `src/main/java/com/logguardian/service/email`: anomaly email delivery

## Model And Mapping

- `src/main/java/com/logguardian/model`: raw runtime models such as log lines and entries
- `src/main/java/com/logguardian/parser/model`: parsed event models
- `src/main/java/com/logguardian/mapper`: mapping between internal and persistence-facing structures
- `src/main/java/com/logguardian/persistance`: persistence documents and repository-facing code

## Change Heuristics

- New CLI command or shell behavior: start in `runners`, then follow the called service
- Dashboard behavior mismatch: inspect `gui` first, then shared services
- Detection threshold or counting bug: inspect `fingerprint/window` and related config
- Parsing bug: inspect `aggregator`, then `parser/json` or `parser/string`
- Summary or notification bug: inspect `ai` and `service/email`
