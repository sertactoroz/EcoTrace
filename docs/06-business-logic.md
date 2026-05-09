# Step 6 — Business Logic

## The domain loop

```
   REPORT                CLAIM                SUBMIT              VERIFY
  ────────             ────────              ────────            ────────
  user reports   →    different user   →   collector uploads  →  system or
  waste pin           claims it             after-photo +         moderator
  (PENDING_REVIEW     (CLAIMED)             location proof        validates
   or ACTIVE)                               (SUBMITTED)           (VERIFIED)
                                                                     │
                                                                     ▼
                                                              POINTS AWARDED
                                                              LEVEL UPDATED
                                                              ACHIEVEMENTS
                                                              CHECKED
```

**Critical rule: points are awarded ONLY at the `VERIFIED` transition.** Not at submission. Not at claim. This is the single chokepoint that makes fraud reversible — if a collection is later flagged, you reverse one ledger entry.

## Points calculation

### Base formula

```
points_awarded = BASE_COLLECTION_POINTS
               × waste_category.points_multiplier
               × volume_multiplier(waste_point.estimated_volume)
```

### Defaults

| Constant | Value | Source |
|---|---|---|
| `BASE_COLLECTION_POINTS` | **10** | `application.yml` → `gamification.points.base-collection` |
| Category multiplier | **1.00** (plastic, paper) → **2.00** (hazardous) | `waste_categories.points_multiplier` |
| Volume multiplier | SMALL=1.0, MEDIUM=1.5, LARGE=2.0 | enum-driven, in `GamificationProperties` |

A standard small-plastic collection: `10 × 1.0 × 1.0 = 10 pts`.

### Other point sources

| Event | Points | When credited |
|---|---|---|
| Verified collection | 10+ (formula above) | on `VERIFIED` |
| Successful report (your pin gets verified by someone) | +5 | on `VERIFIED` of a collection against your pin |
| Achievement unlock | varies (per `achievements.points_reward`) | on unlock |
| Moderator-confirmed false report | **−10** | on moderation decision |
| Streak / event bonuses | configurable | future |

All values in `GamificationProperties` (a `@ConfigurationProperties` POJO) so ops can tune without redeployment via config server later.

## Level system

Stored in the `levels` reference table:

| level | name | min_points |
|---|---|---|
| 1 | Beginner | 0 |
| 2 | Eco Friend | 100 |
| 3 | Eco Warrior | 300 |
| 4 | Green Master | 700 |

**`LevelResolver` is a pure function**: given a points total, return the level whose `min_points` is the highest value `≤ points`. Cache the levels list at boot. No DB hit per resolution.

A level-up is detected by comparing **before-points** and **after-points** within the same transaction; if they cross a threshold, emit a `UserLeveledUp` event for notifications.

## Where the logic lives

```
gamification/
├── service/
│   ├── PointsService.java                    ← orchestrates: write ledger + update user total
│   ├── LevelService.java                     ← resolve level, detect level-up
│   ├── AchievementEvaluator.java             ← rule engine over criteria JSON
│   └── listener/
│       └── CollectionVerifiedListener.java   ← @TransactionalEventListener(BEFORE_COMMIT)
├── domain/
│   ├── PointsCalculator.java                 ← pure function: (category, volume) → int
│   └── LevelResolver.java                    ← pure function: int → Level
├── repository/
│   └── PointsTransactionRepository.java
└── event/
    ├── PointsAwarded.java
    └── UserLeveledUp.java
```

### Layer responsibilities

| Layer | Holds | Does **not** hold |
|---|---|---|
| **`domain/`** (pure logic) | Calculation formulas, level thresholds, rule evaluation | DB calls, Spring annotations, events |
| **`service/`** (application) | Transactions, event publication, coordination | Calculation math (delegate to domain), HTTP concerns |
| **`listener/`** | Glue between modules' events | Business decisions (delegate to service) |
| **`repository/`** | JPA + native queries | Business logic |
| **`controller/`** | Read-side endpoints (`GET /me/points`, `GET /me/level`) | Mutations — those are event-driven from `collection/` |

**Critical principle**: the `gamification` module has **zero write-side controllers**. There's no `POST /points`. Points come into existence only as a *consequence* of a `CollectionVerified` event from the `collection/` module. This keeps the integrity-critical path single-sourced.

## The verification transaction (the integrity-critical path)

When a moderator (or auto-verifier) marks a collection as `VERIFIED`, **exactly one transaction** must do all of:

