# Step 8 — Deployment Topology

## Cloud choice

**AWS**, primary region.

| | AWS *(adopted)* | GCP | Azure |
|---|---|---|---|
| Spring Boot fit | Excellent (ECS, EKS, Beanstalk, Lambda Java) | Excellent | Excellent |
| Postgres + PostGIS managed | RDS / Aurora Postgres (PostGIS extension supported) | Cloud SQL Postgres | Azure Database for Postgres |
| Managed Kafka | MSK (or self-hosted on EKS) | Confluent on GCP | Event Hubs (Kafka API) |
| Managed Redis | ElastiCache | Memorystore | Azure Cache |
| Object storage | S3 | GCS | Blob Storage |
| Operational maturity for our stack | Highest | High | Medium |

The decision is operational, not technical: AWS has the deepest catalog of managed services covering every component we need (Postgres+PostGIS, Redis, Kafka, S3, CloudFront, Route53, KMS, SES, SNS, ECS Fargate, ALB) and the most mature IaC ecosystem (Terraform / CDK).

## Compute model: ECS Fargate (start), EKS (later)

| Option | Verdict |
|---|---|
| **ECS Fargate** *(adopted for Phase 1)* | Serverless containers. No node management. Right-sized for ≤ 20 services. Lower ops burden. |
| EKS (Kubernetes) | Adopt only when (a) team has dedicated platform engineer, or (b) workloads need k8s-specific features (operators, CRDs, advanced scheduling). |
| Lambda | Wrong fit. Spring Boot startup is too slow; we need warm long-running containers for the WebSocket gateway. |
| EC2 (raw) | Don't. We don't want to manage AMIs, patching, autoscaling groups by hand. |

**Rule of thumb**: Fargate until the bill from Fargate is provably > the salary cost of running EKS yourself. That's usually past hundreds of services or thousands of pods.

## Environments

| Env | Purpose | Data | Auth provider | Scale |
|---|---|---|---|---|
| **local** | Developer laptops | Docker Compose: Postgres+PostGIS, Redis, MinIO (S3), Localstack optional | Mock JWT issuer | 1 of each |
| **dev** | Shared integration | Small RDS, ElastiCache micro, single AZ | Real Google OAuth (test client) | minimal |
| **staging** | Pre-prod, prod-shaped | Production-shape (multi-AZ RDS, MSK 3 brokers) | Real Google OAuth (separate client) | 25% of prod baseline |
| **prod** | The real thing | Multi-AZ everything, point-in-time recovery, encrypted | Real Google OAuth (prod client) | autoscaled |

**Rule**: staging and prod must be shape-identical. Different scale is fine; different topology is not. If staging is single-AZ but prod is multi-AZ, you'll discover the AZ-failover bug in prod.

## Topology — Phase 1 (production)

```
                              Internet
                                  │
                                  ▼
                     ┌───────────────────────┐
                     │     CloudFront CDN    │
                     │   (static + media)    │
                     └────┬──────────────┬───┘
                          │              │
                  static/SPA            media (S3)
                          │
                          ▼
                  ┌───────────────────┐
                  │  Route 53 (DNS)   │
                  └────────┬──────────┘
                           │ api.ecotrace.app
                           │ realtime.ecotrace.app
                           ▼
                  ┌───────────────────┐
                  │  ALB (per-svc)    │  TLS termination, WAF
                  └─┬───────────────┬─┘
                    │               │
                    ▼               ▼
        ┌────────────────────┐ ┌────────────────────┐
        │  API service       │ │  WebSocket Gateway │
        │  ECS Fargate       │ │  ECS Fargate       │
        │  (autoscale)       │ │  (autoscale by     │
        │                    │ │   connection count)│
        └─┬────────┬─────────┘ └─────────┬──────────┘
          │        │                     │
          │        │                     │
          ▼        ▼                     ▼
   ┌──────────┐ ┌─────────┐      ┌─────────────┐
   │ RDS      │ │ Elasti- │      │   MSK       │
   │ Postgres │ │ Cache   │      │   (Kafka)   │
   │ +PostGIS │ │ Redis   │      │             │
   │ Multi-AZ │ │ cluster │      │             │
   └──────────┘ └─────────┘      └─────────────┘
          │
          ▼
   ┌──────────────┐
   │ PgBouncer    │  (connection pooling, fronting RDS)
   └──────────────┘

   Workers (separate ECS services):
   - notification-worker (consumes gamification.events, sends APNs/FCM/email)
   - moderation-worker
   - reconciliation-worker (nightly: ledger ↔ users.total_points)
   - leaderboard-snapshotter (every 2s, writes leaderboard.snapshots topic)
```

