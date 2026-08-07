-- 0017_midpoint_circular.sql
-- Apply circular statistics to the midpoint MA/bands, like bedtime/wake.
--
-- 0007 switched the bedtime/wake bands to circular_stats() but left midpoint
-- on linear AVG ± STDDEV, assuming midpoints cluster ~2-3 AM, far from the
-- midnight wrap. The data says otherwise: of 859 nights, 123 (14%) have a
-- midpoint before midnight, so 7-day windows mixing e.g. 23.8 and 2.5 average
-- to ~13:00 — 279 nights (32%) had midpoint_ma in the impossible 06-18 range
-- and 186 had bands wider than 12 h.
--
-- Only the midpoint_ma/upper/lower columns change (now from circular_stats,
-- sharing its 12 h SD cap); the per-day midpoint value was already
-- wrap-corrected and is unchanged, as are all other columns. Grafana queries
-- select by column name and need no edits.
--
-- The SELECT body is otherwise identical to 0004. No trigger work here: 0016's
-- mark-dirty trigger lives on sleep_session and survives the matview rebuild.
-- Design rationale: ../docs/sleep-processing.md

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
    AVG(duration)        OVER w AS duration_ma,
    AVG(duration)        OVER w + COALESCE(STDDEV(duration)        OVER w, 0) AS duration_upper,
    AVG(duration)        OVER w - COALESCE(STDDEV(duration)        OVER w, 0) AS duration_lower,
    AVG(actual_duration) OVER w AS actual_duration_ma,
    AVG(actual_duration) OVER w + COALESCE(STDDEV(actual_duration) OVER w, 0) AS actual_duration_upper,
    AVG(actual_duration) OVER w - COALESCE(STDDEV(actual_duration) OVER w, 0) AS actual_duration_lower,
    array_agg(bedtime)  OVER w AS bedtime_window,
    array_agg(wake)     OVER w AS wake_window,
    array_agg(midpoint) OVER w AS midpoint_window
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
  cs_m.mean  AS midpoint_ma,
  cs_m.upper AS midpoint_upper,
  cs_m.lower AS midpoint_lower,
  cs_b.mean  AS bedtime_ma,
  cs_b.upper AS bedtime_upper,
  cs_b.lower AS bedtime_lower,
  cs_w.mean  AS wake_ma,
  cs_w.upper AS wake_upper,
  cs_w.lower AS wake_lower
FROM windowed
CROSS JOIN LATERAL circular_stats(bedtime_window)  cs_b
CROSS JOIN LATERAL circular_stats(wake_window)     cs_w
CROSS JOIN LATERAL circular_stats(midpoint_window) cs_m;

-- Required for REFRESH ... CONCURRENTLY (0016's job) and the per-day grain.
CREATE UNIQUE INDEX sleep_rolling_stats_ux
  ON sleep_rolling_stats (user_id, sleep_day);

-- DROP loses the matview's explicit grant (docs/grafana-datasource-role.md);
-- conditional so a fresh-DB init without the role does not fail (as in 0010).
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grafana_ro') THEN
    GRANT SELECT ON sleep_rolling_stats TO grafana_ro;
  END IF;
END $$;
