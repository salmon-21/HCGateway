"""Import Health Connect SQLite export into PostgreSQL.

Reads /tmp/hc_export/health_connect_export.db (override with HC_SQLITE).
Idempotent by source uuid — re-running is safe.
"""
import os
import sqlite3
import json
import sys
import uuid
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(__file__))
from _pg_writer import connect, get_user_id, existing_ids, copy_rows  # noqa: E402

SQLITE_PATH = os.environ.get("HC_SQLITE", "/tmp/hc_export/health_connect_export.db")


def blob_to_uuid(b):
    return str(uuid.UUID(bytes=b))


def ms_to_dt(ms):
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc) if ms is not None else None


def load_app_names(sq):
    """`application_info_table` is small (<100 rows); load it once."""
    return {
        r["row_id"]: r["package_name"]
        for r in sq.execute("SELECT row_id, package_name FROM application_info_table")
    }


def import_heart_rate(pg, sq, user_id, apps):
    have = existing_ids(pg, "heart_rate_sample", user_id, id_col="DISTINCT source_id")
    rows = []
    for r in sq.execute("SELECT * FROM heart_rate_record_table").fetchall():
        uid = blob_to_uuid(r["uuid"])
        if uid in have:
            continue
        app = apps.get(r["app_info_id"], "unknown")
        for s in sq.execute(
            "SELECT beats_per_minute, epoch_millis FROM heart_rate_record_series_table "
            "WHERE parent_key = ?",
            (r["row_id"],),
        ):
            t = ms_to_dt(s["epoch_millis"])
            if t is None or s["beats_per_minute"] is None:
                continue
            rows.append((t, user_id, int(s["beats_per_minute"]), uid, app))
    n = copy_rows(pg, "heart_rate_sample",
                  ["time", "user_id", "bpm", "source_id", "app"], rows)
    print(f"  heart_rate_sample: {n} samples")


def import_sleep(pg, sq, user_id, apps):
    have = existing_ids(pg, "sleep_session", user_id)
    rows = []
    for r in sq.execute("SELECT * FROM sleep_session_record_table").fetchall():
        uid = blob_to_uuid(r["uuid"])
        if uid in have:
            continue
        stages = [
            {
                "startTime": ms_to_dt(s["stage_start_time"]).isoformat(),
                "endTime": ms_to_dt(s["stage_end_time"]).isoformat(),
                "stage": s["stage_type"],
            }
            for s in sq.execute(
                "SELECT stage_start_time, stage_end_time, stage_type FROM sleep_stages_table "
                "WHERE parent_key = ?",
                (r["row_id"],),
            )
        ]
        rows.append((
            ms_to_dt(r["start_time"]), uid, user_id,
            ms_to_dt(r["end_time"]), apps.get(r["app_info_id"], "unknown"),
            json.dumps(stages) if stages else None,
        ))
    n = copy_rows(pg, "sleep_session",
                  ["start_at", "id", "user_id", "end_at", "app", "stages"], rows)
    print(f"  sleep_session: {n}")


def _interval_import(pg, sq, apps, *, src_table, src_value_col, dst_table, dst_value_col,
                     dst_cast=None, user_id):
    """1:1 interval importer. dst_cast normalises units (e.g. joules → kcal)."""
    have = existing_ids(pg, dst_table, user_id)
    rows = []
    for r in sq.execute(f"SELECT * FROM {src_table}").fetchall():
        uid = blob_to_uuid(r["uuid"])
        if uid in have or r[src_value_col] is None:
            continue
        v = dst_cast(r[src_value_col]) if dst_cast else r[src_value_col]
        rows.append((
            ms_to_dt(r["start_time"]), uid, user_id,
            ms_to_dt(r["end_time"]), apps.get(r["app_info_id"], "unknown"),
            v,
        ))
    n = copy_rows(pg, dst_table,
                  ["start_at", "id", "user_id", "end_at", "app", dst_value_col], rows)
    print(f"  {dst_table}: {n}")


def import_steps(pg, sq, user_id, apps):
    _interval_import(pg, sq, apps,
        src_table="steps_record_table", src_value_col="count",
        dst_table="steps", dst_value_col="count", user_id=user_id)


