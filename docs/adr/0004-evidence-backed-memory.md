# ADR-0004: Evidence-Backed Memory

- Status: Accepted
- Date: 2026-09-22

## Context

Generated memories can be wrong. Users need to understand where decisions, actions, commitments, and facts came from.

## Decision

Derived memory items always retain references to supporting transcript evidence. Corrections never rewrite the original evidence layer.

## Consequences

- answers can cite source timestamps
- extraction can be reprocessed as models improve
- correction and supersession must be modeled explicitly
