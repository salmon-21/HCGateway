-- Continuous aggregates and materialized views.
-- Runs after 0001_init.sql.

-- ---------------------------------------------------------------------------
-- heart_rate_hourly (replaces Mongo heartRateHourly precompute)
--
-- Bucket alignment: JST hour (time_bucket honours the timezone arg).
-- Returns timestamptz `hour` aligned to JST hour boundaries (UTC instant).
-- ---------------------------------------------------------------------------
CREATE MATERIALIZED VIEW heart_rate_hourly
WITH (timescaledb.continuous) AS
SELECT
  user_id,
  time_bucket(INTERVAL '1 hour', time, 'Asia/Tokyo') AS hour,
  AVG(bpm)::double precision AS avg_bpm,
  MIN(bpm)                   AS min_bpm,
  MAX(bpm)                   AS max_bpm,
  COUNT(*)                   AS n
FROM heart_rate_sample
GROUP BY user_id, hour
WITH NO DATA;

-- Real-time aggregate: queries combine materialized data with un-materialized
-- recent rows, so the current hour shows up before the next refresh tick.
ALTER MATERIALIZED VIEW heart_rate_hourly
  SET (timescaledb.materialized_only = false);

-- Refresh policy: re-materialize the last 7 days every 5 minutes,
-- stopping at the boundary 1 hour before now (current incomplete bucket
-- is served by real-time aggregation).
SELECT add_continuous_aggregate_policy('heart_rate_hourly',
  start_offset      => INTERVAL '7 days',
  end_offset        => INTERVAL '1 hour',
  schedule_interval => INTERVAL '5 minutes');

