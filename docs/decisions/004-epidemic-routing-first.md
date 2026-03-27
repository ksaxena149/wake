# ADR-004: Epidemic Routing First

Status: Accepted
Date: 2026-03-28

## Context

WAKE routing needs an initial implementation that can quickly validate end-to-end forwarding behavior across peers. More advanced probabilistic routing adds complexity and should be introduced after baseline transport confidence is established.

## Decision

Implement epidemic routing (forward all bundles to all peers) in Phase 3, then upgrade to PRoPHET probabilistic routing in Phase 4.

## Consequences

Epidemic routing is straightforward to implement and helps prove the transport layer works under real relay conditions.

The tradeoff is increased message overhead and less efficient forwarding until PRoPHET is introduced in the next phase.
