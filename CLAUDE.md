# CLAUDE.md — AI Daily Memory

Read @AGENTS.md first — it is the canonical rules file for all agents on this repo
(Claude, Codex, Hermes) and contains the build commands, architecture invariants,
coding/design/performance/security standards, and testing gates.

## Project agents (use proactively)

Located in `.claude/agents/`. Dispatch them as subagents for non-trivial work; the
role table at the bottom of AGENTS.md defines what each one reviews.

- `android-reviewer` — after writing/changing Kotlin, Compose, or Wear code
- `backend-reviewer` — after writing/changing Python/FastAPI code
- `security-reviewer` — before merging anything touching capture, permissions,
  auth, storage, upload, or MCP/API surfaces
- `performance-reviewer` — for anything on the recording hot path or backend queries
- `test-quality-reviewer` — before declaring a feature done

## Project skills

Located in `.claude/skills/`:

- `quality-gate` — run the full verification suite before done/commit/PR
- `design-review` — Material 3 / Wear design + accessibility review of Compose UI
- `privacy-security-review` — privacy/security review (recording-app specific)
- `perf-review` — performance review against budgets with evidence
- `phase-task` — pick up the next TODO.md task under phase discipline

## Working rules for Claude in this repo

- **Never report work done without stating which quality gates ran.** Minimum:
  `gradle qualityCheck`; backend changes also need the `uv run` checks from AGENTS.md.
- **Take work from `TODO.md`** in phase order. Don't jump phases or add later-phase
  infrastructure early.
- **Keep the state machine pure** (`core/recording`): no Android imports, no side
  effects. Services dispatch events; they don't mutate state.
- **No secrets or recorded product data in any file.** Never commit a real `.env`.
- Package root is `com.apercallc.aidailymemory`. Ignore (or flag) any stray
  `com/aperca/` source trees — those are untracked leftovers.
- Conventional commits, short-lived `feat/`/`fix/`/`chore/`/`docs/` branches
  (see AGENTS.md and CONTRIBUTING.md).
- If the code-review-graph MCP tools are available and the graph is indexed, prefer
  them over grep/read for exploration; fall back to Grep/Glob/Read when it isn't.