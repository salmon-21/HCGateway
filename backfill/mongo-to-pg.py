"""One-shot Mongo → PostgreSQL migration for HCGateway.

Reads from `hcgateway` (users) and `hcgateway_<userid>` (per-user record
collections) in the Mongo instance, writes into the PG schema defined by
pg/migrations/0001_init.sql via COPY for bulk speed.

Modes:
    --dry-run    Count source rows per collection vs target. No writes.
    --verify     Compare counts after a migration run.
    --reset      Wipe all PG record tables for migrated users before loading.
                 Default mode (no flag) refuses to write if target tables
                 already hold records for the user.
    --user-only OID
                 Migrate only a single Mongo user._id (debug).

Idempotency: with --reset, re-running drops and reloads the user's data.
Without --reset, the script aborts if PG already has records for the user
to avoid silently doubling on accidental re-runs.

ObjectId → UUID: every Mongo `users._id` is given a fresh UUID. The mapping
is written to /tmp/migration_user_map.json so CLAUDE.local.md / external
references can be updated.
"""
import os
import sys
import json
import uuid
import time
import argparse
from datetime import datetime, date

# Allow `from method_schema import …`
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "api"))
from method_schema import METHOD_SCHEMA, get_nested  # noqa: E402

import pymongo
import psycopg


MONGO_URI = os.environ.get(
    "MONGO_URI",
    "mongodb://root:example@localhost:27017/",
)
POSTGRES_URI = os.environ.get(
    "POSTGRES_URI",
    "postgresql://hcgateway:example@localhost:5432/hcgateway",
)
USER_MAP_PATH = "/tmp/migration_user_map.json"


def json_default(o):
    if isinstance(o, (datetime, date)):
        return o.isoformat()
    return str(o)


# Health Connect emits some scalar fields as unit-objects, e.g.
#   distance.samples[].speed   = {"inMetersPerSecond": x, "inMilesPerHour": ...}
# Normalize to a single SI/canonical scalar. Order of preference matches
# what the rest of the schema expects (m, m/s, kcal, °C).
_UNIT_PRIORITY = (
    "inMetersPerSecond", "inMeters", "inKilometers",
    "inKilocalories", "inCalories", "inJoules", "inKilojoules",
    "inCelsius", "inFahrenheit",
)


def _pick_scalar(v):
    """Return v if scalar; if HC unit-object, pick the preferred unit.
    Returns None for empty/missing inputs so the caller can skip the row."""
    if v is None:
        return None
    if isinstance(v, dict):
        for k in _UNIT_PRIORITY:
            if k in v:
                return v[k]
        return None
    return v


def _cast_for_col(v, sql_type):
    """Coerce v to a representation COPY can serialize for sql_type. Mongo
    sometimes stores integer measurements as floats (e.g. percentage=98.0
    in oxygenSaturation); smallint/integer columns reject that."""
    if v is None:
        return None
    if sql_type in ("smallint", "integer"):
        return int(v)
    return v


# PG columns that are nullable per pg/migrations/0001_init.sql.
# Anything not listed is NOT NULL → a None source value forces row skip.
_NULLABLE_COLS = {
    "skin_temperature": {"baseline_c"},
    "sleep_session": {"stages"},
    "vitality_score": {"total_score", "sleep_score", "activity_score", "shr_score"},
}


# ---------------------------------------------------------------------------
# Users
# ---------------------------------------------------------------------------

def migrate_users(mongo, pg, only_oid=None):
    """Insert/update users into PG. Returns {old_oid_str: new_uuid}."""
    user_map = {}
    with pg.cursor() as cur:
        for u in mongo["hcgateway"]["users"].find():
            old_id = str(u["_id"])
            if only_oid and old_id != only_oid:
                continue

            # Reuse existing PG user if username already migrated
            cur.execute("SELECT id FROM users WHERE username = %s", (u["username"],))
            row = cur.fetchone()
            new_id = row[0] if row else uuid.uuid4()
            user_map[old_id] = new_id

            cur.execute(
                "INSERT INTO users (id, username, password, token, refresh, expiry, fcm_token) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s) "
                "ON CONFLICT (username) DO UPDATE SET "
                "  password = EXCLUDED.password, "
                "  token = EXCLUDED.token, "
                "  refresh = EXCLUDED.refresh, "
                "  expiry = EXCLUDED.expiry, "
                "  fcm_token = EXCLUDED.fcm_token",
                (
                    new_id,
                    u["username"],
                    u["password"],
                    u.get("token"),
                    u.get("refresh"),
                    u.get("expiry"),
                    u.get("fcmToken"),
                ),
            )
    pg.commit()
    return user_map


