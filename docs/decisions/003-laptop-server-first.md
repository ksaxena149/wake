# ADR-003: Laptop Server First

Status: Accepted
Date: 2026-03-28

## Context

During early WAKE phases, server behavior and protocol logic need rapid iteration. Introducing Raspberry Pi setup and hardware constraints too early can slow development and block testing.

## Decision

Run the WAKE server daemon on a laptop during Phases 0 through 5. Migrate to Raspberry Pi only in Phase 6.

## Consequences

This removes Raspberry Pi hardware as a development bottleneck, and one phone plus a laptop is sufficient to validate protocol behavior through early and mid project phases.

The deployment target migration to Raspberry Pi is deferred and must be completed in Phase 6.
