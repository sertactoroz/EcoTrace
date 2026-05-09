# Step 9 — Observability

## What "observability" covers

Five pillars, not three. Most teams stop at the first three and then can't answer the questions that matter.

| Pillar | Question it answers |
|---|---|
| **Logs** | What happened? |
| **Metrics** | How much, how often, how fast? |
| **Traces** | Where did the time/error go across services? |
| **Real User Monitoring (RUM)** | What did users actually experience? |
| **Errors / exceptions** | What's broken right now and who owns it? |

A system without all five has blind spots that show up exactly when you need them most.

## Service-Level Objectives (SLOs)

SLOs are the contract. Alerts fire on **error budget burn**, not on raw threshold breaches.

| SLI | SLO target | Window | Why |
|---|---|---|---|
| API availability (5xx-free) | 99.9% | 30 d rolling | Standard |
| API p95 latency `GET /map` | < 250 ms | 30 d | Map is the most-hit endpoint |
| API p95 latency `POST /collections/.../submit` | < 1 s | 30 d | Includes media-finalize round-trip |
| Verification → points award latency | < 500 ms p95 | 30 d | Inside one transaction |
| WebSocket connection success rate | > 99.5% | 30 d | Realtime onboarding |
| Realtime event end-to-end (commit → socket) | < 2 s p95 | 30 d | UX promise |
| Push delivery success | > 99% | 30 d | (best-effort by APNs/FCM) |
| Background-job success rate | > 99.9% | 7 d | Notification, reconciliation |
| **Ledger consistency** | **100%** | always | **No error budget. Period.** |

That last row matters: most things are allowed to fail occasionally; the points ledger is not.

## Logs

### Format

- **Structured JSON only.** No string-concatenated logs in production.
- One event per line. No multi-line stack traces — flatten to a single field.
- Mandatory fields: `timestamp`, `level`, `service`, `env`, `traceId`, `spanId`, `userId` (if known), `requestId`, `event`, `message`.
- Optional structured fields: `wastePointId`, `collectionId`, `latencyMs`, etc.

### Log levels — discipline

| Level | When |
|---|---|
| `ERROR` | Something needs human attention. Maps to alerts. |
| `WARN` | Recoverable, but unusual. Investigate when seen in patterns. |
| `INFO` | Important business events: collection verified, points awarded, login. |
| `DEBUG` | Disabled in prod by default. Enable per-request via `X-Debug-Trace: true` from internal IPs. |
| `TRACE` | Never in prod. |

**Never log secrets, full JWTs, full email addresses (hash them), or full request bodies for auth endpoints.**

### Sampling

- All `ERROR` and `WARN` are kept.
- `INFO` for auth, mutations, payments-equivalent (verifications) are kept.
- High-volume `INFO` (every successful map fetch) is sampled at 1% in prod.

### Storage

- Phase 1: CloudWatch Logs with subscription filter to S3 for cold storage (90 d hot, 1 y cold).
- Phase 2+: ship to a log warehouse (Loki, Elastic, or Grafana Cloud Logs). Don't build your own.

## Metrics

### Tax onomy — naming convention

`{module}.{entity}.{action}.{measurement}`

Examples:
- `waste.pin.created.count`
- `collection.verify.duration_ms` (histogram)
- `gamification.points.awarded.total`
- `auth.login.failure.count{reason="email_unverified"}`
- `realtime.event.lag_ms{stream="map"}`

### Cardinality discipline

| Allowed dimensions | Forbidden dimensions |
|---|---|
| `env`, `region`, `service`, `endpoint`, `status_class` (2xx/4xx/5xx), `error_code`, `module` | `userId`, `wastePointId`, `requestId`, raw URL |

Per-user metrics belong in **logs and traces**, not metrics. Time-series databases die when cardinality explodes.

### Required dashboards (golden signals + business)

