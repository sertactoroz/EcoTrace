# Step 1 — System Architecture

## Product understanding

EcoTrace is a **geospatial, user-generated, gamified civic-action platform**. The core loops are:

- **Discovery loop**: see waste pins on a map near me
- **Contribution loop**: report a new waste location (photo + geo)
- **Action loop**: physically collect waste, mark it collected, earn points
- **Social loop**: levels, badges, leaderboards, optional social feed

Architecturally this puts EcoTrace in the same family as Waze, Strava, and Pokémon GO: spatial reads at scale, write-heavy event stream, leaderboard fan-out, and user-generated content moderation. The "Uber/Instagram-style" target means the system will eventually need live map updates, push fan-out, anti-fraud, and regional sharding.

## Open product questions that shape the architecture

These were flagged as load-bearing decisions. Defaults below are reasonable starting points; revisit as product clarity grows.

1. **Verification & trust** — how does a "collection" get proven?
   - a) Self-reported (trust + later moderation)
   - b) Photo before/after + AI/manual review
   - c) Geo-fenced presence (within X meters for Y minutes)
   - d) Two-party confirmation
2. **Geographic scope at launch** — one city, one country, or global?
3. **Waste taxonomy** — single "trash" type or categories (plastic, glass, e-waste, hazardous)?
4. **Who can add a waste point** — any user, verified users only, or partners?
5. **Realtime expectations** — seconds-fresh or "next refresh" acceptable for v1?
6. **Photo requirement** — mandatory on report? On collection?
7. **Offline mobile usage** — do collectors work in low-signal areas?
8. **Monetization / partnerships** — municipal contracts, sponsors, B2B dashboards?
9. **Privacy posture** — continuous tracking or only at action time?

## High-level architecture

**Recommendation**: start as a *modular monolith* on Spring Boot, deployed behind a managed gateway, with infrastructure ready for service extraction. Microservices on day one would be premature and slow iteration. The modules below are *logical* boundaries that map cleanly to future services once load justifies it.

```
┌──────────────────────────────────────────────────────────────┐
│  CLIENTS                                                     │
│  React Web   │   React Native (iOS/Android)                  │
└─────────┬────────────────────────────┬───────────────────────┘
          │                            │
          ▼                            ▼
┌──────────────────────────────────────────────────────────────┐
│  EDGE                                                        │
│  CDN (static + tiles)  │  API Gateway  │  Auth (Google)      │
│  Rate limiting │ WAF │ TLS │ Geo-routing                     │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  APPLICATION (Spring Boot — modular monolith)                │
│                                                              │
│  Identity │ Profile │ Waste-Location │ Collection │          │
│  Gamification │ Leaderboard │ Media │ Notification │         │
│  Moderation │ Analytics                                      │
│                                                              │
│  Internal event bus (Spring Events → later Kafka)            │
└─────────┬─────────────┬─────────────┬─────────────┬──────────┘
          │             │             │             │
          ▼             ▼             ▼             ▼
   PostgreSQL      Redis        Object Store   Push/Email
   + PostGIS    (cache, LB,      (S3-compat,    (FCM, APNs,
   (primary +   sessions,        photos)         SES)
   read replicas) rate limits)

          ┌──────────── FUTURE (real-time tier) ───────────────┐
          │  WebSocket Gateway │ Kafka │ CDC │ Geo-shards      │
          └────────────────────────────────────────────────────┘
```

## Component breakdown

### Client tier

- **React (web)** — admin/moderation console + read-mostly map view. Use a code-shared package for API client + types between web and mobile.
- **React Native (mobile)** — primary product surface: map, camera, GPS, push. Plan for an offline write-queue (collection events created without signal, synced on reconnect).
- **Map rendering** — managed tile provider (Mapbox / MapLibre + OSM) with vector tiles. Don't render millions of pins on the client — cluster server-side.

### Edge tier

