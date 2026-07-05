set -e

MODE="${KENOMA_INIT_MODE:-seeded}"
echo "Init mode: ${MODE}"

echo "Reading OpenBao root token from init volume..."
if [ ! -f /init-out/unseal-keys.env ]; then
  echo "ERROR: /init-out/unseal-keys.env not found" && exit 1
fi
. /init-out/unseal-keys.env

echo "Reading bootstrap IDs from Raum database..."

RAUM_SERVICE_ID=$(PGPASSWORD="${RAUM_DB_PASSWORD}" psql \
  -h "${RAUM_DB_HOST}" -p "${RAUM_DB_PORT}" \
  -U "${RAUM_DB_USER}" -d "${RAUM_DB_NAME}" \
  -t -A -c "SELECT id FROM services WHERE name = 'Raum' LIMIT 1;")
[ -z "$RAUM_SERVICE_ID" ] && echo "ERROR: Raum service ID not found" && exit 1

VASSAGO_SERVICE_ID=$(PGPASSWORD="${RAUM_DB_PASSWORD}" psql \
  -h "${RAUM_DB_HOST}" -p "${RAUM_DB_PORT}" \
  -U "${RAUM_DB_USER}" -d "${RAUM_DB_NAME}" \
  -t -A -c "SELECT id FROM services WHERE name = 'Vassago' LIMIT 1;")
[ -z "$VASSAGO_SERVICE_ID" ] && echo "ERROR: Vassago service ID not found" && exit 1

BIME_SERVICE_ID=$(PGPASSWORD="${RAUM_DB_PASSWORD}" psql \
  -h "${RAUM_DB_HOST}" -p "${RAUM_DB_PORT}" \
  -U "${RAUM_DB_USER}" -d "${RAUM_DB_NAME}" \
  -t -A -c "SELECT id FROM services WHERE name = 'Bime' LIMIT 1;")
[ -z "$BIME_SERVICE_ID" ] && echo "ERROR: Bime service ID not found" && exit 1

PLATFORM_ORG_ID=$(PGPASSWORD="${RAUM_DB_PASSWORD}" psql \
  -h "${RAUM_DB_HOST}" -p "${RAUM_DB_PORT}" \
  -U "${RAUM_DB_USER}" -d "${RAUM_DB_NAME}" \
  -t -A -c "SELECT id FROM organizations WHERE name = 'Platform' LIMIT 1;")
[ -z "$PLATFORM_ORG_ID" ] && echo "ERROR: Platform org ID not found" && exit 1

CREDENTIAL_ID=$(PGPASSWORD="${RAUM_DB_PASSWORD}" psql \
  -h "${RAUM_DB_HOST}" -p "${RAUM_DB_PORT}" \
  -U "${RAUM_DB_USER}" -d "${RAUM_DB_NAME}" \
  -t -A -c "SELECT id FROM credentials WHERE service_id = '${VASSAGO_SERVICE_ID}' LIMIT 1;")
[ -z "$CREDENTIAL_ID" ] && echo "ERROR: Credential ID not found" && exit 1

BIME_CREDENTIAL_ID=$(PGPASSWORD="${RAUM_DB_PASSWORD}" psql \
  -h "${RAUM_DB_HOST}" -p "${RAUM_DB_PORT}" \
  -U "${RAUM_DB_USER}" -d "${RAUM_DB_NAME}" \
  -t -A -c "SELECT id FROM credentials WHERE service_id = '${BIME_SERVICE_ID}' LIMIT 1;")
[ -z "$BIME_CREDENTIAL_ID" ] && echo "ERROR: Bime credential ID not found" && exit 1

echo "Raum service ID:    ${RAUM_SERVICE_ID}"
echo "Vassago service ID: ${VASSAGO_SERVICE_ID}"
echo "Bime service ID:    ${BIME_SERVICE_ID}"
echo "Platform org ID:    ${PLATFORM_ORG_ID}"
echo "Credential ID:      ${CREDENTIAL_ID}"
echo "Bime credential ID: ${BIME_CREDENTIAL_ID}"

