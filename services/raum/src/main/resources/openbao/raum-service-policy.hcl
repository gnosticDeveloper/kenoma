path "secret/data/credentials/*" {
  capabilities = ["create", "update", "read"]
}
path "secret/metadata/credentials/*" {
  capabilities = ["read", "list"]
}
path "database/config/*" {
  capabilities = ["create", "update", "read"]
}
path "database/roles/*" {
  capabilities = ["create", "update", "read"]
}
path "database/creds/*" {
  capabilities = ["read"]
}
path "transit/keys/vassago-jwt" {
  capabilities = ["read"]
}
