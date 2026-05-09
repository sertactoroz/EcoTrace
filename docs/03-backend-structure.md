# Step 3 — Spring Boot Backend Structure

## The key architectural choice

Two ways to organize Spring Boot layers:

| Approach | Top-level folders | Pros | Cons |
|---|---|---|---|
| **Layer-first** | `controller/`, `service/`, `repository/`… | familiar, simple | every domain dumped together; cross-domain coupling sneaks in; painful to extract services later |
| **Module-first** *(adopted)* | `identity/`, `waste/`, `collection/`… each containing the same layers | enforces domain boundaries; modules can be extracted into microservices with near-zero refactor | slightly more setup |

Module-first is the right call given the "scale like Uber/Instagram" goal and the modular-monolith path. Each module still uses the layered packages — they just live under their domain instead of being scattered across one giant `controller/` folder.

## Top-level package tree

Base package: `com.ecotrace.api`

```
com.ecotrace.api
│
├── EcoTraceApplication.java          ← @SpringBootApplication entry point
│
├── config/                           ← global Spring configuration
├── security/                         ← global security (JWT, OAuth2, filters)
├── common/                           ← cross-cutting building blocks
│
├── identity/                         ← MODULE: auth, providers, devices
├── profile/                          ← MODULE: user profile / preferences
├── waste/                            ← MODULE: waste points (spatial core)
├── collection/                       ← MODULE: collection lifecycle
├── gamification/                     ← MODULE: points, levels, achievements
├── leaderboard/                      ← MODULE: rankings (Redis-backed)
├── media/                            ← MODULE: photo uploads, presigned URLs
├── notification/                     ← MODULE: push, email, in-app inbox
├── moderation/                       ← MODULE: flags, review queue
└── analytics/                        ← MODULE: usage + impact metrics
```

Each business **module** is self-contained. Each cross-cutting **technical** package (`config`, `security`, `common`) holds only generic infrastructure.

## Inside a module — the layered slice

Every module follows the **same internal layout** so onboarding is predictable. Example, `waste/`:

```
waste/
├── controller/        ← REST endpoints (thin, no business logic)
│   └── WastePointController.java
│
├── service/           ← business logic + transactions
│   ├── WastePointService.java
│   └── WastePointSearchService.java
│
├── repository/        ← Spring Data JPA + custom queries
│   ├── WastePointRepository.java
│   └── WastePointSpatialRepository.java   ← PostGIS native queries
│
├── entity/            ← JPA @Entity classes (DB-mapped)
│   ├── WastePoint.java
│   ├── WastePointPhoto.java
│   ├── WasteCategory.java
│   └── enums/
│       ├── WastePointStatus.java
│       └── WasteVolume.java
│
├── dto/               ← request/response models (never expose entities)
│   ├── request/
│   │   ├── CreateWastePointRequest.java
│   │   └── SearchWastePointsRequest.java
│   └── response/
│       ├── WastePointResponse.java
│       └── WastePointSummaryResponse.java
│
├── mapper/            ← entity ↔ DTO conversion (MapStruct)
│   └── WastePointMapper.java
│
├── event/             ← domain events this module publishes
│   ├── WastePointCreated.java
│   └── WastePointClaimed.java
│
└── exception/         ← module-specific exceptions
    ├── WastePointNotFoundException.java
    └── WastePointAlreadyClaimedException.java
```

### Why each sub-package exists

