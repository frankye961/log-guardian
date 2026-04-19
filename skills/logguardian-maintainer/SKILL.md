---
name: logguardian-maintainer
description: Use when working on LogGuardian application changes, including CLI behavior, GUI behavior, runtime integrations, configuration updates, and backend feature work in the Spring Boot monolith.
---

# LogGuardian Maintainer

Use this skill for general implementation work in this repository.

## Scope

This repository is a Spring Boot 4 WebFlux monolith with two runtime modes:

- `logguardian.mode=cli` for command-driven runtime flows
- `logguardian.mode=gui` for the dashboard and SSE updates

Prefer changing the narrowest layer that owns the behavior. Keep the CLI and GUI paths consistent when they share runtime services.

## Workflow

1. Read [references/project-map.md](references/project-map.md) to find the owning package before editing.
2. If the change touches runtime behavior, config defaults, or user-visible commands, inspect `README.md` and `src/main/resources/application.yml`.
3. Make the smallest coherent change instead of spreading logic across controllers, runners, and services.
4. Verify with targeted Maven tests first. Expand to broader test coverage only when the touched area crosses subsystem boundaries.

## Repository Rules

- Preserve startup without an OpenAI API key unless the task explicitly changes that contract.
- Treat MongoDB and Redis as partially wired infrastructure; do not assume persistence is active in the executable path unless the code clearly routes through it.
- Prefer service-layer changes over controller or runner duplication.
- When changing config, preserve existing environment-variable overrides in `application.yml`.

## Verification

Read [references/verification.md](references/verification.md) for the preferred validation order and command patterns.
