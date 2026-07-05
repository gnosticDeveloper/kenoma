set -e

echo "Waiting for OpenBao to be reachable..."
until wget -q -O /dev/null "${BAO_ADDR}/v1/sys/seal-status"; do
  echo "OpenBao not yet reachable, retrying..."
  sleep 2
done

STATUS=$(bao status -format=json 2>/dev/null || true)
INITIALIZED=$(echo "$STATUS" | grep '"initialized"' | grep -c 'true' || true)

if [ "$INITIALIZED" -eq 0 ]; then
  echo "Initializing OpenBao..."
  INIT_OUT=$(bao operator init -key-shares=5 -key-threshold=3)
  ROOT_TOKEN=$(echo "$INIT_OUT" | grep "Initial Root Token" | awk '{print $NF}')
  UNSEAL_KEY_1=$(echo "$INIT_OUT" | grep "Unseal Key 1:" | awk '{print $NF}')
  UNSEAL_KEY_2=$(echo "$INIT_OUT" | grep "Unseal Key 2:" | awk '{print $NF}')
  UNSEAL_KEY_3=$(echo "$INIT_OUT" | grep "Unseal Key 3:" | awk '{print $NF}')
  UNSEAL_KEY_4=$(echo "$INIT_OUT" | grep "Unseal Key 4:" | awk '{print $NF}')
  UNSEAL_KEY_5=$(echo "$INIT_OUT" | grep "Unseal Key 5:" | awk '{print $NF}')
  mkdir -p /init-out
  cat > /init-out/unseal-keys.env << KEYEOF
ROOT_TOKEN=${ROOT_TOKEN}
UNSEAL_KEY_1=${UNSEAL_KEY_1}
UNSEAL_KEY_2=${UNSEAL_KEY_2}
UNSEAL_KEY_3=${UNSEAL_KEY_3}
UNSEAL_KEY_4=${UNSEAL_KEY_4}
UNSEAL_KEY_5=${UNSEAL_KEY_5}
KEYEOF
  echo "Initialization complete. Keys written to /init-out/unseal-keys.env."
else
  echo "OpenBao already initialized. Loading stored keys..."
  if [ ! -f /init-out/unseal-keys.env ]; then
    echo "ERROR: OpenBao is initialized but /init-out/unseal-keys.env is missing." && exit 1
  fi
  . /init-out/unseal-keys.env
fi

SEALED=$(echo "$STATUS" | grep '"sealed"' | grep -c 'true' || true)
if [ "$SEALED" -gt 0 ]; then
  echo "Unsealing OpenBao..."
  bao operator unseal "$UNSEAL_KEY_1"
  bao operator unseal "$UNSEAL_KEY_2"
  bao operator unseal "$UNSEAL_KEY_3"
  echo "OpenBao unsealed."
else
  echo "OpenBao is already unsealed."
fi

export BAO_TOKEN="$ROOT_TOKEN"

echo "Checking KV v2 secrets engine..."
if bao secrets list | grep -q '^secret/'; then
  echo "KV v2 already enabled at secret/, skipping."
else
  echo "Enabling KV v2 secrets engine..."
  bao secrets enable -version=2 -path=secret kv
fi

echo "Checking database secrets engine..."
if bao secrets list | grep -q '^database/'; then
  echo "Database secrets engine already enabled, skipping."
else
  echo "Enabling database secrets engine..."
  bao secrets enable database
fi

echo "Checking Transit secrets engine..."
if bao secrets list | grep -q '^transit/'; then
  echo "Transit secrets engine already enabled, skipping."
else
  echo "Enabling Transit secrets engine..."
  bao secrets enable transit
fi

bao write -f transit/keys/vassago-jwt type=ecdsa-p256

echo "Checking AppRole auth method..."
if bao auth list | grep -q '^approle/'; then
  echo "AppRole already enabled, skipping."
else
  echo "Enabling AppRole auth method..."
  bao auth enable approle
fi

if [ -f /approle-out/approle.env ]; then
  echo "Found existing AppRole credentials, will reuse stored secret IDs..."
  . /approle-out/approle.env
fi

# ── Vassago provisioner (Vassago creates its own working policy/role at boot) ─

