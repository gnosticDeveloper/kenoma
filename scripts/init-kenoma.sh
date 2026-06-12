set -e

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

wait_for "Raum" "${RAUM_BASE_URL}/actuator/health"

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

echo "Provisioning org database schema..."
PGPASSWORD="${CUSTOMER_DB_PASSWORD}" psql \
  -h "${CUSTOMER_DB_HOST}" \
  -p "${CUSTOMER_DB_PORT}" \
  -U "${CUSTOMER_DB_USER}" \
  -d "${CUSTOMER_DB_NAME}" \
  -f /users.sql
echo "Org database schema provisioned."

echo "Registering credentials with Raum..."
post "${RAUM_BASE_URL}/credentials" \
  "{\"orgId\":\"${ORG_ID}\",\"serviceId\":\"${SERVICE_ID}\",\"userName\":\"${CUSTOMER_DB_USER}\",\"password\":\"${CUSTOMER_DB_PASSWORD}\",\"dbHost\":\"${CUSTOMER_DB_HOST}\",\"dbPort\":${CUSTOMER_DB_PORT},\"dbName\":\"${CUSTOMER_DB_NAME}\",\"dbEngine\":\"postgres\"}"
echo "Credentials registered."

if [ "${SEED_DB}" = "true" ]; then
  echo "Inserting seed user..."
  SEED_PASSWORD="${SEED_USER_PASSWORD:-Ch4ng3me!Dev#}"
  SEED_ROLES="${SEED_USER_ROLES:-{\"vassago\":[\"ADMIN\",\"USER\"]}}"

  BCRYPT_HASH=$(python3 -c "
import bcrypt
pwd = '${SEED_PASSWORD}'.encode()
print(bcrypt.hashpw(pwd, bcrypt.gensalt(rounds=10)).decode().replace('\$2b\$', '\$2a\$'))
")

  # Write seed SQL to a temp file to avoid shell quoting issues with
  # the bcrypt hash (\$2a\$...) and JSON roles string.
  cat > /tmp/seed-user.sql << SQLEOF
INSERT INTO users (name, last_name, email, username, password, roles)
VALUES (
    '${SEED_USER_NAME:-Admin}',
    '${SEED_USER_LAST_NAME:-User}',
    '${SEED_USER_EMAIL:-admin@example.com}',
    '${SEED_USER_USERNAME:-admin}',
    '${BCRYPT_HASH}',
    '${SEED_ROLES}'
) ON CONFLICT (username) DO NOTHING;
SQLEOF

  PGPASSWORD="${CUSTOMER_DB_PASSWORD}" psql \
    -h "${CUSTOMER_DB_HOST}" \
    -p "${CUSTOMER_DB_PORT}" \
    -U "${CUSTOMER_DB_USER}" \
    -d "${CUSTOMER_DB_NAME}" \
    -f /tmp/seed-user.sql
  echo "Seed user inserted."
else
  echo "SEED_DB not set to true, skipping seed user insertion."
fi

mkdir -p "$(dirname "${ENV_OUT}")"
cat > "${ENV_OUT}" << ENVEOF
VASSAGO_SERVICE_ID=${SERVICE_ID}
OPENBAO_BASE_URL=http://openbao:8200
OPENBAO_TOKEN=dev-root-token
ENVEOF
echo "Wrote env file to ${ENV_OUT}:"
cat "${ENV_OUT}"
echo "Kenoma initialisation complete."