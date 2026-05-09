# Step 5 — API Contract

## Design principles

1. **REST + verbs for actions.** Resources are nouns (`/waste-points`); state transitions become **action sub-resources** (`/waste-points/{id}/claim`, `/collections/{id}/submit`).
2. **Never expose JPA entities.** Every endpoint returns DTOs. Field names use `camelCase` in JSON regardless of `snake_case` in the DB.
3. **UUIDs everywhere user-visible.** No auto-increment IDs leaking on the wire.
4. **Idempotency by design** on every state-mutating endpoint.
5. **One error envelope** across the entire API.
6. **Versioning is explicit** (`/api/v1/...`).
7. **Pagination is mandatory** on every list endpoint.
8. **The map is a special case** — geographic queries, not list queries.

## URL & resource conventions

```
Base URL:   https://api.ecotrace.app
Version:    /api/v1
```

| Convention | Rule | Example |
|---|---|---|
| Resource collections | plural noun, kebab-case | `/api/v1/waste-points` |
| Single resource | `/{resource}/{id}` (UUID) | `/api/v1/waste-points/3f1a...` |
| Owned sub-resource | one level deep max | `/waste-points/{id}/photos` |
| State transition | `POST /{resource}/{id}/{action}` | `POST /collections/{id}/submit` |
| Current user shorthand | `/me` instead of `/users/{myId}` | `/me/stats` |
| Filters / sorting / paging | query params | `?status=ACTIVE&sort=createdAt,desc` |

**Two-level nesting cap.** Deeper goes flat with a query param.

## Versioning

URI versioning. `/api/v1`, `/api/v2`. Easy to route at the LB, trivially testable.

| Change | Breaking? | Action |
|---|---|---|
| Add new endpoint | No | ship in current version |
| Add optional request field | No | ship in current version |
| Add response field | No | clients ignore unknown |
| Loosen validation | No | ship in current version |
| Add new error code | No | ship |
| Rename / remove field | **Yes** | new version |
| Change field type or enum value | **Yes** | new version |
| Tighten validation | **Yes** | new version |
| Change auth semantics | **Yes** | new version |

**Deprecation policy**: when v2 launches, v1 supported for **≥ 6 months**. Deprecated endpoints return `Deprecation: true` and `Sunset: <date>` headers.

## Standard headers

### Request

| Header | Required | Purpose |
|---|---|---|
| `Authorization: Bearer <jwt>` | yes (except public endpoints) | App access token |
| `Content-Type: application/json` | on body requests | |
| `X-Client-Platform` | recommended | `ios` / `android` / `web` |
| `X-Client-Version` | recommended | client semver, used for forced upgrade banners |
| `X-Request-Id` | optional | client-generated UUID, echoed back, used for tracing |
| `Idempotency-Key` | **required on critical writes** | client-generated UUID |

### Response

| Header | When | Purpose |
|---|---|---|
| `X-Request-Id` | always | echoed (or server-generated) |
| `X-RateLimit-Limit` / `Remaining` / `Reset` | on rate-limited routes | client-side budgeting |
| `Retry-After` | on 429 / 503 | seconds to wait |
| `Location` | on 201 | URL of created resource |
| `ETag` | on cacheable GETs | conditional requests |
| `Deprecation` / `Sunset` | on deprecated endpoints | upgrade signal |

## Error model

**One envelope for every error**, regardless of HTTP status:

```json
{
  "error": {
    "code": "WASTE_POINT_ALREADY_CLAIMED",
    "message": "This waste point is currently claimed by another user.",
    "details": {
      "wastePointId": "3f1a...",
      "claimExpiresAt": "2026-04-27T15:00:00Z"
    },
    "traceId": "01HXY..."
  }
}
```

- **`code`** is the API contract — clients switch on it. Adding new codes = non-breaking; removing/renaming = breaking.
- **`message`** is for humans / logs, not for UI display.
- **`details`** is structured context.
- **`traceId`** is the same trace propagated through to logs and realtime events.

### HTTP status code policy

