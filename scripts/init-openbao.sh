echo "Waiting for OpenBao to be ready..."
until bao status > /dev/null 2>&1; do
  sleep 1
done
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
echo "Writing Vassago policy..."
bao policy write vassago-policy - << POLICY
path "database/creds/*" {
  capabilities = ["read"]
}
path "transit/sign/vassago-jwt" {
  capabilities = ["update"]
}
path "transit/keys/vassago-jwt" {
  capabilities = ["read"]
}
POLICY
echo "Creating Vassago AppRole..."
bao write auth/approle/role/vassago \
  token_policies="vassago-policy" \
  token_ttl=1h \
  token_max_ttl=24h \
  secret_id_ttl=0
ROLE_ID=$(bao read -field=role_id auth/approle/role/vassago/role-id)
SECRET_ID=$(bao write -field=secret_id -f auth/approle/role/vassago/secret-id)
mkdir -p /approle-out
cat > /approle-out/approle.env << ENVEOF
VASSAGO_APPROLE_ROLE_ID=${ROLE_ID}
VASSAGO_APPROLE_SECRET_ID=${SECRET_ID}
ENVEOF
echo "AppRole credentials written to /approle-out/approle.env"
echo "OpenBao initialized."