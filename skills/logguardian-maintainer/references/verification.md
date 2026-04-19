# Verification

Use targeted validation before broad validation.

## Preferred Order

1. Run the smallest relevant test scope for the changed package.
2. If the change crosses multiple layers, run the broader module tests.
3. If config or wiring changed, consider starting the app in the relevant mode after tests pass.

## Command Patterns

- Targeted tests: `mvn test -Dtest=ClassName`
- Multiple targeted tests: `mvn test -Dtest=ClassOne,ClassTwo`
- Integration-oriented checks: `mvn verify`

## What To Check

- CLI changes: command dispatch, exit code behavior, shell/background job behavior
- GUI changes: controller/service contract, SSE updates, job lifecycle
- Pipeline changes: multiline assembly, parser fallback, fingerprint stability, threshold behavior
- Notification changes: email enablement guards, rendering, failure handling

## Practical Rule

If a change only touches one subsystem, do not default to a full-suite run first.
