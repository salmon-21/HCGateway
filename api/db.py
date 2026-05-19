"""PostgreSQL connection pool and small helpers used by routes.

Sync interface (Flask is sync). Single global pool reused across requests.
"""
import os
from psycopg_pool import ConnectionPool
from psycopg.rows import dict_row

POSTGRES_URI = os.environ["POSTGRES_URI"]

# Pool sized for a single Flask worker with light concurrency. Adjust upward
# if we move behind gunicorn with multiple workers.
#
# `prepare_threshold=0` server-side-prepares every statement on first
# execute. /status hits 4 hypertables, each costing ~90 ms of TimescaleDB
# chunk planning per call; with auto-prepare a connection plans once at
# pool warmup and reuses the plan thereafter (~10 ms per call). This
# eliminates the slow-cache-miss tail we were patching around with TTL
# tuning.
def _configure_conn(conn):
    conn.prepare_threshold = 0


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
