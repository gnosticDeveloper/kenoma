set -e

MODE="${KENOMA_INIT_MODE:-seeded}"
echo "Init mode: ${MODE}"

echo "Reading OpenBao root token from init volume..."
if [ ! -f /init-out/unseal-keys.env ]; then
  echo "ERROR: /init-out/unseal-keys.env not found" && exit 1
fi
. /init-out/unseal-keys.env

if [ -n "${DR_BACKUP_S3_ENDPOINT:-}" ]; then
  echo "Storing DR backup S3 credentials in OpenBao KV..."
  wget -q -O - \
    --header="Content-Type: application/json" \
    --header="X-Vault-Token: ${ROOT_TOKEN}" \
    --post-data="{\"data\":{\"endpoint\":\"${DR_BACKUP_S3_ENDPOINT}\",\"bucket\":\"${DR_BACKUP_S3_BUCKET}\",\"access_key\":\"${DR_BACKUP_S3_ACCESS_KEY}\",\"secret_key\":\"${DR_BACKUP_S3_SECRET_KEY}\"}}" \
    "${OPENBAO_BASE_URL}/v1/secret/data/dr-backup/s3"
  echo "DR backup S3 credentials stored."
else
  echo "DR_BACKUP_S3_ENDPOINT not set; skipping DR backup credential seeding."
fi

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
  -t -A -c "SELECT id FROM credentials WHERE service_id = '${VASSAGO_SERVICE_ID}' AND org_id = '${PLATFORM_ORG_ID}' LIMIT 1;")
[ -z "$CREDENTIAL_ID" ] && echo "ERROR: Credential ID not found" && exit 1

BIME_CREDENTIAL_ID=$(PGPASSWORD="${RAUM_DB_PASSWORD}" psql \
  -h "${RAUM_DB_HOST}" -p "${RAUM_DB_PORT}" \
  -U "${RAUM_DB_USER}" -d "${RAUM_DB_NAME}" \
  -t -A -c "SELECT id FROM credentials WHERE service_id = '${BIME_SERVICE_ID}' AND org_id = '${PLATFORM_ORG_ID}' LIMIT 1;")
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

echo "Logging in with Raum AppRole..."
RAUM_LOGIN=$(wget -q -O - \
  --header="Content-Type: application/json" \
  --post-data="{\"role_id\":\"${RAUM_APPROLE_ROLE_ID}\",\"secret_id\":\"${RAUM_APPROLE_SECRET_ID}\"}" \
  "${OPENBAO_BASE_URL}/v1/auth/approle/login")
RAUM_TOKEN=$(echo "$RAUM_LOGIN" | grep -o '"client_token":"[^"]*"' | cut -d'"' -f4)
[ -z "$RAUM_TOKEN" ] && echo "ERROR: Failed to obtain Raum AppRole token." && exit 1
echo "Raum AppRole login successful."

# ---------------------------------------------------------------------------
# Per-tier OpenBao database roles
#
# Grant statements per (service, tier) are generated at build time by
# common.tools.GrantStatementGenerator and checked in as
# scripts/openbao/generated-role-statements.json — raum renders the identical
# text at runtime for tenant orgs, so a Platform-org role and a tenant-org role
# of the same tier are the same grants. This mounts to /role-statements.json in
# the pre-init container; falls back to the in-tree path when run outside Docker.
# ---------------------------------------------------------------------------
ROLE_STATEMENTS_FILE="${ROLE_STATEMENTS_FILE:-/role-statements.json}"
if [ ! -f "$ROLE_STATEMENTS_FILE" ]; then
  _ALT="$(dirname "$0")/openbao/generated-role-statements.json"
  [ -f "$_ALT" ] && ROLE_STATEMENTS_FILE="$_ALT"
fi
[ -f "$ROLE_STATEMENTS_FILE" ] || { echo "ERROR: role statements file not found at $ROLE_STATEMENTS_FILE"; exit 1; }

# Tiers each service supports (weakest -> strongest). Legacy admin/member names
# are also created (mapped to full/readonly text) for the rollout window.
tiers_for() {
  case "$1" in
    vassago) echo "readonly full" ;;
    bime)    echo "catalog readonly sales operations full" ;;
    *)       echo "readonly full" ;;
  esac
}