| Status | When |
|---|---|
| `200 OK` | success with body |
| `201 Created` | POST that creates a resource. Includes `Location` header |
| `202 Accepted` | async work kicked off |
| `204 No Content` | success without body |
| `400 Bad Request` | malformed JSON, missing required field, wrong type |
| `401 Unauthorized` | missing/invalid/expired token |
| `403 Forbidden` | authenticated but not allowed |
| `404 Not Found` | resource doesn't exist or caller can't see it |
| `409 Conflict` | state-machine violation, optimistic-lock failure |
| `410 Gone` | resource was soft-deleted |
| `422 Unprocessable Entity` | well-formed request fails business rule |
| `429 Too Many Requests` | rate-limited |
| `500 Internal Server Error` | bug or unhandled exception |
| `503 Service Unavailable` | dependency down, with `Retry-After` |

**400 vs 422**: `400` = "I can't parse this." `422` = "I parsed it but it breaks a rule."

### Canonical error code families

| Family | Examples |
|---|---|
| Auth | `AUTH_INVALID_TOKEN`, `AUTH_EXPIRED_TOKEN`, `AUTH_GOOGLE_VERIFICATION_FAILED`, `AUTH_EMAIL_NOT_VERIFIED` |
| Authorization | `FORBIDDEN`, `USER_SUSPENDED`, `USER_BANNED` |
| Validation | `VALIDATION_FAILED` (with `details.fields[]`) |
| State | `WASTE_POINT_ALREADY_CLAIMED`, `COLLECTION_NOT_SUBMITTABLE`, `COLLECTION_ALREADY_VERIFIED` |
| Business rules | `CANNOT_CLAIM_OWN_REPORT`, `OUT_OF_GEOFENCE`, `DWELL_TIME_INSUFFICIENT` |
| Rate / quota | `RATE_LIMITED`, `QUOTA_EXCEEDED` |
| System | `INTERNAL_ERROR`, `DEPENDENCY_UNAVAILABLE` |

## Pagination, filtering, sorting

### Pagination — two strategies

| Strategy | When | Shape |
|---|---|---|
| **Cursor-based** *(default for high-write or chronological)* | collections, points transactions, notifications, moderation queue | `?cursor=<opaque>&limit=20` |
| **Offset-based** | small bounded lists (top-100 leaderboard) | `?page=1&size=20` |
| **None** | reference data (categories, levels, achievements <100 items) | flat array |

**Default `limit` = 20. Max = 100.** Clients above the cap are silently clamped.

**Cursors are opaque, base64-encoded server tokens.** Stable across schema changes.

### Filtering

- Comma-separated values for `IN` filters: `?status=ACTIVE,CLAIMED`
- Range filters with suffixes: `?createdAtFrom=...&createdAtTo=...`
- Boolean flags as plain bools: `?onlyMine=true`
- Unknown query params → ignored, not 400 (forward-compatibility)

### Sorting

- `?sort=field,direction` — `?sort=createdAt,desc`
- Multiple sorts: `?sort=points,desc&sort=createdAt,desc`
- Whitelist of sortable fields per endpoint

### Page envelope (canonical)

```json
{
  "items": [ ... ],
  "page": {
    "limit": 20,
    "nextCursor": "eyJpZCI6...",
    "hasMore": true
  }
}
```

## Idempotency

### Where required

| Endpoint | Why |
|---|---|
| `POST /auth/google` | Network retry shouldn't create duplicate users |
| `POST /auth/refresh` | Already idempotent at token layer, but key recommended |
| `POST /waste-points` | Mobile retry shouldn't create duplicate pins |
| `POST /waste-points/{id}/claim` | Critical — claiming twice would create two open collections |
| `POST /collections/{id}/submit` | Re-submission must not double-record evidence |
| `POST /moderation/collections/{id}/verify` | **Critical** — double-verify would double-award points |
| `POST /moderation/collections/{id}/reject` | Same reasoning |
| `POST /media/upload-urls` | Avoids waste |

### Mechanism

1. Client generates a UUID per logical operation, sends `Idempotency-Key: <uuid>`.
2. Server stores `(key, userId, requestHash, response)` in Redis with **24h TTL**.
3. Repeat request with same key:
   - Same `userId` + same `requestHash` → return cached response (replay).
   - Same `userId` + different hash → `409 Conflict` with `IDEMPOTENCY_KEY_REUSED`.
   - Different `userId` → `403`.
