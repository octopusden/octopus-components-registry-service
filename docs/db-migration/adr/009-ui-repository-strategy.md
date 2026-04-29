# ADR-009: UI Repository Strategy — Monorepo vs Separate Repo

## Status
**Superseded by [ADR-012](012-portal-architecture.md)** (UI extracted to `octopus-components-management-portal` as a Spring Cloud Gateway BFF, 2026‑04‑14, commit `26278f2`, PR #147).

## Resolution

The recommendation in this ADR was **Option B-2 (monorepo with embedded JAR, single Pod)**. That recommendation **was not implemented**. The team chose **Option A (separate repository)** instead, with a Spring Cloud Gateway frontend that proxies `/rest/**` and `/auth/**` to this service. Rationale and consequences are captured in ADR-012.

This ADR is preserved for historical context (analysis of A/B/C trade-offs) but the decision section is no longer authoritative.

## Context

We need a web UI for managing components registry. The question is where the UI code lives:

- **Option A**: Separate repository (`octopus-components-registry-ui`)
- **Option B**: Monorepo — UI module in existing repo, **separate deploy unit** (2 Docker images)
- **Option C**: Monorepo with embedded UI in server JAR (like `octopus-dms-ui`, single deploy)

### Team context
- **Small cross-functional team** — same people work on backend and frontend
- **Mostly AI-driven development** — Copilot, Cursor, Claude Code agents generate bulk of code
- **2026 reality**: AI agents work dramatically better with full project context in one place

### Reference projects in the ecosystem

| Project | Pattern | Details |
|---------|---------|---------|
| `octopus-dms-service` | Separate repos | Backend-only; UI lives in `octopus-dms-ui` |
| `octopus-dms-ui` | Monorepo (embedded) | Gradle + `node-gradle` plugin; React app embedded in Spring Boot JAR; serves as OAuth2 gateway |
| `f1-dashboard` | Standalone frontend | Vite + React 19; Docker: nginx serves static + proxies API |

### 2026 industry trend

Monorepos are strongly favored for AI-driven development:
- [SpectroCloud: "Will AI turn 2026 into the year of the monorepo?"](https://www.spectrocloud.com/blog/will-ai-turn-2026-into-the-year-of-the-monorepo)
- [Monorepo.Tools: AI integration](https://monorepo.tools/ai) — AI agents leverage project graphs for cross-cutting changes
- [Agentic Coding Best Practices](https://benhouston3d.com/blog/agentic-coding-best-practices) — `AGENTS.md`, explicit conventions, unified context

## Analysis

### Option A: Separate repository (`octopus-components-registry-ui`)

**Pros:**
- Independent release cycles — UI can ship daily, backend weekly
- Independent CI/CD — frontend build (Vite, ~10s) not blocked by Gradle (~2-5 min)
- Clean separation of concerns — frontend developers don't need Java/Gradle tooling
- Independent scaling — serve UI from CDN/nginx, backend from JVM containers
- Tech stack freedom — no Gradle plugin constraints on Node.js version
- Smaller clone/checkout — frontend devs don't pull 250+ .groovy test files
- Can use different code review teams (frontend vs backend specialists)
- Follows the DMS pattern: `dms-service` + `dms-ui` are separate repos

**Cons:**
- Cross-repo coordination for API changes (need to update both repos)
- Two CI pipelines to maintain
- API contract drift risk (schema changes in backend missed by frontend)
- More infrastructure: separate Docker image, separate deployment config
- Harder to run full stack locally (need to start two services)

### Option B: Monorepo — Gradle module (`components-registry-ui/`)

**Pros:**
- Atomic commits — API + UI changes in one PR, one review, one merge
- Single CI pipeline — Gradle orchestrates both Java and npm builds
- Shared versioning — backend and UI always at same version
- Easier local dev — `./gradlew bootRun` starts everything
- API contract always in sync (shared types or generated from OpenAPI)
- Single Docker image simplifies deployment
- Proven pattern: `octopus-dms-ui` does exactly this with `node-gradle` plugin

**Cons:**
- Coupled release cycles — UI fix requires full backend CI (tests, etc.)
- Slower CI — npm build added to Gradle pipeline (though parallelizable)
- Mixed tech stack in one repo — Java/Kotlin + TypeScript/React
- Gradle `node-gradle` plugin adds complexity and version constraints
- Frontend devs must deal with Gradle tooling
- Larger repo — more files, longer clones
- Current repo already has 7 modules; adding UI makes it 8

### Option C: Monorepo with separate deployable

A hybrid: UI code lives in the same repo (`components-registry-ui/` module) but builds to a **separate Docker image** (nginx-based) instead of being embedded in the backend JAR.

**Pros (combines best of A and B):**
- Atomic commits for API + UI changes
- Independent scaling (nginx for UI, JVM for backend)
- UI served from nginx/CDN (faster static asset delivery)
- No `node-gradle` plugin complexity — just `npm run build` in CI
- Single repo, single PR for cross-cutting changes

**Cons:**
- Two Docker images from one repo (slightly more complex CI)
- Still mixed tech stack in one repo
- Release cycle still coupled (same CI pipeline)

## Comparison Matrix

| Factor | A: Separate repo | B: Mono embedded | C: Mono separate deploy |
|--------|:-:|:-:|:-:|
| Independent release cycles | ✅ | ❌ | ⚠️ (same repo, but can tag separately) |
| Atomic API+UI changes | ❌ | ✅ | ✅ |
| CI speed for UI-only changes | ✅ | ❌ | ⚠️ |
| Local dev simplicity | ⚠️ | ✅ | ✅ |
| API contract safety | ⚠️ (drift risk) | ✅ | ✅ |
| Frontend dev experience | ✅ | ⚠️ | ✅ |
| Deployment simplicity | ⚠️ (2 deploys) | ✅ (1 JAR) | ⚠️ (2 images) |
| Ecosystem consistency | ✅ (like DMS) | ✅ (like dms-ui) | ⚠️ (new pattern) |
| Scaling independently | ✅ | ❌ | ✅ |
| Repo complexity | ✅ (clean) | ⚠️ (8 modules) | ⚠️ (8 modules) |

## Key Decision Factors

### 1. Team structure
- **Same team** does backend + frontend → **Monorepo** (less coordination overhead)
- **Separate teams** → **Separate repo** (independent workflows)

### 2. Release cadence
- UI expected to change **more frequently** than backend → **Separate repo** (independent deploys)
- Changes are usually **cross-cutting** (API + UI together) → **Monorepo** (atomic PRs)

### 3. Existing infrastructure
- `common-java-gradle-build.yml` is the standard CI → Monorepo fits existing CI
- But adding `node-gradle` plugin adds build complexity

### 4. API contract management
- Monorepo: OpenAPI spec generated by backend, frontend uses it directly
- Separate repo: need published OpenAPI spec + code generation step (e.g., `openapi-typescript`)

## Recommendation

**→ Option B-2: Monorepo + embedded in JAR (single Pod in OKD)** — strongest fit for small AI-driven team on OKD.

### Why this is the winner

1. **AI agents** — full stack context in one repo, atomic cross-cutting PRs
2. **OKD ops** — 1 Template, 1 Route, 1 Pod (half the ops of nginx approach)
3. **Same origin** — no CORS, natural OAuth2/BFF, simpler security
4. **Proven** — dms-ui does exactly this in the same OKD cluster
5. **Dev DX** — Vite HMR with proxy to backend, no Gradle plugin needed
6. **No nginx to maintain** — no extra container, no SCC concerns, no security patches

### Why monorepo wins for AI-driven development

| Factor | Monorepo | Separate repos |
|--------|:--------:|:--------------:|
| AI agent sees full context (API + UI + types) | ✅ | ❌ agent must switch repos |
| Atomic cross-stack changes in one PR | ✅ | ❌ 2 PRs, manual sync |
| AI generates API client from backend schema | ✅ trivial (same repo) | ⚠️ needs published spec + codegen step |
| AI agent refactors API + updates UI callers | ✅ one operation | ❌ manual coordination |
| `AGENTS.md` / `.cursor/rules` covers full stack | ✅ | ❌ duplicated per repo |
| Small team overhead | ✅ one repo, one CI, one PR | ⚠️ two repos, two CIs, two PRs |

### Why separate deploy units (not embedded JAR)

Given **OKD hosting**, there are actually 3 sub-options for deployment:

#### B-1: Two Pods (backend JVM + frontend nginx) — like f1-dashboard
```
OKD Template 1: components-registry-service (JVM, port 4567)
OKD Template 2: components-registry-ui (nginx, port 8080)
Route 1: /rest/api/** → backend Service
Route 2: / → frontend Service
```

#### B-2: Single Pod, embedded in JAR — like dms-ui  
```
OKD Template: components-registry-service (JVM serves both API + static)
Single Route: components-registry-service → JVM pod
  /rest/api/** → Spring controllers
  /**          → classpath:/static (built React app)
```

#### B-3: Single Pod, sidecar nginx + JVM
```
OKD Template: 1 Pod with 2 containers (nginx + JVM)
```

**Comparison in OKD context:**

| Factor | B-1: 2 Pods (nginx) | B-2: Embedded JAR | B-3: Sidecar |
|--------|:---:|:---:|:---:|
| OKD Templates to maintain | 2 | 1 ✅ | 1 |
| Routes to configure | 2 | 1 ✅ | 1 |
| Resource overhead | Higher (extra Pod) | Lower ✅ | Medium |
| Independent UI deploy | ✅ | ❌ | ❌ |
| Vite HMR in dev | ✅ native | ✅ proxy to backend | ✅ |
| Frontend caching (immutable assets) | ✅ nginx headers | ⚠️ Spring config | ✅ nginx |
| Ops complexity | Higher | Lower ✅ | Medium |
| nginx security updates | Needed | Not needed ✅ | Needed |
| OAuth2/BFF handling | Needs CORS or BFF | Natural (same origin) ✅ | Natural ✅ |
| OKD SCC restrictions for nginx | Possible issue | No issue ✅ | Possible issue |
| f1-dashboard precedent | ✅ proven | dms-ui proven ✅ | New pattern |

**OKD favors B-2 (embedded)** for a small team:
- Half the ops burden (1 Template, 1 Route, 1 Pod)
- No CORS issues (same origin)
- No nginx security patching
- OAuth2 session handling is simpler (BFF pattern, like dms-ui)
- Existing dms-ui proves it works in the same OKD cluster

**Dev experience concern (Gradle node-gradle plugin) is solvable:**
```
Development:  vite dev --proxy → localhost:4567   ← full HMR, zero Gradle
Production:   npm run build → copy dist/ → Spring Boot static resources → single JAR
```

No `node-gradle` plugin needed — just a simple Gradle `Exec` task:
```gradle
tasks.register('npmBuild', Exec) {
    workingDir 'components-registry-ui/frontend'
    commandLine 'npm', 'run', 'build'
}

tasks.named('processResources') {
    dependsOn 'npmBuild'
    from('components-registry-ui/frontend/dist') { into 'static' }
}
```

### Proposed structure (B-2)

```
octopus-components-registry-service/        ← existing repo
├── AGENTS.md                               ← AI agent instructions (full stack)
├── .cursor/rules/                          ← Cursor-specific rules
├── components-registry-ui/                 ← NEW: Gradle module (frontend)
│   ├── src/
│   │   ├── api/                            ← generated from OpenAPI spec
│   │   ├── components/                     ← shadcn/ui
│   │   ├── pages/                          ← routes
│   │   └── lib/                            ← shared utils, Zod schemas
│   ├── package.json
│   ├── vite.config.ts                      ← dev proxy to localhost:4567
│   └── tsconfig.json
├── components-registry-service-server/     ← existing backend
│   ├── src/
│   │   └── main/resources/static/          ← built UI copied here by Gradle
│   └── Dockerfile                          ← single JVM image serves API + UI
├── docs/db-migration/                      ← architecture docs
├── settings.gradle                         ← include 'components-registry-ui'
└── build.gradle
```

### CI/CD: one repo, one image

```yaml
# GitHub Actions (extends existing common-java-gradle-build.yml)
steps:
  - npm ci --prefix components-registry-ui
  - npm run build --prefix components-registry-ui
  # Gradle copies dist/ → server resources/static/
  - ./gradlew :components-registry-service-server:bootJar
  - docker build -t components-registry-service   # single image: API + UI
```

Single OKD Template (extend existing `okd/components-registry.yaml`):
- Same Pod, same Service, same Route
- UI served from `classpath:/static` by Spring Boot
- SPA fallback: Spring `WebMvcConfigurer` redirects unknown paths to `index.html`

### API contract sync (in-repo)

```
Backend generates OpenAPI spec:
  ./gradlew :components-registry-service-server:generateOpenApiDocs
  → components-registry-service-server/build/openapi/v4.json

Frontend consumes it:
  cd components-registry-ui/frontend
  npx openapi-typescript ../components-registry-service-server/build/openapi/v4.json -o src/api/schema.ts

CI gate: if spec changes → auto-regenerate frontend types → fail if drift detected
```

### AI agent workflow

```
Developer prompt: "Add a 'tags' field to components — backend entity, API, and UI form"

AI agent (Copilot/Cursor/Claude) in monorepo:
  1. Adds 'tags' column to Flyway migration
  2. Updates JPA entity + DTO
  3. Updates v4 controller
  4. Regenerates OpenAPI → TypeScript types
  5. Updates React form (Zod schema + RHF field)
  6. Updates TanStack Table column
  7. All in ONE PR, ONE commit
```

This is impossible with separate repos — the agent would need to open two PRs and manually coordinate.

## References
- [SpectroCloud: Will AI turn 2026 into the year of the monorepo?](https://www.spectrocloud.com/blog/will-ai-turn-2026-into-the-year-of-the-monorepo)
- [Monorepo.Tools: Monorepos & AI](https://monorepo.tools/ai)
- [Agentic Coding Best Practices (Ben Houston, 2026)](https://benhouston3d.com/blog/agentic-coding-best-practices)
- [Graphite: Frontend + Backend monorepo best practices](https://www.graphite.com/guides/monorepo-frontend-backend-best-practices)
- [octopus-dms-ui](https://github.com/octopusden/octopus-dms-ui): monorepo embedded pattern (React 16 + Gradle `node-gradle` plugin)
- [octopus-dms-service](https://github.com/octopusden/octopus-dms-service): separate backend repo
- f1-dashboard (`<gitserver>/releng/f1-dashboard`): standalone frontend (React 19 + Vite + nginx)
- ADR-003: UI stack decision (React 19 + Vite + shadcn/ui)
