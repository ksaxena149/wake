import time
from pathlib import Path
from unittest.mock import AsyncMock, patch

import aiosqlite
import httpx
import nacl.signing
import pytest
import pytest_asyncio

import database
import main
from main import app

SEARCH_HTML = b"<html><body>Search results</body></html>"
ARTICLE_HTML = b"<html><body><h1>Water</h1></body></html>"


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest_asyncio.fixture(autouse=True)
async def _setup_app(tmp_path: Path):
    """Initialise DB, signing key, and a mock kiwix client for every test."""
    await database.init_db(tmp_path / "test.db")
    main._signing_key = nacl.signing.SigningKey.generate()

    def kiwix_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=SEARCH_HTML, headers={"content-type": "text/html"})

    transport = httpx.MockTransport(kiwix_handler)
    client = httpx.AsyncClient(transport=transport, base_url="http://kiwix")
    app.state.kiwix_client = client

    yield

    await client.aclose()


def _make_request_body(query_string: str = "water cycle", query_id: str = "test-qid-001") -> dict:
    return {
        "node_id": "550e8400-e29b-41d4-a716-446655440000",
        "query_id": query_id,
        "query_string": query_string,
        "timestamp": int(time.time()),
        "ttl_seconds": 3600,
        "hop_count": 0,
    }


# ---------------------------------------------------------------------------
# Health & pubkey (smoke tests — pre-existing endpoints)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_health() -> None:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        resp = await ac.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


@pytest.mark.asyncio
async def test_pubkey() -> None:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        resp = await ac.get("/pubkey")
    assert resp.status_code == 200
    assert "pubkey_b64" in resp.json()


# ---------------------------------------------------------------------------
# POST /request — happy path
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_post_request_search() -> None:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        resp = await ac.post("/request", json=_make_request_body("water cycle"))

    assert resp.status_code == 200
    chunks = resp.json()
    assert len(chunks) >= 1
    assert chunks[0]["query_id"] == "test-qid-001"
    assert chunks[0]["server_id"] == "wake-server-01"
    assert chunks[0]["signature"] is not None
    assert chunks[0]["chunk_index"] == 0


@pytest.mark.asyncio
async def test_post_request_article_path() -> None:
    def article_handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.startswith("/wikipedia"):
            return httpx.Response(200, content=ARTICLE_HTML, headers={"content-type": "text/html"})
        return httpx.Response(404)

    mock_client = httpx.AsyncClient(
        transport=httpx.MockTransport(article_handler), base_url="http://kiwix"
    )
    app.state.kiwix_client = mock_client

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        resp = await ac.post(
            "/request",
            json=_make_request_body("/wikipedia_en_all/A/Water", "article-qid-001"),
        )

    assert resp.status_code == 200
    chunks = resp.json()
    assert chunks[0]["query_id"] == "article-qid-001"
    assert chunks[0]["content_type"] == "text/html"

    await mock_client.aclose()


# ---------------------------------------------------------------------------
# POST /request — deduplication
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_post_request_dedup_returns_cached() -> None:
    body = _make_request_body("dedup test", "dedup-qid-001")
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        resp1 = await ac.post("/request", json=body)
        resp2 = await ac.post("/request", json=body)

    assert resp1.status_code == 200
    assert resp2.status_code == 200
    assert resp1.json() == resp2.json()


# ---------------------------------------------------------------------------
# POST /request — kiwix errors
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_post_request_kiwix_404_returns_502() -> None:
    def not_found_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404)

    mock_client = httpx.AsyncClient(
        transport=httpx.MockTransport(not_found_handler), base_url="http://kiwix"
    )
    app.state.kiwix_client = mock_client

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        resp = await ac.post(
            "/request", json=_make_request_body("nonexistent", "err-qid-001")
        )

    assert resp.status_code == 502
    assert "kiwix-serve error" in resp.json()["detail"]

    await mock_client.aclose()


