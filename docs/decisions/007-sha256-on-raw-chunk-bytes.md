# ADR-007: Per-chunk SHA-256 is computed on raw bytes before base64 encoding

Status: Accepted
Date: 2026-03-28

## Context

The `ResponseBundle` schema includes a `sha256` field described as "hex SHA-256 of the decoded chunk payload". The plan did not define whether "decoded" meant decoded from base64 (raw bytes) or whether the hash covered the base64 string itself. The distinction matters for Android's chunk integrity check during reassembly.

## Decision

The SHA-256 in `sha256` is computed on the raw byte slice of the chunk **before** it is base64-encoded. In `chunker.py`:

```python
sha256=hashlib.sha256(raw).hexdigest()
payload_b64=base64.b64encode(raw).decode()
```

The value stored and transmitted in `payload_b64` is the base64 encoding of the same `raw` bytes.

## Consequences

To verify chunk integrity, a receiver must:

1. Base64-decode `payload_b64` to get raw bytes.
2. Compute `SHA-256` of those raw bytes.
3. Compare the hex digest to `sha256`.

Computing SHA-256 on the base64 string directly will produce a different digest and the check will always fail. This must be documented for Android's reassembly logic in Phase 2 (issue #16).
