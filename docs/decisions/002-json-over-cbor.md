# ADR-002: JSON over CBOR

Status: Accepted
Date: 2026-03-28

## Context

WAKE bundle serialization needs to be easy to inspect during early phases of development and testing. The team needs a format that is simple to debug with standard tools and does not add new dependency overhead.

## Decision

Use JSON with base64-encoded payloads for bundle serialization, not CBOR.

## Consequences

JSON is human-readable, debuggable with common tooling, and requires no new libraries.

The tradeoff is larger bundles compared with CBOR. This choice will be revisited if bandwidth becomes a measured problem.
