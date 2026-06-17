set -e

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

echo "Raum service ID:    ${RAUM_SERVICE_ID}"
echo "Vassago service ID: ${VASSAGO_SERVICE_ID}"
echo "Platform org ID:    ${PLATFORM_ORG_ID}"
echo "Credential ID:      ${CREDENTIAL_ID}"

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

echo "Provisioning operational database schema..."
PGPASSWORD="${OPERATIONAL_DB_PASSWORD}" psql \
  -h "${OPERATIONAL_DB_HOST}" -p "${OPERATIONAL_DB_PORT}" \
  -U "${OPERATIONAL_DB_USER}" -d "${OPERATIONAL_DB_NAME}" \
  -f /users.sql
echo "Operational database schema provisioned."

echo "Seeding operator user..."
OPERATOR_ROLES="{\"${RAUM_SERVICE_ID}\":[\"RAUM_ADMIN\"]}"
BCRYPT_HASH=$(python3 -c "
import bcrypt
pwd = '${OPERATOR_PASSWORD}'.encode()
print(bcrypt.hashpw(pwd, bcrypt.gensalt(rounds=10)).decode().replace('\$2b\$', '\$2a\$'))
")
PGPASSWORD="${OPERATIONAL_DB_PASSWORD}" psql \
  -h "${OPERATIONAL_DB_HOST}" -p "${OPERATIONAL_DB_PORT}" \
  -U "${OPERATIONAL_DB_USER}" -d "${OPERATIONAL_DB_NAME}" \
  -c "INSERT INTO users (name, last_name, email, username, password, roles, is_ready)
      VALUES ('${OPERATOR_NAME}', '${OPERATOR_LAST_NAME}', '${OPERATOR_EMAIL}',
              '${OPERATOR_USERNAME}', '${BCRYPT_HASH}', '${OPERATOR_ROLES}', true)
      ON CONFLICT (username) DO NOTHING;"
echo "Operator user seeded."

echo "Storing credentials in OpenBao KV..."
wget -q -O - \
  --header="Content-Type: application/json" \
  --header="X-Vault-Token: ${OPENBAO_ROOT_TOKEN}" \
  --post-data="{\"data\":{\"username\":\"${OPERATIONAL_DB_USER}\",\"password\":\"${OPERATIONAL_DB_PASSWORD}\"}}" \
  "${OPENBAO_BASE_URL}/v1/secret/data/credentials/${CREDENTIAL_ID}"
echo "Credentials stored."

echo "Registering database connection in OpenBao..."
cat > /tmp/db-config-payload.json << DBCONFIG
{
  "plugin_name": "postgresql-database-plugin",
  "allowed_roles": "${CREDENTIAL_ID}-role",
  "connection_url": "postgresql://{{username}}:{{password}}@${OPERATIONAL_DB_HOST}:${OPERATIONAL_DB_PORT}/${OPERATIONAL_DB_NAME}?sslmode=disable",
  "username": "${OPERATIONAL_DB_USER}",
  "password": "${OPERATIONAL_DB_PASSWORD}"
}
DBCONFIG
wget -q -O - \
  --header="Content-Type: application/json" \
  --header="X-Vault-Token: ${OPENBAO_ROOT_TOKEN}" \
  --post-file=/tmp/db-config-payload.json \
  "${OPENBAO_BASE_URL}/v1/database/config/${CREDENTIAL_ID}"
echo "Database connection registered."

echo "Creating database role in OpenBao..."
cat > /tmp/role-payload.json << ROLEJSON
{
  "db_name": "${CREDENTIAL_ID}",
  "creation_statements": "CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; GRANT CONNECT ON DATABASE \"${OPERATIONAL_DB_NAME}\" TO \"{{name}}\"; GRANT USAGE ON SCHEMA public TO \"{{name}}\"; GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO \"{{name}}\";",
  "default_ttl": "1h",
  "max_ttl": "24h"
}
ROLEJSON
wget -q -O - \
  --header="Content-Type: application/json" \
  --header="X-Vault-Token: ${OPENBAO_ROOT_TOKEN}" \
  --post-file=/tmp/role-payload.json \
  "${OPENBAO_BASE_URL}/v1/database/roles/${CREDENTIAL_ID}-role"
echo "Database role created."

mkdir -p "$(dirname "${ENV_OUT}")"
cat > "${ENV_OUT}" << ENVEOF
OPENBAO_BASE_URL=${OPENBAO_BASE_URL}
VASSAGO_SERVICE_ID=${VASSAGO_SERVICE_ID}
VASSAGO_OPENBAO_TOKEN=${VASSAGO_TOKEN}
RAUM_SERVICE_ID=${RAUM_SERVICE_ID}
RAUM_OPENBAO_TOKEN=${RAUM_TOKEN}
RAUM_JWT_TRANSIT_KEY_NAME=vassago-jwt
ENVEOF
echo "Wrote env file to ${ENV_OUT}."
echo "Kenoma pre-init complete."