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

echo "Reading AppRole credentials..."
if [ ! -f /approle-out/approle.env ]; then
  echo "ERROR: AppRole credentials not found at /approle-out/approle.env"
  exit 1
fi
. /approle-out/approle.env

echo "Logging in with AppRole..."
LOGIN_RESPONSE=$(wget -q -O - \
  --header="Content-Type: application/json" \
  --post-data="{\"role_id\":\"${VASSAGO_APPROLE_ROLE_ID}\",\"secret_id\":\"${VASSAGO_APPROLE_SECRET_ID}\"}" \
  "${OPENBAO_BASE_URL}/v1/auth/approle/login")
VASSAGO_TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"client_token":"[^"]*"' | cut -d'"' -f4)
if [ -z "$VASSAGO_TOKEN" ]; then
  echo "ERROR: Failed to obtain AppRole token. Response: ${LOGIN_RESPONSE}"
  exit 1
fi
echo "AppRole login successful."

mkdir -p "$(dirname "${ENV_OUT}")"
cat > "${ENV_OUT}" << ENVEOF
VASSAGO_SERVICE_ID=${SERVICE_ID}
OPENBAO_BASE_URL=${OPENBAO_BASE_URL}
VASSAGO_OPENBAO_TOKEN=${VASSAGO_TOKEN}
ENVEOF
echo "Wrote env file to ${ENV_OUT}."
echo "Kenoma initialisation complete."