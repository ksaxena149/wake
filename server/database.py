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
    bundle_id TEXT    PRIMARY KEY,
    seen_at  INTEGER NOT NULL
);
"""


def bundle_id_for_request(query_id: str) -> str:
    """Dedup key for a request bundle (matches Android REQUEST bundleId)."""
    return query_id


def bundle_id_for_response_chunk(query_id: str, chunk_index: int) -> str:
    """Dedup key for one response chunk (matches Android RESPONSE bundleId)."""
    return f"{query_id}:{chunk_index}"


async def _migrate_seen_bundle_ids_schema(db: aiosqlite.Connection) -> None:
    """Rename query_id → bundle_id on existing DBs (pre–issue #13 used query_id as PK)."""
    async with db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='seen_bundle_ids'"
    ) as cursor:
        if await cursor.fetchone() is None:
            return
    async with db.execute("PRAGMA table_info(seen_bundle_ids)") as cursor:
        columns = {row[1] for row in await cursor.fetchall()}
    if "bundle_id" in columns:
        return
    if "query_id" not in columns:
        return
    await db.executescript(
        """
        BEGIN;
        CREATE TABLE seen_bundle_ids_new (
            bundle_id TEXT    PRIMARY KEY,
            seen_at   INTEGER NOT NULL
        );
        INSERT INTO seen_bundle_ids_new (bundle_id, seen_at)
            SELECT query_id, seen_at FROM seen_bundle_ids;
        DROP TABLE seen_bundle_ids;
        ALTER TABLE seen_bundle_ids_new RENAME TO seen_bundle_ids;
        COMMIT;
        """
    )


async def init_db(path: Path) -> None:
    """Create all tables and record the DB path for subsequent connections."""
    global _db_path
    _db_path = path
    async with aiosqlite.connect(path) as db:
        await db.executescript(_DDL)
        await _migrate_seen_bundle_ids_schema(db)
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
    """Caller must commit (or roll back) the surrounding transaction."""
    await db.execute(
        """
        INSERT OR IGNORE INTO inbound_requests
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


async def is_seen(db: aiosqlite.Connection, bundle_id: str) -> bool:
    async with db.execute(
        "SELECT 1 FROM seen_bundle_ids WHERE bundle_id = ?", (bundle_id,)
    ) as cursor:
        return await cursor.fetchone() is not None


async def mark_seen(db: aiosqlite.Connection, bundle_id: str, seen_at: int) -> None:
    """Caller must commit (or roll back) the surrounding transaction."""
    await db.execute(
        "INSERT OR IGNORE INTO seen_bundle_ids (bundle_id, seen_at) VALUES (?, ?)",
        (bundle_id, seen_at),
    )


async def insert_seen_ignore_many(
    db: aiosqlite.Connection, entries: list[tuple[str, int]]
) -> None:
    """Append seen_bundle_ids rows without committing (caller owns transaction)."""
    await db.executemany(
        "INSERT OR IGNORE INTO seen_bundle_ids (bundle_id, seen_at) VALUES (?, ?)",
        entries,
    )


# ---------------------------------------------------------------------------
# outbound_bundles
# ---------------------------------------------------------------------------


async def insert_outbound_bundle(
    db: aiosqlite.Connection,
    bundle: ResponseBundle,
    created_at: int,
) -> None:
    """Execute the INSERT without committing. Caller is responsible for committing
    (or rolling back) so that multi-chunk batches are stored atomically."""
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
