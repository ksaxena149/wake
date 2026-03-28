import base64
import json
import logging
from pathlib import Path
from typing import Union

import nacl.exceptions
import nacl.signing

from models import RequestBundle, ResponseBundle

logger = logging.getLogger(__name__)

BundleT = Union[RequestBundle, ResponseBundle]


def generate_or_load_signing_key(path: Path) -> nacl.signing.SigningKey:
    """Return the server's Ed25519 signing key, generating and persisting it if absent."""
    if path.exists():
        seed = path.read_bytes()
        key = nacl.signing.SigningKey(seed)
        logger.info("Loaded signing key from %s", path)
    else:
        key = nacl.signing.SigningKey.generate()
        path.write_bytes(bytes(key))
        logger.info("Generated new signing key at %s", path)
    return key


def _canonical_bytes(bundle: BundleT) -> bytes:
    """Serialize the bundle fields (excluding `signature`) to deterministic UTF-8 JSON."""
    data = bundle.model_dump(exclude={"signature"})
    return json.dumps(data, sort_keys=True, separators=(",", ":")).encode()


def sign_bundle(key: nacl.signing.SigningKey, bundle: BundleT) -> str:
    """Return a base64-encoded Ed25519 signature over the bundle's canonical fields."""
    signed = key.sign(_canonical_bytes(bundle))
    return base64.b64encode(signed.signature).decode()


def verify_bundle(verify_key: nacl.signing.VerifyKey, bundle: BundleT) -> bool:
    """Return True iff bundle.signature is a valid Ed25519 signature for this bundle."""
    if bundle.signature is None:
        return False
    try:
        sig_bytes = base64.b64decode(bundle.signature)
        verify_key.verify(_canonical_bytes(bundle), sig_bytes)
        return True
    except nacl.exceptions.BadSignatureError:
        return False
