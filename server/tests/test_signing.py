import time
from pathlib import Path

import nacl.signing
import pytest

from models import RequestBundle, ResponseBundle
from signing import (
    generate_or_load_signing_key,
    sign_bundle,
    verify_bundle,
)

# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture
def signing_key() -> nacl.signing.SigningKey:
    return nacl.signing.SigningKey.generate()


@pytest.fixture
def verify_key(signing_key: nacl.signing.SigningKey) -> nacl.signing.VerifyKey:
    return signing_key.verify_key


@pytest.fixture
def sample_request() -> RequestBundle:
    return RequestBundle(
        node_id="550e8400-e29b-41d4-a716-446655440000",
        query_id="6ba7b810-9dad-11d1-80b4-00c04fd430c8",
        query_string="water cycle",
        timestamp=int(time.time()),
        ttl_seconds=3600,
        hop_count=2,
    )


@pytest.fixture
def sample_response() -> ResponseBundle:
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
# generate_or_load_signing_key tests
# ---------------------------------------------------------------------------


def test_generates_key_when_absent(tmp_path: Path) -> None:
    key_path = tmp_path / "test.key"
    assert not key_path.exists()
    key = generate_or_load_signing_key(key_path)
    assert key_path.exists()
    assert isinstance(key, nacl.signing.SigningKey)


def test_loads_same_key_on_second_call(tmp_path: Path) -> None:
    key_path = tmp_path / "test.key"
    key1 = generate_or_load_signing_key(key_path)
    key2 = generate_or_load_signing_key(key_path)
    assert bytes(key1) == bytes(key2)


def test_different_paths_produce_different_keys(tmp_path: Path) -> None:
    key1 = generate_or_load_signing_key(tmp_path / "a.key")
    key2 = generate_or_load_signing_key(tmp_path / "b.key")
    assert bytes(key1) != bytes(key2)


# ---------------------------------------------------------------------------
# sign_bundle / verify_bundle — RequestBundle
# ---------------------------------------------------------------------------


def test_sign_and_verify_request_bundle(
    signing_key: nacl.signing.SigningKey,
    verify_key: nacl.signing.VerifyKey,
    sample_request: RequestBundle,
) -> None:
    sig = sign_bundle(signing_key, sample_request)
    signed = sample_request.model_copy(update={"signature": sig})
    assert verify_bundle(verify_key, signed)


def test_verify_fails_wrong_key_request(
    signing_key: nacl.signing.SigningKey,
    sample_request: RequestBundle,
) -> None:
    sig = sign_bundle(signing_key, sample_request)
    signed = sample_request.model_copy(update={"signature": sig})
    other_verify_key = nacl.signing.SigningKey.generate().verify_key
    assert not verify_bundle(other_verify_key, signed)


def test_verify_fails_tampered_request(
    signing_key: nacl.signing.SigningKey,
    verify_key: nacl.signing.VerifyKey,
    sample_request: RequestBundle,
) -> None:
    sig = sign_bundle(signing_key, sample_request)
    # Change a field after signing
    tampered = sample_request.model_copy(update={"query_string": "malicious", "signature": sig})
    assert not verify_bundle(verify_key, tampered)


# ---------------------------------------------------------------------------
# sign_bundle / verify_bundle — ResponseBundle
# ---------------------------------------------------------------------------


def test_sign_and_verify_response_bundle(
    signing_key: nacl.signing.SigningKey,
    verify_key: nacl.signing.VerifyKey,
    sample_response: ResponseBundle,
) -> None:
    sig = sign_bundle(signing_key, sample_response)
    signed = sample_response.model_copy(update={"signature": sig})
    assert verify_bundle(verify_key, signed)


def test_verify_fails_wrong_key_response(
    signing_key: nacl.signing.SigningKey,
    sample_response: ResponseBundle,
) -> None:
    sig = sign_bundle(signing_key, sample_response)
    signed = sample_response.model_copy(update={"signature": sig})
    other_verify_key = nacl.signing.SigningKey.generate().verify_key
    assert not verify_bundle(other_verify_key, signed)


def test_verify_fails_tampered_response(
    signing_key: nacl.signing.SigningKey,
    verify_key: nacl.signing.VerifyKey,
    sample_response: ResponseBundle,
) -> None:
    sig = sign_bundle(signing_key, sample_response)
    tampered = sample_response.model_copy(update={"payload_b64": "dGFtcGVyZWQ=", "signature": sig})
    assert not verify_bundle(verify_key, tampered)


# ---------------------------------------------------------------------------
# unsigned bundle
# ---------------------------------------------------------------------------


def test_verify_returns_false_for_unsigned_bundle(
    verify_key: nacl.signing.VerifyKey,
    sample_request: RequestBundle,
) -> None:
    assert sample_request.signature is None
    assert not verify_bundle(verify_key, sample_request)