# allowed_roles list for a connection: every tier role + legacy pair + dr-backup role.
allowed_roles_for() {
  _svc="$1"; _conn="$2"; _out=""
  for _t in $(tiers_for "$_svc"); do _out="${_out}${_conn}-${_t}-role,"; done
  echo "${_out}${_conn}-admin-role,${_conn}-member-role,${_conn}-dr-backup-role"
}

# write_role_payload <service> <tier> <conn_id> <db_name> <org_id> <outfile>
write_role_payload() {
  python3 -c "
import json, sys
svc, tier, conn, dbname, orgid, outfile = sys.argv[1:7]
data = json.load(open('${ROLE_STATEMENTS_FILE}'))
stmt = data[svc][tier].replace('__DBNAME__', dbname).replace('__ORG_ID__', orgid)
json.dump({'db_name': conn, 'creation_statements': stmt, 'default_ttl': '1h', 'max_ttl': '24h'},
          open(outfile, 'w'))
" "$1" "$2" "$3" "$4" "$5" "$6"
}

# create_tier_roles <service> <conn_id> <db_name> <org_id>
create_tier_roles() {
  _svc="$1"; _conn="$2"; _dbn="$3"; _org="$4"
  for _t in $(tiers_for "$_svc"); do
    write_role_payload "$_svc" "$_t" "$_conn" "$_dbn" "$_org" "/tmp/role-${_conn}-${_t}.json"
    wget -q -O - --header="Content-Type: application/json" --header="X-Vault-Token: ${ROOT_TOKEN}" \
      --post-file="/tmp/role-${_conn}-${_t}.json" \
      "${OPENBAO_BASE_URL}/v1/database/roles/${_conn}-${_t}-role"
  done
  # Legacy names: admin == full text, member == readonly text.
  write_role_payload "$_svc" "full" "$_conn" "$_dbn" "$_org" "/tmp/role-${_conn}-admin.json"
  wget -q -O - --header="Content-Type: application/json" --header="X-Vault-Token: ${ROOT_TOKEN}" \
    --post-file="/tmp/role-${_conn}-admin.json" \
    "${OPENBAO_BASE_URL}/v1/database/roles/${_conn}-admin-role"
  write_role_payload "$_svc" "readonly" "$_conn" "$_dbn" "$_org" "/tmp/role-${_conn}-member.json"
  wget -q -O - --header="Content-Type: application/json" --header="X-Vault-Token: ${ROOT_TOKEN}" \
    --post-file="/tmp/role-${_conn}-member.json" \
    "${OPENBAO_BASE_URL}/v1/database/roles/${_conn}-member-role"
}

# register_connection <conn_id> <host> <port> <db_name> <user> <pass> <allowed_roles>
register_connection() {
  python3 -c "
import json, sys
cid, host, port, dbn, usr, pwd, allowed, outf = sys.argv[1:9]
json.dump({
  'plugin_name': 'postgresql-database-plugin',
  'allowed_roles': allowed,
  'connection_url': 'postgresql://{{username}}:{{password}}@%s:%s/%s?sslmode=disable' % (host, port, dbn),
  'username': usr,
  'password': pwd,
}, open(outf, 'w'))
" "$1" "$2" "$3" "$4" "$5" "$6" "$7" "/tmp/conn-$1.json"
  wget -q -O - --header="Content-Type: application/json" --header="X-Vault-Token: ${ROOT_TOKEN}" \
    --post-file="/tmp/conn-$1.json" \
    "${OPENBAO_BASE_URL}/v1/database/config/$1"
}


