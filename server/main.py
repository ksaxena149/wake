import base64
import logging
import time
from contextlib import asynccontextmanager
from pathlib import Path

import httpx
import nacl.signing
from fastapi import Depends, FastAPI, HTTPException, Request

from chunker import chunk_payload
from database import (
    get_db,
    get_outbound_bundles,
    init_db,
    insert_inbound_request,
    insert_outbound_bundle,
    list_pending_query_ids,
    mark_request_done,
    mark_seen,
)
from kiwix_proxy import fetch_from_kiwix
from models import RequestBundle, ResponseBundle
from signing import generate_or_load_signing_key, sign_bundle

logger = logging.getLogger(__name__)

DB_PATH = Path(__file__).parent / "wake.db"
KEY_PATH = Path(__file__).parent / "wake_signing.key"
KIWIX_URL = "http://localhost:8888"
SERVER_ID = "wake-server-01"

_signing_key: nacl.signing.SigningKey | None = None


def get_signing_key() -> nacl.signing.SigningKey:
    if _signing_key is None:
        raise RuntimeError("Signing key not initialised — lifespan did not run")
    return _signing_key


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _signing_key
    await init_db(DB_PATH)
    _signing_key = generate_or_load_signing_key(KEY_PATH)
    async with httpx.AsyncClient(base_url=KIWIX_URL, timeout=30.0) as client:
        app.state.kiwix_client = client
        yield


app = FastAPI(
    title="WAKE Gateway Daemon",
    version="0.1.0",
    description="Wireless Asynchronous Knowledge Exchange — server daemon",
    lifespan=lifespan,
)


@app.get("/health")
async def health_check():
    return {"status": "ok"}


@app.get("/pubkey")
async def get_pubkey():
    """Return the server's Ed25519 verify key as base64."""
    key = get_signing_key()
    pub_b64 = base64.b64encode(bytes(key.verify_key)).decode()
    return {"pubkey_b64": pub_b64}


@app.post("/request")
async def submit_request(
    bundle: RequestBundle,
    request: Request,
    db=Depends(get_db),
) -> list[dict]:
    """Accept a RequestBundle, proxy to kiwix-serve, return signed response chunks."""
    existing = await get_outbound_bundles(db, bundle.query_id)
    if existing:
        return [b.model_dump() for b in existing]

    now = int(time.time())
    await insert_inbound_request(db, bundle, received_at=now)
    await mark_seen(db, bundle.query_id, seen_at=now)

    try:
        payload_bytes, content_type = await fetch_from_kiwix(
            request.app.state.kiwix_client, bundle.query_string
        )
    except httpx.HTTPStatusError as exc:
        logger.error("kiwix-serve returned %s for query %s", exc.response.status_code, bundle.query_id)
        raise HTTPException(status_code=502, detail=f"kiwix-serve error: {exc.response.status_code}")
    except httpx.RequestError as exc:
        logger.error("Cannot reach kiwix-serve: %s", exc)
        raise HTTPException(status_code=502, detail="kiwix-serve unreachable")

    chunks = chunk_payload(payload_bytes, content_type, bundle.query_id, SERVER_ID)

    key = get_signing_key()
    signed_chunks: list[ResponseBundle] = []
    try:
        for chunk in chunks:
            sig = sign_bundle(key, chunk)
            signed = chunk.model_copy(update={"signature": sig})
            await insert_outbound_bundle(db, signed, created_at=now)
            signed_chunks.append(signed)
        await db.commit()
    except Exception:
        await db.rollback()
        logger.exception("Failed to store outbound chunks for query_id=%s", bundle.query_id)
        raise HTTPException(status_code=500, detail="Failed to store response chunks")

    await mark_request_done(db, bundle.query_id)

    return [c.model_dump() for c in signed_chunks]


@app.get("/pending")
async def list_pending(db=Depends(get_db)):
    """Return query IDs of requests that have not yet been fulfilled."""
    return {"pending_query_ids": await list_pending_query_ids(db)}


@app.get("/bundle/{query_id}")
async def get_bundle(query_id: str, db=Depends(get_db)) -> list[dict]:
    """Return stored outbound response bundles for a given query_id."""
    bundles = await get_outbound_bundles(db, query_id)
    if not bundles:
        raise HTTPException(status_code=404, detail=f"No bundles found for query_id={query_id}")
    return [b.model_dump() for b in bundles]