echo "Reading AppRole credentials..."
if [ ! -f /approle-out/approle.env ]; then
  echo "ERROR: AppRole credentials not found at /approle-out/approle.env"
  exit 1
fi
. /approle-out/approle.env

echo "Logging in with Vassago AppRole..."
VASSAGO_LOGIN=$(wget -q -O - \
  --header="Content-Type: application/json" \
  --post-data="{\"role_id\":\"${VASSAGO_APPROLE_ROLE_ID}\",\"secret_id\":\"${VASSAGO_APPROLE_SECRET_ID}\"}" \
  "${OPENBAO_BASE_URL}/v1/auth/approle/login")
VASSAGO_TOKEN=$(echo "$VASSAGO_LOGIN" | grep -o '"client_token":"[^"]*"' | cut -d'"' -f4)
[ -z "$VASSAGO_TOKEN" ] && echo "ERROR: Failed to obtain Vassago AppRole token." && exit 1
echo "Vassago AppRole login successful."

echo "Logging in with Raum AppRole..."
RAUM_LOGIN=$(wget -q -O - \
  --header="Content-Type: application/json" \
  --post-data="{\"role_id\":\"${RAUM_APPROLE_ROLE_ID}\",\"secret_id\":\"${RAUM_APPROLE_SECRET_ID}\"}" \
  "${OPENBAO_BASE_URL}/v1/auth/approle/login")
RAUM_TOKEN=$(echo "$RAUM_LOGIN" | grep -o '"client_token":"[^"]*"' | cut -d'"' -f4)
[ -z "$RAUM_TOKEN" ] && echo "ERROR: Failed to obtain Raum AppRole token." && exit 1
echo "Raum AppRole login successful."

echo "Logging in with Raum service AppRole..."
RAUM_SERVICE_LOGIN=$(wget -q -O - \
  --header="Content-Type: application/json" \
  --post-data="{\"role_id\":\"${RAUM_SERVICE_APPROLE_ROLE_ID}\",\"secret_id\":\"${RAUM_SERVICE_APPROLE_SECRET_ID}\"}" \
  "${OPENBAO_BASE_URL}/v1/auth/approle/login")
RAUM_SERVICE_TOKEN=$(echo "$RAUM_SERVICE_LOGIN" | grep -o '"client_token":"[^"]*"' | cut -d'"' -f4)
[ -z "$RAUM_SERVICE_TOKEN" ] && echo "ERROR: Failed to obtain Raum service AppRole token." && exit 1
echo "Raum service AppRole login successful."


echo "Provisioning Vassago database schema..."
PGPASSWORD="${VASSAGO_DB_PASSWORD}" psql \
  -h "${VASSAGO_DB_HOST}" -p "${VASSAGO_DB_PORT}" \
  -U "${VASSAGO_DB_USER}" -d "${VASSAGO_DB_NAME}" \
  -f /users.sql
echo "Operational database schema provisioned."

SHOULD_SEED_OPERATOR=true
if [ "$MODE" = "clean" ]; then
  USER_COUNT=$(PGPASSWORD="${VASSAGO_DB_PASSWORD}" psql \
    -h "${VASSAGO_DB_HOST}" -p "${VASSAGO_DB_PORT}" \
    -U "${VASSAGO_DB_USER}" -d "${VASSAGO_DB_NAME}" \
    -t -A -c "SELECT count(*) FROM users;")
  if [ "$USER_COUNT" -ne 0 ]; then
    SHOULD_SEED_OPERATOR=false
    echo "Clean mode: users already exist, skipping operator seed."
  fi
fi

