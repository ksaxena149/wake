---
description: Python and FastAPI conventions for the WAKE server daemon
paths:
  - "server/**/*.py"
---

# Server Rules

## Stack

Python 3.11+, FastAPI, uvicorn, aiosqlite, PyNaCl, httpx. SQLite only (no Postgres, no Redis). kiwix-serve is a separate process on `localhost:8888` — call it via the shared `httpx.AsyncClient` on `app.state.kiwix_client`.

## Code Style

- `async def` for all endpoint handlers and DB operations.
- Type-annotate all function signatures; use `pydantic` models for request/response schemas.
- `pathlib.Path` over `os.path` for all file paths.
- Imports: stdlib → third-party → local. One blank line between groups. No wildcard imports.

## Error Handling

- Never swallow exceptions silently. Use the `logging` module at the appropriate level.
- HTTP status codes: 400 bad input, 404 missing bundle, 502 kiwix-serve error, 500 internal.
- Use `HTTPException(status_code=..., detail="...")` with a descriptive detail string.

## Testing

- `pytest` + `pytest-asyncio` for async tests. Files live in `server/tests/test_<module>.py`.
- Use `httpx.AsyncClient` with FastAPI's `TestClient` for endpoint tests.
- Every new module needs at least one corresponding test file.
