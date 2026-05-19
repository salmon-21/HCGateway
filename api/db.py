"""PostgreSQL connection pool and small helpers used by routes.

Sync interface (Flask is sync). Single global pool reused across requests.
"""
import os
from psycopg_pool import ConnectionPool
from psycopg.rows import dict_row

POSTGRES_URI = os.environ["POSTGRES_URI"]

# `prepare_threshold=0` makes psycopg server-side-prepare every statement on
# first execute. Without it, /status pays ~90 ms of TimescaleDB chunk-planning
# *per hypertable, per call* (~360 ms total). Once prepared, the plan lives
# for the life of that connection and subsequent executes are ~10 ms each.
#
# Prepared plans are per-connection though. The pool grows to max_size under
# /sync bursts; when a request later reaches a fresh connection (or one only
# used for INSERTs) the plans aren't there. To avoid the user-visible spike
# this caused, we eagerly prime every new connection with the SELECTs that
# /status and the auth path issue, so the plans are ready before the first
# real request.
_PRIMER_SQL = (
    "SELECT id, expiry FROM users WHERE token = %s",
    "SELECT id::text AS id FROM users WHERE username = %s",
    "SELECT id::text FROM users ORDER BY id LIMIT 1",
    "SELECT user_id::text AS user_id, time AS latest FROM heart_rate_sample "
    "WHERE time = (SELECT max(time) FROM heart_rate_sample) LIMIT 1",
    "SELECT max(start_at) AS latest FROM steps WHERE user_id = %s",
    "SELECT max(start_at) AS latest FROM distance WHERE user_id = %s",
    "SELECT max(start_at) AS latest FROM total_calories_burned WHERE user_id = %s",
    "SELECT DISTINCT (hour AT TIME ZONE %s)::date AS d FROM heart_rate_hourly "
    "WHERE user_id = %s AND hour >= %s",
)


def _prime_params(sql):
    """Return dummy params matching the placeholders in sql."""
    n = sql.count("%s")
    if n == 0:
        return ()
    # Inputs are designed not to match real data — first SELECT is on `token`
    # (text), the heart_rate_hourly one is (tz_key, uuid, ts). We pass strings
    # everywhere because PG only needs the value to *type-check* the prepare;
    # the row count we'd get back isn't relevant.
    if "hour AT TIME ZONE" in sql:
        from datetime import datetime, timezone
        return ("UTC", "00000000-0000-0000-0000-000000000000",
                datetime(1970, 1, 1, tzinfo=timezone.utc))
    return tuple(["00000000-0000-0000-0000-000000000000"] * n)


def _configure_conn(conn):
    conn.prepare_threshold = 0
    # Run primers in autocommit so each SELECT leaves the connection in
    # IDLE (not INTRANS) — otherwise the pool discards the connection
    # right after configure returns and we get PoolTimeout on every
    # request.
    conn.autocommit = True
    try:
        with conn.cursor() as cur:
            for sql in _PRIMER_SQL:
                try:
                    cur.execute(sql, _prime_params(sql))
                    cur.fetchall()
                except Exception as e:
                    print(f"  primer skipped: {e}")
    finally:
        conn.autocommit = False


pool = ConnectionPool(
    POSTGRES_URI,
    min_size=1,
    max_size=10,
    open=True,
    timeout=10,
    configure=_configure_conn,
)


def fetch_one(sql, params=()):
    with pool.connection() as conn:
        with conn.cursor(row_factory=dict_row) as cur:
            cur.execute(sql, params)
            return cur.fetchone()


def fetch_all(sql, params=()):
    with pool.connection() as conn:
        with conn.cursor(row_factory=dict_row) as cur:
            cur.execute(sql, params)
            return cur.fetchall()


def execute(sql, params=()):
    """Run a statement with no result (INSERT/UPDATE/DELETE)."""
    with pool.connection() as conn:
        with conn.cursor() as cur:
            cur.execute(sql, params)