1. **API health**: RPS, p50/p95/p99 latency by endpoint, error rate, saturation (DB pool, JVM heap).
2. **Database**: connection count, query duration, replication lag (when read replicas exist), top slow queries, deadlocks.
3. **Realtime**: active WS connections, frames/sec, drop rate, Kafka consumer lag.
4. **Domain** (the one that matters to the business):
   - Pins created / hour
   - Collections submitted / verified / rejected per hour
   - Points awarded per hour
   - Active users (DAU realtime)
   - Verification rate (verified / submitted)
   - Median time pin → first claim
   - Median time submit → verify
5. **Cost-aware**: NAT data transfer, S3 request count, CloudWatch ingestion, Fargate task hours.

## Traces

- **OpenTelemetry** SDK in the API and gateway. Traces exported to AWS X-Ray (Phase 1) or Tempo/Jaeger (Phase 2).
- **Sample at the edge**: 100% of errors, 10% of normal traffic, 1% during peak.
- **Span name = operation**, not the URL: `WastePointService.createWastePoint`, not `POST /v1/waste-points`.
- **Required attributes** on every span: `userId`, `tenantId` (future), `featureFlag` (when relevant).
- Distributed traces must follow events: `requestId` → DB span → Kafka publish → gateway consume → socket write all carry the same trace id.

### What traces solve that metrics don't

> "p99 latency on `POST /collections/{id}/submit` doubled this morning."

Metrics tell you it doubled. The trace tells you the extra 400 ms is in the S3 head-object call inside `MediaService.assertPhotoExists`.

## Errors

Use **Sentry** (or Rollbar / Bugsnag — pick one and standardize).

- Capture every uncaught exception with stack, request context, user id, release version.
- Group intelligently — the platform does this automatically; review and merge wrong groups weekly.
- **Release tagging**: every deploy gets a release id; errors are attributed to the release that introduced them.
- **Source maps** uploaded for the web app on every build.
- **Issue → owner**: each Sentry project mapped to a code-owner team (or just a human while small).

## Real User Monitoring (RUM)

Frontend-only. Captures actual user experience, not synthetic metrics.

- Web: Sentry Performance or Datadog RUM — page load timing, interaction-to-next-paint, route-change time, JS error rate.
- Mobile: Firebase Performance Monitoring or Sentry Mobile — app start, network call timing, crash-free session rate.

**Single most important RUM metric for this product**: time from "tap to claim" to "see the in-progress collection screen." If that drops, users abandon mid-flow.

## Alerting

Three tiers, with very different triggers and recipients.

### P1 — wake someone up

Customer impact in progress. Page on-call.

- API 5xx rate > 1% for 5 min
- API availability burn rate > 14× (will exhaust 30-day budget in 2 days)
- Database CPU > 90% sustained 10 min OR connection pool exhausted
- Ledger reconciliation drift detected (any drift)
- Any `ERROR` log with `event=ledger_inconsistency`
- Authentication failures > 50/min sustained (likely attack)

### P2 — Slack/email, response within an hour

Degraded but not bleeding.

- API p95 latency 50% above SLO sustained 15 min
- Background-job failure rate > 0.5% for 30 min
- Kafka consumer lag > 30 s for 10 min
- WS connection success rate < 99% for 15 min
- Sentry: new error type with > 100 events in 1 h

### P3 — ticket, fix this week

Trends and capacity.

- Disk > 70%
- Cost anomaly > 25% week-over-week on any line item
- Top-N slow queries shifted

### Anti-patterns to refuse

- Alerting on every 5xx. Use a rate.
- Alerting on every CPU spike. Use sustained burn rate.
- Alerts that no one knows how to action. Every alert needs a **runbook link** in its description.
- Alerts that fire and self-resolve constantly. Either fix the cause or change the threshold — alert fatigue kills response.

## Runbooks

Every P1 and P2 alert links to a runbook. Runbook format:

```
# Alert: <alert name>

## What it means
<Plain English>

## Likely causes (ranked)
1. ...
2. ...

## How to verify
<commands, dashboards, queries>

## Mitigation steps
<step-by-step, including rollback if applicable>

## Who owns this
<team / person>
```

