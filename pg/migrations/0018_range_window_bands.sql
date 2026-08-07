-- 0018_range_window_bands.sql
-- Make the "7-day" MA/bands a true calendar window, and suppress bands on
-- tiny samples. Applies to BOTH sleep matviews.
--
-- Problems with the 0004/0010-era ROWS BETWEEN 3 PRECEDING AND 3 FOLLOWING:
--   1. ROWS counts observations, not days. The history has 472 missing days
--      across 1331 (incl. four tracking gaps of 38/93/143/150 days), so the
--      "7-day MA" mixed nights up to 5 months apart across a gap.
--   2. With RANGE the frame shrinks at gap edges, so windows of 1-2 points
--      become common there — and STDDEV(1 point) is NULL, which the old
--      COALESCE(…, 0) turned into a zero-width band (false certainty).
--
-- Changes:
--   * WINDOW: ROWS → RANGE BETWEEN INTERVAL '3 days' PRECEDING AND INTERVAL
--     '3 days' FOLLOWING — a true current-day ±3 calendar window. (date ORDER
--     BY requires interval offsets; integer offsets are not supported.)
--   * upper/lower (linear AND circular): NULL when the frame has <3 points.
--     Grafana simply skips NULL points. The MA itself stays at any n — a 1-2
--     point average is still informative, just not band-worthy.
--
-- Known, accepted asymmetries (documented in ../docs/sleep-processing.md):
--   * linear bands use sample SD (n-1), circular_stats uses the population-
--     style sqrt(-2·ln R) — no standard small-sample correction exists for
--     the circular SD; the n>=3 gate bounds the discrepancy where it matters.
--   * The window is centered, so the newest 3 days' MA revises as data lands.
--
-- Column names/sets are unchanged; Grafana queries need no edits.

-- ---------------------------------------------------------------------------
-- sleep_rolling_stats (0017 definition; only `windowed` and the final SELECT
-- change: RANGE frame, n_w gate, SD split out of upper/lower)
-- ---------------------------------------------------------------------------
DROP MATERIALIZED VIEW IF EXISTS sleep_rolling_stats;

