path "secret/data/credentials/*" {
  capabilities = ["create", "update", "read"]
}
path "secret/metadata/credentials/*" {
  capabilities = ["read", "list"]
}
path "secret/data/dr-backup/*" {
  capabilities = ["read"]
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
path "transit/keys/dr-backup" {
capabilities = ["create", "read", "sudo"]
}
path "transit/encrypt/dr-backup" {
  capabilities = ["update"]
}
path "transit/decrypt/dr-backup" {
  capabilities = ["update"]
}
