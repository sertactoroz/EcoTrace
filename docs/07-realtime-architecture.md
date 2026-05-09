# Step 7 — Real-time Architecture

## What "real-time" means here

Three distinct streams, each with different semantics, fan-out shape, and tolerance for loss.

| Stream | Audience | Trigger | Latency target | Loss tolerance |
|---|---|---|---|---|
| **Map / viewport stream** | Anyone with the map open in a region | new pin, pin claimed, pin verified, pin expired | 1–3 s | Low — but reconcilable via re-fetch |
| **User stream** (personal) | The signed-in user, all their devices | their collection verified, points awarded, level-up, achievement, moderation outcome, push receipt | < 1 s | **Zero** — these drive UX confirmations |
| **Leaderboard stream** | Everyone on a leaderboard view | rank changes in the visible top-N or around the user | 2–5 s | High — derived state, can lag |

The three streams are **separated by design**. They have different fan-out factors (one pin update can hit thousands of viewers; a level-up hits one user), different consistency requirements, and different scaling strategies.

## Phased rollout

Don't build the end-state on day one. Each phase is shippable and earns the right to the next.

### Phase 0 — Polling baseline (MVP)

- Map polls `GET /map` every 30 s when in foreground.
- "My points" polls `GET /me/profile` after a collection submit.
- Leaderboard polls `GET /leaderboards/...` every 60 s on view.
- **No WebSocket, no Kafka, no streams.** All requests hit the API directly.

This is the correct starting point. It works for thousands of concurrent users, finds product-market-fit cheaply, and surfaces exactly which surfaces actually need realtime.

### Phase 1 — Server-Sent Events (SSE) for the user stream

- One endpoint: `GET /events/me` (SSE).
- Pushes only events for the authenticated user (level-up, achievement, points awarded, moderation outcome).
- Backed by an in-memory pub/sub on each app instance + Redis pub/sub for cross-instance fan-out.

**Why SSE first**: it's HTTP, traverses every proxy and corporate firewall, requires no client library on web, no WebSocket framing on the server. The user stream is low-fan-out and unidirectional — SSE is the right tool.

### Phase 2 — WebSocket gateway for the map + leaderboard streams

When concurrent map viewers per region exceed a few thousand, polling stops being cheap. Introduce:

- A dedicated **WebSocket Gateway** service (separate from the main API).
- Clients open one WS connection on app start and **subscribe** to channels:
  - `map:geohash:{prefix}` — events in a viewport region
  - `leaderboard:{scope}:{period}` — leaderboard diffs for a specific board
  - `user:{userId}` — personal stream (now WS-multiplexed instead of separate SSE)
- Backend services publish events to **Kafka topics**; the gateway consumes Kafka and fans out to subscribed sockets.

### Phase 3 — Geographic sharding

When a single Kafka consumer group can't keep up with global pin events, shard the map topic by **geohash prefix** (e.g., 4-character geohash → ~20 km cells). Each gateway instance subscribes only to the cells its connected clients care about.

### Phase 4 — Specialized streams

- Moderation queue live updates (for moderator dashboards) — separate channel.
- Operational dashboards (event flow, queue depths) — separate stream entirely, internal only.

**Phases 0–1 cover the first ~12 months. Phases 2+ are demand-driven.**

## Event taxonomy

All realtime is downstream of **domain events** published by the modules. The same event drives:
- The synchronous database update (inside the transaction).
- The realtime fan-out (after commit).
- Analytics / audit (also after commit).

| Event | Source module | Realtime channels |
|---|---|---|
| `WastePointCreated` | `waste/` | `map:geohash:{prefix}` |
| `WastePointClaimed` | `collection/` | `map:geohash:{prefix}` (pin status changes) |
| `CollectionVerified` | `collection/` | `map:geohash:{prefix}`, `user:{collectorId}`, `user:{reporterId}` |
| `PointsAwarded` | `gamification/` | `user:{userId}` |
| `UserLeveledUp` | `gamification/` | `user:{userId}` |
| `AchievementUnlocked` | `gamification/` | `user:{userId}` |
| `LeaderboardSnapshot` | `leaderboard/` | `leaderboard:{scope}:{period}` (server-paced, every 2 s) |
| `ModerationOutcome` | `moderation/` | `user:{collectorId}` |

## The WebSocket Gateway pattern

```
                   ┌────────────────────────────┐
                   │       API service          │
                   │   (Spring Boot, REST)      │
                   └──────────────┬─────────────┘
                                  │ writes domain events
                                  ▼
                   ┌────────────────────────────┐
                   │           Kafka            │
                   │   topics: map.events       │
                   │           user.events      │
                   │           leaderboard.snap │
                   └──────┬─────────────┬───────┘
                          │             │
                          ▼             ▼
              ┌───────────────────┐  ┌───────────────────┐
              │ WebSocket Gateway │  │ Notification      │
              │  (separate svc)   │  │ Service (push)    │
              │  - JWT verify     │  └───────────────────┘
              │  - subscriptions  │
              │  - fan-out        │
              └─────────┬─────────┘
                        │ WebSocket
                        ▼
                ┌──────────────────┐
                │  Web / Mobile    │
                └──────────────────┘
```

### Why a separate gateway service

- Different scaling profile: API scales on request rate, gateway on **concurrent connections**.
- Different deployment cadence: gateway is mostly stable, API churns.
- Different runtime: gateway benefits from event-loop or virtual-thread tuning specific to many idle sockets.
- Failure isolation: a gateway crash doesn't take the API down.