4. In-flight request with same key → `409 IDEMPOTENCY_IN_PROGRESS` with `Retry-After: 1`.

## Rate limiting

| Tier | Default limits | Notes |
|---|---|---|
| **Anonymous** (auth endpoints) | 10/min, 30/hour per IP | tight — credential stuffing defense |
| **USER** | 120/min, 5,000/day per user | normal app use |
| **USER — write-heavy** | 30/hour for `POST /waste-points`, `POST /collections/.../submit`, photo uploads | prevents pin spam |
| **MODERATOR** | 600/min | bulk review workflows |
| **ADMIN** | 1,200/min | dashboards, exports |
| **Service-to-service** | per-credential | when integrations come |

429 responses include `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, error code `RATE_LIMITED` with `details.scope`.

**Burst handling**: token bucket, not fixed window.

## Endpoint catalog

Auth requirements: P public · U USER · M MODERATOR · A ADMIN

### Identity / Auth

| Method | Path | Auth | Idempotent | Purpose |
|---|---|---|---|---|
| POST | `/auth/google` | P | key required | Exchange Google ID token → app tokens. Creates user if first login |
| POST | `/auth/refresh` | P (refresh token) | key recommended | Rotate access + refresh tokens |
| POST | `/auth/logout` | U | yes | Revoke current refresh token, blocklist current access token |
| POST | `/auth/devices` | U | yes (upsert by token) | Register/update push token |
| DELETE | `/auth/devices/{deviceId}` | U | yes | Unregister device |

### Profile / Me

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/me` | U | Full current-user profile + stats summary |
| PATCH | `/me` | U | Update displayName, bio, avatarUrl, locale, timezone |
| DELETE | `/me` | U | Soft-delete account (kicks off GDPR scrub job) |
| GET | `/me/stats` | U | Counts, points, level, recent activity summary |
| GET | `/me/notifications` | U | Cursor-paginated inbox |
| PATCH | `/me/notifications/{id}/read` | U | Mark single notification read |
| PATCH | `/me/notifications/read-all` | U | Bulk mark read |
| GET | `/users/{userId}` | U | Public profile |

### Waste Points

| Method | Path | Auth | Idempotent | Purpose |
|---|---|---|---|---|
| GET | `/waste-points` | U | yes | **Spatial** search — see "Map endpoint" below |
| GET | `/waste-points/{id}` | U | yes | Full pin detail with photos |
| POST | `/waste-points` | U | key required | Create new pin |
| PATCH | `/waste-points/{id}` | U (owner, while PENDING_REVIEW) | yes | Edit description, category, volume — **not location** |
| DELETE | `/waste-points/{id}` | U (owner) or M | yes | Soft-delete; only if no active claim |
| POST | `/waste-points/{id}/claim` | U | key required | Start a Collection. 422 if claimed/own pin |
| POST | `/waste-points/{id}/flag` | U | key recommended | Create moderation report |
| GET | `/waste-points/{id}/photos` | U | yes | Photos attached to pin |

### Waste Categories

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/waste-categories` | P | Public reference data, ETag-cacheable |

### Collections

| Method | Path | Auth | Idempotent | Purpose |
|---|---|---|---|---|
| GET | `/me/collections` | U | yes | Caller's collection history, cursor-paginated |
| GET | `/collections/{id}` | U (collector or pin reporter) or M | yes | Single collection detail |
| POST | `/collections/{id}/submit` | U (collector) | key required | Move CLAIMED → SUBMITTED |
| POST | `/collections/{id}/cancel` | U (collector) | yes | Abandon claim → pin returns to ACTIVE |

> **No public `verify` endpoint.** Verification is a moderator action.

### Media

| Method | Path | Auth | Idempotent | Purpose |
|---|---|---|---|---|
| POST | `/media/upload-urls` | U | key recommended | Request N presigned upload URLs |
| POST | `/media/{photoId}/finalize` | U | yes | Confirm upload completed; server validates object exists |

### Gamification

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/me/points` | U | Current total, level, points to next level |
| GET | `/me/points/transactions` | U | Cursor-paginated ledger view |
| GET | `/levels` | P | All levels (cacheable) |
| GET | `/achievements` | P | All achievements (cacheable) |
| GET | `/me/achievements` | U | Unlocked achievements with timestamps |

