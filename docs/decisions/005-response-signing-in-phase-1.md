# ADR-005: Server response bundles are signed in Phase 1

Status: Accepted
Date: 2026-03-28

## Context

The master plan described the `signature` field on `ResponseBundle` as "None until issue #9 signs it", implying that Ed25519 signing of server responses was a Phase 1 stub to be activated later. Issue #9 covered keypair generation and the signing primitives; the plan did not explicitly schedule activation of signing on outbound chunks.

## Decision

Server response bundles are signed as part of the `POST /request` endpoint implemented in Phase 1. Every chunk returned by the server carries a non-null `signature` field. The signing key is generated or loaded at startup via the FastAPI lifespan hook.

Signing was not deferred because the signing infrastructure (keypair + `sign_bundle`) was built in the same phase, making it trivial to activate. Leaving signatures as `None` would have required a later migration of stored chunks.

## Consequences

Android clients in Phase 2 can and should verify server signatures immediately (issue #19). They must not treat `signature: null` on a `ResponseBundle` as valid. The `GET /pubkey` endpoint is available for Android to fetch the server's `VerifyKey` during initial setup.

The `signature` field on `RequestBundle` remains `None` in Phase 1 — request signing by Android devices is still deferred to Phase 5.
