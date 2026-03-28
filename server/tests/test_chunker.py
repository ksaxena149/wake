import base64
import hashlib

from chunker import CHUNK_SIZE, chunk_payload
from models import ResponseBundle

SERVER_ID = "wake-server-01"
QUERY_ID = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
CONTENT_TYPE = "text/html; charset=utf-8"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_chunks(payload: bytes, chunk_size: int = CHUNK_SIZE) -> list[ResponseBundle]:
    return chunk_payload(
        payload=payload,
        content_type=CONTENT_TYPE,
        query_id=QUERY_ID,
        server_id=SERVER_ID,
        chunk_size=chunk_size,
    )


def _decode(bundle: ResponseBundle) -> bytes:
    return base64.b64decode(bundle.payload_b64)


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


def test_single_chunk_small_payload() -> None:
    payload = b"hello world"
    bundles = _make_chunks(payload)
    assert len(bundles) == 1
    assert bundles[0].chunk_index == 0
    assert bundles[0].total_chunks == 1


def test_multiple_chunks() -> None:
    # 2.5 × CHUNK_SIZE → 3 bundles
    payload = b"x" * int(CHUNK_SIZE * 2.5)
    bundles = _make_chunks(payload)
    assert len(bundles) == 3
    assert [b.chunk_index for b in bundles] == [0, 1, 2]
    assert all(b.total_chunks == 3 for b in bundles)


def test_exact_multiple() -> None:
    # Exactly 2 × CHUNK_SIZE → 2 bundles, no empty trailing chunk
    payload = b"y" * (CHUNK_SIZE * 2)
    bundles = _make_chunks(payload)
    assert len(bundles) == 2
    assert bundles[0].total_chunks == 2
    assert bundles[1].total_chunks == 2


def test_sha256_integrity() -> None:
    payload = b"integrity check " * 500
    bundles = _make_chunks(payload, chunk_size=512)
    for bundle in bundles:
        raw = _decode(bundle)
        assert hashlib.sha256(raw).hexdigest() == bundle.sha256


def test_reassembly() -> None:
    payload = bytes(range(256)) * 600  # 153 600 bytes → spans multiple 100 KiB chunks
    bundles = _make_chunks(payload)
    reassembled = b"".join(_decode(b) for b in sorted(bundles, key=lambda b: b.chunk_index))
    assert reassembled == payload


def test_empty_payload() -> None:
    bundles = _make_chunks(b"")
    assert len(bundles) == 1
    assert bundles[0].chunk_index == 0
    assert bundles[0].total_chunks == 1
    assert _decode(bundles[0]) == b""
    assert bundles[0].sha256 == hashlib.sha256(b"").hexdigest()


def test_metadata_propagation() -> None:
    payload = b"data" * 1000
    bundles = _make_chunks(payload, chunk_size=512)
    for bundle in bundles:
        assert bundle.server_id == SERVER_ID
        assert bundle.query_id == QUERY_ID
        assert bundle.content_type == CONTENT_TYPE
