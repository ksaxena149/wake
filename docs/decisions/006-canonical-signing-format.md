# ADR-006: Canonical JSON format for Ed25519 bundle signatures

Status: Accepted
Date: 2026-03-28

## Context

The plan specified Ed25519 signing of WAKE bundles via PyNaCl but did not define what bytes are signed. Without a precise canonical form, any two independent implementations (server in Python, Android verifier in Kotlin/Tink) will produce incompatible signatures.

## Decision

The bytes signed for any bundle (both `RequestBundle` and `ResponseBundle`) are produced by:

1. Call `bundle.model_dump(exclude={"signature"})` to get all fields except `signature`.
2. Serialize to JSON with `sort_keys=True` and compact separators `(",", ":")` — no spaces.
3. Encode the resulting string as UTF-8.

In Python (`signing.py`):

```python
data = bundle.model_dump(exclude={"signature"})
canonical = json.dumps(data, sort_keys=True, separators=(",", ":")).encode()
```

The Ed25519 signature is then base64-encoded (standard alphabet) and stored in the `signature` field.

## Consequences

Any verifier (Android, relay, test harness) must reconstruct the exact same canonical bytes to verify a signature. Specifically:

- Field order does not matter (sort_keys ensures determinism).
- The `signature` field is excluded from the signed payload; including it would create a circular dependency.
- Whitespace in the JSON output must be absent (compact separators).
- The character encoding must be UTF-8.

Android implementation of `verify_bundle` in Phase 2 must follow this exact scheme. A mismatch in any of these points will produce a `BadSignatureError` at verification time.
