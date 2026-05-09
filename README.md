# EcoTrace

Cross-platform environmental application that gamifies waste collection.

- **Discover**: see waste (trash / recycling) points on a map near you
- **Contribute**: report a new waste location
- **Act**: physically collect waste, prove it, and earn points
- **Compete**: levels, achievements, and leaderboards drive engagement

## Product goals

- Increase recycling awareness through a low-friction reporting flow
- Build a gamified environmental impact system with verifiable actions
- Architect for eventual Uber/Instagram-style real-time scale

## Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 21 + Spring Boot |
| Web | React |
| Mobile | React Native (iOS + Android) |
| Database | PostgreSQL + PostGIS |
| Cache / leaderboards | Redis |
| Object storage | S3-compatible |
| Auth | Google OAuth2 (direct, not Firebase) — app issues its own JWTs |
| Event bus (Phase 2+) | Kafka |
| Cloud | AWS (ECS Fargate at MVP, optional EKS later) |

## Design phase — index

The design is split into nine sequential documents under `docs/`. Each layer rests on the one before it; the order matters.

| # | Document | Status |
|---|---|---|
| 1 | [System architecture](docs/01-system-architecture.md) | Done |
| 2 | [Database design](docs/02-database-design.md) | Done |
| 3 | [Backend structure](docs/03-backend-structure.md) | Done |
| 4 | [Authentication](docs/04-authentication.md) | Done |
| 5 | [API contract](docs/05-api-design.md) | Done |
| 6 | [Business logic](docs/06-business-logic.md) | Done |
| 7 | [Realtime architecture](docs/07-realtime-architecture.md) | Done |
| 8 | [Deployment topology](docs/08-deployment-topology.md) | Done |
| 9 | [Observability](docs/09-observability.md) | Done |

## How to read these docs

- Read in order. Each document assumes the previous ones.
- "No code yet" is intentional through all nine. Implementation kickoff comes after.
- Every document closes with **risks** and **what's deferred**. Both are load-bearing — read them.

## Project conventions adopted in the design

- **Modular monolith first**, microservice extraction along event seams later
- **UUIDs everywhere user-visible**; no auto-increment IDs leak on the wire
- **One error envelope** across the entire API
- **Idempotency keys** on every state-mutating endpoint
- **Append-only ledger** for points; `users.total_points` is a denormalized projection
- **Server-paced realtime** (debounced viewport, batched leaderboard) — never chatty
- **Alert on symptoms, not causes**; SLOs first, dashboards second
- **Ledger consistency has a zero error budget** — drift is a correctness incident

## What comes next (post-design)

After the nine design steps, the recommended path is:

1. **Implementation kickoff plan** — pick the first vertical slice (e.g., "log in with Google → see map → report a pin"), map it to all nine layers, define acceptance criteria.
2. **Build the slice end-to-end** through every layer (auth → API → DB → frontend) before starting a second slice.
3. **Iterate** by adding one vertical at a time (claim → submit → verify → award points → leaderboard).

Vertical slices over horizontal layers. Always.
