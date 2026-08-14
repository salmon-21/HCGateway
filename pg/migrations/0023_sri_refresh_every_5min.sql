-- 0023_sri_refresh_every_5min.sql
-- Move sleep_regularity from the hourly job onto the same 5-minute cadence as
-- sleep_rolling_stats, and make the two refreshes strictly ordered.
--
-- ---------------------------------------------------------------------------
-- Why the hourly job was not actually buying anything
-- ---------------------------------------------------------------------------
-- 0019 gave SRI its own hourly job because a full recompute is ~10 s and that
-- looked too heavy for 0016's 5-minute tick. That reasoning conflated cost per
-- run with cost per day: the procedure refreshes only when the dirty counter
-- moved, so the number of refreshes is set by how often sleep data ARRIVES, not
-- by how often the job ticks. Measured on this deployment:
--
--   REFRESH MATERIALIZED VIEW CONCURRENTLY sleep_regularity   10.2 s / 10.0 s
--   sleepSession sync events, last 7 days                     5
--     (08-10 17:01, 08-10 22:32, 08-11 22:35, 08-13 00:08, 08-14 00:08)
--   dirty-counter bumps, 08-08 -> 08-14                       16 (~3.2/day)
--   actual SRI refreshes in the last 24 h                     1
--
-- The syncs land ~24 h apart, so the hourly window coalesces almost nothing.
-- Ticking every 5 min instead runs the same handful of refreshes EARLIER, not
-- more often; the added load is bounded by a couple of runs a day (~20 s of
-- CPU) and every non-dirty tick costs the same 0.03 s it already does.
--
-- Worst case lag drops from 1 h 5 min to 10 min (see the two-tick note below).
--
-- ---------------------------------------------------------------------------
-- Why the gate changes from `changes` to `refreshed_changes`
-- ---------------------------------------------------------------------------
-- drift_min_per_day (0021) is the one column in sleep_regularity that reads
-- sleep_rolling_stats rather than sleep_session. Both jobs watch the SAME
-- counter, so once they share a 5-minute schedule every dirty event wakes both
-- at once -- and REFRESH ... CONCURRENTLY keeps the pre-refresh snapshot
-- visible to readers until it commits. A simultaneous SRI run would therefore
-- compute drift from the OLD midpoints, leaving it permanently one sync behind
-- instead of an hour behind. Strictly worse than what it replaces.
--
-- Gating on refreshed_changes -- the watermark 0016 stamps AFTER its own
-- refresh commits -- makes the ordering structural: sleep_regularity can only
-- become dirty once sleep_rolling_stats is already up to date, so the two never
-- contend and drift never reads a stale midpoint. The cost is that SRI lands on
-- the tick after the one that refreshed the rolling stats: two ticks, <= 10 min.
--
-- This also closes a rare pre-existing race. The hourly job fires at :46:55 and
-- a 5-min tick at :47:13, so a sync landing just before :46:55 already made SRI
-- run first and read stale midpoints -- roughly once per 720 ticks, which is why
-- it was never visible.
--
-- ---------------------------------------------------------------------------
-- REJECTED: folding the refresh into sleep_stats_refresh_if_dirty
-- ---------------------------------------------------------------------------
-- One job, sequential by construction, and a 5-minute (not 10-minute) worst
-- case. It couples the failures: the watermark is stamped after all refreshes,
-- so an SRI failure would leave refreshed_changes unstamped and the 5-min job
-- would redo the ~9 s rolling-stats refresh every tick and fail again -- freezing
-- Bedtime/Wake/Duration on the dashboard because the expensive, more fragile
-- view broke. 0016 split refreshes out of the write path precisely so one
-- surface's maintenance cannot take another down; merging walks that back.
--
-- Design rationale: ../docs/sleep-processing.md

CREATE OR REPLACE PROCEDURE sleep_regularity_refresh_if_dirty(job_id int, config jsonb)
LANGUAGE plpgsql AS $$
DECLARE
    c bigint;
BEGIN
    -- refreshed_changes, NOT changes: 0016 stamps it only after its own refresh
    -- has committed, so gating on it guarantees sleep_rolling_stats is current
    -- before drift_min_per_day reads its midpoints. See the header.
    SELECT refreshed_changes INTO c FROM sleep_stats_refresh_state
    WHERE refreshed_changes > sri_refreshed_changes;
    IF c IS NULL THEN
        RETURN;
    END IF;
    REFRESH MATERIALIZED VIEW CONCURRENTLY sleep_regularity;
    -- Stamp the pre-refresh value, as 0016/0019 do: anything landing mid-refresh
    -- stays > sri_refreshed_changes and triggers the next run.
    UPDATE sleep_stats_refresh_state
    SET sri_refreshed_changes = c, sri_last_refreshed = now();
END $$;

-- Look the job up by proc_name rather than hardcoding the id add_job() handed
-- out on this deployment.
DO $$
DECLARE
    j int;
BEGIN
    SELECT job_id INTO j FROM timescaledb_information.jobs
    WHERE proc_name = 'sleep_regularity_refresh_if_dirty';
    IF j IS NOT NULL THEN
        PERFORM alter_job(j, schedule_interval => INTERVAL '5 minutes');
    END IF;
END $$;
