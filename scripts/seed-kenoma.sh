set -e

echo "Seeding dev user..."

SEED_ROLES="{\"${VASSAGO_SERVICE_ID}\":[\"VASSAGO_ADMIN\",\"VASSAGO_USER\"],\"${BIME_SERVICE_ID}\":[\"BIME_ADMIN\",\"BIME_MANAGER\"]}"

BCRYPT_HASH=$(python3 -c "
import bcrypt
pwd = '${SEED_USER_PASSWORD}'.encode()
print(bcrypt.hashpw(pwd, bcrypt.gensalt(rounds=10)).decode().replace('\$2b\$', '\$2a\$'))
")

PGPASSWORD="${OPERATIONAL_DB_PASSWORD}" psql \
  -h "${OPERATIONAL_DB_HOST}" -p "${OPERATIONAL_DB_PORT}" \
  -U "${OPERATIONAL_DB_USER}" -d "${OPERATIONAL_DB_NAME}" \
  -c "INSERT INTO users (name, last_name, email, username, password, roles, is_ready)
      VALUES ('${SEED_USER_NAME}', '${SEED_USER_LAST_NAME}', '${SEED_USER_EMAIL}',
              '${SEED_USER_USERNAME}', '${BCRYPT_HASH}', '${SEED_ROLES}', true)
      ON CONFLICT (username) DO UPDATE SET roles = EXCLUDED.roles, modified_at = current_timestamp;"

echo "Dev user seeded."