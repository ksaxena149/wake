# Contributing to WAKE

This guide documents the workflow for the AI-assisted development loop used in this project.

## Branching Model

| Branch | Purpose |
|---|---|
| `main` | Stable. One merge per completed phase. Never commit directly. |
| `dev` | Integration. All feature branches merge here via PR. |
| `feature/phase-X-description` | One branch per issue. |

## Starting Work on an Issue

```bash
git checkout dev
git pull origin dev
git checkout -b feature/phase-N-short-description
```

## Commit Convention

This project uses [Conventional Commits](https://www.conventionalcommits.org/).

```
<type>(<scope>): <short imperative description>
```

**Types:** `feat`, `fix`, `test`, `docs`, `refactor`, `chore`

**Scopes:** `server`, `android`, `bundle`, `routing`, `security`, `ci`

**Examples:**
```
feat(server): add bundle chunker for responses over 100KB
fix(android): prevent Room migration crash on cold start
test(server): add signature verification round-trip tests
docs: add CONTRIBUTING guide
```

Rules:
- One logical change per commit
- Never commit "misc", "WIP", or "various fixes"
- Commit message body is optional but encouraged for non-obvious changes

## Opening a Pull Request

1. Push your feature branch: `git push origin feature/phase-N-description`
2. Open a PR on GitHub: base `dev` ← compare `feature/phase-N-description`
3. Reference the issue in the PR description: `Closes #N`
4. Merge when satisfied

## Merging a Phase to `main`

At the end of each phase, once all phase issues are closed:

1. Open a PR on GitHub: base `main` ← compare `dev`
2. Write a phase summary in the PR description
3. Merge
4. Tag the release:

```bash
git checkout main
git pull origin main
git tag -a vX.Y.Z -m "Phase N complete — short description"
git push origin vX.Y.Z
```

5. Close the milestone on GitHub

## AI Session Protocol

- State the current issue number and phase at the start of every session
- One issue per session — do not mix concerns
- Never let the AI commit directly; always review and commit yourself
- If the AI makes an architecture decision you didn't ask for, write an ADR before continuing

## Project Structure

```
server/     → Python FastAPI server (WAKE Gateway Daemon)
android/    → Kotlin Android app (Jetpack Compose)
docs/decisions/ → Architecture Decision Records (ADRs)
.github/    → Issue templates, CI workflows
```