- **API Gateway** (Spring Cloud Gateway, or managed: AWS API Gateway / Cloudflare) — auth verification, rate limits, request shaping.
- **CDN** in front of static assets, map tiles, photo URLs.
- **Auth** — Google OAuth2 via Spring Security OAuth2 Resource Server. Backend verifies ID tokens and mints its own short-lived JWT (so the system isn't coupled to Firebase forever). Refresh tokens stored server-side (Redis).

### Application tier (logical modules)

| Module | Responsibility |
|---|---|
| **Identity** | OAuth verification, JWT issuance, session, device registration |
| **Profile** | User profile, preferences, stats projections |
| **Waste-Location** | CRUD of waste pins, PostGIS spatial queries, clustering |
| **Collection** | Collection events, proof-of-action, state machine |
| **Media** | Pre-signed uploads to object storage, image processing |
| **Gamification** | Rules engine for points / levels / badges (event-driven) |
| **Leaderboard** | Materialized rankings in Redis sorted sets |
| **Notification** | Push (FCM/APNs), email digests, in-app inbox |
| **Moderation** | Reports queue, auto-flagging, admin actions, ban/shadow-ban |
| **Analytics** | Usage metrics, environmental impact aggregates, exports |

Modules communicate **inside the monolith via an internal event bus** (Spring Application Events). When extracted to services, those events become Kafka topics with **zero domain change** — only the transport.

### Data tier

- **PostgreSQL + PostGIS** — single source of truth. Geospatial GIST index on waste locations. Read replicas for map queries.
- **Redis** — leaderboard sorted sets, hot spatial-query cache (cluster results per tile), session/refresh tokens, rate-limiting counters, idempotency keys.
- **Object storage** (S3 / GCS / R2) — photos. Direct-to-storage uploads via pre-signed URLs (don't proxy bytes through Spring).
- **Search/analytics (later)** — OpenSearch or ClickHouse if analytics workload grows.

## Data flow — three critical paths

### A. "Show me waste near me" (read-heavy, latency-sensitive)

```
Mobile GPS → Gateway → Waste-Location module
   → check Redis tile cache (key: geohash@zoom)
       hit  → return clustered pins
       miss → PostGIS ST_DWithin query on read replica
            → cluster server-side → write Redis (TTL 30–120s)
            → return
```

Server-side clustering and geohash-keyed cache are the two levers that keep this cheap as scale grows.

### B. "Report a new waste point" (write + fan-out)

```
Mobile → request pre-signed upload URL → uploads photo directly to S3
       → POST waste pin (with photo ref) → Waste-Location module
       → write to Postgres → emit WastePinCreated event
            ├→ Moderation (auto-checks, queue if suspicious)
            ├→ Gamification (small "report" reward)
            ├→ Notification (notify nearby active users — future)
            └→ Cache invalidation (geohash tiles touched)
```

### C. "I collected this" (the integrity-critical path)

```
Mobile → Collection module
       → verify: user is within geofence? recent enough? not self-collected?
       → state: REPORTED → CLAIMED (lock for N minutes for this user)
       → user uploads "after" photo
       → state: CLAIMED → SUBMITTED
       → Moderation: auto + sample-based human review
       → state: SUBMITTED → VERIFIED (or REJECTED)
       → emit CollectionVerified event
            ├→ Gamification: award points, update level
            ├→ Leaderboard: ZINCRBY on Redis sorted sets
            ├→ Profile: update stats
            └→ Notification: "+50 pts!"
```

The **state machine** is what keeps this honest. Rewards are issued on `VERIFIED`, not on submit — so fraud reversal is trivial (state transition + compensating event).

## Path to Uber/Instagram-style real-time

Don't build this in v1. Build *toward* it:

| Phase | What you add | When |
|---|---|---|
| **0 — MVP** | Modular monolith, polling map (refresh every N seconds), Postgres + Redis | Launch |
| **1 — Live-ish** | Server-Sent Events: "new pins in your viewport" | DAU > ~10k or stale-map complaints |
| **2 — Real-time** | Dedicated WebSocket gateway, Kafka as event backbone, viewport-subscribed rooms | Concurrent map viewers > ~5k |
| **3 — Service extraction** | Pull out Leaderboard, Notification, Media. Keep core (Identity/Waste/Collection) together longer | Team > ~15 engineers or modules deploy on different cadences |
| **4 — Geo-shard** | Partition Postgres by region; route at gateway | Single-DB write throughput hits ceiling |

Each phase swaps **one** transport or boundary. The modular event-driven core means domain logic is never rewritten.

## Risks & key design decisions

### High-impact risks

1. **Gamification fraud is inevitable.** Leaderboards attract gaming. Mitigations: geofence + dwell time, photo proof, velocity checks, peer/admin review for top-of-leaderboard, shadow-ban over hard-ban.
2. **UGC moderation cost.** Photos sometimes contain people, license plates, NSFW. Plan for: EXIF stripping, automated NSFW/face detection, human queue, clear ToS, take-down workflow.
3. **Photo storage cost balloon.** Mandatory photos × millions of users = real money. Mitigations: aggressive resize on upload, lifecycle policies (cold-tier after 90 days), one canonical photo per pin.
4. **PostGIS at scale.** Performs well into the 10–50M-row range with proper GIST indexes; beyond, geographic partitioning. Don't shard prematurely — it's a one-way door.
5. **Privacy / GDPR.** Location is sensitive PII. Decisions to lock in early: data residency, retention windows, right-to-delete cascades into leaderboard recompute, location precision (5m vs 50m).
6. **Hazardous waste liability.** A user reporting a chemical drum creates safety/legal exposure. Either explicitly exclude (ToS + UI), or build a partner-only escalation channel.

### Decisions adopted

- **Modular monolith over microservices for v1.** One deployable, one DB, fast iteration. Modules are domain-aligned so extraction is mechanical when justified.
- **Backend mints its own JWT** even when using Google OAuth. Keeps the auth provider swappable.
- **Pre-signed direct-to-storage uploads.** Don't proxy media bytes through Spring.
- **Server-side clustering for the map.** Never ship raw pins to the client at low zoom levels.
- **State machine + event sourcing for collections.** Auditability and reversibility for the integrity-critical path.
- **PostGIS over a separate geo-DB.** One database, transactional consistency between profile + pin + collection. Revisit only if it becomes a bottleneck.
- **Redis is non-optional, not "later".** Leaderboards on Postgres alone won't scale.
- **Polling first, WebSockets later.** Most users will accept a 30s map refresh. Real-time is a Phase-2 investment.
