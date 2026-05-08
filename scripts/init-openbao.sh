echo "Waiting for OpenBao to be ready..."
until bao status > /dev/null 2>&1; do
  sleep 1
done

echo "Enabling transit engine..."
bao secrets enable transit

echo "Creating JWT signing key..."
bao write transit/keys/jwt-signing-key type=ecdsa-p256

echo "Creating credential encryption key..."
bao write transit/keys/credential-encryption-key type=aes256-gcm96

echo "Enabling database secrets engine..."
bao secrets enable database

echo "OpenBao initialized."