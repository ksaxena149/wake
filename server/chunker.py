import base64
import hashlib

from models import ResponseBundle

CHUNK_SIZE = 100 * 1024  # 100 KiB of raw bytes per chunk


def chunk_payload(
    payload: bytes,
    content_type: str,
    query_id: str,
    server_id: str,
    chunk_size: int = CHUNK_SIZE,
) -> list[ResponseBundle]:
    """Split payload bytes into ResponseBundle chunks of at most chunk_size bytes each.

    SHA-256 is computed on the raw bytes of each chunk before base64 encoding,
    matching the ResponseBundle.sha256 field semantics ("decoded chunk payload").
    """
    if not payload:
        return [
            ResponseBundle(
                server_id=server_id,
                query_id=query_id,
                chunk_index=0,
                total_chunks=1,
                content_type=content_type,
                payload_b64=base64.b64encode(b"").decode(),
                sha256=hashlib.sha256(b"").hexdigest(),
            )
        ]

    raw_chunks = [payload[i : i + chunk_size] for i in range(0, len(payload), chunk_size)]
    total = len(raw_chunks)

    return [
        ResponseBundle(
            server_id=server_id,
            query_id=query_id,
            chunk_index=idx,
            total_chunks=total,
            content_type=content_type,
            payload_b64=base64.b64encode(raw).decode(),
            sha256=hashlib.sha256(raw).hexdigest(),
        )
        for idx, raw in enumerate(raw_chunks)
    ]
