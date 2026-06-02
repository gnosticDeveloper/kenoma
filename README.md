# Kenoma

Modular base layer for multi-tenant credential management. Raum stores database connection metadata and issues short-lived credentials through OpenBao. Vassago is the client-facing gateway with user management. Future modules plug in on top.

No static credentials sit in your app. OpenBao handles generation, rotation, and TTL.

## Prerequisites

- **Docker** and **Docker Compose**
- **Java 25**
- **Maven 3.9+** (included as `mvnw` wrapper)
- **Git**

## Installation

```bash
git clone https://github.com/gnosticDeveloper/kenoma
cd kenoma

docker-compose up -d
```

Vassago requires a service record in Raum to reference at startup. This is a one-time dev setup step — create the record once Raum is healthy, then restart the Vassago container with the ID.

```bash
# Register Vassago as a service in Raum
curl -X POST http://localhost:8080/services \
  -H "Content-Type: application/json" \
  -d '{"name": "Vassago", "description": "Auth gateway"}'
```

Copy the `id` from the response and create a `.env` file in the project root:

```
VASSAGO_SERVICE_ID=<id from above>
```

Then restart Vassago:

```bash
docker-compose restart vassago
```

Both services are ready once `docker-compose ps` shows them healthy.

## Usage

### Organizations

```bash
# Create
curl -X POST http://localhost:8080/orgs \
  -H "Content-Type: application/json" \
  -d '{"name": "Acme Corp", "contactName": "Jane Doe", "contactEmail": "jane@acme.com"}'

# Get
curl http://localhost:8080/orgs/{id}

# Update
curl -X PUT http://localhost:8080/orgs/{id} \
  -H "Content-Type: application/json" \
  -d '{"name": "Acme Corp", "contactName": "Jane Doe", "contactEmail": "jane@acme.com"}'

# Delete (soft)
curl -X DELETE http://localhost:8080/orgs/{id}
```

### Services

```bash
# Create
curl -X POST http://localhost:8080/services \
  -H "Content-Type: application/json" \
  -d '{"name": "Customer Database", "description": "Primary customer data repository"}'

# Get
curl http://localhost:8080/services/{id}

# List all
curl http://localhost:8080/services

# Update
curl -X PUT http://localhost:8080/services/{id} \
  -H "Content-Type: application/json" \
  -d '{"name": "Customer Database v2", "description": "Updated customer repository"}'

# Delete (soft)
curl -X DELETE http://localhost:8080/services/{id}
```

### Credentials

Store static credentials and register the database with OpenBao:

```bash
curl -X POST http://localhost:8080/credentials \
  -H "Content-Type: application/json" \
  -d '{
    "orgId": "550e8400-e29b-41d4-a716-446655440000",
    "serviceId": "660e8400-e29b-41d4-a716-446655440001",
    "userName": "admin",
    "password": "SecurePass123!@#",
    "dbHost": "db.example.com",
    "dbPort": 5432,
    "dbName": "production",
    "dbEngine": "postgresql"
  }'
```

Request ephemeral credentials via Raum:

```bash
curl -X POST http://localhost:8080/credentials/ephemeral \
  -H "Content-Type: application/json" \
  -d '{"orgId": "...", "serviceId": "..."}'
```

### Users

```bash
# Create
curl -X POST http://localhost:8081/user \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "name": "John",
    "lastName": "Doe",
    "username": "johndoe",
    "roles": ["admin", "user"],
    "password": "SecurePass123!@#",
    "orgId": "550e8400-e29b-41d4-a716-446655440000"
  }'

# Get
curl http://localhost:8081/user/{orgId}/{userId}

# List by org
curl http://localhost:8081/user/org/{orgId}

# Update (password optional)
curl -X PUT http://localhost:8081/user/{orgId}/{userId} \
  -H "Content-Type: application/json" \
  -d '{"email": "...", "name": "...", "lastName": "...", "username": "...", "roles": ["user"]}'

# Delete (soft)
curl -X DELETE http://localhost:8081/user/{orgId}/{userId}
```

### Health

```bash
curl http://localhost:8080/actuator/health  # Raum
curl http://localhost:8081/actuator/health  # Vassago
curl http://localhost:8200/v1/sys/health    # OpenBao
```

## Overview

**Raum** — credential manager. Owns organizations, services, and database connection metadata. Registers databases with OpenBao and issues short-lived credentials on request.

**Vassago** — client gateway. Calls Raum for ephemeral credentials, handles org-scoped user management with role-based access.

Both run on Spring WebFlux. PostgreSQL stores persistent metadata. OpenBao handles credential generation, rotation, and TTL. Everything runs in Docker Compose.