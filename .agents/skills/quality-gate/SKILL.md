---
name: quality-gate
description: Run the full AI Daily Memory verification suite before declaring work done, committing, or opening a PR. Use whenever the user asks to "check quality", "run the gates", "verify before commit/PR/merge", or says work is ready — and proactively before reporting any non-trivial change as complete.
---

# Quality gate

The repo's merge bar is the `qualityCheck` Gradle task (tests + coverage + lint for
all Android modules) plus the backend checks. Run the full set below; never report
work done without stating exactly which gates ran and which were skipped.

## What to run

```bash
# Android modules (committed wrapper, Gradle 9.7.1)
./gradlew qualityCheck

# Backend (only when backend/ changed)
cd backend
uv sync --locked --extra dev
uv run ruff check .
uv run ruff format --check .
uv run mypy app tests
uv run pytest
uv run pip-audit
```

`qualityCheck` covers: `:core:recording:check` (unit tests + JaCoCo ≥90% line & branch),
`:apps:android` and `:apps:wear` unit tests, and Android Lint for both app modules.
Lint runs `abortOnError=true` + `warningsAsErrors=true`, so a warning is a failure.

## Decision guide

- **All gates pass** → report done, list the commands that passed.
- **A gate fails** → fix it before proceeding; report the failure and the fix.
  Never downgrade a lint error or coverage threshold to make a gate pass.
- **A gate is irrelevant to the change** (e.g., backend untouched) → state plainly
  that it was skipped and why.
- **Coverage fails** → write missing tests for the uncovered behavior; don't delete
  code to game the percentage.
- **Environment can't build Android** (no SDK, or Google Maven blocked) → say so, run
  what can run (`core:recording` is pure JVM), and report `qualityCheck` as NOT RUN.
  Never report it as passing.

## Report format

```
Quality gate: PASS|FAIL
- ./gradlew qualityCheck — <result>
- backend ruff/mypy/pytest/pip-audit — <result or "skipped: no backend changes">
Follow-ups: <any findings, or none>
```