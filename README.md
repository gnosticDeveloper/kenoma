# Kenoma

**Privacy-first B2B SaaS infrastructure.** Kenoma is a reactive, multi-tenant microservices platform providing authentication, organization management, secrets handling, and inventory management as composable backend services.

[![CI](https://github.com/gnosticDeveloper/Kenoma/actions/workflows/ci.yml/badge.svg)](https://github.com/gnosticDeveloper/Kenoma/actions/workflows/ci.yml)
[![CodeQL](https://github.com/gnosticDeveloper/Kenoma/actions/workflows/codeql.yml/badge.svg)](https://github.com/gnosticDeveloper/Kenoma/actions/workflows/codeql.yml)
[![License: BUSL-1.1](https://img.shields.io/badge/License-BUSL--1.1-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-0.1.0--BETA-informational.svg)](pom.xml)

---

## Services

| Service | Port | Description |
|---|---|---|
| **Raum** | `8080` | Organization registry. Manages tenants, registered services, ephemeral database credentials via OpenBao, and billing/invoicing |
| **Vassago** | `8081` | Authentication and identity. JWT issuance, session management, user lifecycle, and password recovery |
| **Bime** | `8082` | Inventory management. Products, variants, metadata, stock ledger, and warehouse locations |
| **Common** | No port | Shared library: DTOs, exception handling, JWT validation, R2DBC connection pooling, and the Mailgun email client |
| **Frontend** | `5173` (dev) | React/Vite admin UI. Dark/light/auto themes, EN/ES i18n |

All backend traffic is fronted by **nginx** (config templated from `gateway/nginx.conf.template`), which terminates TLS, applies per-IP rate limiting in front of Vassago's public endpoints, and reverse-proxies `api.<BASE_DOMAIN>` to the three services by path, plus `grafana.<BASE_DOMAIN>` to the observability stack.

### Raum: Organization & Credential Registry

Raum is the platform's administrative backbone. It provisions tenant organizations, registers services that consume the platform, and issues ephemeral database credentials through OpenBao AppRole so downstream services never hold long-lived secrets. It also orchestrates new organization onboarding, provisioning the org admin account in Vassago and seeding initial inventory data in Bime according to a configurable preset, with Redis-backed retry so partial failures can be recovered automatically. Raum additionally owns billing: per-module pricing, multi-currency support with a scheduled FX rate refresh, invoice generation and delivery, manual payment-status management, disaster-recovery backups, and on-demand per-tenant data export.

**API surface:**

| Method | Path | Description |
|---|---|---|
| `POST` | `/orgs` | Create an organization |
| `GET` | `/orgs/{id}` | Get an organization |
| `PUT` | `/orgs/{id}` | Update an organization |
| `DELETE` | `/orgs/{id}` | Delete an organization |
| `PUT` | `/orgs/{id}/billing-info` | Update an organization's billing details |
| `POST` | `/orgs/{id}/billing-email` | Set/change the organization's billing email |
| `POST` | `/orgs/{id}/billing-email/confirm` | Confirm a billing email change |
| `POST` | `/orgs/{id}/export` | Kick off an async per-tenant data export job |
| `GET` | `/orgs/{id}/export/{jobId}` | Poll export job status / retrieve the result |
| `GET` | `/orgs/{orgId}/billing-history` | List an organization's billing history |
| `GET` | `/orgs/{orgId}/billing-history/{historyId}/invoice` | Download a generated invoice |
| `PUT` | `/orgs/{orgId}/billing-history/{historyId}/payment-status` | Manually set a payment's status |
| `POST` | `/orgs/{orgId}/billing-history/{historyId}/resend` | Resend an invoice email |
| `GET`/`POST` | `/pricing/base` | Get/set base module pricing |
| `GET`/`POST` | `/pricing/modules` | Get/set per-module (service) pricing |
| `GET`/`POST` | `/pricing/exchange-rates` | Get/set exchange rates |
| `GET` | `/pricing/rate` | Get the current exchange rate for a currency pair |
| `POST` | `/services` | Register a service |
| `GET` | `/services` | List all services |
| `GET` | `/services/{id}` | Get a service |
| `PUT` | `/services/{id}` | Update a service |
| `DELETE` | `/services/{id}` | Delete a service |
| `POST` | `/credentials` | Register credentials for a service |
| `POST` | `/credentials/ephemeral` | Issue ephemeral credentials for an org/service pair |
| `POST` | `/onboarding/{orgId}` | Onboard an organization. Seed Vassago admin user and Bime inventory preset |

**Scheduled jobs:** daily DR backup (`pg_dump` per database, gzipped, uploaded to S3-compatible storage), invoice deadline notifications, and periodic FX rate refresh (org-configurable periodic vs. real-time).

**Roles:** `RAUM_ADMIN`, `RAUM_ONBOARDING`

### Vassago: Authentication & Identity

Vassago issues and validates ES256 JWTs (ECDSA P-256 via OpenBao transit), manages user sessions via Redis, and handles the full user lifecycle including email-based password recovery through Mailgun. Public keys are exposed so downstream services can validate tokens without calling back into Vassago on every request.

**API surface:**

| Method | Path | Description |
|---|---|---|
| `POST` | `/auth/login` | Authenticate and receive JWT + refresh cookie |
| `POST` | `/auth/refresh` | Refresh an access token |
| `POST` | `/auth/logout` | Invalidate the current session |
| `GET` | `/auth/public-key` | Retrieve the current ES256 public key |
| `POST` | `/auth/recover` | Initiate password recovery (sends email) |
| `POST` | `/user` | Create a user |
| `GET` | `/user` | List users |
| `GET` | `/user/{id}` | Get a user |
| `PUT` | `/user/{id}` | Update a user |
| `DELETE` | `/user/{id}` | Delete a user |
| `POST` | `/user/verify` | Verify email / complete account setup |
| `PATCH` | `/user/password` | Change password |

**Roles:** `VASSAGO_ADMIN`, `VASSAGO_USER`

### Bime: Inventory Management

Bime is a multi-tenant inventory service with tenant data isolated at the data layer. It supports a rich product model with configurable metadata, multi-option variants, and an append-only stock ledger.

**API surface:**

| Method | Path | Description |
|---|---|---|
| `POST` | `/locations` | Create a warehouse location |
| `GET` | `/locations` | List locations |
| `GET` | `/locations/{id}` | Get a location |
| `PUT` | `/locations/{id}` | Update a location |
| `DELETE` | `/locations/{id}` | Delete a location |
| `POST` | `/products` | Create a product |
| `GET` | `/products` | List products |
| `GET` | `/products/{id}` | Get a product |
| `PUT` | `/products/{id}` | Update a product |
| `DELETE` | `/products/{id}` | Delete a product |
| `PUT` | `/products/{id}/metadata` | Assign metadata definitions to a product |
| `PATCH` | `/products/{id}/metadata/{metadataId}/options` | Update selected options for a metadata assignment |
| `POST` | `/products/{productId}/variants` | Create a product variant |
| `GET` | `/products/{productId}/variants` | List variants for a product |
| `GET` | `/products/{productId}/variants/{variantId}` | Get a variant |
| `PATCH` | `/products/{productId}/variants/{variantId}` | Update a variant |
| `DELETE` | `/products/{productId}/variants/{variantId}` | Delete a variant |
| `POST` | `/metadata` | Create a metadata definition |
| `GET` | `/metadata` | List metadata definitions |
| `GET` | `/metadata/{id}` | Get a metadata definition |
| `DELETE` | `/metadata/{id}` | Delete a metadata definition |
| `POST` | `/metadata/{id}/options` | Add an option to a metadata definition |
| `DELETE` | `/metadata/{id}/options/{optionId}` | Remove an option |
| `POST` | `/stock/movements` | Record a stock movement |
| `GET` | `/stock/movements` | List stock movements |
| `GET` | `/stock/movements/{id}` | Get a stock movement |
| `GET` | `/stock/balances` | Get current stock balances per variant/location |
| `PUT` | `/stock/alerts/thresholds` | Set a low-stock alert threshold for a variant/location |
| `GET` | `/stock/alerts/thresholds` | List configured alert thresholds |
| `DELETE` | `/stock/alerts/thresholds` | Remove an alert threshold |
| `GET` | `/stock/alerts/active` | List currently active (triggered) alerts |

**Scheduled jobs:** daily stock-threshold check that emails alerts for any variant/location below its configured threshold.

**Roles:** `BIME_ADMIN`, `BIME_MANAGER`, `BIME_VIEWER`, `BIME_USER`

---

## Architecture

```mermaid
---
config:
  layout: elk
  theme: base
  themeVariables:
    primaryColor: "#f8fafc"
    primaryTextColor: "#1f2937"
    primaryBorderColor: "#94a3b8"
    lineColor: "#64748b"
    edgeLabelBackground: "#f8fafc"
---
flowchart TB
    Nginx["nginx (TLS, reverse proxy, rate limiting)"] --> Vassago["Vassago Service"] & Bime["Bime Service"] & Raum["Raum Service"] & Grafana["Grafana"]
    OpenBao[("OpenBao")] -- public api keys --> Vassago
    Redis[("Redis")] -- onboarding --> Raum
    Redis -- refresh tokens --> Vassago
    RaumDB[("Raum DB")] -- fixed credentials --> Raum
    Raum -- dynamic db credentials --> Vassago & Bime
    VassagoDB[("Vassago DB")] -- provisioned from Raum --> Vassago
    BimeDB[("Bime DB")] -- provisioned from Raum --> Bime
    Vassago -- auth --> Bime
    OpenBao -- db credentials --> Raum
    Raum & Vassago & Bime -- logs --> Promtail --> Loki[("Loki")] --> Grafana
    Raum & Vassago & Bime -- metrics --> Prometheus[("Prometheus")] --> Grafana
    Raum & Vassago & Bime -- transactional emails --> Mailgun(["Mailgun"])
    Raum -- DR backups, tenant exports, API docs --> BackupStore[("S3-compatible storage")]
    Raum -- exchange rates --> FxProvider(["FX rate provider"])

     OpenBao:::orange
     Vassago:::sky
     Redis:::teal
     Raum:::violet
     RaumDB:::indigo
     Bime:::green
     VassagoDB:::indigo
     BimeDB:::indigo
     Grafana:::orange
     Mailgun:::slate
     BackupStore:::slate
     FxProvider:::slate
    classDef orange stroke:#fb923c,fill:#fff7ed,color:#1f2937
    classDef teal stroke:#2dd4bf,fill:#f0fdfa,color:#1f2937
    classDef indigo stroke:#818cf8,fill:#eef2ff,color:#1f2937
    classDef violet stroke:#a78bfa,fill:#f5f3ff,color:#1f2937
    classDef sky stroke:#38bdf8,fill:#f0f9ff,color:#1f2937
    classDef green stroke:#4ade80,fill:#f0fdf4,color:#1f2937
    classDef slate stroke:#94a3b8,fill:#f8fafc,color:#1f2937,stroke-dasharray: 4 3
```

Rounded nodes (Mailgun, FX rate provider) and the dashed-border style mark third-party external services; everything else runs inside the Kenoma stack.

- All services are **reactive** (Spring WebFlux + R2DBC).
- JWT signing keys live in **OpenBao**'s transit engine, managed by Vassago. Vassago and Raum read the public key directly from OpenBao; Bime fetches it from Vassago's public key endpoint.
- Database credentials are short-lived and issued through Raum. Vassago and Bime call Raum to obtain ephemeral credentials, which Raum provisions via OpenBao. No service holds a static database password.
- **Redis** is shared between Vassago (session tokens) and Raum (onboarding preset config for retry).
- During onboarding, Raum calls Vassago and Bime over HTTP to seed the new tenant. A scheduled retry recovers any credential that failed mid-flow using the preset config stored in Redis.
- The `common` module provides shared `WhereClause` query building, R2DBC connection pool management, JWT validation, a unified exception hierarchy, and the Mailgun email client (used by Raum, Vassago, and Bime for transactional emails).
- **nginx** terminates TLS and reverse-proxies `api.<BASE_DOMAIN>` (path-routed to Raum/Vassago/Bime) and `grafana.<BASE_DOMAIN>`. No service publishes its app port directly to the host.
- **OpenBao runs in production mode**: Raft integrated storage, Shamir secret sharing (5 key shares, 3-share unseal threshold), and per-service AppRole auth (Vassago, Raum, and a Raum-service role). Services fetch and renew their own AppRole tokens at runtime and retry indefinitely rather than failing at boot.
- **Observability**: Promtail ships container logs to Loki; each service exposes metrics that Prometheus scrapes; Grafana ships with a default "Kenoma overview" dashboard covering both, provisioned automatically.
- **Localization**: transactional emails (password recovery, invoices, stock alerts) and onboarding presets are localized in English and Spanish, shared across services via the `common` module's resource bundles.
- **Data protection**: Raum runs a nightly `pg_dump`-based disaster-recovery backup per database to S3-compatible storage, and exposes an on-demand per-tenant export (`POST /orgs/{id}/export`) that pulls an organization's own data — excluding platform-internal credentials.

> **Database separation note:** The three databases run as separate containers in development to enforce proper tenant isolation at the infrastructure level during testing. In practice, they can all live on the same PostgreSQL instance without issue, although it is not the intended setup.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.0.6, Spring WebFlux |
| Database | PostgreSQL 18 (R2DBC) |
| Secrets | OpenBao 2.5.2 (production mode, Raft storage) |
| Session cache | Redis 7 |
| API docs | springdoc-openapi 3.0.2 |
| Testing | JUnit 5, Testcontainers 2.0.5 |
| Build | Maven (multi-module) |
| Frontend | React 18, TypeScript, Vite |
| Reverse proxy / TLS | nginx |
| Observability | Grafana, Loki, Promtail, Prometheus |
| CI | GitHub Actions |
| Diagrams | [Mermaid](https://mermaid.js.org/) |

---

## Prerequisites

- Docker and Docker Compose
- JDK 25 (for local builds/tests)
- Maven 3.9+

---

## Getting Started

### 1. Configure environment variables

Copy the example file and fill in your values:

```bash
cp .env.example .env
```

The values you **must** set before starting are the Mailgun credentials (required for password recovery emails) and the Grafana admin user/password (compose refuses to start without them — Grafana is reachable at `grafana.<BASE_DOMAIN>`, so there's no silent `admin`/`admin` default). Everything else has working dev defaults. The `.env` file is gitignored and never committed.

Key variables:

| Variable | Description | Default |
|---|---|---|
| `RAUM_DB_PASSWORD` | Raum PostgreSQL password | `postgres` |
| `BIME_DB_PASSWORD` | Bime PostgreSQL password | `postgres` |
| `VASSAGO_DB_PASSWORD` | Vassago PostgreSQL password | `adminpass` |
| `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` | Grafana login | *(required, no default)* |
| `OPERATOR_PASSWORD` | Platform operator account password | `Ch4ng3me!Ops#` |
| `MAILGUN_API_KEY` | Mailgun private API key | *(required)* |
| `MAILGUN_DOMAIN` | Mailgun sending domain | *(required)* |
| `APP_BASE_URL` | Frontend base URL for email links | `http://localhost:3000` |
| `BASE_DOMAIN` | Root domain nginx serves; API at `api.<BASE_DOMAIN>` | `localhost` |
| `CORS_ALLOWED_ORIGINS` | Allowed CORS origins, shared by all three services | *(empty — CORS disabled)* |
| `RAUM_JDWP_OPTS` / `VASSAGO_JDWP_OPTS` / `BIME_JDWP_OPTS` | Opt-in JDWP remote-debug agent per service | *(unset — debugging off)* |
| `DR_BACKUP_S3_ENDPOINT` / `_BUCKET` / `_ACCESS_KEY` / `_SECRET_KEY` | S3-compatible storage for DR backups, tenant exports, and generated API docs | *(required for backups)* |
| `DR_BACKUP_CRON` | Cron schedule for the nightly DR backup job | `0 0 2 * * *` |
| `MAILGUN_INVOICE_FROM` / `MAILGUN_STOCK_ALERT_FROM` | Sender addresses for invoice and stock-alert emails | *(required)* |
| `FX_PROVIDER_API_KEY` / `FX_PROVIDER_BASE_URL` | External exchange-rate provider used for multi-currency pricing | *(required for FX refresh)* |

OpenBao itself has no root-token env var to configure: on first boot it initializes with Shamir key shares and auto-unseals using the keys it writes to a Docker volume (see `scripts/init-openbao.sh`); nothing to set in `.env` for it.

See `.env.example` for the full list.

### 2. Start the platform

```bash
docker compose up --build
```

This brings up OpenBao, all PostgreSQL instances, Redis, and all three services. An init sequence runs automatically:

1. **OpenBao** is configured. KV, database, and transit secrets engines are enabled; AppRole auth is set up for each service; the JWT signing key is generated.
2. **Raum and Bime database schemas** are initialized from their respective `init.sql` files.
3. **`kenoma-pre-init`** runs after the above complete. It provisions the Vassago database schema, seeds the platform operator account, registers both database connections in OpenBao, and writes dynamic runtime values (service IDs, AppRole tokens) to `.env-out/.env` for the services to consume on startup.

### 3. Seed a demo user (optional)

```bash
docker compose --profile seed up kenoma-seed
```

Creates the user defined by `SEED_USER_EMAIL` / `SEED_USER_PASSWORD` in your `.env` (defaults to `admin@example.com`).

### 4. Access the services

Each service's app port is bound to `127.0.0.1` only (not exposed to the network) and is reachable directly for local development:

| Service | Base URL | OpenAPI UI |
|---|---|---|
| Raum | http://localhost:8080 | http://localhost:8080/swagger-ui.html |
| Vassago | http://localhost:8081 | http://localhost:8081/swagger-ui.html |
| Bime | http://localhost:8082 | http://localhost:8082/swagger-ui.html |

In a full deployment, nginx instead fronts everything at `https://api.<BASE_DOMAIN>` (path-routed to the three services) and `https://grafana.<BASE_DOMAIN>` for the Grafana dashboards.

To attach a remote debugger, set the relevant `*_JDWP_OPTS` variable in `.env` (see above) before starting — debug agents are off by default. Once enabled, debuggers can attach on ports `5005` (Raum), `5006` (Vassago), and `5007` (Bime).

### Continuous Deployment

Merges to `main` auto-deploy to the test VPS via `.github/workflows/cd.yml`, which runs on a
GitHub Actions self-hosted runner installed on the VPS itself (labeled `kenoma-vps`). The workflow
checks out the pushed commit, runs `docker compose up -d --build` (Compose only rebuilds/restarts
containers whose image or config actually changed), and waits for Raum/Vassago/Bime's
`/actuator/health` endpoints to report healthy before finishing. `.env` on the VPS is never touched
by the workflow — it must already exist there, same as any other deployment target.

To register the runner on a new VPS: GitHub → repo Settings → Actions → Runners → New self-hosted
runner, label it `kenoma-vps`, and install it as a systemd service (`./svc.sh install && ./svc.sh
start`) running as a non-root user that's a member of the `docker` group.

### 5. Run the frontend (optional)

```bash
cd frontend
npm install
npm run dev
```

Serves the admin UI at http://localhost:5173, calling the services directly — make sure `CORS_ALLOWED_ORIGINS` in `.env` includes `http://127.0.0.1:5173` (or your dev origin) before starting the backend.

---

## Running Tests

```bash
# Unit tests
mvn test -pl services/common,services/raum,services/vassago,services/bime -am

# Integration tests (requires Docker)
mvn verify -pl services/raum,services/vassago,services/bime -am
```

Integration tests use Testcontainers and spin up real PostgreSQL, OpenBao, and Redis instances; no external infrastructure is needed.

---

## CI

GitHub Actions runs on every pull request to `main` or `develop`:

- **Unit Tests**: fast feedback; no Docker required
- **Integration Tests**: full Testcontainers suite against real dependencies
- **CodeQL**: static security analysis

---

## License

Licensed under the [Business Source License 1.1](LICENSE). The licensed work will convert to Apache 2.0 on **2030-01-01**.