## Component placement

| Component | Service | Why |
|---|---|---|
| **API** | ECS Fargate, behind ALB | Stateless HTTP; autoscale on CPU + RPS |
| **WebSocket Gateway** | ECS Fargate, separate ALB target group with sticky sessions | Long-lived connections need different scale + sticky routing |
| **Postgres** | RDS Postgres (gp3 SSD, 100 GB initial, autosize), PostGIS extension, multi-AZ | Managed backups, failover, point-in-time recovery |
| **Connection pool** | PgBouncer in transaction mode, ECS sidecar or ECS service | RDS max_connections is the bottleneck; pool aggressively |
| **Redis** | ElastiCache Redis 7, cluster mode, multi-AZ | Sorted sets for leaderboards, blocklist, sessions |
| **Kafka** | MSK (3 brokers, multi-AZ) | Domain-event backbone; only enabled at Phase 2 |
| **Object storage** | S3 with lifecycle rules (move to IA at 90 d, Glacier at 1 y) | Photos |
| **CDN** | CloudFront in front of S3 + the SPA bucket | Media + web assets |
| **Email** | SES | Transactional email (account, moderation outcomes) |
| **Push** | SNS → APNs / FCM | Mobile push notifications |
| **Secrets** | AWS Secrets Manager + KMS | JWT signing keys, OAuth client secrets, DB creds |
| **Logs** | CloudWatch Logs → optionally exported to a log warehouse | Centralized; structured JSON |
| **Metrics** | CloudWatch + Prometheus (managed via AMP later) | App metrics on Prometheus, infra on CloudWatch |
| **Tracing** | AWS X-Ray or OpenTelemetry → Tempo/Jaeger | End-to-end traces |

## Network layout

```
┌──────────── VPC ─────────────────────────────┐
│                                              │
│  Public subnets (per AZ):                    │
│   • ALBs                                     │
│   • NAT gateways                             │
│                                              │
│  Private subnets (per AZ):                   │
│   • ECS tasks (API, gateway, workers)        │
│   • Lambda for batch jobs (later)            │
│                                              │
│  Data subnets (per AZ):                      │
│   • RDS                                      │
│   • ElastiCache                              │
│   • MSK                                      │
│                                              │
│  Egress: NAT GW → Internet                   │
│  Ingress: ALB only                           │
│                                              │
└──────────────────────────────────────────────┘
```

- **Private** subnets host all compute; **data** subnets host all data services with no internet route.
- Security groups, not NACLs, are the primary control.
- VPC endpoints for S3, Secrets Manager, KMS — keeps that traffic off the NAT.

## Domain & DNS

| Hostname | Use |
|---|---|
| `ecotrace.app` | Marketing site (separate, static) |
| `app.ecotrace.app` | Web app SPA |
| `api.ecotrace.app` | REST API |
| `realtime.ecotrace.app` | WebSocket / SSE |
| `cdn.ecotrace.app` | CloudFront for media |
| `*.ecotrace.app` | Wildcard ACM certificate |

## CI/CD pipeline