CREATE MATERIALIZED VIEW sleep_rolling_stats AS
WITH ordered AS (
  -- running max end of all *earlier* sessions, per user (mirrors the MCP's
  -- episodes[-1]["end"] = max(...) so overlapping/out-of-order ends still cluster).
  SELECT
    user_id, start_at, end_at, stages,
    MAX(end_at) OVER (
      PARTITION BY user_id ORDER BY start_at
      ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
    ) AS prev_max_end
  FROM sleep_session
),
flagged AS (
  SELECT *,
    CASE
      WHEN prev_max_end IS NULL OR start_at - prev_max_end > INTERVAL '60 minutes'
      THEN 1 ELSE 0
    END AS is_new_cluster
  FROM ordered
),
clustered AS (
  -- gaps-and-islands: cumulative sum of new-cluster flags = cluster id
  SELECT *,
    SUM(is_new_cluster) OVER (PARTITION BY user_id ORDER BY start_at) AS cluster_id
  FROM flagged
),
clusters AS (
  SELECT
    user_id, cluster_id,
    MIN(start_at) AS cluster_start,
    MAX(end_at)   AS cluster_end
  FROM clustered
  GROUP BY user_id, cluster_id
),
cluster_day AS (
  -- assign each cluster a JST sleep_day from its start (18:00 cutoff → wake-day).
  -- This is the single sleep_day key used for BOTH the duration sum and bedtime.
  SELECT
    user_id, cluster_id, cluster_start, cluster_end,
    CASE
      WHEN EXTRACT(HOUR FROM (cluster_start AT TIME ZONE 'Asia/Tokyo')) >= 18
        THEN ((cluster_start AT TIME ZONE 'Asia/Tokyo')::date + 1)
      ELSE   (cluster_start AT TIME ZONE 'Asia/Tokyo')::date
    END AS sleep_day
  FROM clusters
),
per_session AS (
  -- each session's duration/actual, tagged with its cluster's sleep_day
  SELECT
    c.user_id,
    cd.sleep_day,
    EXTRACT(EPOCH FROM (c.end_at - c.start_at)) / 3600.0 AS duration_h,
    COALESCE((
      SELECT SUM(EXTRACT(EPOCH FROM (
        (s->>'endTime')::timestamptz - (s->>'startTime')::timestamptz
      )) / 3600.0)
      FROM jsonb_array_elements(c.stages) s
      WHERE (s->>'stage')::int NOT IN (1, 3)  -- exclude Awake / OutOfBed
    ), EXTRACT(EPOCH FROM (c.end_at - c.start_at)) / 3600.0) AS actual_h
  FROM clustered c
  JOIN cluster_day cd USING (user_id, cluster_id)
),
day_totals AS (
  SELECT user_id, sleep_day,
         SUM(duration_h) AS duration_total,
         SUM(actual_h)   AS actual_total
  FROM per_session
  GROUP BY user_id, sleep_day
),
day_main AS (
  -- bedtime/wake from the LONGEST cluster (by span) per sleep_day
  SELECT DISTINCT ON (user_id, sleep_day)
    user_id, sleep_day,
    EXTRACT(HOUR   FROM (cluster_start AT TIME ZONE 'Asia/Tokyo'))
    + EXTRACT(MINUTE FROM (cluster_start AT TIME ZONE 'Asia/Tokyo'))::double precision / 60.0
      AS bedtime_jst,
    EXTRACT(HOUR   FROM (cluster_end AT TIME ZONE 'Asia/Tokyo'))
    + EXTRACT(MINUTE FROM (cluster_end AT TIME ZONE 'Asia/Tokyo'))::double precision / 60.0
      AS wake_jst
  FROM cluster_day
  ORDER BY user_id, sleep_day, (cluster_end - cluster_start) DESC
),
per_day AS (
  SELECT
    m.user_id, m.sleep_day,
    m.bedtime_jst AS bedtime,
    m.wake_jst    AS wake,
    CASE
      WHEN m.bedtime_jst > m.wake_jst THEN
        CASE
          WHEN (m.bedtime_jst + m.wake_jst + 24) / 2.0 >= 24
            THEN (m.bedtime_jst + m.wake_jst + 24) / 2.0 - 24
          ELSE (m.bedtime_jst + m.wake_jst + 24) / 2.0
        END
      ELSE (m.bedtime_jst + m.wake_jst) / 2.0
    END AS midpoint,
    t.duration_total AS duration,
    t.actual_total   AS actual_duration
  FROM day_main m
  JOIN day_totals t USING (user_id, sleep_day)
),
windowed AS (
  SELECT
    user_id, sleep_day,
    bedtime, wake, midpoint, duration, actual_duration,
    COUNT(*)             OVER w AS n_w,
    AVG(duration)        OVER w AS duration_ma,
    STDDEV(duration)     OVER w AS duration_sd,
    AVG(actual_duration) OVER w AS actual_duration_ma,
    STDDEV(actual_duration) OVER w AS actual_duration_sd,
    array_agg(bedtime)  OVER w AS bedtime_window,
    array_agg(wake)     OVER w AS wake_window,
    array_agg(midpoint) OVER w AS midpoint_window
  FROM per_day
  WINDOW w AS (
    PARTITION BY user_id
    ORDER BY sleep_day
    RANGE BETWEEN INTERVAL '3 days' PRECEDING AND INTERVAL '3 days' FOLLOWING
  )
)
SELECT
  user_id, sleep_day,
  bedtime, wake, midpoint, duration, actual_duration,
  duration_ma,
  CASE WHEN n_w >= 3 THEN duration_ma + duration_sd END AS duration_upper,
  CASE WHEN n_w >= 3 THEN duration_ma - duration_sd END AS duration_lower,
  actual_duration_ma,
  CASE WHEN n_w >= 3 THEN actual_duration_ma + actual_duration_sd END AS actual_duration_upper,
  CASE WHEN n_w >= 3 THEN actual_duration_ma - actual_duration_sd END AS actual_duration_lower,
  cs_m.mean AS midpoint_ma,
  CASE WHEN n_w >= 3 THEN cs_m.upper END AS midpoint_upper,
  CASE WHEN n_w >= 3 THEN cs_m.lower END AS midpoint_lower,
  cs_b.mean AS bedtime_ma,
  CASE WHEN n_w >= 3 THEN cs_b.upper END AS bedtime_upper,
  CASE WHEN n_w >= 3 THEN cs_b.lower END AS bedtime_lower,
  cs_w.mean AS wake_ma,
  CASE WHEN n_w >= 3 THEN cs_w.upper END AS wake_upper,
  CASE WHEN n_w >= 3 THEN cs_w.lower END AS wake_lower