```
BEGIN TRANSACTION

 1. Lock the collection row (SELECT ... FOR UPDATE)
 2. Validate state: collection.status MUST be SUBMITTED
       → if not, throw IllegalStateException, rollback
 3. Validate idempotency: no existing points_transactions row
    with reason='COLLECTION' AND collection_id=?
       → if exists, no-op success (idempotent endpoint)
 4. Compute points (pure function, no I/O):
       awarded = base × category.multiplier × volume.multiplier
 5. UPDATE collections
       SET status='VERIFIED',
           verified_at=now(),
           points_awarded=?,
           reviewed_by_user_id=?
 6. UPDATE waste_points
       SET status='VERIFIED',
           verified_collection_id=?,
           updated_at=now()
 7. INSERT points_transactions (collector_user_id, +awarded, 'COLLECTION', ...)
 8. UPDATE users
       SET total_points = total_points + awarded,
           level = newLevel  -- computed in step 4
       WHERE id = collector AND version = expectedVersion  -- @Version
 9. INSERT points_transactions (reporter_user_id, +5, 'BONUS', ...)
       UPDATE users SET total_points = total_points + 5 ... (reporter)
10. Achievement evaluation:
       for each rule whose criteria might be newly met:
         INSERT user_achievements if first-time match
         INSERT points_transactions for achievement reward
         UPDATE users.total_points
11. PUBLISH events (deferred until AFTER_COMMIT):
       • CollectionVerified
       • PointsAwarded (one per credit)
       • UserLeveledUp (if level changed)
       • AchievementUnlocked (if any)

COMMIT
```

Then, **after commit**, asynchronous listeners react:

- `leaderboard/` updates Redis sorted sets (`ZINCRBY`)
- `notification/` queues a push: "+10 pts! You're now Eco Friend"
- `analytics/` records the event

If the post-commit work fails, the user already has their points and level — the leaderboard/notification will catch up via retry. **Eventual consistency is acceptable for derived state, never for the ledger itself.**

## Consistency rules — the non-negotiables

### Rule 1 — Single transactional boundary for "verify"

All ledger writes + user total updates + collection/pin status updates in one DB transaction. Spring's `@Transactional` on `PointsService.awardForVerifiedCollection(...)` is the boundary.

### Rule 2 — Ledger as source of truth

`users.total_points` must equal `SUM(points_transactions.delta)` for that user.

The ledger is authoritative. The `total_points` column is a denormalized projection. A daily reconciliation job recomputes from the ledger and alerts on drift.

### Rule 3 — Idempotency on the verify endpoint

Verify can be retried by clients/moderators. Enforce idempotency via:
- A **partial unique index** on `points_transactions (collection_id) WHERE reason = 'COLLECTION'` — second insert raises a constraint error → caught and treated as success.
- State guard: if `collection.status` is already `VERIFIED`, return current state.

### Rule 4 — Optimistic locking on `users`

`@Version` column on `User`. Concurrent point-awards retry on `OptimisticLockException`. Bounded retries (3 attempts, then fail loudly).

### Rule 5 — Async work is post-commit only

`@TransactionalEventListener(phase = AFTER_COMMIT)` for everything downstream. Never `BEFORE_COMMIT` for side effects, because a commit failure leaves stale notifications and inflated leaderboards.

### Rule 6 — Reversal is symmetric

A reversed collection (fraud, mistake) emits `CollectionReversed`. The handler inserts a **negative** `points_transactions` row (reason = `REVERSAL`), updates `users.total_points`, recomputes level (which may go down), and the leaderboard listener does `ZINCRBY -N`. No deletes — history is preserved.

### Rule 7 — Anti-fraud gates run before VERIFY

Pre-conditions checked at the `SUBMIT → VERIFIED` transition:
- `collector_user_id ≠ reported_by_user_id` (already a CHECK constraint)
- `distance_from_pin_m ≤ MAX_DISTANCE` (configurable, default 50m)
- `dwell_seconds ≥ MIN_DWELL` (default 30s)
- User velocity sane: ≤ N collections in last hour
- Both before-photo (on the pin) and after-photo (on the collection) exist
- Collection is not a duplicate of one already verified for the same pin

Failures route to `REJECTED` with a reason, not silently to `VERIFIED`.

## Deferred decisions

- **Auto-verify vs. moderator-only verify** — depends on the verification model decision (still pending). The state-machine design works with either.
- **Anti-fraud ML model** — start with the deterministic gates above; layer ML in once labeled data exists.
- **Streaks, weekly bonuses, seasonal events** — all expressible as additional `points_transactions` reasons; no schema change needed.
- **Decay (points lose value over time)** — adds complexity to leaderboards. Skip until product asks.
- **Multi-currency points** (e.g., separate "impact points" vs "redeemable points") — only if a redemption / rewards system is added.
