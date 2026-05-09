# EcoTrace API

Spring Boot 3.5 backend for EcoTrace. Java 21, PostgreSQL+PostGIS, Redis, Flyway, JWT (RS256) over Google OAuth2.

See `../docs/` for the full design (steps 01–09).

## Prerequisites

- Java 21+ (any 21+ JDK; the wrapper provisions Gradle).
- Docker or Podman with Compose.
- A Google OAuth2 client ID (only required to log in with real Google tokens; the app boots without it).

## Quick start

```fish
cd EcoTrace/backend

# 1. Start Postgres+PostGIS, Redis, MinIO
podman compose up -d        # or: docker compose up -d

# 2. Run the app (local profile is the default)
./gradlew bootRun
```

The API is at `http://localhost:8080`.

## What works in Phase 0 → 1

| Endpoint | What it does |
|---|---|
| `POST /auth/google` | Exchange a Google ID token for app access + refresh tokens |
| `POST /auth/refresh` | Rotate the refresh token, get a new access token |
| `POST /auth/logout` | Revoke access JWT + delete refresh token |
| `POST /waste-points` | Create a waste pin (auth required) |
| `GET /waste-points/{id}` | Fetch one pin (auth required) |
| `GET /map?minLon=..&minLat=..&maxLon=..&maxLat=..` | List pins inside a bounding box (auth required) |
| `POST /media/uploads` | Get a presigned PUT URL for an image upload |
| `POST /waste-points/{id}/claim` | Claim a pin (24h TTL); creates a Collection in `CLAIMED` |
| `POST /collections/{id}/submit` | Submit collection with evidence photo storage keys |
| `GET /collections/{id}` | Fetch your own collection + evidence |
| `POST /collections/{id}/verify` | Verify a submitted collection — awards points + flips the pin to `VERIFIED` (idempotent on the ledger). **Moderator only.** |
| `POST /collections/{id}/reject` | Reject a submitted collection — releases the pin back to `ACTIVE`. **Moderator only.** |
| `GET /leaderboard?scope=GLOBAL\|WEEKLY\|MONTHLY&limit=20` | Top-N leaderboard from Redis sorted sets, plus the viewer's own rank |
| `GET /me/stream` | SSE stream of user-scoped events (`points.awarded`, `collection.verified`, `collection.rejected`) |
| `GET /actuator/health` | Liveness/readiness |

Roles live in the `user_roles` table (`MODERATOR`, `ADMIN`); `USER` is implicit. Emails listed in `auth.moderator-emails` (env var `MODERATOR_EMAILS`, comma-separated) act as a one-time *bootstrap allowlist* — on first login, a matching user is auto-granted `MODERATOR` in DB. After that, the DB row is authoritative; removing the email from the allowlist does not revoke the role.

## Running tests

```fish
./gradlew test
```

The architecture test (`ModuleBoundaryTest`) verifies the cross-module import rules from `docs/03-backend-structure.md`. Repository tests use Testcontainers with the `postgis/postgis` image.

Testcontainers-backed tests (`LeaderboardIntegrationTest`) skip when no Docker socket is reachable. To run them under Podman on macOS, point `DOCKER_HOST` at the host-side API socket and disable Ryuk (Podman doesn't run Ryuk's privileged cleanup container):

```fish
set -x DOCKER_HOST "unix://"(ls /var/folders/*/*/T/podman/podman-machine-default-api.sock | head -1)
set -x TESTCONTAINERS_RYUK_DISABLED true
./gradlew test
```

## Configuration

All config in `src/main/resources/application*.yml`. Override per environment via env vars:

| Var | Purpose |
|---|---|
| `GOOGLE_CLIENT_ID` | Audience expected on Google ID tokens |
| `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` | PEM-encoded RSA keypair (PKCS#8 + X.509). If unset, an ephemeral keypair is generated at boot — DEV ONLY. |
| `JWT_KEY_ID` | `kid` claim in issued JWTs |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | Override DB connection |

## Project layout

```
backend/
├── build.gradle.kts           ← Spring Boot 3.5, Java 21, Hibernate Spatial, MapStruct, ArchUnit
├── compose.yml                ← Postgres+PostGIS, Redis, MinIO
└── src/main/
    ├── java/com/ecotrace/api/
    │   ├── EcoTraceApplication.java
    │   ├── config/properties/  ← @ConfigurationProperties POJOs
    │   ├── security/           ← SecurityConfig, JwtService, JwtAuthenticationFilter,
    │   │                          GoogleTokenVerifier, AuthenticatedUser
    │   ├── common/             ← BaseEntity, BusinessException, GlobalExceptionHandler,
    │   │                          PageResponse, GeoHashUtil, AuditorAware
    │   ├── identity/           ← User + UserAuthProvider entities, AuthService,
    │   │                          AuthController, RefreshTokenStore
    │   └── waste/              ← WastePoint + WasteCategory entities,
    │                              WastePointService, WastePointController, MapController
    └── resources/
        ├── application.yml
        ├── application-local.yml
        └── db/migration/       ← V1 schema, V2 categories, V3 levels, V4 achievements
```

Module folders (`profile/`, `collection/`, `gamification/`, …) exist as empty placeholders; they get filled in as features land.

## Conventions

- Module-first packages, layered sub-packages — see `../docs/03-backend-structure.md`.
- Entities are module-private; cross-module communication is via `event/` POJOs published with `ApplicationEventPublisher`. Enforced by `ModuleBoundaryTest`.
- Records for DTOs.
- All times in `OffsetDateTime` UTC. JPA stores as `TIMESTAMPTZ`.
- Errors use the envelope from `docs/05-api-design.md` — `GlobalExceptionHandler` is the single mapper.
- Points come into existence only via the `CollectionVerified` event (Phase 0 has no collection flow yet — points/levels paths are scaffolded but inactive).

## Next milestones

- Admin endpoints to grant/revoke roles (today the only path is direct SQL or the bootstrap allowlist).
- Multi-replica SSE: today the emitter registry is in-process; needs a Redis pub/sub fanout for HA.
- Weekly/monthly leaderboard rollover/snapshot if you want history; today the keys auto-expire after 14d/62d.
