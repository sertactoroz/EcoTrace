# Step 4 — Authentication

## Firebase Auth vs Direct OAuth2 — decision

| | Firebase Auth | **Direct Google OAuth2** *(adopted)* |
|---|---|---|
| What client gets | Firebase ID token (JWT signed by Google via Firebase) | Google ID token (JWT signed by Google) |
| What backend verifies | Firebase Admin SDK | Google's JWKS endpoint |
| Vendor lock-in | Firebase (Google) — UID format, account linking, recovery flows are Firebase-shaped | Just Google's identity service |
| Spring Boot fit | Works, but Admin SDK is a bolt-on | First-class: `spring-boot-starter-oauth2-resource-server` |
| Adding Apple/email later | Trivial — Firebase handles it | Add provider verifiers (one class each) |
| Operational complexity | Two identity systems (Firebase + DB) | One identity system (DB) |

**Decision**: Direct Google OAuth2. Spring's `oauth2-resource-server` is built for exactly this pattern. No third-party dependency, no Firebase outage in the auth path, and `user_auth_providers` is already designed to hold multiple providers per user.

> The one valid reason to pick Firebase is if client-side auth UX (sign-in widget, account linking, email verification) is desired for free. For "sign in with Google," that's overkill.

## The token-exchange pattern

A common mistake is using the Google ID token directly as the API access token. Don't. It's short-lived, can't be revoked, and can't carry app-specific claims.

**Correct pattern: Google token gets exchanged for the app's own JWT, once, at login.**