def import_distance(pg, sq, user_id, apps):
    _interval_import(pg, sq, apps,
        src_table="distance_record_table", src_value_col="distance",
        dst_table="distance", dst_value_col="meters", user_id=user_id)


def import_calories(pg, sq, user_id, apps):
    _interval_import(pg, sq, apps,
        src_table="total_calories_burned_record_table", src_value_col="energy",
        dst_table="total_calories_burned", dst_value_col="kcal",
        dst_cast=lambda joules: joules / 4184.0, user_id=user_id)


def import_oxygen_saturation(pg, sq, user_id, apps):
    have = existing_ids(pg, "oxygen_saturation", user_id)
    rows = []
    for r in sq.execute("SELECT * FROM oxygen_saturation_record_table").fetchall():
        uid = blob_to_uuid(r["uuid"])
        if uid in have or r["percentage"] is None:
            continue
        rows.append((
            uid, user_id, ms_to_dt(r["time"]),
            int(r["percentage"]), apps.get(r["app_info_id"], "unknown"),
        ))
    n = copy_rows(pg, "oxygen_saturation",
                  ["id", "user_id", "start_at", "percentage", "app"], rows)
    print(f"  oxygen_saturation: {n}")


def import_speed(pg, sq, user_id, apps):
    have = existing_ids(pg, "speed_sample", user_id, id_col="DISTINCT source_id")
    try:
        parents = sq.execute("SELECT * FROM SpeedRecordTable").fetchall()
    except sqlite3.OperationalError:
        print("  speed_sample: 0 (no SpeedRecordTable)")
        return
    rows = []
    for r in parents:
        uid = blob_to_uuid(r["uuid"])
        if uid in have:
            continue
        app = apps.get(r["app_info_id"], "unknown")
        for s in sq.execute(
            "SELECT speed, epoch_millis FROM speed_record_table WHERE parent_key = ?",
            (r["row_id"],),
        ):
            t = ms_to_dt(s["epoch_millis"])
            if t is None or s["speed"] is None:
                continue
            rows.append((t, user_id, float(s["speed"]), uid, app))
    n = copy_rows(pg, "speed_sample",
                  ["time", "user_id", "speed_mps", "source_id", "app"], rows)
    print(f"  speed_sample: {n} samples")


def import_exercise(pg, sq, user_id, apps):
    """exercise_session — title/notes are dropped (no columns in PG schema)."""
    have = existing_ids(pg, "exercise_session", user_id)
    rows = []
    for r in sq.execute("SELECT * FROM exercise_session_record_table").fetchall():
        uid = blob_to_uuid(r["uuid"])
        if uid in have or r["exercise_type"] is None:
            continue
        rows.append((
            ms_to_dt(r["start_time"]), uid, user_id,
            ms_to_dt(r["end_time"]), apps.get(r["app_info_id"], "unknown"),
            int(r["exercise_type"]),
        ))
    n = copy_rows(pg, "exercise_session",
                  ["start_at", "id", "user_id", "end_at", "app", "exercise_type"], rows)
    print(f"  exercise_session: {n}")


def main():
    if not os.path.exists(SQLITE_PATH):
        print(f"SQLite export not found at {SQLITE_PATH}. "
              f"Set HC_SQLITE to override.", file=sys.stderr)
        sys.exit(1)

    pg = connect()
    user_id = get_user_id(pg)
    print(f"Importing Health Connect export → user {user_id}")

    sq = sqlite3.connect(SQLITE_PATH)
    sq.row_factory = sqlite3.Row
    apps = load_app_names(sq)

    import_heart_rate(pg, sq, user_id, apps)
    import_sleep(pg, sq, user_id, apps)
    import_steps(pg, sq, user_id, apps)
    import_distance(pg, sq, user_id, apps)
    import_calories(pg, sq, user_id, apps)
    import_oxygen_saturation(pg, sq, user_id, apps)
    import_speed(pg, sq, user_id, apps)
    import_exercise(pg, sq, user_id, apps)

    pg.close()
    sq.close()
    print("Done.")


if __name__ == "__main__":
    main()
