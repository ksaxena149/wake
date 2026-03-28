import time
from pathlib import Path

import aiosqlite
import pytest

from database import (
    get_db,
    init_db,
    insert_inbound_request,
    insert_outbound_bundle,
    is_seen,
    list_pending_query_ids,
    mark_request_done,
    mark_seen,
    get_outbound_bundles,
)
from models import RequestBundle, ResponseBundle


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture
def db_path(tmp_path: Path) -> Path:
    return tmp_path / "test_wake.db"


@pytest.fixture
def now() -> int:
    return int(time.time())


@pytest.fixture
def sample_request(now: int) -> RequestBundle:
    return RequestBundle(
        node_id="550e8400-e29b-41d4-a716-446655440000",
        query_id="6ba7b810-9dad-11d1-80b4-00c04fd430c8",
        query_string="water cycle",
        timestamp=now,
        ttl_seconds=3600,
        hop_count=0,
    )


@pytest.fixture
def sample_bundle() -> ResponseBundle:
    return ResponseBundle(
        server_id="wake-server-01",
        query_id="6ba7b810-9dad-11d1-80b4-00c04fd430c8",
        chunk_index=0,
        total_chunks=1,
        content_type="text/html; charset=utf-8",
        payload_b64="PGh0bWw+SGVsbG88L2h0bWw+",
        sha256="a" * 64,
    )


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_init_db_creates_tables(db_path: Path) -> None:
    await init_db(db_path)
    async with aiosqlite.connect(db_path) as db:
        async with db.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        ) as cursor:
            tables = {row[0] for row in await cursor.fetchall()}
    assert tables == {"inbound_requests", "outbound_bundles", "seen_bundle_ids"}


@pytest.mark.asyncio
async def test_insert_and_list_pending(
    db_path: Path, sample_request: RequestBundle, now: int
) -> None:
    await init_db(db_path)
    async with aiosqlite.connect(db_path) as db:
        db.row_factory = aiosqlite.Row
        await insert_inbound_request(db, sample_request, received_at=now)
        pending = await list_pending_query_ids(db)
    assert sample_request.query_id in pending


@pytest.mark.asyncio
async def test_mark_request_done_removes_from_pending(
    db_path: Path, sample_request: RequestBundle, now: int
) -> None:
    await init_db(db_path)
    async with aiosqlite.connect(db_path) as db:
        db.row_factory = aiosqlite.Row
        await insert_inbound_request(db, sample_request, received_at=now)
        await mark_request_done(db, sample_request.query_id)
        pending = await list_pending_query_ids(db)
    assert sample_request.query_id not in pending


@pytest.mark.asyncio
async def test_seen_deduplication(db_path: Path, now: int) -> None:
    query_id = "test-query-id-abc"
    await init_db(db_path)
    async with aiosqlite.connect(db_path) as db:
        db.row_factory = aiosqlite.Row
        assert not await is_seen(db, query_id)
        await mark_seen(db, query_id, seen_at=now)
        assert await is_seen(db, query_id)


@pytest.mark.asyncio
async def test_insert_outbound_bundle(
    db_path: Path, sample_bundle: ResponseBundle, now: int
) -> None:
    await init_db(db_path)
    async with aiosqlite.connect(db_path) as db:
        db.row_factory = aiosqlite.Row
        await insert_outbound_bundle(db, sample_bundle, created_at=now)
        chunks = await get_outbound_bundles(db, sample_bundle.query_id)
    assert len(chunks) == 1
    assert chunks[0].chunk_index == 0
    assert chunks[0].payload_b64 == sample_bundle.payload_b64


@pytest.mark.asyncio
async def test_duplicate_chunk_raises(
    db_path: Path, sample_bundle: ResponseBundle, now: int
) -> None:
    await init_db(db_path)
    async with aiosqlite.connect(db_path) as db:
        db.row_factory = aiosqlite.Row
        await insert_outbound_bundle(db, sample_bundle, created_at=now)
        with pytest.raises(aiosqlite.IntegrityError):
            await insert_outbound_bundle(db, sample_bundle, created_at=now)


@pytest.mark.asyncio
async def test_insert_outbound_bundle_does_not_auto_commit(
    db_path: Path, sample_bundle: ResponseBundle, now: int
) -> None:
    """insert_outbound_bundle must leave the transaction open so callers can batch atomically.

    If it auto-committed, the rollback below would be a no-op and a second connection
    would still see the row.  With the correct behaviour the row is rolled back and the
    second connection sees nothing.
    """
    await init_db(db_path)
    async with aiosqlite.connect(db_path) as conn:
        conn.row_factory = aiosqlite.Row
        await insert_outbound_bundle(conn, sample_bundle, created_at=now)
        await conn.rollback()  # simulate a mid-batch failure

    async with aiosqlite.connect(db_path) as conn2:
        conn2.row_factory = aiosqlite.Row
        chunks = await get_outbound_bundles(conn2, sample_bundle.query_id)
    assert len(chunks) == 0


@pytest.mark.asyncio
async def test_get_outbound_bundles_returns_pydantic_models(
    db_path: Path, sample_bundle: ResponseBundle, now: int
) -> None:
    await init_db(db_path)
    async with aiosqlite.connect(db_path) as db:
        db.row_factory = aiosqlite.Row
        await insert_outbound_bundle(db, sample_bundle, created_at=now)
        chunks = await get_outbound_bundles(db, sample_bundle.query_id)
    assert len(chunks) == 1
    assert isinstance(chunks[0], ResponseBundle)
    assert chunks[0].server_id == "wake-server-01"
    assert chunks[0].sha256 == "a" * 64
