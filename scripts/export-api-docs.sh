#!/usr/bin/env bash
set -euo pipefail
# Usage: scripts/export-api-docs.sh [--upload]
#
# Pulls the live /v3/api-docs spec from the raum/vassago/bime containers (local
# `docker compose up` stack or the production stack), merges them into a single
# OpenAPI document, and bundles that into one self-contained Redoc HTML file at
# ./kenoma-api-docs.html.
#
# With --upload, also pushes that file to the existing DR-backup Hetzner bucket
# (creds from .env) under the api-docs/ prefix, kept separate from dr-backups/.

UPLOAD=false
[[ "${1:-}" == "--upload" ]] && UPLOAD=true

OUT_DIR="$(mktemp -d)"
trap 'rm -rf "${OUT_DIR}"' EXIT

declare -A SVC_PORTS=( [raum]=8080 [vassago]=8081 [bime]=8082 )

for svc in "${!SVC_PORTS[@]}"; do
  port="${SVC_PORTS[$svc]}"
  docker exec "$svc" curl -sf "http://localhost:${port}/v3/api-docs" -o "/tmp/${svc}-openapi.json"
  docker cp "${svc}:/tmp/${svc}-openapi.json" "${OUT_DIR}/${svc}-openapi.json"
done

docker run --rm -v "${OUT_DIR}:/docs" redocly/cli join \
  /docs/raum-openapi.json /docs/vassago-openapi.json /docs/bime-openapi.json \
  --prefix-components-with-info-prop=title \
  --prefix-tags-with-info-prop=title \
  -o /docs/kenoma-openapi.yaml

docker run --rm -v "${OUT_DIR}:/docs" redocly/cli build-docs \
  /docs/kenoma-openapi.yaml -o /docs/kenoma-api-docs.html

cp "${OUT_DIR}/kenoma-api-docs.html" ./kenoma-api-docs.html
echo "Generated ./kenoma-api-docs.html"

if [[ "$UPLOAD" == true ]]; then
  # Extract only the vars we need rather than sourcing .env wholesale — other
  # entries (e.g. DR_BACKUP_CRON's unquoted cron expression) aren't valid shell.
  DR_BACKUP_S3_ENDPOINT="$(grep -m1 '^DR_BACKUP_S3_ENDPOINT=' .env | cut -d= -f2-)"
  DR_BACKUP_S3_BUCKET="$(grep -m1 '^DR_BACKUP_S3_BUCKET=' .env | cut -d= -f2-)"
  DR_BACKUP_S3_ACCESS_KEY="$(grep -m1 '^DR_BACKUP_S3_ACCESS_KEY=' .env | cut -d= -f2-)"
  DR_BACKUP_S3_SECRET_KEY="$(grep -m1 '^DR_BACKUP_S3_SECRET_KEY=' .env | cut -d= -f2-)"
  # Mirrors S3ArtifactStore#withScheme (raum): the endpoint stored in .env/OpenBao
  # may or may not include a scheme depending on how it was entered.
  [[ "${DR_BACKUP_S3_ENDPOINT}" == *"://"* ]] || DR_BACKUP_S3_ENDPOINT="https://${DR_BACKUP_S3_ENDPOINT}"
  docker run --rm \
    -e AWS_ACCESS_KEY_ID="${DR_BACKUP_S3_ACCESS_KEY}" \
    -e AWS_SECRET_ACCESS_KEY="${DR_BACKUP_S3_SECRET_KEY}" \
    -v "${OUT_DIR}:/docs" amazon/aws-cli \
    --endpoint-url "${DR_BACKUP_S3_ENDPOINT}" \
    s3 cp /docs/kenoma-api-docs.html "s3://${DR_BACKUP_S3_BUCKET}/api-docs/kenoma-api-docs.html"
  echo "Uploaded to s3://${DR_BACKUP_S3_BUCKET}/api-docs/kenoma-api-docs.html"
fi
