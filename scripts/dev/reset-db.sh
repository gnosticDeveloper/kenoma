#!/bin/sh
# Reset one or more Postgres volumes and force kenoma-pre-init to re-provision
# schema, seed data, and OpenBao grants against the fresh data.
#
# Compose only reruns a one-shot service (condition: service_completed_successfully)
# when it recreates the container; if kenoma-pre-init already exited 0 in a prior
# run, a plain `docker compose up` will not rerun it even after a volume reset,
# leaving OpenBao's grants stale relative to the new database. --force-recreate
# closes that gap.
set -e

if [ "$#" -eq 0 ]; then
  echo "Usage: $0 <vassago-postgres|raum-postgres|bime-postgres> [...]" >&2
  exit 1
fi

for svc in "$@"; do
  echo "Stopping $svc and removing its volume..."
  docker compose rm -sf "$svc"
  docker volume rm "$(basename "$(pwd)")_${svc}-data" 2>/dev/null || true
done

echo "Recreating databases and re-running kenoma-pre-init..."
docker compose up -d "$@"
docker compose up --force-recreate kenoma-pre-init
