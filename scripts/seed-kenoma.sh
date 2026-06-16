set -e

echo "Reading service ID from env file..."
if [ ! -f /env-out/.env ]; then
  echo "ERROR: env file not found at /env-out/.env"
  exit 1
fi
. /env-out/.env
SERVICE_ID="${VASSAGO_SERVICE_ID}"
if [ -z "$SERVICE_ID" ]; then
  echo "ERROR: VASSAGO_SERVICE_ID not found in env file"
  exit 1
fi
echo "Service ID: ${SERVICE_ID}"

echo "Inserting seed user..."

SEED_PASSWORD="${SEED_USER_PASSWORD:-Ch4ng3me!Dev#}"
SEED_ROLES="${SEED_USER_ROLES:-{\"${SERVICE_ID}\":[\"VASSAGO_ADMIN\",\"VASSAGO_USER\"]}}"

BCRYPT_HASH=$(python3 -c "
import bcrypt
pwd = '${SEED_PASSWORD}'.encode()
print(bcrypt.hashpw(pwd, bcrypt.gensalt(rounds=10)).decode().replace('\$2b\$', '\$2a\$'))
")

cat > /tmp/seed-user.sql << SQLEOF
INSERT INTO users (name, last_name, email, username, password, roles, is_ready)
VALUES (
    '${SEED_USER_NAME:-Admin}',
    '${SEED_USER_LAST_NAME:-User}',
    '${SEED_USER_EMAIL:-admin@example.com}',
    '${SEED_USER_USERNAME:-admin}',
    '${BCRYPT_HASH}',
    '${SEED_ROLES}',
    true
) ON CONFLICT (username) DO NOTHING;
SQLEOF

PGPASSWORD="${CUSTOMER_DB_PASSWORD}" psql \
  -h "${CUSTOMER_DB_HOST}" \
  -p "${CUSTOMER_DB_PORT}" \
  -U "${CUSTOMER_DB_USER}" \
  -d "${CUSTOMER_DB_NAME}" \
  -f /tmp/seed-user.sql

echo "Seed user inserted."