OPERATOR_ROLES="{\"${VASSAGO_SERVICE_ID}\":[\"VASSAGO_ADMIN\",\"VASSAGO_MEMBER\"],\"${RAUM_SERVICE_ID}\":[\"RAUM_ADMIN\",\"RAUM_ONBOARDING\"],\"${BIME_SERVICE_ID}\":[\"BIME_ADMIN\"]}"

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
  BCRYPT_HASH=$(python3 -c "
import bcrypt
pwd = '${OPERATOR_PASSWORD}'.encode()
print(bcrypt.hashpw(pwd, bcrypt.gensalt(rounds=10)).decode().replace('\$2b\$', '\$2a\$'))
")
  PGPASSWORD="${VASSAGO_DB_PASSWORD}" psql \
    -h "${VASSAGO_DB_HOST}" -p "${VASSAGO_DB_PORT}" \
    -U "${VASSAGO_DB_USER}" -d "${VASSAGO_DB_NAME}" \
    -c "INSERT INTO users (org_id, name, last_name, email, username, password, roles, is_ready)
        VALUES ('${PLATFORM_ORG_ID}', '${OPERATOR_NAME}', '${OPERATOR_LAST_NAME}', '${OPERATOR_EMAIL}',
                '${OPERATOR_USERNAME}', '${BCRYPT_HASH}', '${OPERATOR_ROLES}', true)
        ON CONFLICT (org_id, username) DO UPDATE SET roles = EXCLUDED.roles;"
  echo "Operator user seeded/reconciled."
else
  echo "Reconciling operator roles to current service IDs (no insert, no password change)..."
  PGPASSWORD="${VASSAGO_DB_PASSWORD}" psql \
    -h "${VASSAGO_DB_HOST}" -p "${VASSAGO_DB_PORT}" \
    -U "${VASSAGO_DB_USER}" -d "${VASSAGO_DB_NAME}" \
    -c "UPDATE users SET roles = '${OPERATOR_ROLES}'
        WHERE org_id = '${PLATFORM_ORG_ID}' AND username = '${OPERATOR_USERNAME}';"
  echo "Operator role reconciliation complete."
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

echo "Registering Vassago database connection in OpenBao..."
register_connection "${CREDENTIAL_ID}" "${VASSAGO_DB_HOST}" "${VASSAGO_DB_PORT}" "${VASSAGO_DB_NAME}" \
  "${VASSAGO_DB_USER}" "${VASSAGO_DB_PASSWORD}" "$(allowed_roles_for vassago "${CREDENTIAL_ID}")"
echo "Vassago database connection registered."

echo "Creating Vassago per-tier database roles in OpenBao..."
create_tier_roles vassago "${CREDENTIAL_ID}" "${VASSAGO_DB_NAME}" "${PLATFORM_ORG_ID}"
echo "Vassago database roles created."

echo "Storing Bime credentials in OpenBao KV..."
wget -q -O - \
  --header="Content-Type: application/json" \
  --header="X-Vault-Token: ${ROOT_TOKEN}" \
  --post-data="{\"data\":{\"username\":\"${BIME_DB_USER}\",\"password\":\"${BIME_DB_PASSWORD}\"}}" \
  "${OPENBAO_BASE_URL}/v1/secret/data/credentials/${BIME_CREDENTIAL_ID}"
echo "Bime credentials stored."

echo "Registering Bime database connection in OpenBao..."
register_connection "${BIME_CREDENTIAL_ID}" "${BIME_DB_HOST}" "${BIME_DB_PORT}" "${BIME_DB_NAME}" \
  "${BIME_DB_USER}" "${BIME_DB_PASSWORD}" "$(allowed_roles_for bime "${BIME_CREDENTIAL_ID}")"
echo "Bime database connection registered."

echo "Creating Bime per-tier database roles in OpenBao..."
create_tier_roles bime "${BIME_CREDENTIAL_ID}" "${BIME_DB_NAME}" "${PLATFORM_ORG_ID}"
echo "Bime database roles created."

echo "Marking pre-initialized credentials as initialized..."
PGPASSWORD="${RAUM_DB_PASSWORD}" psql \
  -h "${RAUM_DB_HOST}" -p "${RAUM_DB_PORT}" \
  -U "${RAUM_DB_USER}" -d "${RAUM_DB_NAME}" \
  -c "UPDATE credentials SET is_initialized = true WHERE id IN ('${CREDENTIAL_ID}', '${BIME_CREDENTIAL_ID}');"
echo "Credentials marked as initialized."

fi

# ---------------------------------------------------------------------------
# Reconcile per-tier OpenBao roles for EVERY existing Bime/Vassago credentials
# row (all tenants, not just Platform). Back-fills the new <id>-<tier>-role
# names and widens allowed_roles so raum can issue them immediately after
# deploy. Idempotent — POST replaces. Runs regardless of KENOMA_INIT_MODE.
# ---------------------------------------------------------------------------
echo "Reconciling per-tier OpenBao roles for all existing Bime/Vassago credentials rows..."
PGPASSWORD="${RAUM_DB_PASSWORD}" psql \
  -h "${RAUM_DB_HOST}" -p "${RAUM_DB_PORT}" \
  -U "${RAUM_DB_USER}" -d "${RAUM_DB_NAME}" \
  -t -A -F '|' -c "
    SELECT c.id, c.db_host, c.db_port, c.db_name, c.org_id, lower(s.name)
    FROM credentials c JOIN services s ON s.id = c.service_id
    WHERE s.name IN ('Bime', 'Vassago');" | while IFS='|' read -r RC_ID RC_HOST RC_PORT RC_DB RC_ORG RC_SVC; do
  [ -z "$RC_ID" ] && continue
  RC_KV=$(wget -q -O - --header="X-Vault-Token: ${ROOT_TOKEN}" \
    "${OPENBAO_BASE_URL}/v1/secret/data/credentials/${RC_ID}" 2>/dev/null || true)
  RC_USER=$(echo "$RC_KV" | python3 -c "import sys,json;
try:
    print(json.load(sys.stdin)['data']['data']['username'])
except Exception:
    pass" 2>/dev/null)
  RC_PASS=$(echo "$RC_KV" | python3 -c "import sys,json;
try:
    print(json.load(sys.stdin)['data']['data']['password'])
except Exception:
    pass" 2>/dev/null)
  if [ -z "$RC_USER" ] || [ -z "$RC_PASS" ]; then
    echo "  skip ${RC_SVC} credential ${RC_ID}: no static credentials in KV"
    continue
  fi
  echo "  reconciling ${RC_SVC} credential ${RC_ID} (db=${RC_DB})"
  register_connection "$RC_ID" "$RC_HOST" "$RC_PORT" "$RC_DB" "$RC_USER" "$RC_PASS" \
    "$(allowed_roles_for "$RC_SVC" "$RC_ID")"
  create_tier_roles "$RC_SVC" "$RC_ID" "$RC_DB" "$RC_ORG"
done
echo "Per-tier role reconciliation complete."

mkdir -p "$(dirname "${ENV_OUT}")"
cat > "${ENV_OUT}" << ENVEOF
OPENBAO_BASE_URL=${OPENBAO_BASE_URL}
VASSAGO_SERVICE_ID=${VASSAGO_SERVICE_ID}
VASSAGO_PROVISIONER_ROLE_ID=${VASSAGO_PROVISIONER_ROLE_ID}
VASSAGO_PROVISIONER_SECRET_ID=${VASSAGO_PROVISIONER_SECRET_ID}
RAUM_SERVICE_ID=${RAUM_SERVICE_ID}
RAUM_PROVISIONER_ROLE_ID=${RAUM_PROVISIONER_ROLE_ID}
RAUM_PROVISIONER_SECRET_ID=${RAUM_PROVISIONER_SECRET_ID}
RAUM_JWT_TRANSIT_KEY_NAME=vassago-jwt
BIME_SERVICE_ID=${BIME_SERVICE_ID}
BIME_JWT_TRANSIT_KEY_NAME=vassago-jwt
BIME_PROVISIONER_ROLE_ID=${BIME_PROVISIONER_ROLE_ID}
BIME_PROVISIONER_SECRET_ID=${BIME_PROVISIONER_SECRET_ID}
ENVEOF
echo "Wrote env file to ${ENV_OUT}."

cat > "${ENV_OUT}.local" << LOCALEOF
# Dynamic values generated at startup — regenerated every time pre-init runs
RAUM_SERVICE_ID=${RAUM_SERVICE_ID}
RAUM_PROVISIONER_ROLE_ID=${RAUM_PROVISIONER_ROLE_ID}
RAUM_PROVISIONER_SECRET_ID=${RAUM_PROVISIONER_SECRET_ID}
VASSAGO_SERVICE_ID=${VASSAGO_SERVICE_ID}
VASSAGO_PROVISIONER_ROLE_ID=${VASSAGO_PROVISIONER_ROLE_ID}
VASSAGO_PROVISIONER_SECRET_ID=${VASSAGO_PROVISIONER_SECRET_ID}
VASSAGO_JWT_TRANSIT_KEY_NAME=vassago-jwt
BIME_SERVICE_ID=${BIME_SERVICE_ID}
BIME_JWT_TRANSIT_KEY_NAME=vassago-jwt
BIME_PROVISIONER_ROLE_ID=${BIME_PROVISIONER_ROLE_ID}
BIME_PROVISIONER_SECRET_ID=${BIME_PROVISIONER_SECRET_ID}

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