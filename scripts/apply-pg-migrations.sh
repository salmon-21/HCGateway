#!/usr/bin/env bash
# Apply pending pg/migrations/*.sql files to the running postgres container.
#
# First-time setup on an existing DB (after docker-entrypoint-initdb.d
# already ran the initial migrations):
#   ./scripts/apply-pg-migrations.sh bootstrap   # mark existing as applied
#
# Normal use (apply any new file):
#   ./scripts/apply-pg-migrations.sh
#
# Migrations are tracked by filename in the `schema_migrations` table.
# Files are applied in lexicographic order. Each file must be re-runnable
# safely (use `IF EXISTS` / `CREATE OR REPLACE` etc.) so partial recovery
# from a half-applied state stays sane.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIG_DIR="$DIR/pg/migrations"
CONTAINER="${PG_CONTAINER:-hcgateway_postgres}"
DB="${PG_DB:-hcgateway}"
USER="${PG_USER:-hcgateway}"
MODE="${1:-apply}"

psql() { docker exec -i "$CONTAINER" psql -U "$USER" -d "$DB" "$@"; }

# Tracker table — created lazily on first run.
psql -v ON_ERROR_STOP=1 -q >/dev/null <<'SQL'
CREATE TABLE IF NOT EXISTS schema_migrations (
  filename   text PRIMARY KEY,
  applied_at timestamptz NOT NULL DEFAULT now()
);
SQL

shopt -s nullglob

if [ "$MODE" = "bootstrap" ]; then
  for f in "$MIG_DIR"/*.sql; do
    name=$(basename "$f")
    psql -q -c "INSERT INTO schema_migrations (filename) VALUES ('$name') ON CONFLICT DO NOTHING" \
      >/dev/null
    echo "  marked $name as applied"
  done
  exit 0
fi

if [ "$MODE" = "status" ]; then
  echo "applied:"
  psql -tAc "SELECT '  ' || filename || '  (' || applied_at || ')' FROM schema_migrations ORDER BY filename"
  echo "pending:"
  applied=$(psql -tAc "SELECT filename FROM schema_migrations" | tr -d '\r')
  for f in "$MIG_DIR"/*.sql; do
    name=$(basename "$f")
    grep -qFx "$name" <<<"$applied" || echo "  $name"
  done
  exit 0
fi

if [ "$MODE" != "apply" ]; then
  echo "unknown mode: $MODE (expected: apply | bootstrap | status)" >&2
  exit 2
fi

applied=$(psql -tAc "SELECT filename FROM schema_migrations" | tr -d '\r')
applied_count=0
for f in "$MIG_DIR"/*.sql; do
  name=$(basename "$f")
  if grep -qFx "$name" <<<"$applied"; then
    continue
  fi
  echo "  apply $name"
  docker cp "$f" "$CONTAINER:/tmp/$name" >/dev/null
  psql -v ON_ERROR_STOP=1 -f "/tmp/$name"
  psql -q -c "INSERT INTO schema_migrations (filename) VALUES ('$name')" >/dev/null
  applied_count=$((applied_count + 1))
done

echo "ok (${applied_count} new migration(s) applied)"