- **controller/** — Translates HTTP ↔ DTOs, calls service. Validates input, doesn't decide anything. One controller per resource family.
- **service/** — The brain. Owns transactions (`@Transactional`), enforces invariants, publishes events. Multiple services per module is fine when responsibilities differ.
- **repository/** — Spring Data interfaces. Custom queries (PostGIS, complex joins) go in a `*SpatialRepository` or `*CustomRepository` sibling.
- **entity/** — JPA `@Entity` classes. Mirror the DB schema. Never returned from controllers.
- **dto/** — API contract types. Splitting `request/` and `response/` makes the contract obvious. Records (Java 17+) ideal here.
- **mapper/** — Use **MapStruct** (compile-time, fast, no reflection).
- **event/** — POJOs published via Spring's `ApplicationEventPublisher`. The seams along which microservice boundaries will later be cut.
- **exception/** — Module-specific business exceptions. Mapped to HTTP status by the global exception handler.

## The three technical packages

### `config/`

Global Spring configuration that doesn't belong to any single module.

```
config/
├── DatabaseConfig.java          ← DataSource, JPA, transaction manager
├── RedisConfig.java             ← RedisTemplate, connection factory
├── JacksonConfig.java           ← JSON (sealed types, Java time)
├── OpenApiConfig.java           ← Swagger / springdoc
├── AsyncConfig.java             ← @Async executors, virtual-thread pool
├── CorsConfig.java
├── CacheConfig.java
└── properties/                  ← @ConfigurationProperties POJOs
    ├── AppProperties.java
    ├── StorageProperties.java
    └── GamificationProperties.java
```

### `security/`

Everything authentication- and authorization-related at the framework level.

```
security/
├── SecurityConfig.java                ← SecurityFilterChain
├── jwt/
│   ├── JwtService.java                ← issue + verify our app JWTs
│   ├── JwtAuthenticationFilter.java
│   └── JwtProperties.java
├── oauth2/
│   ├── GoogleTokenVerifier.java       ← verifies Google ID tokens
│   └── OAuth2Properties.java
├── principal/
│   ├── AuthenticatedUser.java         ← exposed via @AuthenticationPrincipal
│   └── UserContextHolder.java
├── annotation/
│   ├── CurrentUser.java               ← @CurrentUser parameter resolver
│   └── RequiresRole.java
└── filter/
    └── RateLimitFilter.java
```

The `identity/` **module** handles user records and auth flows (login, register, link provider). The `security/` **package** handles framework wiring (filters, JWT verification, principal resolution). They collaborate but stay separate — important when `identity` is eventually pulled into its own service.

### `common/`

Shared building blocks. Nothing here may import from any business module.

```
common/
├── persistence/
│   ├── BaseEntity.java                ← id, createdAt, updatedAt, version
│   ├── SoftDeletable.java
│   └── converter/
│       └── PointConverter.java        ← PostGIS Point ↔ JPA
├── audit/
│   ├── AuditingConfig.java            ← @EnableJpaAuditing
│   └── AuditorAwareImpl.java          ← maps SecurityContext → createdBy
├── error/
│   ├── ApiError.java                  ← uniform error response
│   ├── ErrorCode.java
│   ├── GlobalExceptionHandler.java    ← @RestControllerAdvice
│   └── BusinessException.java         ← parent of all module exceptions
├── pagination/
│   ├── PageRequestFactory.java
│   └── PageResponse.java
├── validation/
│   ├── annotation/                    ← @ValidGeoCoordinate, etc.
│   └── validator/
├── event/
│   └── DomainEvent.java               ← marker interface
└── util/
    ├── GeoHashUtil.java
    └── TimeUtil.java
```

## Module communication rules

This is the most important part for scalability. Get it wrong and the "modular monolith" becomes a regular monolith with extra folders.

**Three rules**:

1. **A module may only import from**:
   - Its own packages
   - `common/`
   - `security/` (only for principal types and annotations)
   - **Another module's `event/` package** (events are public contract)
   - **Another module's read-side DTO package** *only if* exposed as a public API
2. **No cross-module entity imports.** `gamification/` must never import `waste.entity.WastePoint`. If it needs waste data, listen to an event or call a query service.
3. **No cross-module repositories.** Repositories are private to their module.

These rules make extraction mechanical: when `gamification` is pulled into a separate service, it's already only depending on `event/` payloads — they become Kafka messages with the same shape, and the rest of the code is unchanged.

### How modules talk

- **Same-transaction reads/writes inside a module**: direct service-to-repository calls.
- **Cross-module reactions (most cases)**: `ApplicationEventPublisher` → `@TransactionalEventListener(phase = AFTER_COMMIT)`. The event is the public contract.
- **Cross-module synchronous queries** (avoid when possible): call the other module's service interface — never its repository.

Example: when `collection/` verifies a collection, it publishes `CollectionVerified`. `gamification/` listens and writes a `points_transactions` row. `leaderboard/` listens and updates Redis. `notification/` listens and queues a push. None touch each other's tables.

## Build structure

### Option A — Single Gradle module, package boundaries (start here)

- Lower friction, fast iteration.
- Module rules enforced by **convention + ArchUnit tests** (a 30-line test that fails the build if `gamification` imports `waste.entity`).

### Option B — Multi-module Gradle (move here when team grows)

```
ecotrace-backend/
├── settings.gradle
├── app/                              ← @SpringBootApplication, wires modules
├── common/                           ← shared lib
├── security/                         ← shared lib
├── module-identity/
├── module-waste/
├── module-collection/
├── module-gamification/
├── module-leaderboard/
├── module-media/
├── module-notification/
├── module-moderation/
└── module-analytics/
```

Each module declares its dependencies in its own `build.gradle`. The compiler stops cross-module entity imports — boundaries become physical, not aspirational.

**Recommendation**: start with Option A + ArchUnit. Migrate to Option B when (a) team > 5 backend engineers, or (b) any module is two weeks away from being extracted into a real service.

## Test structure (mirror of `main`)

```
src/test/java/com/ecotrace/api/
├── waste/
│   ├── controller/   ← @WebMvcTest slices
│   ├── service/      ← unit tests with mocked repos
│   └── repository/   ← @DataJpaTest with Testcontainers PostGIS
├── collection/
├── ...
└── architecture/
    └── ModuleBoundaryTest.java   ← ArchUnit: enforce the import rules
```

Use **Testcontainers** for Postgres (with the `postgis/postgis` image) and Redis. Don't mock the database for integration tests — that path catches the bugs that matter.

## Configuration profiles

```
src/main/resources/
├── application.yml              ← shared defaults
├── application-local.yml        ← dev laptop
├── application-test.yml         ← Testcontainers
├── application-staging.yml
├── application-prod.yml
└── db/migration/                ← Flyway migrations
    ├── V1__initial_schema.sql
    ├── V2__seed_categories.sql
    └── ...
```

Secrets via env vars only (Spring's `${ENV_VAR}`), never committed.

## Deferred decisions

- **API versioning strategy** (`/v1/...` URI vs. media-type) — covered in Step 5.
- **DTO mapping strategy details** (MapStruct config) — set when the first endpoint is written.
- **Caching annotations** (`@Cacheable` placements) — premature without traffic shape data.
- **Reactive (`WebFlux`)** — stay on Spring MVC + virtual threads (Java 21). Reactive is overkill until there's a measured back-pressure problem.
- **Internal scheduler vs. external workers** — start with `@Scheduled` in-process; move to a worker module + queue when scale demands.

## Summary

- **Module-first folders** (`waste/`, `collection/`, …), **layer-first sub-packages** inside each.
- **Three technical packages** at the top: `config/`, `security/`, `common/`.
- **Strict module rules**: events are the only public contract; no cross-module entities or repositories.
- **Single Gradle module + ArchUnit** today; multi-module Gradle when justified.
