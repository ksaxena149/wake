from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI

from database import init_db

DB_PATH = Path(__file__).parent / "wake.db"


@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db(DB_PATH)
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
