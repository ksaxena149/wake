from fastapi import FastAPI

app = FastAPI(
    title="WAKE Gateway Daemon",
    version="0.1.0",
    description="Wireless Asynchronous Knowledge Exchange — server daemon",
)


@app.get("/health")
async def health_check():
    return {"status": "ok"}