```
Developer → PR → CI checks (build, test, ArchUnit, Trivy scan, lint)
                    │
                    ▼
       Merge to main → Build container → Push to ECR
                    │                       │
                    ▼                       │
       Auto-deploy to dev                   │
                    │                       │
                    ▼                       │
       Manual promote to staging            │
                    │                       │
                    ▼                       │
       Manual promote to prod (blue/green via ECS)
```

### Pipeline rules

- **One artifact, multiple environments.** The same container image is promoted; only configuration changes between envs.
- **Database migrations** run as a separate ECS task before app rollout. Flyway in **migrate** mode. Migrations must be **backward-compatible** with the previous app version (the deployment overlap window).
- **Feature flags** for risky features. Don't gate via deploy.
- **Blue/green** for the API. Rolling for workers (idempotent).
- **Smoke tests** post-deploy: health, login, create-pin, list-pins.
- **Auto-rollback** on error-rate spike or 5xx > threshold for 2 min.

## Secrets

- All secrets in **AWS Secrets Manager**.
- ECS task IAM role grants read access to specific secret ARNs only.
- JWT signing keys: stored in Secrets Manager, rotated quarterly. The `kid` field in JWT headers makes rotation graceful (two active keys at once during rotation window).
- No secrets in container images. No secrets in env files committed to git.
- Per-env secrets are physically separate (different ARNs, different KMS keys for prod vs non-prod).

## Regions & DR

| Aspect | Phase 1 | Later |
|---|---|---|
| Primary region | one (e.g., `eu-central-1`) | unchanged |
| Multi-AZ | yes (RDS, MSK, ECS) | yes |
| Cross-region DR | RDS automated cross-region snapshot, S3 CRR | Pilot-light warm standby in second region |
| RPO target | 1 h (snapshot frequency) | 5 min |
| RTO target | 4 h (manual failover) | 30 min |

DR is **deliberately deferred** beyond multi-AZ. A second-region active-active is expensive to build and to test; it's worth doing only after the business has revenue at risk.

## Cost outlook (Phase 1, rough)

| Item | Approx monthly |
|---|---|
| RDS db.m6g.large multi-AZ + 100 GB | $300 |
| ElastiCache cache.m6g.large multi-AZ | $200 |
| MSK 3 × m5.large brokers | $400 (skip until Phase 2) |
| ECS Fargate (5 services, baseline) | $200–$400 |
| ALB (×2) + NAT + data transfer | $150 |
| S3 + CloudFront | $50 (initial) |
| CloudWatch + observability | $100 |
| **Total before MSK** | **~$1,000–1,200/mo** |

Costs scale with traffic. The cheap-but-non-negotiable items are multi-AZ Postgres and proper backups.

## Risks specific to deployment

| Risk | Mitigation |
|---|---|
| **RDS connection exhaustion** under load | PgBouncer in transaction mode; tune `max_connections` |
| **ECS task count thrashing** under spiky load | Conservative target tracking; long cooldown |
| **MSK at 100% disk** | Retention bytes-based limits per topic; alert at 70% |
| **NAT gateway data charges** runaway | VPC endpoints for S3/SecretsManager/KMS; review monthly |
| **Single region outage** | Documented RTO/RPO; runbook for restoring from snapshots in alt region |
| **Deploy breaks reverse-incompatible** | Migration policy: never drop columns in same release that stops reading them |

## Deferred decisions

- **EKS migration trigger** — when ops cost crosses Fargate bill or when service count > ~20.
- **Multi-region active/active** — only when SLA demands it.
- **Spot for workers** — viable for non-time-critical workers (analytics, snapshotter); skip until cost matters.
- **Dedicated bastion / VPN** — start with AWS SSM Session Manager (no bastion).

## Summary

- AWS, single region, multi-AZ. ECS Fargate for compute. RDS Postgres multi-AZ.
- One container image, promoted across environments. Same shape staging→prod.
- Stateful systems isolated to data subnets; compute in private subnets; ALB in public.
- Deploy via blue/green API + rolling workers, with auto-rollback.
- Defer EKS, multi-region, exotic topology until traffic and team justify them.
