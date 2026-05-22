# ADR-003: UI Technology Stack — React 19 + Vite + shadcn/ui

## Status
Accepted

## Context

A web UI is needed to replace Git-based configuration editing. The UI must support:
- Complex multi-tab forms (component configuration: build, escrow, VCS, distribution, jira, versions)
- Data tables with filtering/sorting/pagination
- Audit log viewer with JSON diff
- Keycloak authentication
- Modern, maintainable stack with long-term support

Reference projects in the octopus ecosystem:
- `octopus-dms-ui` — React 16, Redux, Blueprint.js 3, Webpack 4 (legacy)
- `f1-dashboard-fe` — React 19, Vite 7, shadcn/ui, TanStack React Query, TailwindCSS (modern)

## Considered Options

### Option A: f1-dashboard style (minimal)
React 19 + Vite + shadcn/ui + React Query. No form library, no table library.
- Sufficient for read-only dashboards
- **Not enough** for complex CRUD forms

### Option B: Modern + Enterprise (recommended)
React 19 + Vite + shadcn/ui + React Query + React Hook Form + Zod + TanStack Table + Zustand + Keycloak JS.
- Full CRUD capability with complex nested forms
- Table with filters for component list
- Proper form validation (Zod for frontend, Bean Validation for backend)
- Lightweight UI state management (Zustand)

## Decision

**Option B: Modern + Enterprise stack.**

| Component | Technology |
|-----------|-----------|
| Framework | React 19 + TypeScript (strict) |
| Build | Vite 7 |
| UI Kit | shadcn/ui + Radix UI |
| Styles | TailwindCSS 4 |
| Server State | TanStack React Query 5 |
| UI State | Zustand |
| Forms | React Hook Form + Zod |
| Tables | TanStack Table |
| Routing | react-router 7 |
| Auth | Keycloak JS Adapter |
| API client | fetchApi + React Query hooks (f1-dashboard pattern) |

## Consequences

### Positive
- Modern stack, active community, long-term support
- React Hook Form + Zod essential for complex config forms (30+ fields, nested sections)
- TanStack Table handles component list with filtering/sorting
- Patterns from f1-dashboard can be reused (API hooks, query keys, layout)
- Vite provides fast builds and HMR

### Negative
- New repository (`octopus-components-registry-ui`) to maintain
- Different stack from legacy octopus-dms-ui (but DMS-UI is outdated)
- Keycloak JS adapter needs Keycloak client configuration

### Risks
- No existing design system → mitigate with v0.dev / Figma AI for rapid prototyping

## References
- f1-dashboard: `<gitserver>/releng/f1-dashboard`
- [shadcn/ui](https://ui.shadcn.com/)
- [React Hook Form](https://react-hook-form.com/)
- [v0.dev](https://v0.dev/) for AI-powered UI prototyping
