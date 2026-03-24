# ADR-008: Component-Level Routing (Canary Migration)

## Status
Superseded by [ADR-007](007-dual-read-migration.md)

## Context

This ADR originally described a component-level routing phase as a refinement of the dual-read strategy (ADR-007). The routing concept — per-component source tracking via `component_source` table — has been promoted to the **primary and only migration mechanism** in the updated ADR-007.

The previous 4-mode design (`registry.storage=git|db|routing|dual`) was simplified to a single `ComponentRoutingResolver` that always routes per component based on the `component_source` table. There is no global mode flag.

## Decision

Merged into [ADR-007: Component-Source Routing](007-dual-read-migration.md).

Key changes from original design:
- No global `registry.storage` flag — routing is always active
- `dual` mode removed — validation is a step in per-component import, not a system mode
- Rollback reframed as one-way cutover after first DB write per component
- UI shows only DB-sourced components (Git-sourced components available via legacy API only)

## References
- [ADR-007: Component-Source Routing](007-dual-read-migration.md)