### Leaderboards

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/leaderboards/{scope}` | U | Top-N for a scope |
| GET | `/leaderboards/{scope}/me` | U | Caller's rank in that scope |

`scope` is opaque from client perspective — server defines valid scopes.

### Moderation

| Method | Path | Auth | Idempotent | Purpose |
|---|---|---|---|---|
| GET | `/moderation/reports` | M | yes | Queue, filter by status / target / reason |
| GET | `/moderation/reports/{id}` | M | yes | Detail with target snapshot |
| POST | `/moderation/reports/{id}/resolve` | M | key required | `{action, reason}` |
| GET | `/moderation/collections/pending` | M | yes | Collections awaiting verify |
| POST | `/moderation/collections/{id}/verify` | M | **key required** | The integrity-critical endpoint — awards points |
| POST | `/moderation/collections/{id}/reject` | M | key required | `{reason}` |
| POST | `/moderation/users/{id}/suspend` | A | key required | `{reason, until?}` |
| POST | `/moderation/users/{id}/reinstate` | A | key required | |

### Admin

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST/PATCH | `/admin/waste-categories` / `/admin/waste-categories/{id}` | A | Manage taxonomy |
| POST/PATCH | `/admin/achievements` / `/admin/achievements/{id}` | A | Manage rules |
| GET | `/admin/users` | A | Search incl. soft-deleted |
| GET | `/admin/users/{id}` | A | Full audit view |
| GET | `/admin/audit-log` | A | System-wide audit trail |

### System

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/health/live` | P | Kubernetes liveness |
| GET | `/health/ready` | P | Kubernetes readiness (checks DB, Redis) |
| GET | `/version` | P | Build SHA, version, profile |

## The map endpoint — special treatment

`GET /waste-points` is the most-called read in the system.

### Required parameters (one of two modes)

| Mode | Params | Use case |
|---|---|---|
| **Bounding box** | `bbox=swLng,swLat,neLng,neLat` | Map view |
| **Radius** | `center=lat,lng&radius=meters` (max 5000) | "Near me" mobile flow |

Without one, return `400 BBOX_OR_CENTER_REQUIRED`. Map data is **never unbounded**.

### Optional parameters

| Param | Default | Purpose |
|---|---|---|
| `status` | `ACTIVE,CLAIMED` | Comma-list filter |
| `category` | all | Comma-list filter |
| `cluster` | `true` | Server-side clustering |
| `zoom` | required if `cluster=true` | Determines clustering precision |
| `limit` | 500 | Max raw pins returned (clamps to 2000) |

### Response shape (GeoJSON-shaped + clusters extension)

```json
{
  "type": "FeatureCollection",
  "viewport": { "bbox": [...] },
  "clusters": [
    { "geohash": "u4pruyd", "count": 42, "lat": 41.0, "lng": 29.0,
      "categoryBreakdown": { "PLASTIC": 30, "GLASS": 12 } }
  ],
  "features": [
    { "id": "3f1a...", "type": "Feature",
      "geometry": { "type": "Point", "coordinates": [29.01, 41.02] },
      "properties": {
        "status": "ACTIVE", "categoryCode": "PLASTIC", "estimatedVolume": "SMALL",
        "createdAt": "2026-04-26T...", "thumbnailUrl": "..."
      }
    }
  ]
}
```

**No personal info** — reporter identity is private until pin detail (`GET /waste-points/{id}`).

### Caching

`Cache-Control: public, max-age=30, s-maxage=120` on the bbox endpoint. Cache key includes the geohash bucket of the bbox center, not the raw bbox, so similar viewports share cache entries.

## Photo upload flow

Bytes never traverse Spring Boot.

