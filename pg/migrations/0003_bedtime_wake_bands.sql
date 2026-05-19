-- Circular mean and stddev for hour-of-day values. Used to give Bedtime
-- and Wake panels Upper/Lower bands on a 24-hour cycle (e.g. bedtimes
-- 23.5 and 0.5 are 1 h apart, not 23 h).
CREATE OR REPLACE FUNCTION circular_stats(vals double precision[])
RETURNS TABLE(mean double precision,
              upper double precision,
              lower double precision) AS $$
DECLARE
  sin_m double precision;
  cos_m double precision;
  m double precision;
  sd double precision;
BEGIN
  IF vals IS NULL OR array_length(vals, 1) IS NULL THEN
    RETURN;
  END IF;

  SELECT avg(sin(x * pi() / 12.0)), avg(cos(x * pi() / 12.0))
    INTO sin_m, cos_m
    FROM unnest(vals) AS t(x);

  m := atan2(sin_m, cos_m) * 12.0 / pi();
  IF m < 0 THEN
    m := m + 24.0;
  END IF;

  IF array_length(vals, 1) < 2 THEN
    mean := m; upper := m; lower := m;
    RETURN NEXT;
    RETURN;
  END IF;

  -- Shift each value into [m-12, m+12] in one arithmetic step before stddev.
  SELECT stddev(v - 24.0 * floor(((v - m) + 12.0) / 24.0))
    INTO sd FROM unnest(vals) AS t(v);

  mean := m;
  upper := m + COALESCE(sd, 0);
  lower := m - COALESCE(sd, 0);
  RETURN NEXT;
END;
$$ LANGUAGE plpgsql IMMUTABLE;


-- Matview columns can't be added in-place, so drop and recreate.
DROP TRIGGER IF EXISTS sleep_session_refresh_stats ON sleep_session;
DROP FUNCTION IF EXISTS refresh_sleep_rolling_stats();
DROP MATERIALIZED VIEW IF EXISTS sleep_rolling_stats;

CREATE MATERIALIZED VIEW sleep_rolling_stats AS
WITH per_session AS (
  SELECT
    user_id, start_at, end_at,
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
      WHERE (s->>'stage')::int NOT IN (1, 3)
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
  SELECT DISTINCT ON (user_id, sleep_day)
    user_id, sleep_day, bedtime_jst, wake_jst
  FROM per_session
  ORDER BY user_id, sleep_day, duration_h DESC
),
per_day AS (
  SELECT
    l.user_id, l.sleep_day,
    l.bedtime_jst AS bedtime,
    l.wake_jst    AS wake,
    CASE
      WHEN l.bedtime_jst > l.wake_jst THEN
        CASE
          WHEN (l.bedtime_jst + l.wake_jst + 24) / 2.0 >= 24
            THEN (l.bedtime_jst + l.wake_jst + 24) / 2.0 - 24
          ELSE (l.bedtime_jst + l.wake_jst + 24) / 2.0
        END
      ELSE (l.bedtime_jst + l.wake_jst) / 2.0
    END AS midpoint,
    s.duration_total AS duration,
    s.actual_total   AS actual_duration
  FROM per_day_longest l
  JOIN (
    SELECT user_id, sleep_day,
           SUM(duration_h) AS duration_total,
           SUM(actual_h)   AS actual_total
    FROM per_session
    GROUP BY user_id, sleep_day
  ) s USING (user_id, sleep_day)
),
windowed AS (
  SELECT
    user_id, sleep_day,
    bedtime, wake, midpoint, duration, actual_duration,
    AVG(duration)        OVER w AS duration_ma,
    AVG(duration)        OVER w + COALESCE(STDDEV(duration)        OVER w, 0) AS duration_upper,
    AVG(duration)        OVER w - COALESCE(STDDEV(duration)        OVER w, 0) AS duration_lower,
    AVG(actual_duration) OVER w AS actual_duration_ma,
    AVG(actual_duration) OVER w + COALESCE(STDDEV(actual_duration) OVER w, 0) AS actual_duration_upper,
    AVG(actual_duration) OVER w - COALESCE(STDDEV(actual_duration) OVER w, 0) AS actual_duration_lower,
    AVG(midpoint)        OVER w AS midpoint_ma,
    AVG(midpoint)        OVER w + COALESCE(STDDEV(midpoint)        OVER w, 0) AS midpoint_upper,
    AVG(midpoint)        OVER w - COALESCE(STDDEV(midpoint)        OVER w, 0) AS midpoint_lower,
    array_agg(bedtime) OVER w AS bedtime_window,
    array_agg(wake)    OVER w AS wake_window
  FROM per_day
  WINDOW w AS (
    PARTITION BY user_id
    ORDER BY sleep_day
    ROWS BETWEEN 3 PRECEDING AND 3 FOLLOWING
  )
)
SELECT
  user_id, sleep_day,
  bedtime, wake, midpoint, duration, actual_duration,
  duration_ma, duration_upper, duration_lower,
  actual_duration_ma, actual_duration_upper, actual_duration_lower,
  midpoint_ma, midpoint_upper, midpoint_lower,
  cs_b.mean  AS bedtime_ma,
  cs_b.upper AS bedtime_upper,
  cs_b.lower AS bedtime_lower,
  cs_w.mean  AS wake_ma,
  cs_w.upper AS wake_upper,
  cs_w.lower AS wake_lower
FROM windowed
CROSS JOIN LATERAL circular_stats(bedtime_window) cs_b
CROSS JOIN LATERAL circular_stats(wake_window)    cs_w;

CREATE UNIQUE INDEX sleep_rolling_stats_ux
  ON sleep_rolling_stats (user_id, sleep_day);


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

REFRESH MATERIALIZED VIEW sleep_rolling_stats;
