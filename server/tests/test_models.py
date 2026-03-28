import time

import pytest
from pydantic import ValidationError

from models import RequestBundle, ResponseBundle


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture
def valid_request_data() -> dict:
    return {
        "node_id": "550e8400-e29b-41d4-a716-446655440000",
        "query_id": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
        "query_string": "water cycle",
        "timestamp": int(time.time()),
        "ttl_seconds": 3600,
        "hop_count": 0,
    }


@pytest.fixture
def valid_response_data() -> dict:
    return {
        "server_id": "wake-server-01",
        "query_id": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
        "chunk_index": 0,
        "total_chunks": 3,
        "content_type": "text/html; charset=utf-8",
        "payload_b64": "PGh0bWw+SGVsbG88L2h0bWw+",
        "sha256": "a" * 64,
    }


# ---------------------------------------------------------------------------
# RequestBundle tests
# ---------------------------------------------------------------------------


def test_request_bundle_valid(valid_request_data: dict) -> None:
    bundle = RequestBundle(**valid_request_data)
    dumped = bundle.model_dump()
    assert dumped["node_id"] == valid_request_data["node_id"]
    assert dumped["query_string"] == "water cycle"
    assert dumped["hop_count"] == 0
    assert dumped["signature"] is None


def test_request_bundle_signature_defaults_none(valid_request_data: dict) -> None:
    bundle = RequestBundle(**valid_request_data)
    assert bundle.signature is None


def test_request_bundle_invalid_hop_count(valid_request_data: dict) -> None:
    with pytest.raises(ValidationError):
        RequestBundle(**{**valid_request_data, "hop_count": -1})


def test_request_bundle_invalid_ttl(valid_request_data: dict) -> None:
    with pytest.raises(ValidationError):
        RequestBundle(**{**valid_request_data, "ttl_seconds": 0})


# ---------------------------------------------------------------------------
# ResponseBundle tests
# ---------------------------------------------------------------------------


def test_response_bundle_valid(valid_response_data: dict) -> None:
    bundle = ResponseBundle(**valid_response_data)
    dumped = bundle.model_dump()
    assert dumped["server_id"] == "wake-server-01"
    assert dumped["chunk_index"] == 0
    assert dumped["total_chunks"] == 3
    assert dumped["signature"] is None


def test_response_bundle_signature_defaults_none(valid_response_data: dict) -> None:
    bundle = ResponseBundle(**valid_response_data)
    assert bundle.signature is None


def test_response_bundle_invalid_chunk_index(valid_response_data: dict) -> None:
    with pytest.raises(ValidationError):
        ResponseBundle(**{**valid_response_data, "chunk_index": -1})


def test_response_bundle_invalid_total_chunks(valid_response_data: dict) -> None:
    with pytest.raises(ValidationError):
        ResponseBundle(**{**valid_response_data, "total_chunks": 0})