@pytest.mark.asyncio
async def test_post_request_retry_after_kiwix_failure() -> None:
    """A retry of the same query_id after a kiwix failure must not 500."""
    call_count = 0

    def flaky_handler(request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        if call_count == 1:
            return httpx.Response(500)
        return httpx.Response(200, content=SEARCH_HTML, headers={"content-type": "text/html"})

    mock_client = httpx.AsyncClient(
        transport=httpx.MockTransport(flaky_handler), base_url="http://kiwix"
    )
    app.state.kiwix_client = mock_client

    body = _make_request_body("retry me", "retry-qid-001")
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        resp1 = await ac.post("/request", json=body)
        assert resp1.status_code == 502

        resp2 = await ac.post("/request", json=body)
        assert resp2.status_code == 200
        chunks = resp2.json()
        assert chunks[0]["query_id"] == "retry-qid-001"
        assert chunks[0]["signature"] is not None

    await mock_client.aclose()


@pytest.mark.asyncio
async def test_post_request_kiwix_unreachable_returns_502() -> None:
    mock_fetch = AsyncMock(side_effect=httpx.RequestError("Connection refused"))

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        with patch("main.fetch_from_kiwix", mock_fetch):
            resp = await ac.post(
                "/request", json=_make_request_body("anything", "err-qid-002")
            )

    assert resp.status_code == 502
    assert "unreachable" in resp.json()["detail"]


# ---------------------------------------------------------------------------
# GET /pending
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_pending_empty() -> None:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        resp = await ac.get("/pending")
    assert resp.status_code == 200
    assert resp.json() == {"pending_query_ids": []}


@pytest.mark.asyncio
async def test_get_pending_after_failed_request() -> None:
    """Kiwix failure rolls back inbound + seen_bundle_ids — no phantom pending row."""

    def error_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500)

    mock_client = httpx.AsyncClient(
        transport=httpx.MockTransport(error_handler), base_url="http://kiwix"
    )
    app.state.kiwix_client = mock_client

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        post = await ac.post("/request", json=_make_request_body("fail", "pending-qid-001"))
        assert post.status_code == 502
        resp = await ac.get("/pending")

    assert "pending-qid-001" not in resp.json()["pending_query_ids"]

    await mock_client.aclose()


# ---------------------------------------------------------------------------
# GET /bundle/{query_id}
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_get_bundle_not_found() -> None:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        resp = await ac.get("/bundle/nonexistent-qid")
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_get_bundle_returns_stored_chunks() -> None:
    body = _make_request_body("stored test", "stored-qid-001")
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        await ac.post("/request", json=body)
        resp = await ac.get("/bundle/stored-qid-001")

    assert resp.status_code == 200
    chunks = resp.json()
    assert len(chunks) >= 1
    assert chunks[0]["query_id"] == "stored-qid-001"
    assert chunks[0]["signature"] is not None


# ---------------------------------------------------------------------------
# POST /request — chunk atomicity
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_chunk_insertion_is_atomic() -> None:
    """A DB error on chunk N must roll back chunks 0..N-1 so no partial set is committed.

    Verified by: checking that GET /bundle/{query_id} returns 404 after the 500, meaning
    no chunks leaked to the database.
    """
    # ~210 KiB → 3 chunks at the default 100 KiB chunk size
    large_html = b"<html>" + b"x" * (210 * 1024) + b"</html>"

    def big_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=large_html, headers={"content-type": "text/html"})

    mock_client = httpx.AsyncClient(
        transport=httpx.MockTransport(big_handler), base_url="http://kiwix"
    )
    app.state.kiwix_client = mock_client

    call_count = 0
    original_insert = database.insert_outbound_bundle

    async def fail_on_second_chunk(db, bundle, created_at):
        nonlocal call_count
        call_count += 1
        if call_count == 2:
            raise aiosqlite.IntegrityError("simulated mid-batch failure")
        await original_insert(db, bundle, created_at=created_at)

    body = _make_request_body("big article", "atomic-qid-001")
    transport = httpx.ASGITransport(app=app)

    with patch("main.insert_outbound_bundle", fail_on_second_chunk):
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
            resp = await ac.post("/request", json=body)

    assert resp.status_code == 500

    # Roll back must include outbound chunks, inbound request, and seen ids — not only chunks.
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        bundle_resp = await ac.get("/bundle/atomic-qid-001")
        pending = await ac.get("/pending")
    assert bundle_resp.status_code == 404
    assert "atomic-qid-001" not in pending.json()["pending_query_ids"]

    await mock_client.aclose()
