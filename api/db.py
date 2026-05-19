"""PostgreSQL connection pool and small helpers used by routes.

Sync interface (Flask is sync). Single global pool reused across requests.
"""
import os
from psycopg_pool import ConnectionPool
from psycopg.rows import dict_row

POSTGRES_URI = os.environ["POSTGRES_URI"]

# Pool sized for a single Flask worker with light concurrency. Adjust upward
# if we move behind gunicorn with multiple workers.
pool = ConnectionPool(
    POSTGRES_URI,
    min_size=1,
    max_size=10,
    open=True,
    timeout=10,
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