```
1. Client → POST /media/upload-urls { count: 2, contentType: "image/jpeg", purpose: "WASTE_POINT" }

   Server returns:
   [
     { photoId: "p1...", uploadUrl: "https://s3...presigned...", expiresAt: "...",
       requiredHeaders: { "Content-Type": "image/jpeg" } },
     { photoId: "p2...", uploadUrl: "...", ... }
   ]

2. Client PUTs each file directly to its uploadUrl (S3 / GCS / R2).

3. Client → POST /media/{photoId}/finalize for each.

   Server:
     - HEAD checks the object exists
     - extracts metadata (size, dimensions)
     - runs lightweight checks (EXIF strip, content-type validation)
     - persists waste_point_photos / collection_evidence row
     - returns canonical CDN URL

4. Client uses photoId in:
     POST /waste-points          { ..., photoIds: ["p1","p2"] }
     POST /collections/{id}/submit { ..., evidencePhotoIds: [...] }
```

Presigned URLs expire (15 min). Unfinalized photos reaped by a daily job.

## Key request/response shapes

### `POST /auth/google`

**Request**
```json
{
  "idToken": "<google-id-token>",
  "device": {
    "platform": "IOS",
    "deviceId": "550e8400-...",
    "appVersion": "1.0.3",
    "pushToken": "fcm:..."
  }
}
```

**Response 200**
```json
{
  "accessToken": "<jwt>",
  "refreshToken": "<opaque>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "isNewUser": false,
  "user": {
    "id": "...", "email": "...", "displayName": "...",
    "avatarUrl": "...", "level": { "level": 2, "name": "Eco Friend" },
    "totalPoints": 145, "status": "ACTIVE"
  }
}
```

### `POST /waste-points`

**Request**
```json
{
  "location": { "lat": 41.0082, "lng": 28.9784 },
  "categoryCode": "PLASTIC",
  "estimatedVolume": "MEDIUM",
  "description": "Pile of bottles by the bench",
  "photoIds": ["p1...", "p2..."]
}
```

**Response 201** (with `Location: /api/v1/waste-points/3f1a...`)
```json
{
  "id": "3f1a...",
  "status": "PENDING_REVIEW",
  "location": { "lat": 41.0082, "lng": 28.9784 },
  "category": { "code": "PLASTIC", "displayName": "Plastic" },
  "estimatedVolume": "MEDIUM",
  "description": "Pile of bottles by the bench",
  "reportedBy": { "id": "...", "displayName": "..." },
  "photos": [{ "id": "p1...", "url": "..." }],
  "createdAt": "2026-04-27T14:30:00Z"
}
```

### `POST /waste-points/{id}/claim`

**Request**: empty body (idempotency key in header).

**Response 201** (returns the new Collection):
```json
{
  "id": "c-...",
  "wastePointId": "3f1a...",
  "status": "CLAIMED",
  "claimedAt": "2026-04-27T14:35:00Z",
  "claimExpiresAt": "2026-04-27T15:35:00Z"
}
```

**422 cases**: `WASTE_POINT_ALREADY_CLAIMED`, `CANNOT_CLAIM_OWN_REPORT`, `WASTE_POINT_NOT_ACTIVE`.

### `POST /collections/{id}/submit`

**Request**
```json
{
  "collectorLocation": { "lat": 41.0083, "lng": 28.9785 },
  "dwellSeconds": 47,
  "evidencePhotoIds": ["p3...", "p4..."],
  "notes": "Cleaned the whole area"
}
```

**Response 200**: full Collection in `SUBMITTED` status. **Points are not yet awarded** — that's at verification.

### `POST /moderation/collections/{id}/verify`

**Request**: empty body, `Idempotency-Key` required.

**Response 200**:
```json
{
  "collection": {
    "id": "c-...", "status": "VERIFIED",
    "verifiedAt": "...", "pointsAwarded": 15
  },
  "pointsTransaction": {
    "id": "...", "userId": "...", "delta": 15, "reason": "COLLECTION"
  },
  "userAfter": {
    "totalPoints": 160, "level": { "level": 2, "name": "Eco Friend" },
    "leveledUp": false
  }
}
```

The response carries enough state for the moderator UI to update without a follow-up GET. **Idempotent replay returns the exact same body.**

## Out of scope for this step

- **OpenAPI / Swagger document** — generated from controller annotations later. The spec above is the source of truth that the OpenAPI doc must match.
- **Webhook / outbound API** — defer until there's a partner.
- **GraphQL** — REST is sufficient.
- **Bulk endpoints** — add only when a real client need appears.
- **Search by text / filters beyond category** — defer until product asks.
- **CSV / data export** — admin-only, defer.