# ---------------------------------------------------------------------------
# Per-collection migration
# ---------------------------------------------------------------------------

def _ensure_empty(pg, table, user_id, kind):
    with pg.cursor() as cur:
        cur.execute(f"SELECT 1 FROM {table} WHERE user_id = %s LIMIT 1", (user_id,))
        if cur.fetchone():
            raise RuntimeError(
                f"target table {table} already has rows for user {user_id}; "
                f"re-run with --reset to wipe before loading."
            )


def _wipe(pg, table, user_id):
    with pg.cursor() as cur:
        cur.execute(f"DELETE FROM {table} WHERE user_id = %s", (user_id,))
    pg.commit()


def _migrate_samples(pg, schema, user_id, mongo_col):
    table = schema["table"]
    val_cols = schema["value_cols"]
    sample_path = schema["sample_path"]
    time_field = schema["sample_time_field"]
    cols = ["time", "user_id", "source_id", "app"] + [c[1] for c in val_cols]

    rows_written = 0
    docs_skipped = 0
    samples_skipped = 0
    with pg.cursor() as cur, cur.copy(
        f"COPY {table} ({','.join(cols)}) FROM STDIN"
    ) as cp:
        for doc in mongo_col.find():
            try:
                source_id = uuid.UUID(doc["_id"])
            except (ValueError, TypeError):
                docs_skipped += 1
                continue
            app = doc.get("app") or "unknown"
            for s in (doc.get(sample_path) or []):
                t = s.get(time_field)
                if not isinstance(t, datetime):
                    samples_skipped += 1
                    continue
                values = []
                missing = False
                for src, _, sql_type in val_cols:
                    v = _cast_for_col(_pick_scalar(s.get(src)), sql_type)
                    if v is None:
                        missing = True
                        break
                    values.append(v)
                if missing:
                    samples_skipped += 1
                    continue
                cp.write_row([t, user_id, source_id, app, *values])
                rows_written += 1
    pg.commit()
    return rows_written, docs_skipped + samples_skipped


def _migrate_records(pg, schema, user_id, mongo_col):
    table = schema["table"]
    kind = schema["kind"]
    val_cols = schema["value_cols"]

    cols = ["id", "user_id", "start_at"]
    if kind != "instant":
        cols.append("end_at")
    cols.append("app")
    cols += [c[1] for c in val_cols]

    rows_written = 0
    docs_skipped = 0
    with pg.cursor() as cur, cur.copy(
        f"COPY {table} ({','.join(cols)}) FROM STDIN"
    ) as cp:
        for doc in mongo_col.find():
            try:
                rid = uuid.UUID(doc["_id"])
            except (ValueError, TypeError):
                docs_skipped += 1
                continue
            start = doc.get("start")
            if not isinstance(start, datetime):
                docs_skipped += 1
                continue
            end = doc.get("end")
            app = doc.get("app") or "unknown"

            nullables = _NULLABLE_COLS.get(table, set())
            extra = []
            row_skip = False
            for src, dst, sql_type in val_cols:
                v = get_nested(doc, src)
                if sql_type == "jsonb":
                    if v is not None:
                        v = json.dumps(v, default=json_default)
                else:
                    v = _cast_for_col(_pick_scalar(v), sql_type)
                if v is None and dst not in nullables:
                    row_skip = True
                    break
                extra.append(v)
            if row_skip:
                docs_skipped += 1
                continue
            row = [rid, user_id, start]
            if kind != "instant":
                row.append(end if isinstance(end, datetime) else None)
            row.append(app)
            row.extend(extra)
            cp.write_row(row)
            rows_written += 1
    pg.commit()
    return rows_written, docs_skipped