echo "Writing Vassago provisioner policy..."
bao policy write vassago-provisioner-policy - << POLICY
path "sys/policies/acl/vassago-policy" {
  capabilities = ["create", "read", "update"]
}
path "auth/approle/role/vassago" {
  capabilities = ["create", "read", "update"]
}
path "auth/approle/role/vassago/role-id" {
  capabilities = ["read"]
}
path "auth/approle/role/vassago/secret-id" {
  capabilities = ["update"]
}
POLICY

echo "Creating Vassago provisioner AppRole..."
bao write auth/approle/role/vassago-provisioner \
  token_policies="vassago-provisioner-policy" \
  token_ttl=5m \
  token_max_ttl=15m \
  secret_id_ttl=0

VASSAGO_PROVISIONER_ROLE_ID=$(bao read -field=role_id auth/approle/role/vassago-provisioner/role-id)
if [ -n "${VASSAGO_PROVISIONER_SECRET_ID:-}" ]; then
  echo "Reusing existing Vassago provisioner secret ID."
else
  VASSAGO_PROVISIONER_SECRET_ID=$(bao write -field=secret_id -f auth/approle/role/vassago-provisioner/secret-id)
fi

# ── Raum (external callers authenticating to Raum's credential API) ───────────

echo "Writing Raum policy..."
bao policy write raum-policy - << POLICY
path "transit/keys/vassago-jwt" {
  capabilities = ["read"]
}
POLICY

echo "Creating Raum AppRole..."
bao write auth/approle/role/raum \
  token_policies="raum-policy" \
  token_ttl=1h \
  token_max_ttl=24h \
  secret_id_ttl=0

RAUM_ROLE_ID=$(bao read -field=role_id auth/approle/role/raum/role-id)
if [ -n "${RAUM_APPROLE_SECRET_ID:-}" ]; then
  echo "Reusing existing Raum AppRole secret ID."
  RAUM_SECRET_ID="${RAUM_APPROLE_SECRET_ID}"
else
  RAUM_SECRET_ID=$(bao write -field=secret_id -f auth/approle/role/raum/secret-id)
fi

# ── Raum service provisioner (Raum creates its own working policy/role at boot)

echo "Writing Raum service provisioner policy..."
bao policy write raum-provisioner-policy - << POLICY
path "sys/policies/acl/raum-service-policy" {
  capabilities = ["create", "read", "update"]
}
path "auth/approle/role/raum-service" {
  capabilities = ["create", "read", "update"]
}
path "auth/approle/role/raum-service/role-id" {
  capabilities = ["read"]
}
path "auth/approle/role/raum-service/secret-id" {
  capabilities = ["update"]
}
POLICY

echo "Creating Raum service provisioner AppRole..."
bao write auth/approle/role/raum-provisioner \
  token_policies="raum-provisioner-policy" \
  token_ttl=5m \
  token_max_ttl=15m \
  secret_id_ttl=0

RAUM_PROVISIONER_ROLE_ID=$(bao read -field=role_id auth/approle/role/raum-provisioner/role-id)
if [ -n "${RAUM_PROVISIONER_SECRET_ID:-}" ]; then
  echo "Reusing existing Raum service provisioner secret ID."
else
  RAUM_PROVISIONER_SECRET_ID=$(bao write -field=secret_id -f auth/approle/role/raum-provisioner/secret-id)
fi

# ── Write AppRole credentials ─────────────────────────────────────────────────

mkdir -p /approle-out
cat > /approle-out/approle.env << ENVEOF
VASSAGO_PROVISIONER_ROLE_ID=${VASSAGO_PROVISIONER_ROLE_ID}
VASSAGO_PROVISIONER_SECRET_ID=${VASSAGO_PROVISIONER_SECRET_ID}
RAUM_APPROLE_ROLE_ID=${RAUM_ROLE_ID}
RAUM_APPROLE_SECRET_ID=${RAUM_SECRET_ID}
RAUM_PROVISIONER_ROLE_ID=${RAUM_PROVISIONER_ROLE_ID}
RAUM_PROVISIONER_SECRET_ID=${RAUM_PROVISIONER_SECRET_ID}
ENVEOF
echo "AppRole credentials written to /approle-out/approle.env"
echo "OpenBao initialized."
