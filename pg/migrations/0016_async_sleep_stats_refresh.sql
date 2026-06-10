-- 0016_async_sleep_stats_refresh.sql
-- Move the sleep matview refreshes out of the /sync transaction.
--
-- The 0010-era trigger ran REFRESH MATERIALIZED VIEW CONCURRENTLY twice
-- (sleep_rolling_stats + sleep_stage_daily, ~5 s combined on the RPi) inside
-- the INSERT transaction of /sync/sleepSession. Two problems:
--   1. Nearly every delta sync carries a sleep record, so every sync paid ~5 s
--      to fully recompute both views for a one-row change.
--   2. A refresh failure aborts the INSERT → /sync 500s → the client counts
--      the type as failed and freezes its Changes token (the same failure
--      class that froze heartRate when 0015's DELETE blew the decompression
--      limit). Dashboard maintenance must not gate ingest.
--
-- New shape: the trigger only bumps a change counter (<1 ms); a TimescaleDB
-- background job runs every 5 min and refreshes ONLY when the counter moved.
-- Change-driven semantics are preserved, just deferred ≤5 min — noise next to
-- Samsung Health's ~1 h batch latency into Health Connect.

DROP TRIGGER IF EXISTS sleep_session_refresh_stats ON sleep_session;
DROP FUNCTION IF EXISTS refresh_sleep_rolling_stats();

CREATE TABLE IF NOT EXISTS sleep_stats_refresh_state (
    single_row        boolean PRIMARY KEY DEFAULT true CHECK (single_row),
    changes           bigint NOT NULL DEFAULT 0,
    refreshed_changes bigint NOT NULL DEFAULT 0,
    last_refreshed    timestamptz
);
INSERT INTO sleep_stats_refresh_state (single_row) VALUES (true)
ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION sleep_stats_mark_dirty() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE sleep_stats_refresh_state SET changes = changes + 1;
    RETURN NULL;
END $$;

CREATE TRIGGER sleep_session_mark_stats_dirty
AFTER INSERT OR UPDATE OR DELETE ON sleep_session
FOR EACH STATEMENT EXECUTE FUNCTION sleep_stats_mark_dirty();

CREATE OR REPLACE PROCEDURE sleep_stats_refresh_if_dirty(job_id int, config jsonb)
LANGUAGE plpgsql AS $$
DECLARE
    c bigint;
BEGIN
    SELECT changes INTO c FROM sleep_stats_refresh_state
    WHERE changes > refreshed_changes;
    IF c IS NULL THEN
        RETURN;
    END IF;
    REFRESH MATERIALIZED VIEW CONCURRENTLY sleep_rolling_stats;
    REFRESH MATERIALIZED VIEW CONCURRENTLY sleep_stage_daily;
    -- Stamp the counter value read BEFORE the refresh: changes that land
    -- mid-refresh stay > refreshed_changes and trigger the next run.
    UPDATE sleep_stats_refresh_state
    SET refreshed_changes = c, last_refreshed = now();
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM timescaledb_information.jobs
                   WHERE proc_name = 'sleep_stats_refresh_if_dirty') THEN
        PERFORM add_job('sleep_stats_refresh_if_dirty', INTERVAL '5 minutes');
    END IF;
END $$;
