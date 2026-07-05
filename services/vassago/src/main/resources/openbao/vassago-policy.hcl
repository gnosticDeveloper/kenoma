path "database/creds/*" {
  capabilities = ["read"]
}
path "transit/sign/vassago-jwt" {
  capabilities = ["update"]
}
path "transit/keys/vassago-jwt" {
  capabilities = ["read"]
}