if [ "$SHOULD_SEED_OPERATOR" = true ]; then
  echo "Seeding operator user..."
  OPERATOR_ROLES="{\"${RAUM_SERVICE_ID}\":[\"RAUM_ADMIN\",\"RAUM_ONBOARDING\"],\"${BIME_SERVICE_ID}\":[\"BIME_ADMIN\"]}"
  BCRYPT_HASH=$(python3 -c "
import bcrypt
pwd = '${OPERATOR_PASSWORD}'.encode()
print(bcrypt.hashpw(pwd, bcrypt.gensalt(rounds=10)).decode().replace('\$2b\$', '\$2a\$'))
")
  PGPASSWORD="${VASSAGO_DB_PASSWORD}" psql \
    -h "${VASSAGO_DB_HOST}" -p "${VASSAGO_DB_PORT}" \
    -U "${VASSAGO_DB_USER}" -d "${VASSAGO_DB_NAME}" \
    -c "INSERT INTO users (name, last_name, email, username, password, roles, is_ready)
        VALUES ('${OPERATOR_NAME}', '${OPERATOR_LAST_NAME}', '${OPERATOR_EMAIL}',
                '${OPERATOR_USERNAME}', '${BCRYPT_HASH}', '${OPERATOR_ROLES}', true)
        ON CONFLICT (username) DO NOTHING;"
  echo "Operator user seeded."
fi

if [ "$MODE" = "clean" ]; then
  echo "Clean mode: skipping OpenBao database credential/connection/role registration for Vassago and Bime."
else

echo "Storing credentials in OpenBao KV..."
wget -q -O - \
  --header="Content-Type: application/json" \
  --header="X-Vault-Token: ${ROOT_TOKEN}" \
  --post-data="{\"data\":{\"username\":\"${VASSAGO_DB_USER}\",\"password\":\"${VASSAGO_DB_PASSWORD}\"}}" \
  "${OPENBAO_BASE_URL}/v1/secret/data/credentials/${CREDENTIAL_ID}"
echo "Credentials stored."

echo "Registering database connection in OpenBao..."
cat > /tmp/db-config-payload.json << DBCONFIG
{
  "plugin_name": "postgresql-database-plugin",
  "allowed_roles": "${CREDENTIAL_ID}-role",
  "connection_url": "postgresql://{{username}}:{{password}}@${VASSAGO_DB_HOST}:${VASSAGO_DB_PORT}/${VASSAGO_DB_NAME}?sslmode=disable",
  "username": "${VASSAGO_DB_USER}",
  "password": "${VASSAGO_DB_PASSWORD}"
}
DBCONFIG
wget -q -O - \
  --header="Content-Type: application/json" \
  --header="X-Vault-Token: ${ROOT_TOKEN}" \
  --post-file=/tmp/db-config-payload.json \
  "${OPENBAO_BASE_URL}/v1/database/config/${CREDENTIAL_ID}"
echo "Database connection registered."

echo "Creating database role in OpenBao..."
cat > /tmp/role-payload.json << ROLEJSON
{
  "db_name": "${CREDENTIAL_ID}",
  "creation_statements": "CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; GRANT CONNECT ON DATABASE \"${VASSAGO_DB_NAME}\" TO \"{{name}}\"; GRANT USAGE ON SCHEMA public TO \"{{name}}\"; GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO \"{{name}}\";",
  "default_ttl": "1h",
  "max_ttl": "24h"
}
ROLEJSON
wget -q -O - \
  --header="Content-Type: application/json" \
  --header="X-Vault-Token: ${ROOT_TOKEN}" \
  --post-file=/tmp/role-payload.json \
  "${OPENBAO_BASE_URL}/v1/database/roles/${CREDENTIAL_ID}-role"
echo "Database role created."

echo "Registering Bime database connection in OpenBao..."
cat > /tmp/bime-db-config-payload.json << DBCONFIG
{
  "plugin_name": "postgresql-database-plugin",
  "allowed_roles": "${BIME_CREDENTIAL_ID}-role",
  "connection_url": "postgresql://{{username}}:{{password}}@${BIME_DB_HOST}:${BIME_DB_PORT}/${BIME_DB_NAME}?sslmode=disable",
  "username": "${BIME_DB_USER}",
  "password": "${BIME_DB_PASSWORD}"
}
DBCONFIG
wget -q -O - \
  --header="Content-Type: application/json" \
  --header="X-Vault-Token: ${ROOT_TOKEN}" \
  --post-file=/tmp/bime-db-config-payload.json \
  "${OPENBAO_BASE_URL}/v1/database/config/${BIME_CREDENTIAL_ID}"
echo "Bime database connection registered."

echo "Creating Bime database role in OpenBao..."
cat > /tmp/bime-role-payload.json << ROLEJSON
{
  "db_name": "${BIME_CREDENTIAL_ID}",
  "creation_statements": "CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; GRANT CONNECT ON DATABASE \"${BIME_DB_NAME}\" TO \"{{name}}\"; GRANT USAGE ON SCHEMA public TO \"{{name}}\"; GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO \"{{name}}\";",
  "default_ttl": "1h",
  "max_ttl": "24h"
}
ROLEJSON
wget -q -O - \
  --header="Content-Type: application/json" \
  --header="X-Vault-Token: ${ROOT_TOKEN}" \
  --post-file=/tmp/bime-role-payload.json \
  "${OPENBAO_BASE_URL}/v1/database/roles/${BIME_CREDENTIAL_ID}-role"
echo "Bime database role created."

echo "Marking pre-initialized credentials as initialized..."
PGPASSWORD="${RAUM_DB_PASSWORD}" psql \
  -h "${RAUM_DB_HOST}" -p "${RAUM_DB_PORT}" \
  -U "${RAUM_DB_USER}" -d "${RAUM_DB_NAME}" \
  -c "UPDATE credentials SET is_initialized = true WHERE id IN ('${CREDENTIAL_ID}', '${BIME_CREDENTIAL_ID}');"
echo "Credentials marked as initialized."

fi

mkdir -p "$(dirname "${ENV_OUT}")"
cat > "${ENV_OUT}" << ENVEOF
OPENBAO_BASE_URL=${OPENBAO_BASE_URL}
VASSAGO_SERVICE_ID=${VASSAGO_SERVICE_ID}
VASSAGO_OPENBAO_TOKEN=${VASSAGO_TOKEN}
RAUM_SERVICE_ID=${RAUM_SERVICE_ID}
RAUM_OPENBAO_TOKEN=${RAUM_SERVICE_TOKEN}
RAUM_JWT_TRANSIT_KEY_NAME=vassago-jwt
BIME_SERVICE_ID=${BIME_SERVICE_ID}
BIME_JWT_TRANSIT_KEY_NAME=vassago-jwt
ENVEOF
echo "Wrote env file to ${ENV_OUT}."

cat > "${ENV_OUT}.local" << LOCALEOF
# Dynamic values generated at startup — regenerated every time pre-init runs
RAUM_SERVICE_ID=${RAUM_SERVICE_ID}
RAUM_OPENBAO_TOKEN=${RAUM_SERVICE_TOKEN}
VASSAGO_SERVICE_ID=${VASSAGO_SERVICE_ID}
VASSAGO_OPENBAO_TOKEN=${VASSAGO_TOKEN}
VASSAGO_JWT_TRANSIT_KEY_NAME=vassago-jwt
BIME_SERVICE_ID=${BIME_SERVICE_ID}
BIME_JWT_TRANSIT_KEY_NAME=vassago-jwt

# Infrastructure — localhost addresses for running services outside Docker
OPENBAO_BASE_URL=http://localhost:8200
OPENBAO_HOST=http://localhost:8200
RAUM_BASE_URL=http://localhost:8080
REDIS_HOST=localhost
REDIS_PORT=6379

# Add your Mailgun credentials below (required by Vassago — not managed by init):
# MAILGUN_API_KEY=
# MAILGUN_DOMAIN=
LOCALEOF
echo "Wrote local env file to ${ENV_OUT}.local."
echo "Kenoma pre-init complete."