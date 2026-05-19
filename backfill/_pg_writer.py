"""Shared PostgreSQL writer helpers for backfill scripts.

Backfill scripts read from external exports (Samsung Health CSV, Health
Connect SQLite, etc.) and stream into the live PG via COPY. Gap-fill is
the typical use case — only insert rows that aren't already covered.
"""
import os
import psycopg

POSTGRES_URI = os.environ.get(
    "POSTGRES_URI",
    "postgresql://hcgateway:example@localhost:5432/hcgateway",
)
DEFAULT_USERNAME = os.environ.get("BACKFILL_USERNAME", "salmon21")


def connect():
    return psycopg.connect(POSTGRES_URI)


def get_user_id(conn, username=DEFAULT_USERNAME):
    with conn.cursor() as cur:
        cur.execute("SELECT id::text FROM users WHERE username = %s", (username,))
        row = cur.fetchone()
        if not row:
            raise RuntimeError(
                f"user '{username}' not found. Set BACKFILL_USERNAME or "
                f"create the user via /api/v2/login first."
            )
        return row[0]


def existing_ids(conn, table, user_id, id_col="id"):
    """Return set of `id_col::text` values already present in `table` for user."""
    with conn.cursor() as cur:
        cur.execute(
            f"SELECT {id_col}::text FROM {table} WHERE user_id = %s",
            (user_id,),
        )
        return {r[0] for r in cur.fetchall()}


def existing_days_jst(conn, table, user_id, time_col):
    """JST days already covered in `table` for `user_id`. Returns {(Y, M, D)}."""
    with conn.cursor() as cur:
        cur.execute(
            f"SELECT DISTINCT ({time_col} AT TIME ZONE 'Asia/Tokyo')::date "
            f"FROM {table} WHERE user_id = %s",
            (user_id,),
        )
        return {(d.year, d.month, d.day) for (d,) in cur.fetchall() if d is not None}


def is_gap_jst(dt, existing_days):
    """True iff dt's JST calendar date is NOT in `existing_days`."""
    from datetime import timedelta, timezone
    JST = timezone(timedelta(hours=9))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    j = dt.astimezone(JST)
    return (j.year, j.month, j.day) not in existing_days


def copy_rows(conn, table, columns, rows):
    """Bulk-load `rows` (list of tuples) into `table`. Commits inside."""
    if not rows:
        return 0
    cols = ",".join(columns)
    with conn.cursor() as cur, cur.copy(
        f"COPY {table} ({cols}) FROM STDIN"
    ) as cp:
        for row in rows:
            cp.write_row(row)
    conn.commit()
    return len(rows)
