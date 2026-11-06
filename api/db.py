"""PostgreSQL connection pool and small helpers used by routes.

Sync interface (Flask is sync). Single global pool reused across requests.
"""
import os
from psycopg_pool import ConnectionPool
from psycopg.rows import dict_row

POSTGRES_URI = os.environ["POSTGRES_URI"]

from datetime import datetime, timezone

# Hypertable chunks older than 30 days are compressed (0015); the /status
# freshness probes floor their scan at 30 + 5 days of chunk-boundary slack so
# they skip that history. An unbounded max(time) costs ~700 ms vs ~50 ms floored.
PROBE_FLOOR_DAYS = 35

# Queries shared by the routes and _PRIMER below. Plans are cached per exact
# SQL text, so the primer only helps if it runs these very strings.
AUTH_SQL = "SELECT id, expiry FROM users WHERE token = %s"
STATUS_HR_LATEST_SQL = (
    "SELECT user_id::text AS user_id, time AS latest FROM heart_rate_sample "
    f"WHERE time > now() - interval '{PROBE_FLOOR_DAYS} days' "
    "ORDER BY time DESC LIMIT 1"
)
STATUS_ANY_USER_SQL = "SELECT id::text AS id FROM users ORDER BY id LIMIT 1"
STATUS_PROBES = (("steps", "steps"),
                 ("distance", "distance"),
                 ("totalCaloriesBurned", "total_calories_burned"))
STATUS_LATEST_SQL = {
    table: f"SELECT max(start_at) AS latest FROM {table} "
           f"WHERE user_id = %s AND start_at > now() - interval '{PROBE_FLOOR_DAYS} days'"
    for _, table in STATUS_PROBES
}
STREAK_SQL = ("SELECT DISTINCT (hour AT TIME ZONE %s)::date AS d "
              "FROM heart_rate_hourly WHERE user_id = %s AND hour >= %s")

# Each pooled connection caches its own server-side prepared plans, so when
# /sync bursts grow the pool the status-page /status request can land on a
# connection that's never planned the SELECTs and pay ~90 ms × 4 hypertables.
# Prime every new connection up-front with the queries /status and auth use.
_DUMMY_UUID = "00000000-0000-0000-0000-000000000000"
_PRIMER = (
    (AUTH_SQL, ("dummy",)),
    (STATUS_HR_LATEST_SQL, ()),
    (STATUS_ANY_USER_SQL, ()),
    *((sql, (_DUMMY_UUID,)) for sql in STATUS_LATEST_SQL.values()),
    (STREAK_SQL, ("UTC", _DUMMY_UUID, datetime(1970, 1, 1, tzinfo=timezone.utc))),
)


def _configure_conn(conn):
    conn.prepare_threshold = 0
    # autocommit so each primer leaves the connection in IDLE — otherwise the
    # pool sees INTRANS and discards the connection.
    conn.autocommit = True
    try:
        with conn.cursor() as cur:
            for sql, params in _PRIMER:
                try:
                    cur.execute(sql, params)
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
