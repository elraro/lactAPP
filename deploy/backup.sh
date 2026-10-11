#!/bin/sh
# Dumps the database of the running compose stack to a dated, compressed file.
#   ./deploy/backup.sh [directory]        (default: ./backups)
# Restore into an empty database with:
#   gunzip -c backups/babytracker-YYYYMMDD-HHMMSS.sql.gz | docker compose exec -T db psql -U "$POSTGRES_USER" "$POSTGRES_DB"
# After restoring an older backup, phones notice that history changed and re-upload what
# only they have (docs/SYNC.md §3, "epoch mismatch"): run
#   docker compose exec db psql -U "$POSTGRES_USER" "$POSTGRES_DB" -c "UPDATE families SET sync_epoch = gen_random_uuid()"
set -eu
cd "$(dirname "$0")/.."
dir="${1:-backups}"
mkdir -p "$dir"
file="$dir/babytracker-$(date +%Y%m%d-%H%M%S).sql.gz"
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' | gzip > "$file"
echo "written $file"
