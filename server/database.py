import logging
from collections.abc import AsyncGenerator
from pathlib import Path
from typing import Optional

import aiosqlite

from models import RequestBundle, ResponseBundle

logger = logging.getLogger(__name__)

_db_path: Optional[Path] = None

_DDL = """
CREATE TABLE IF NOT EXISTS inbound_requests (
    query_id     TEXT    PRIMARY KEY,
    node_id      TEXT    NOT NULL,
    query_string TEXT    NOT NULL,
    timestamp    INTEGER NOT NULL,
    ttl_seconds  INTEGER NOT NULL,
    hop_count    INTEGER NOT NULL,
    signature    TEXT,
    received_at  INTEGER NOT NULL,
    status       TEXT    NOT NULL DEFAULT 'pending'
);

CREATE TABLE IF NOT EXISTS outbound_bundles (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    server_id    TEXT    NOT NULL,
    query_id     TEXT    NOT NULL,
    chunk_index  INTEGER NOT NULL,
    total_chunks INTEGER NOT NULL,
    content_type TEXT    NOT NULL,
    payload_b64  TEXT    NOT NULL,
    sha256       TEXT    NOT NULL,
    signature    TEXT,
    created_at   INTEGER NOT NULL,
    UNIQUE (query_id, chunk_index)
);

CREATE TABLE IF NOT EXISTS seen_bundle_ids (
    query_id TEXT    PRIMARY KEY,
    seen_at  INTEGER NOT NULL
);
"""


async def init_db(path: Path) -> None:
    """Create all tables and record the DB path for subsequent connections."""
    global _db_path
    _db_path = path
    async with aiosqlite.connect(path) as db:
        await db.executescript(_DDL)
        await db.commit()
    logger.info("Database initialised at %s", path)


async def get_db() -> AsyncGenerator[aiosqlite.Connection, None]:
    """Async generator yielding a connection; use as a FastAPI Depends."""
    if _db_path is None:
        raise RuntimeError("init_db() must be called before get_db()")
    async with aiosqlite.connect(_db_path) as db:
        db.row_factory = aiosqlite.Row
        yield db


# ---------------------------------------------------------------------------
# inbound_requests
# ---------------------------------------------------------------------------


async def insert_inbound_request(
    db: aiosqlite.Connection,
    bundle: RequestBundle,
    received_at: int,
) -> None:
    await db.execute(
        """
        INSERT INTO inbound_requests
            (query_id, node_id, query_string, timestamp, ttl_seconds,
             hop_count, signature, received_at, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending')
        """,
        (
            bundle.query_id,
            bundle.node_id,
            bundle.query_string,
            bundle.timestamp,
            bundle.ttl_seconds,
            bundle.hop_count,
            bundle.signature,
            received_at,
        ),
    )
    await db.commit()


async def list_pending_query_ids(db: aiosqlite.Connection) -> list[str]:
    async with db.execute(
        "SELECT query_id FROM inbound_requests WHERE status = 'pending'"
    ) as cursor:
        rows = await cursor.fetchall()
    return [row["query_id"] for row in rows]


async def mark_request_done(db: aiosqlite.Connection, query_id: str) -> None:
    await db.execute(
        "UPDATE inbound_requests SET status = 'done' WHERE query_id = ?",
        (query_id,),
    )
    await db.commit()


# ---------------------------------------------------------------------------
# seen_bundle_ids
# ---------------------------------------------------------------------------


async def is_seen(db: aiosqlite.Connection, query_id: str) -> bool:
    async with db.execute(
        "SELECT 1 FROM seen_bundle_ids WHERE query_id = ?", (query_id,)
    ) as cursor:
        return await cursor.fetchone() is not None


async def mark_seen(db: aiosqlite.Connection, query_id: str, seen_at: int) -> None:
    await db.execute(
        "INSERT OR IGNORE INTO seen_bundle_ids (query_id, seen_at) VALUES (?, ?)",
        (query_id, seen_at),
    )
    await db.commit()


# ---------------------------------------------------------------------------
# outbound_bundles
# ---------------------------------------------------------------------------


async def insert_outbound_bundle(
    db: aiosqlite.Connection,
    bundle: ResponseBundle,
    created_at: int,
) -> None:
    await db.execute(
        """
        INSERT INTO outbound_bundles
            (server_id, query_id, chunk_index, total_chunks, content_type,
             payload_b64, sha256, signature, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            bundle.server_id,
            bundle.query_id,
            bundle.chunk_index,
            bundle.total_chunks,
            bundle.content_type,
            bundle.payload_b64,
            bundle.sha256,
            bundle.signature,
            created_at,
        ),
    )
    await db.commit()


async def get_outbound_bundles(
    db: aiosqlite.Connection, query_id: str
) -> list[ResponseBundle]:
    async with db.execute(
        """
        SELECT server_id, query_id, chunk_index, total_chunks, content_type,
               payload_b64, sha256, signature
        FROM outbound_bundles
        WHERE query_id = ?
        ORDER BY chunk_index
        """,
        (query_id,),
    ) as cursor:
        rows = await cursor.fetchall()
    return [ResponseBundle(**dict(row)) for row in rows]