### Connection lifecycle

1. Client obtains a short-lived **WS auth ticket** by calling `POST /realtime/ticket` (HTTP, JWT-authenticated). Ticket is opaque, ~30 s TTL, single-use, bound to userId + deviceId.
2. Client opens `wss://realtime.ecotrace.app/v1/stream?ticket=...`.
3. Gateway verifies the ticket against Redis, creates the connection record, deletes ticket.
4. Client sends `SUBSCRIBE` frames: `{"type":"sub","channel":"map:geohash:gcpv"}`.
5. Gateway validates the subscription against the user's permissions (e.g., user channels must match `userId`).
6. On Kafka events, gateway looks up subscribers by channel and writes to their socket.
7. Heartbeat ping every 25 s; client must pong within 10 s or the socket is dropped.

### Why a ticket, not the JWT directly

Browsers can't set arbitrary headers on `WebSocket` constructor. Putting the JWT in the URL leaks it into proxy logs. Tickets are throwaway, scoped, and let the gateway log only an opaque id.

## Subscription registry

In-memory per gateway instance:

```
Map<channelId, Set<connectionId>>
Map<connectionId, ConnectionState{ userId, deviceId, channels[], lastPongAt }>
```

When a Kafka event arrives, the gateway computes the channel id and iterates the set. **No DB lookup on the hot path.** When a client subscribes/unsubscribes/disconnects, both maps are updated atomically.

For multi-instance fan-out: each gateway instance consumes from a Kafka **shared consumer group** for its assigned partitions; partitions are sharded by channel id (geohash prefix or userId). A user's connection lands on whichever gateway holds their userId's partition (sticky routing via a thin load balancer with consistent hashing on the ticket → userId mapping).

## Backpressure & flow control

- **Per-socket send buffer cap**: e.g., 64 KB or 200 messages. If exceeded, the client is "slow" — drop the lowest-priority queued frames (map updates first, then leaderboard, never user-personal).
- **Coalescing**: for map updates in the same channel arriving within 250 ms, merge into one frame.
- **Server-paced leaderboard diffs**: never push raw `ZINCRBY` events. Every 2 s, snapshot the top-N from Redis and broadcast a single `LeaderboardSnapshot` containing only the rows that changed since the last snapshot.
- **Reconciliation on reconnect**: on reconnect, the client sends its last-seen event id per channel; the gateway replays missed events from a short Redis Streams buffer (last 60 s). Beyond that, the client must do a full re-fetch.

## Mobile push (background fallback)

WebSockets only work when the app is foregrounded. For background events that matter (collection verified, push notification of points), the **Notification module** consumes the same Kafka events and dispatches via APNs / FCM. The realtime stream and push notification are sibling consumers of the same event — never one driving the other.

## Topic catalog (Kafka)

| Topic | Partitioning key | Retention | Consumers |
|---|---|---|---|
| `waste.events` | geohash prefix (4 chars) | 7 d | gateway (map fan-out), analytics |
| `collection.events` | `wastePointId` | 30 d | gateway, gamification, leaderboard, analytics |
| `gamification.events` | `userId` | 30 d | gateway (user channel), notification, leaderboard |
| `leaderboard.snapshots` | `scope:period` | 1 h | gateway only |
| `moderation.events` | `collectionId` | 90 d | gateway (user channel), audit |

Retention values are realtime-driven; the source of truth is always Postgres.

## Scalability levers

| Pressure | Lever |
|---|---|
| Too many sockets per instance | Horizontal-scale the gateway, consistent-hash routing |
| Hot map region (one geohash dominates) | Increase partition count; shorten geohash prefix length |
| Slow consumers | Per-socket buffer + drop policy; never block the consumer |
| Kafka lag | Add partitions, scale consumer group, consider regional clusters |
| Leaderboard fan-out chatter | Server-paced diffs (already designed in) — tune from 2 s to 5 s if needed |

## Observability for realtime

| Metric | Purpose |
|---|---|
| `ws.connections.active` | Capacity planning |
| `ws.connections.churn_rate` | Detect bad clients / network issues |
| `ws.send.queue_depth` | Find slow consumers |
| `ws.frames.dropped{reason}` | Backpressure visibility |
| `kafka.consumer.lag{topic}` | End-to-end latency budget |
| `realtime.event.age_ms_p95` | Time from DB commit to socket write |

Trace every event end-to-end: a single trace id from API request → DB commit → Kafka publish → gateway consume → socket write.

## Deferred decisions

- **WebSocket protocol** (raw vs STOMP vs GraphQL subscriptions) — start raw JSON frames; revisit if multi-tenant subscription semantics get complex.
- **Cross-region realtime** — single-region is fine until there's measured latency pain in remote markets.
- **Presence / typing indicators / chat** — not in scope; would warrant a different stream design.
- **Replay window length** — 60 s of Redis Streams replay is a guess; tune from production reconnect data.

## Summary

- Three streams: viewport, user, leaderboard — each scaled independently.
- Phase 0 = polling. Phase 1 = SSE for user. Phase 2 = WebSocket gateway + Kafka. Phase 3+ = sharding.
- The gateway is a separate service consuming domain events from Kafka; the API never opens a socket.
- Server-pace leaderboards. Coalesce map updates. Reconcile on reconnect.
- Realtime never owns truth — Postgres does. Realtime is a cache with motion.
