---
name: logguardian-incident-debugger
description: Use when debugging LogGuardian anomaly detection, parsing, fingerprinting, AI incident summaries, or email notification behavior across the log-processing pipeline.
---

# LogGuardian Incident Debugger

Use this skill when the problem is about why an incident was or was not produced, how it was summarized, or how it was delivered.

## Debug Order

Follow the pipeline in order. Do not jump straight to AI or email unless the upstream event is already proven correct.

1. Read [references/pipeline-map.md](references/pipeline-map.md).
2. Confirm the raw input shape and whether multiline aggregation changes it.
3. Confirm parser selection and fallback behavior.
4. Confirm fingerprint normalization, window counting, and threshold comparison.
5. Only then inspect AI summarization and email delivery.

## Guardrails

- A missing incident is often a parsing, fingerprint, or threshold problem rather than an AI problem.
- GUI cards currently show raw container log errors instead of the AI summary; do not treat that as a regression unless the task says otherwise.
- Email delivery is best-effort. SMTP failure logging is not the same as incident generation failure.
- Keep configuration-driven behavior aligned with `application.yml` defaults and environment overrides.

## Verification

Prefer a focused reproducer or targeted test around the failing stage of the pipeline. Expand only if the failure spans multiple stages.
