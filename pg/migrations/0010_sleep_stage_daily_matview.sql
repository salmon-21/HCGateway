-- Materialize sleep_stage_daily (was a plain view in 0008/0009).
--
-- The per-night stage computation (cluster + jsonb stage explosion + window
-- functions over ALL history) ran ~1.2 s per query, and five Grafana panels hit it.
-- As a matview, reads drop to ~2 ms (like sleep_rolling_stats). Refreshed by the
-- existing sleep_session trigger, alongside sleep_rolling_stats. The SELECT body is
-- identical to 0009.

DROP VIEW IF EXISTS sleep_stage_daily;

CREATE MATERIALIZED VIEW sleep_stage_daily AS
WITH ordered AS (
  SELECT user_id, start_at, end_at, stages,
    MAX(end_at) OVER (
      PARTITION BY user_id ORDER BY start_at
      ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
    ) AS prev_max_end
  FROM sleep_session
),
flagged AS (
  SELECT *,
    CASE WHEN prev_max_end IS NULL OR start_at - prev_max_end > INTERVAL '60 minutes'
         THEN 1 ELSE 0 END AS is_new_cluster
  FROM ordered
),
clustered AS (
  SELECT *,
    SUM(is_new_cluster) OVER (PARTITION BY user_id ORDER BY start_at) AS cluster_id
  FROM flagged
),
clusters_start AS (
  SELECT user_id, stages,
    MIN(start_at) OVER (PARTITION BY user_id, cluster_id) AS cluster_start
  FROM clustered
),
tagged AS (
  SELECT user_id, stages,
    CASE
      WHEN EXTRACT(HOUR FROM (cluster_start AT TIME ZONE 'Asia/Tokyo')) >= 18
        THEN ((cluster_start AT TIME ZONE 'Asia/Tokyo')::date + 1)
      ELSE   (cluster_start AT TIME ZONE 'Asia/Tokyo')::date
    END AS sleep_day
  FROM clusters_start
),
stage_rows AS (
  SELECT user_id, sleep_day,
    (st->>'stage')::int AS stage,
    EXTRACT(EPOCH FROM ((st->>'endTime')::timestamptz - (st->>'startTime')::timestamptz)) / 60.0 AS minutes
  FROM tagged, jsonb_array_elements(stages) st
),
per_day AS (
  SELECT user_id, sleep_day,
    COALESCE(SUM(minutes) FILTER (WHERE stage = 4), 0)          AS light_min,
    COALESCE(SUM(minutes) FILTER (WHERE stage = 5), 0)          AS deep_min,
    COALESCE(SUM(minutes) FILTER (WHERE stage = 6), 0)          AS rem_min,
    COALESCE(SUM(minutes) FILTER (WHERE stage IN (1, 3, 7)), 0) AS awake_min
  FROM stage_rows
  GROUP BY user_id, sleep_day
),
pct AS (
  SELECT user_id, sleep_day, light_min, deep_min, rem_min, awake_min,
    (light_min + deep_min + rem_min + awake_min) AS total_min,
    100.0 * light_min / NULLIF(light_min + deep_min + rem_min + awake_min, 0) AS light_pct,
    100.0 * deep_min  / NULLIF(light_min + deep_min + rem_min + awake_min, 0) AS deep_pct,
    100.0 * rem_min   / NULLIF(light_min + deep_min + rem_min + awake_min, 0) AS rem_pct,
    100.0 * awake_min / NULLIF(light_min + deep_min + rem_min + awake_min, 0) AS awake_pct
  FROM per_day
)
SELECT
  user_id, sleep_day,
  light_min, deep_min, rem_min, awake_min, total_min,
  light_pct, deep_pct, rem_pct, awake_pct,
  AVG(light_pct) OVER w AS light_pct_ma,
  AVG(deep_pct)  OVER w AS deep_pct_ma,
  AVG(rem_pct)   OVER w AS rem_pct_ma,
  AVG(awake_pct) OVER w AS awake_pct_ma,
  AVG(light_pct) OVER w + COALESCE(STDDEV(light_pct) OVER w, 0) AS light_pct_upper,
  AVG(light_pct) OVER w - COALESCE(STDDEV(light_pct) OVER w, 0) AS light_pct_lower,
  AVG(deep_pct)  OVER w + COALESCE(STDDEV(deep_pct)  OVER w, 0) AS deep_pct_upper,
  AVG(deep_pct)  OVER w - COALESCE(STDDEV(deep_pct)  OVER w, 0) AS deep_pct_lower,
  AVG(rem_pct)   OVER w + COALESCE(STDDEV(rem_pct)   OVER w, 0) AS rem_pct_upper,
  AVG(rem_pct)   OVER w - COALESCE(STDDEV(rem_pct)   OVER w, 0) AS rem_pct_lower,
  AVG(awake_pct) OVER w + COALESCE(STDDEV(awake_pct) OVER w, 0) AS awake_pct_upper,
  AVG(awake_pct) OVER w - COALESCE(STDDEV(awake_pct) OVER w, 0) AS awake_pct_lower
FROM pct
WINDOW w AS (PARTITION BY user_id ORDER BY sleep_day ROWS BETWEEN 3 PRECEDING AND 3 FOLLOWING);

-- Required for REFRESH ... CONCURRENTLY (and matches the one-row-per-day grain).
CREATE UNIQUE INDEX sleep_stage_daily_ux ON sleep_stage_daily (user_id, sleep_day);

-- A matview is NOT covered by `GRANT SELECT ON ALL TABLES`, so the Grafana
-- read-only role needs an explicit grant. Conditional so a fresh-DB init (where
-- grafana_ro is created manually afterwards — see docs/grafana-datasource-role.md)
-- does not fail.
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grafana_ro') THEN
    GRANT SELECT ON sleep_stage_daily TO grafana_ro;
  END IF;
END $$;

-- Refresh both sleep matviews after sleep_session writes (the trigger
-- sleep_session_refresh_stats already calls this function).
CREATE OR REPLACE FUNCTION refresh_sleep_rolling_stats()
RETURNS trigger AS $$
BEGIN
  REFRESH MATERIALIZED VIEW CONCURRENTLY sleep_rolling_stats;
  REFRESH MATERIALIZED VIEW CONCURRENTLY sleep_stage_daily;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;
