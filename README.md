# Kenoma

**Privacy-first B2B SaaS infrastructure.** Kenoma is a reactive, multi-tenant microservices platform providing authentication, organization management, secrets handling, and inventory management as composable backend services.

[![CI](https://github.com/gnosticDeveloper/Kenoma/actions/workflows/ci.yml/badge.svg)](https://github.com/gnosticDeveloper/Kenoma/actions/workflows/ci.yml)
[![CodeQL](https://github.com/gnosticDeveloper/Kenoma/actions/workflows/codeql.yml/badge.svg)](https://github.com/gnosticDeveloper/Kenoma/actions/workflows/codeql.yml)
[![License: BUSL-1.1](https://img.shields.io/badge/License-BUSL--1.1-blue.svg)](LICENSE)

---

## Services

| Service | Port | Description |
|---|---|---|
| **Raum** | `8080` | Organization registry. Manages tenants, registered services, and ephemeral database credentials via OpenBao |
| **Vassago** | `8081` | Authentication and identity. JWT issuance, session management, user lifecycle, and password recovery |
| **Bime** | `8082` | Inventory management. Products, variants, metadata, stock ledger, and warehouse locations |
| **Common** |No port | Shared library: DTOs, exception handling, JWT validation, and R2DBC connection pooling |

### Raum: Organization & Credential Registry

Raum is the platform's administrative backbone. It provisions tenant organizations, registers services that consume the platform, and issues ephemeral database credentials through OpenBao AppRole so downstream services never hold long-lived secrets. It also orchestrates new organization onboarding. Provisioning the org admin account in Vassago and seeding initial inventory data in Bime according to a configurable preset, with Redis-backed retry so partial failures can be recovered automatically.

**API surface:**

| Method | Path | Description |
|---|---|---|
| `POST` | `/orgs` | Create an organization |
| `GET` | `/orgs/{id}` | Get an organization |
| `PUT` | `/orgs/{id}` | Update an organization |
| `DELETE` | `/orgs/{id}` | Delete an organization |
| `POST` | `/services` | Register a service |
| `GET` | `/services` | List all services |
| `GET` | `/services/{id}` | Get a service |
| `PUT` | `/services/{id}` | Update a service |
| `DELETE` | `/services/{id}` | Delete a service |
| `POST` | `/credentials` | Register credentials for a service |
| `POST` | `/credentials/ephemeral` | Issue ephemeral credentials for an org/service pair |
| `POST` | `/onboarding/{orgId}` | Onboard an organization. Seed Vassago admin user and Bime inventory preset |

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

**Roles:** `BIME_ADMIN`, `BIME_MANAGER`, `BIME_VIEWER`, `BIME_USER`

---

## Architecture

```mermaid
---
config:
  layout: elk
---
flowchart TB
    OpenBao[("OpenBao")] -- public api keys --> Vassago["Vassago Service"]
    Redis[("Redis")] -- onboarding --> Raum["Raum Service"]
    Redis -- refresh tokens --> Vassago
    RaumDB[("Raum DB")] -- fixed credentials --> Raum
    Raum -- dynamic db credentials --> Vassago & Bime["Bime Service"]
    VassagoDB[("Vassago DB")] -- provisioned from Raum --> Vassago
    BimeDB[("Bime DB")] -- provisioned from Raum --> Bime
    Vassago -- auth --> Bime
    OpenBao -- db credentials --> Raum

     OpenBao:::orange
     Vassago:::sky
     Redis:::teal
     Raum:::violet
     RaumDB:::indigo
     Bime:::green
     VassagoDB:::indigo
     BimeDB:::indigo
    classDef orange stroke:#fb923c,fill:#fff7ed
    classDef teal stroke:#2dd4bf,fill:#f0fdfa
    classDef indigo stroke:#818cf8,fill:#eef2ff
    classDef violet stroke:#a78bfa,fill:#f5f3ff
    classDef sky stroke:#38bdf8,fill:#f0f9ff
    classDef green stroke:#4ade80,fill:#f0fdf4
```

- All services are **reactive** (Spring WebFlux + R2DBC).
- JWT signing keys live in **OpenBao**'s transit engine, managed by Vassago. Vassago and Raum read the public key directly from OpenBao; Bime fetches it from Vassago's public key endpoint.
- Database credentials are short-lived and issued through Raum. Vassago and Bime call Raum to obtain ephemeral credentials, which Raum provisions via OpenBao. No service holds a static database password.
- **Redis** is shared between Vassago (session tokens) and Raum (onboarding preset config for retry).
- During onboarding, Raum calls Vassago and Bime over HTTP to seed the new tenant. A scheduled retry recovers any credential that failed mid-flow using the preset config stored in Redis.
- The `common` module provides shared `WhereClause` query building, R2DBC connection pool management, JWT validation, and a unified exception hierarchy.

> **Database separation note:** The three databases run as separate containers in development to enforce proper tenant isolation at the infrastructure level during testing. In practice, they can all live on the same PostgreSQL instance without issue, although it is not the intended setup.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.0.6, Spring WebFlux |
| Database | PostgreSQL 18 (R2DBC) |
| Secrets | OpenBao 2.5.2 |
| Session cache | Redis 7 |
| API docs | springdoc-openapi 3.0.2 |
| Testing | JUnit 5, Testcontainers 2.0.5 |
| Build | Maven (multi-module) |
| CI | GitHub Actions |

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

The only values you **must** change before starting are the Mailgun credentials (required for password recovery emails). Everything else has working dev defaults. The `.env` file is gitignored and never committed.

Key variables:

| Variable | Description | Default |
|---|---|---|
| `RAUM_DB_PASSWORD` | Raum PostgreSQL password | `postgres` |
| `BIME_DB_PASSWORD` | Bime PostgreSQL password | `postgres` |
| `VASSAGO_DB_PASSWORD` | Vassago PostgreSQL password | `adminpass` |
| `OPENBAO_ROOT_TOKEN` | OpenBao dev root token | `dev-root-token` |
| `OPERATOR_PASSWORD` | Platform operator account password | `Ch4ng3me!Ops#` |
| `MAILGUN_API_KEY` | Mailgun private API key | *(required)* |
| `MAILGUN_DOMAIN` | Mailgun sending domain | *(required)* |
| `APP_BASE_URL` | Frontend base URL for email links | `http://localhost:3000` |

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

| Service | Base URL | OpenAPI UI |
|---|---|---|
| Raum | http://localhost:8080 | http://localhost:8080/swagger-ui.html |
| Vassago | http://localhost:8081 | http://localhost:8081/swagger-ui.html |
| Bime | http://localhost:8082 | http://localhost:8082/swagger-ui.html |

Remote debuggers can attach on ports `5005` (Raum), `5006` (Vassago), and `5007` (Bime).

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

GitHub Actions runs on every pull request to `main`:

- **Unit Tests**: fast feedback; no Docker required
- **Integration Tests**: full Testcontainers suite against real dependencies
- **CodeQL**: static security analysis

---

## License

Licensed under the [Business Source License 1.1](LICENSE). The licensed work will convert to Apache 2.0 on **2030-01-01**.