- Google ID token = proof of identity (used only at login/refresh)
- **App access token (the app's JWT)** = what every subsequent API call uses
- **App refresh token** = opaque, server-side-stored, lets the client get a new access token without re-doing Google sign-in

| Token | Format | Lifetime | Storage | Purpose |
|---|---|---|---|---|
| Google ID token | JWT (Google-signed) | ~1 hour | client memory only | one-time proof at login |
| App access token | JWT (app-signed, RS256) | **15 min** | client memory | every API call |
| App refresh token | opaque random + `jti` | **30 days** (rotating) | client secure storage + Redis (server) | obtain new access tokens |

### Why the app's own JWT, not Google's

- App-specific claims: `userId`, `role`, `level`, `status`
- Revocable (Redis blocklist by `jti`)
- Not coupled to Google's token format — adding Apple later is the same code path
- Rotation cadence under app control

## Authentication flow

### Login flow

```
┌────────┐           ┌────────┐         ┌─────────────────┐         ┌──────────┐         ┌───────┐
│ Client │           │ Google │         │ Backend         │         │ Postgres │         │ Redis │
│ (web/  │           │        │         │ (Spring Boot)   │         │          │         │       │
│ mobile)│           │        │         │                 │         │          │         │       │
└───┬────┘           └────┬───┘         └────────┬────────┘         └────┬─────┘         └───┬───┘
    │                     │                      │                       │                   │
    │ 1. Tap "Sign in     │                      │                       │                   │
    │    with Google"     │                      │                       │                   │
    │ ───────────────────▶│                      │                       │                   │
    │                     │                      │                       │                   │
    │ 2. Google ID token  │                      │                       │                   │
    │ ◀───────────────────│                      │                       │                   │
    │                                                                                        │
    │ 3. POST /auth/google { idToken }                                                       │
    │ ──────────────────────────────────────────▶│                                           │
    │                                            │                                           │
    │                                            │ 4. Fetch JWKS (cached) ──▶ Google         │
    │                                            │    Verify signature, iss, aud, exp        │
    │                                            │    Require email_verified = true          │
    │                                            │                                           │
    │                                            │ 5. Lookup user by (provider=GOOGLE,       │
    │                                            │    provider_user_id=sub)                  │
    │                                            │ ─────────────────────▶│                   │
    │                                            │ 6a. Found → load                          │
    │                                            │ ◀─────────────────────│                   │
    │                                            │ 6b. Not found:                            │
    │                                            │     • create users row                    │
    │                                            │     • create user_auth_providers row      │
    │                                            │     • emit UserRegistered event           │
    │                                            │ ─────────────────────▶│                   │
    │                                            │                                           │
    │                                            │ 7. Issue:                                 │
    │                                            │     • access JWT (15 min)                 │
    │                                            │     • refresh token (30 d, rotating)      │
    │                                            │    Store refresh by jti ─────────────────▶│
    │                                            │                                           │
    │ 8. { accessToken, refreshToken, user }     │                                           │
    │ ◀──────────────────────────────────────────│                                           │
    │                                                                                        │
```

### Authenticated request

```
    │ 9.  GET /api/waste-points        Authorization: Bearer <accessToken>
    │ ──────────────────────────────────────────▶│
    │                                            │ JwtAuthenticationFilter:
    │                                            │   • verify signature (local key)
    │                                            │   • check exp, nbf
    │                                            │   • check jti not in blocklist (Redis)
    │                                            │   • load AuthenticatedUser into context
    │                                            │
    │ 10. Response                               │
    │ ◀──────────────────────────────────────────│
```

### Refresh flow

```
    │ 11. POST /auth/refresh { refreshToken }
    │ ──────────────────────────────────────────▶│
    │                                            │ • lookup refresh by jti in Redis
    │                                            │ • verify not revoked, not expired
    │                                            │ • verify bound device matches
    │                                            │ • ROTATE: invalidate old jti, issue new pair
    │                                            │
    │ 12. { newAccessToken, newRefreshToken }    │
    │ ◀──────────────────────────────────────────│
```

### Logout

```
    │ 13. POST /auth/logout
    │ ──────────────────────────────────────────▶│
    │                                            │ • delete refresh token from Redis
    │                                            │ • add access token jti to blocklist
    │                                            │   (TTL = remaining access token lifetime)
```

## Backend responsibilities

| # | Responsibility | Notes |
|---|---|---|
| 1 | **Verify Google ID token** | Signature against Google JWKS (cache keys for 6h), issuer = `https://accounts.google.com`, audience = client ID, exp not passed |
| 2 | **Reject unverified emails** | `email_verified = true` required — Google signs tokens for unverified emails too |
| 3 | **Find or create user** | Lookup by `(GOOGLE, sub)` first; if email exists with another provider, link rather than create |
| 4 | **Account-linking policy** | Decide upfront: same email across providers → same user, or always separate? Recommend: *link if email_verified on both sides* |
| 5 | **Issue app access token (JWT)** | Claims: `sub` = userId, `email`, `roles`, `lvl`, `iat`, `exp`, `jti`. Sign with **RS256** (key in KMS / env) |
| 6 | **Issue refresh token** | Opaque random (256-bit), persist in Redis `refresh:{jti} → { userId, deviceId, expiresAt }` |
| 7 | **Bind refresh token to device** | Use `user_devices` row id; refresh from a different device fails |
| 8 | **Rotate on refresh** | Old `jti` deleted, new one stored. Detection of reuse = compromise → revoke all sessions for that user |
| 9 | **Verify app access token on every request** | Stateless (signature + Redis blocklist check on `jti`) |
| 10 | **Authorization layer** | After authentication, check `user.status = ACTIVE`; suspended/banned users get 403 even with valid token |
| 11 | **Audit auth events** | Login success/fail, refresh, logout, suspension. Goes to `audit_log` (or stdout for SIEM later) |
| 12 | **Rate limit auth endpoints** | `/auth/google`, `/auth/refresh` per IP and per user — protects against credential stuffing and refresh-storms |

## Spring Boot security approach

```
security/
├── SecurityConfig.java            ← stateless filter chain
├── jwt/
│   ├── JwtService.java            ← issue + verify the app's JWTs
│   ├── JwtAuthenticationFilter.java
│   └── JwtBlocklistService.java   ← Redis-backed
├── oauth2/
│   └── GoogleTokenVerifier.java   ← google-api-client or nimbus + JWKS
├── principal/
│   ├── AuthenticatedUser.java     ← record exposed via @CurrentUser
│   └── CurrentUserResolver.java
└── ratelimit/
    └── AuthRateLimitFilter.java
```

### Key decisions

- **Stateless** — `SessionCreationPolicy.STATELESS`. No `JSESSIONID`, no CSRF middleware needed (bearer-token-only, not cookies).
- **Filter chain order**: rate-limit → JWT auth → user-status check → controllers.
- **Authorization model** — start with role enum (`USER`, `MODERATOR`, `ADMIN`) on the JWT. Method-level `@PreAuthorize("hasRole('MODERATOR')")` for moderation endpoints. No ABAC/Spring ACL until multi-tenancy.
- **CORS** — explicit allow-list of web origins. Mobile uses headers, not browsers.
- **Public endpoints** — only `/auth/google`, `/auth/refresh`, `/health`, `/actuator/health`, `/swagger-ui/*` (only in non-prod).
- **HTTPS-only** in prod (HSTS header). Reject plain HTTP at the load balancer, not in Spring.
- **Secrets** — JWT signing key from KMS or env, never config files. Rotation supported via key id (`kid`) in JWT header — must support **two active keys simultaneously** for rotation without downtime.
