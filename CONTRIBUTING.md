# Contributing

## Principles

- Reliability and privacy outrank feature velocity.
- Prefer simple, measurable designs.
- Keep capture independent from backend availability.
- Never log raw audio, full transcripts, secrets, or access tokens.
- Preserve immutable evidence; AI-derived data is correctable and replaceable.

## Branches

Use short-lived branches:

- `feat/<name>`
- `fix/<name>`
- `chore/<name>`
- `docs/<name>`

Keep pull requests small enough to review.

## Commit style

Use Conventional Commit-style prefixes where practical:

- `feat:`
- `fix:`
- `test:`
- `refactor:`
- `perf:`
- `security:`
- `docs:`
- `build:`
- `ci:`

## Kotlin

Follow the official Kotlin coding conventions.

Prefer:

- immutable state
- explicit domain models
- unidirectional data flow in Compose
- pure functions for state transitions and business rules
- coroutines with structured concurrency
- no blocking I/O on the main thread
- small interfaces at real boundaries, not speculative abstractions

Avoid:

- global mutable state
- hidden side effects
- catch-all exception swallowing
- provider-specific SDK calls spread across the codebase
- unnecessary framework/DI complexity

## Python

- Ruff formatting/linting
- mypy strict mode
- typed public functions
- Pydantic for API boundaries
- bounded timeouts for network calls
- explicit transaction boundaries
- no broad `except Exception` without handling/rethrow context

## Testing

The target is not vanity coverage. The target is trustworthy behavior.

Required:

- >=90% line coverage for core business logic
- >=90% line coverage for backend application code
- >=90% branch coverage for safety-critical state machines/retry/idempotency logic
- integration tests at storage/API/provider boundaries
- Compose/instrumentation tests for critical user flows
- fault-injection tests for capture and processing reliability
- regression test for every confirmed production bug

Generated code, trivial UI wiring, and framework boilerplate should not be used to inflate coverage.

## Pull requests

Before merge:

1. Tests pass.
2. Lint/type checks pass.
3. Coverage gates pass.
4. Security/privacy implications are addressed.
5. No sensitive data is logged or committed.
6. Performance-sensitive changes include measurements.
7. README/TODO/ADR is updated when architecture changes.
