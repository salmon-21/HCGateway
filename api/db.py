"""PostgreSQL connection pool and small helpers used by routes.

Sync interface (Flask is sync). Single global pool reused across requests.
"""
import os
from psycopg_pool import ConnectionPool
from psycopg.rows import dict_row

POSTGRES_URI = os.environ["POSTGRES_URI"]

from datetime import datetime, timezone

# Each pooled connection caches its own server-side prepared plans, so when
# /sync bursts grow the pool the moromiso /status request can land on a
# connection that's never planned the SELECTs and pay ~90 ms × 4 hypertables.
# Prime every new connection up-front with the queries /status and auth use.
_DUMMY_UUID = "00000000-0000-0000-0000-000000000000"
_PRIMER = (
    ("SELECT id, expiry FROM users WHERE token = %s", ("dummy",)),
    ("SELECT id::text AS id FROM users WHERE username = %s", ("dummy",)),
    ("SELECT id::text FROM users ORDER BY id LIMIT 1", ()),
    ("SELECT user_id::text AS user_id, time AS latest FROM heart_rate_sample "
     "WHERE time = (SELECT max(time) FROM heart_rate_sample) LIMIT 1", ()),
    ("SELECT max(start_at) AS latest FROM steps WHERE user_id = %s", (_DUMMY_UUID,)),
    ("SELECT max(start_at) AS latest FROM distance WHERE user_id = %s", (_DUMMY_UUID,)),
    ("SELECT max(start_at) AS latest FROM total_calories_burned WHERE user_id = %s", (_DUMMY_UUID,)),
    ("SELECT DISTINCT (hour AT TIME ZONE %s)::date AS d FROM heart_rate_hourly "
     "WHERE user_id = %s AND hour >= %s",
     ("UTC", _DUMMY_UUID, datetime(1970, 1, 1, tzinfo=timezone.utc))),
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
