# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Identity

WAKE (Wireless Asynchronous Knowledge Exchange) — an Android DTN (Delay-Tolerant Network) mesh that delivers offline educational content from a kiwix-serve server to Android phones via store-and-forward relay.

## Architecture

- **Server**: Python FastAPI daemon wrapping kiwix-serve (runs on laptop, migrates to Raspberry Pi in Phase 6).
- **Android app**: Kotlin + Jetpack Compose. Foreground Service owns all network/storage ops. OkHttp for server HTTP; Google Nearby Connections API (Phase 3+) for phone-to-phone transport.
- **Bundle protocol**: JSON + base64 payloads. Two request types distinguished by `query_string` prefix: plain text = search (`GET /search?pattern=...`), `/A/...` = article fetch.
  - Request fields: `node_id, query_id, query_string, timestamp, ttl_seconds, hop_count, signature`
  - Response fields: `server_id, query_id, chunk_index, total_chunks, content_type, payload_b64, sha256, signature`
- **Routing**: Epidemic flood-fill (Phase 3) → PRoPHET probabilistic routing (Phase 4).
- **Security**: PyNaCl Ed25519 signing on server; Google Tink verification on Android. Android Keystore identity is Phase 5 (issue #31).

## Branching & Commits

- `main` — stable; merged only at phase completion via PR. Never commit directly.
- `dev` — integration branch; all feature branches merge here.
- `feature/phase-X-description` — one branch per issue.

Conventional Commits: `<type>(<scope>): <imperative description>`
Types: `feat`, `fix`, `test`, `docs`, `refactor`, `chore`
Scopes: `server`, `android`, `bundle`, `routing`, `security`, `ci`

## Server

```bash
# Start kiwix-serve (ZIM lives outside the repo)
kiwix-serve --port 8888 ~/zim/wikipedia_en_top_mini_2026-03.zim

# Install dependencies
cd server && pip install -r requirements.txt

# Start WAKE daemon
cd server && uvicorn main:app --reload --port 8000

# Run all tests
pytest server/tests/

# Run one test file or one test
pytest server/tests/test_chunker.py
pytest server/tests/test_signing.py::test_sign_and_verify
```

`server/conftest.py` puts `server/` on `sys.path` — no package install needed for tests.

## Android

```bash
cd android

# Build
./gradlew assembleDebug

# All instrumented tests (requires physical device)
./gradlew connectedDebugAndroidTest

# Single test class
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.wake.dtn.data.BundleDaoTest

# Single package
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.wake.dtn.service
```

Emulators cannot run Nearby Connections or BLE features — always use a physical device.

## Constraints

- Never introduce a new library or dependency without asking.
- Never change project structure without asking.
- If a task requires an architecture decision, flag it and suggest writing an ADR in `docs/decisions/` rather than deciding unilaterally.
- Do not migrate bundles from JSON+base64 to CBOR unless explicitly asked.
- `node_id` is a random UUID for Phases 1–4; Keystore identity is Phase 5.

## Learned Corrections

### 2026-03-28 — Source directory must match Kotlin package declaration
Android's UTP test runner derives the `-e package` filter from the source *directory path*. If `src/main/java/com/wake/dtm/` is the path but `package com.wake.dtn` is declared, UTP passes the wrong package and finds 0 tests. The directory path and package declaration must be identical.

### 2026-03-28 — Always write Android instrumented tests alongside implementation
When implementing Android features, always write `androidTest/` tests that mirror the feature structure (`androidTest/service/` for Services, etc.). Use `@RunWith(AndroidJUnit4::class)` and `ServiceTestRule` for service tests. Do this by default, without being asked.

## Plans

- @.claude/plans/wake_revised_roadmap_8cbd3923.plan.md — active technical reference: per-phase decisions, component responsibilities, done criteria
- @.claude/plans/wake_master_plan_cf4d8d1c.plan.md — GitHub issue list, collaboration protocol, weekly rhythm