FROM windowed
CROSS JOIN LATERAL circular_stats(bedtime_window)  cs_b
CROSS JOIN LATERAL circular_stats(wake_window)     cs_w
CROSS JOIN LATERAL circular_stats(midpoint_window) cs_m;

-- Required for REFRESH ... CONCURRENTLY (0016's job) and the per-day grain.
CREATE UNIQUE INDEX sleep_rolling_stats_ux
  ON sleep_rolling_stats (user_id, sleep_day);

-- ---------------------------------------------------------------------------
-- sleep_stage_daily (0010 definition; same RANGE frame + n_w gate)
-- ---------------------------------------------------------------------------
DROP MATERIALIZED VIEW IF EXISTS sleep_stage_daily;

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
),
windowed AS (
  SELECT user_id, sleep_day, light_min, deep_min, rem_min, awake_min, total_min,
    light_pct, deep_pct, rem_pct, awake_pct,
    COUNT(*)          OVER w AS n_w,
    AVG(light_pct)    OVER w AS light_pct_ma,
    STDDEV(light_pct) OVER w AS light_pct_sd,
    AVG(deep_pct)     OVER w AS deep_pct_ma,
    STDDEV(deep_pct)  OVER w AS deep_pct_sd,
    AVG(rem_pct)      OVER w AS rem_pct_ma,
    STDDEV(rem_pct)   OVER w AS rem_pct_sd,
    AVG(awake_pct)    OVER w AS awake_pct_ma,
    STDDEV(awake_pct) OVER w AS awake_pct_sd
  FROM pct
  WINDOW w AS (
    PARTITION BY user_id
    ORDER BY sleep_day
    RANGE BETWEEN INTERVAL '3 days' PRECEDING AND INTERVAL '3 days' FOLLOWING
  )
)
SELECT
  user_id, sleep_day,
  light_min, deep_min, rem_min, awake_min, total_min,
  light_pct, deep_pct, rem_pct, awake_pct,
  light_pct_ma, deep_pct_ma, rem_pct_ma, awake_pct_ma,
  CASE WHEN n_w >= 3 THEN light_pct_ma + light_pct_sd END AS light_pct_upper,
  CASE WHEN n_w >= 3 THEN light_pct_ma - light_pct_sd END AS light_pct_lower,
  CASE WHEN n_w >= 3 THEN deep_pct_ma  + deep_pct_sd  END AS deep_pct_upper,
  CASE WHEN n_w >= 3 THEN deep_pct_ma  - deep_pct_sd  END AS deep_pct_lower,
  CASE WHEN n_w >= 3 THEN rem_pct_ma   + rem_pct_sd   END AS rem_pct_upper,
  CASE WHEN n_w >= 3 THEN rem_pct_ma   - rem_pct_sd   END AS rem_pct_lower,
  CASE WHEN n_w >= 3 THEN awake_pct_ma + awake_pct_sd END AS awake_pct_upper,
  CASE WHEN n_w >= 3 THEN awake_pct_ma - awake_pct_sd END AS awake_pct_lower
FROM windowed;

-- Required for REFRESH ... CONCURRENTLY (and matches the one-row-per-day grain).
CREATE UNIQUE INDEX sleep_stage_daily_ux ON sleep_stage_daily (user_id, sleep_day);

-- DROP loses the matviews' explicit grants (docs/grafana-datasource-role.md);
-- conditional so a fresh-DB init without the role does not fail (as in 0010/0017).
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grafana_ro') THEN
    GRANT SELECT ON sleep_rolling_stats TO grafana_ro;
    GRANT SELECT ON sleep_stage_daily   TO grafana_ro;
  END IF;
END $$;
