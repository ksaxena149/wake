import base64
from contextlib import asynccontextmanager
from pathlib import Path

import nacl.signing
from fastapi import FastAPI

from database import init_db
from signing import generate_or_load_signing_key

DB_PATH = Path(__file__).parent / "wake.db"
KEY_PATH = Path(__file__).parent / "wake_signing.key"

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