def migrate_user_records(mongo, pg, old_oid, new_uid, reset=False):
    src_db = mongo[f"hcgateway_{old_oid}"]
    summary = {}

    for method, schema in METHOD_SCHEMA.items():
        if method not in src_db.list_collection_names():
            continue
        col = src_db[method]
        n_src = col.count_documents({})
        if n_src == 0:
            continue

        if reset:
            _wipe(pg, schema["table"], new_uid)
        else:
            _ensure_empty(pg, schema["table"], new_uid, schema["kind"])

        t0 = time.time()
        if schema["kind"] == "samples":
            n_written, n_skipped = _migrate_samples(pg, schema, new_uid, col)
        else:
            n_written, n_skipped = _migrate_records(pg, schema, new_uid, col)
        elapsed = time.time() - t0

        summary[method] = {
            "source_docs": n_src,
            "target_rows": n_written,
            "skipped": n_skipped,
            "seconds": round(elapsed, 2),
        }
        print(f"  {method:28s} src={n_src:>7d} → "
              f"rows={n_written:>7d} skip={n_skipped:>3d} "
              f"({elapsed:.1f}s)")
    return summary


# ---------------------------------------------------------------------------
# Dry-run / verify
# ---------------------------------------------------------------------------

def dry_run(mongo, pg, only_oid=None):
    print("DRY RUN — source counts only.\n")
    for u in mongo["hcgateway"]["users"].find():
        old_id = str(u["_id"])
        if only_oid and old_id != only_oid:
            continue
        src_db = mongo[f"hcgateway_{old_id}"]
        print(f"=== user {old_id} ({u.get('username')}) ===")
        total = 0
        for method, schema in METHOD_SCHEMA.items():
            if method not in src_db.list_collection_names():
                continue
            n = src_db[method].count_documents({})
            if n == 0:
                continue
            kind = schema["kind"]
            print(f"  {method:28s} {n:>7d} docs  [{kind}]")
            total += n
        print(f"  -- total docs: {total} --\n")


def verify(mongo, pg, user_map):
    print("\nVerify — comparing counts.\n")
    ok = True
    for old_id, new_uid in user_map.items():
        src_db = mongo[f"hcgateway_{old_id}"]
        for method, schema in METHOD_SCHEMA.items():
            if method not in src_db.list_collection_names():
                continue
            n_src = src_db[method].count_documents({})
            if n_src == 0:
                continue
            table = schema["table"]
            with pg.cursor() as cur:
                if schema["kind"] == "samples":
                    cur.execute(
                        f"SELECT count(DISTINCT source_id) FROM {table} WHERE user_id = %s",
                        (new_uid,),
                    )
                else:
                    cur.execute(
                        f"SELECT count(*) FROM {table} WHERE user_id = %s",
                        (new_uid,),
                    )
                n_pg = cur.fetchone()[0]
            match = "✓" if n_pg == n_src else "✗"
            if n_pg != n_src:
                ok = False
            print(f"  {match} {method:28s} mongo={n_src:>7d}  pg={n_pg:>7d}")
    print()
    return ok


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--verify", action="store_true")
    ap.add_argument("--reset", action="store_true",
                    help="wipe target rows before loading (allows re-runs)")
    ap.add_argument("--user-only", help="migrate only this Mongo user._id")
    args = ap.parse_args()

    mongo = pymongo.MongoClient(MONGO_URI)
    pg = psycopg.connect(POSTGRES_URI)

    if args.dry_run:
        dry_run(mongo, pg, only_oid=args.user_only)
        return

    print(f"Source: {MONGO_URI.split('@')[-1]}")
    print(f"Target: {POSTGRES_URI.split('@')[-1]}")
    if args.reset:
        print("Mode:   --reset (will wipe target rows for migrated users)\n")
    else:
        print("Mode:   safe (aborts if target has rows for the user)\n")

    user_map = migrate_users(mongo, pg, only_oid=args.user_only)
    if not user_map:
        print("No users to migrate.")
        return

    with open(USER_MAP_PATH, "w") as f:
        json.dump({k: str(v) for k, v in user_map.items()}, f, indent=2)
    print(f"User map → {USER_MAP_PATH}")
    for old_id, new_uid in user_map.items():
        print(f"  {old_id}  →  {new_uid}")
    print()

    overall_t0 = time.time()
    for old_id, new_uid in user_map.items():
        print(f"=== migrating user records for {old_id} → {new_uid} ===")
        migrate_user_records(mongo, pg, old_id, new_uid, reset=args.reset)
    elapsed = time.time() - overall_t0
    print(f"\nTotal record-migration time: {elapsed:.1f}s")

    if args.verify:
        ok = verify(mongo, pg, user_map)
        sys.exit(0 if ok else 1)

    print("\nNote: run with --verify (or as a second invocation) to check counts.")
    print("Note: refresh continuous aggregate with:")
    print("  CALL refresh_continuous_aggregate('heart_rate_hourly', NULL, NULL);")


if __name__ == "__main__":
    main()