-- ---------------------------------------------------------------------------
-- sleep_rolling_stats (replaces post-process sleepRollingStats)
--
-- One row per (user_id, sleep_day), where sleep_day is JST and bedtime
-- 18:00–23:59 maps to the next calendar day (matches the Python heuristic).
--
-- Window stats: 7-day rolling (3 preceding, current, 3 following) per user.
-- - duration / actual_duration / midpoint use linear stats (AVG, STDDEV).
-- - bedtime / wake use circular mean via atan2 (mod 24).
--   TODO: bedtime/wake upper/lower (circular stddev with unwrap) is not
--   reproduced in SQL — add a PL/pgSQL function if/when Grafana needs bands
--   on those fields. The Python implementation lived in
--   post-process/process.py::_circular_stats.
-- ---------------------------------------------------------------------------
CREATE MATERIALIZED VIEW sleep_rolling_stats AS
WITH per_session AS (
  SELECT
    user_id,
    start_at,
    end_at,
    CASE
      WHEN EXTRACT(HOUR FROM (start_at AT TIME ZONE 'Asia/Tokyo')) >= 18
        THEN ((start_at AT TIME ZONE 'Asia/Tokyo')::date + 1)
      ELSE   (start_at AT TIME ZONE 'Asia/Tokyo')::date
    END AS sleep_day,
    EXTRACT(EPOCH FROM (end_at - start_at)) / 3600.0 AS duration_h,
    COALESCE((
      SELECT SUM(EXTRACT(EPOCH FROM (
        (s->>'endTime')::timestamptz - (s->>'startTime')::timestamptz
      )) / 3600.0)
      FROM jsonb_array_elements(stages) s
      WHERE (s->>'stage')::int NOT IN (1, 3)  -- exclude Awake / OutOfBed
    ), EXTRACT(EPOCH FROM (end_at - start_at)) / 3600.0) AS actual_h,
    EXTRACT(HOUR   FROM (start_at AT TIME ZONE 'Asia/Tokyo'))
    + EXTRACT(MINUTE FROM (start_at AT TIME ZONE 'Asia/Tokyo'))::double precision / 60.0
      AS bedtime_jst,
    EXTRACT(HOUR   FROM (end_at AT TIME ZONE 'Asia/Tokyo'))
    + EXTRACT(MINUTE FROM (end_at AT TIME ZONE 'Asia/Tokyo'))::double precision / 60.0
      AS wake_jst
  FROM sleep_session
),
per_day_longest AS (
  -- For each (user_id, sleep_day), pick bedtime/wake from the LONGEST session.
  -- Naps still contribute their duration via the totals below.
  SELECT DISTINCT ON (user_id, sleep_day)
    user_id, sleep_day, bedtime_jst, wake_jst
  FROM per_session
  ORDER BY user_id, sleep_day, duration_h DESC
),
per_day AS (
  SELECT
    l.user_id,
    l.sleep_day,
    l.bedtime_jst        AS bedtime,
    l.wake_jst           AS wake,
    CASE
      WHEN l.bedtime_jst > l.wake_jst THEN
        CASE
          WHEN (l.bedtime_jst + l.wake_jst + 24) / 2.0 >= 24
            THEN (l.bedtime_jst + l.wake_jst + 24) / 2.0 - 24
          ELSE (l.bedtime_jst + l.wake_jst + 24) / 2.0
        END
      ELSE (l.bedtime_jst + l.wake_jst) / 2.0
    END                  AS midpoint,
    s.duration_total     AS duration,
    s.actual_total       AS actual_duration
  FROM per_day_longest l
  JOIN (
    SELECT user_id, sleep_day,
           SUM(duration_h) AS duration_total,
           SUM(actual_h)   AS actual_total
    FROM per_session
    GROUP BY user_id, sleep_day
  ) s USING (user_id, sleep_day)
)
SELECT
  user_id,
  sleep_day,
  bedtime,
  wake,
  midpoint,
  duration,
  actual_duration,

  -- Linear rolling — duration / actual_duration / midpoint
  AVG(duration)        OVER w AS duration_ma,
  AVG(duration)        OVER w + COALESCE(STDDEV(duration)        OVER w, 0) AS duration_upper,
  AVG(duration)        OVER w - COALESCE(STDDEV(duration)        OVER w, 0) AS duration_lower,
  AVG(actual_duration) OVER w AS actual_duration_ma,
  AVG(actual_duration) OVER w + COALESCE(STDDEV(actual_duration) OVER w, 0) AS actual_duration_upper,
  AVG(actual_duration) OVER w - COALESCE(STDDEV(actual_duration) OVER w, 0) AS actual_duration_lower,
  AVG(midpoint)        OVER w AS midpoint_ma,
  AVG(midpoint)        OVER w + COALESCE(STDDEV(midpoint)        OVER w, 0) AS midpoint_upper,
  AVG(midpoint)        OVER w - COALESCE(STDDEV(midpoint)        OVER w, 0) AS midpoint_lower,

  -- Circular mean for bedtime / wake (atan2 on hour-of-day, then wrap to [0,24)).
  -- atan2 ∈ [-π,π] ⇒ atan2*12/π ∈ [-12,12] ⇒ +24 ∈ [12,36] ⇒ subtract 24 if ≥ 24.
  CASE
    WHEN atan2(
           AVG(SIN(bedtime * PI() / 12.0)) OVER w,
           AVG(COS(bedtime * PI() / 12.0)) OVER w
         ) * 12.0 / PI() + 24.0 >= 24.0
    THEN   atan2(
           AVG(SIN(bedtime * PI() / 12.0)) OVER w,
           AVG(COS(bedtime * PI() / 12.0)) OVER w
         ) * 12.0 / PI()
    ELSE   atan2(
           AVG(SIN(bedtime * PI() / 12.0)) OVER w,
           AVG(COS(bedtime * PI() / 12.0)) OVER w
         ) * 12.0 / PI() + 24.0
  END AS bedtime_ma,
  CASE
    WHEN atan2(
           AVG(SIN(wake * PI() / 12.0)) OVER w,
           AVG(COS(wake * PI() / 12.0)) OVER w
         ) * 12.0 / PI() + 24.0 >= 24.0
    THEN   atan2(
           AVG(SIN(wake * PI() / 12.0)) OVER w,
           AVG(COS(wake * PI() / 12.0)) OVER w
         ) * 12.0 / PI()
    ELSE   atan2(
           AVG(SIN(wake * PI() / 12.0)) OVER w,
           AVG(COS(wake * PI() / 12.0)) OVER w
         ) * 12.0 / PI() + 24.0
  END AS wake_ma
FROM per_day
WINDOW w AS (
  PARTITION BY user_id
  ORDER BY sleep_day
  ROWS BETWEEN 3 PRECEDING AND 3 FOLLOWING
);

-- Required for REFRESH MATERIALIZED VIEW CONCURRENTLY.
CREATE UNIQUE INDEX sleep_rolling_stats_ux
  ON sleep_rolling_stats (user_id, sleep_day);

-- Trigger to keep sleep_rolling_stats in sync after sleep_session writes.
-- sleep_session changes ~1–2 / day, so synchronous refresh inside the
-- INSERT tx is acceptable. CONCURRENTLY avoids blocking SELECTs.
CREATE OR REPLACE FUNCTION refresh_sleep_rolling_stats()
RETURNS trigger AS $$
BEGIN
  REFRESH MATERIALIZED VIEW CONCURRENTLY sleep_rolling_stats;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER sleep_session_refresh_stats
AFTER INSERT OR UPDATE OR DELETE ON sleep_session
FOR EACH STATEMENT
EXECUTE FUNCTION refresh_sleep_rolling_stats();
