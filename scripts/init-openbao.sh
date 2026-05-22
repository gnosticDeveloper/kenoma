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

echo "OpenBao initialized."