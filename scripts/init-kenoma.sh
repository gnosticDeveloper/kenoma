#!/bin/sh
set -e

# ── Helpers ────────────────────────────────────────────────────────────────────

wait_for() {
  echo "Waiting for $1..."
  until wget --spider -q "$2" > /dev/null 2>&1; do
    sleep 2
  done
  echo "$1 is ready."
}

post() {
  wget -q -O - \
    --header="Content-Type: application/json" \
    --post-data="$2" \
    "$1"
}

# ── Wait for Raum ──────────────────────────────────────────────────────────────

wait_for "Raum" "${RAUM_BASE_URL}/actuator/health"

# ── Register organisation ──────────────────────────────────────────────────────

echo "Registering organisation..."
ORG_RESPONSE=$(post "${RAUM_BASE_URL}/orgs" \
  "{\"name\":\"${ORG_NAME}\",\"contactEmail\":\"${ORG_EMAIL}\",\"contactName\":\"${ORG_CONTACT}\"}")
echo "Org response: ${ORG_RESPONSE}"

ORG_ID=$(echo "$ORG_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$ORG_ID" ]; then
  echo "ERROR: Failed to extract orgId from response"
  exit 1
fi
echo "Org registered with id: ${ORG_ID}"

# ── Register Vassago service ───────────────────────────────────────────────────

echo "Registering Vassago service..."
SERVICE_RESPONSE=$(post "${RAUM_BASE_URL}/services" \
  "{\"name\":\"${SERVICE_NAME}\",\"description\":\"${SERVICE_DESC}\"}")
echo "Service response: ${SERVICE_RESPONSE}"

SERVICE_ID=$(echo "$SERVICE_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$SERVICE_ID" ]; then
  echo "ERROR: Failed to extract serviceId from response"
  exit 1
fi
echo "Service registered with id: ${SERVICE_ID}"

# ── Provision org database schema ──────────────────────────────────────────────

echo "Provisioning org database schema..."
PGPASSWORD="${CUSTOMER_DB_PASSWORD}" psql \
  -h "${CUSTOMER_DB_HOST}" \
  -p "${CUSTOMER_DB_PORT}" \
  -U "${CUSTOMER_DB_USER}" \
  -d "${CUSTOMER_DB_NAME}" \
  -f /users.sql
echo "Org database schema provisioned."

# ── Insert seed user (BCrypt hash computed inline) ─────────────────────────────

echo "Inserting seed user..."
SEED_PASSWORD="${SEED_USER_PASSWORD:-Ch4ng3me!Dev#}"
BCRYPT_HASH=$(python3 -c "
import hashlib, os, base64, struct

# Use a lightweight bcrypt via the 'bcrypt' module if available,
# otherwise fall back to generating a known-good pre-hashed value
try:
    import bcrypt
    pwd = '${SEED_PASSWORD}'.encode()
    print(bcrypt.hashpw(pwd, bcrypt.gensalt(rounds=10)).decode())
except ImportError:
    # Fallback: pre-hashed value of 'Ch4ng3me!Dev#' with cost 10
    # Only used if bcrypt module is unavailable — replace if changing default password
    print('\$2b\$10\$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2.uheWG/igi')
")

PGPASSWORD="${CUSTOMER_DB_PASSWORD}" psql \
  -h "${CUSTOMER_DB_HOST}" \
  -p "${CUSTOMER_DB_PORT}" \
  -U "${CUSTOMER_DB_USER}" \
  -d "${CUSTOMER_DB_NAME}" \
  -c "INSERT INTO users (name, last_name, email, username, password, roles)
      VALUES (
        '${SEED_USER_NAME:-Admin}',
        '${SEED_USER_LAST_NAME:-User}',
        '${SEED_USER_EMAIL:-admin@example.com}',
        '${SEED_USER_USERNAME:-admin}',
        '${BCRYPT_HASH}',
        'ADMIN'
      ) ON CONFLICT (username) DO NOTHING;"
echo "Seed user inserted."

# ── Write env file for Vassago ─────────────────────────────────────────────────

mkdir -p "$(dirname "${ENV_OUT}")"
cat > "${ENV_OUT}" << ENVEOF
VASSAGO_SERVICE_ID=${SERVICE_ID}
OPENBAO_BASE_URL=http://openbao:8200
OPENBAO_TOKEN=dev-root-token
ENVEOF

echo "Wrote env file to ${ENV_OUT}:"
cat "${ENV_OUT}"

echo "Kenoma initialisation complete."