Runbooks live in the repo, near the code (`/runbooks/...`), so they version with the system. Stale runbooks are worse than no runbooks.

## Frontend / mobile observability

- **Web**: Sentry for errors + RUM. Web vitals (CLS, INP, LCP) sent to the same backend. Source maps uploaded per release.
- **Mobile**: Sentry mobile SDK for crashes; Firebase Performance for network timing. Crash-free session rate is the headline metric.
- **Logs**: never POST raw user logs to the backend in volume. Use the platform SDK's batched ingestion.

## Security observability

Treated as a first-class metric stream, not an afterthought.

- All authentication events (login, refresh, logout, suspension) → `audit_log` table AND structured-log stream.
- Failed-login spike alert (above) doubles as brute-force detector.
- All admin / moderator actions logged with actor, target, before/after.
- WAF logs on the ALB → S3 → reviewed weekly initially, then alerted on patterns.
- Quarterly: review IAM access, secret rotations, dependency CVE scans (Trivy on every build).

## Tooling stack — recommended starting point

| Need | Phase 1 pick | Why |
|---|---|---|
| Logs | CloudWatch Logs (+ S3 archive) | Already in AWS; structured-JSON friendly |
| Metrics | CloudWatch + Prometheus (AMP later) | App metrics in Prometheus, infra in CloudWatch |
| Traces | AWS X-Ray | Native, low setup |
| Errors | Sentry (SaaS) | Best-in-class grouping + release tagging |
| RUM | Sentry RUM (web) + Firebase Performance (mobile) | Same vendor for web; native for mobile |
| Dashboards | Grafana Cloud (or self-hosted) | Single pane over Prometheus/CloudWatch/Tempo/Loki |
| Alerts | Grafana Alerting / PagerDuty | Routing + on-call rotations |
| Synthetic checks | CloudWatch Synthetics or Checkly | One per critical user flow |

**Phase 2 evolution**: introduce Loki for logs (cheaper at scale), Tempo for traces, Mimir for metrics — managed via Grafana Cloud or self-hosted, depending on team size.

## What gets observability *first*

Order of implementation, because you can't do everything on day one:

1. Structured JSON logs with `traceId`/`requestId`/`userId` everywhere.
2. CloudWatch dashboard for API health (RPS, latency, errors).
3. Sentry for the API and the web app.
4. SLO dashboard for the top 5 endpoints.
5. P1 alerts (above) wired to PagerDuty.
6. Runbooks for every P1.
7. OpenTelemetry tracing on the critical path: login, create-pin, claim, submit, verify.
8. Domain dashboard (verifications/hour, points/hour, DAU).
9. Synthetic check: complete a "report → claim → submit → verify" round-trip every 5 min in staging, hourly in prod.
10. Frontend RUM, mobile crash reporting.

Anything past step 10 is platform polish — important, but not what makes the product reliable on day one.

## Deferred decisions

- **Self-hosted vs SaaS observability** — start SaaS (Sentry, Grafana Cloud). Migrate self-hosted only when bill > engineer-time savings.
- **Trace sampling tuning** — start at 10%, adjust based on storage cost and signal value.
- **Anomaly detection / ML alerts** — premature; start with static thresholds and burn rates.
- **Per-tenant observability** — N/A until multi-tenancy.

## Summary

- Five pillars: logs, metrics, traces, RUM, errors. All present from Phase 1.
- SLOs drive alerts via burn-rate, not raw thresholds. Ledger consistency has zero error budget.
- Structured JSON logs with mandatory `traceId`. Metric cardinality kept tight.
- OpenTelemetry traces tie API → DB → Kafka → gateway → socket together.
- Three alert tiers, every alert links a runbook, alert fatigue is treated as a bug.
- Tooling: CloudWatch + Sentry + Grafana to start; introduce Loki/Tempo/Mimir as scale demands.
