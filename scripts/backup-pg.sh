#!/usr/bin/env bash
# Weekly PG dump for hcgateway. Designed to be run from cron.
#
# Install (example: every Sunday 04:00 local):
#   crontab -e
#   0 4 * * 0 /home/salmon21/Docker/HCGateway/scripts/backup-pg.sh
#
# Restore:
#   docker exec -i hcgateway_postgres pg_restore -U hcgateway -d hcgateway \
#     --clean --if-exists < pg/backups/hcgateway-YYYYmmdd-HHMMSS.dump
#
# Backups are gitignored under /pg/backups/. Rotation keeps the latest
# $KEEP files; bump KEEP or store off-host (rclone copy, etc.) if you
# want longer history.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$DIR/pg/backups"
KEEP="${BACKUP_KEEP:-8}"
CONTAINER="${PG_CONTAINER:-hcgateway_postgres}"
DB="${PG_DB:-hcgateway}"
USER="${PG_USER:-hcgateway}"

mkdir -p "$OUT_DIR"
ts="$(date -u +%Y%m%d-%H%M%S)"
out="$OUT_DIR/hcgateway-$ts.dump"

# -Fc = custom-format (smaller, parallel restore-capable, schema+data).
# Send to host via stdout so we don't depend on a shared volume.
docker exec "$CONTAINER" pg_dump -U "$USER" -Fc "$DB" > "$out"
size="$(du -h "$out" | cut -f1)"
echo "$(date -u '+%F %T') backup ok: $out ($size)"

# Rotate: keep the $KEEP most recent dumps.
ls -1t "$OUT_DIR"/hcgateway-*.dump 2>/dev/null | tail -n "+$((KEEP + 1))" | xargs -r rm --
