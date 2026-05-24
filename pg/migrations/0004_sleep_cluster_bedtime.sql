-- Rework sleep_rolling_stats so bedtime/wake/midpoint come from the longest
-- *cluster* of sessions (sessions ≤60 min apart merged), not the longest single
-- session. Fixes split-night bedtime (e.g. 00:30+02:30 → bedtime 00:30, not 02:30).
--
-- duration / actual_duration stay a blind day-sum of ALL sessions in the sleep_day
-- (naps included) — only the *timing* (bedtime/wake) uses clustering.
--
-- Also adds the sleep_hypnogram view so the Grafana "Sleep Stages" panel reads a
-- named view instead of inlining the stage-explosion + (-6h) night-label SQL.
--
-- Design rationale: ../docs/sleep-processing.md
-- Supersedes the sleep_rolling_stats definition from 0003_bedtime_wake_bands.sql.
-- circular_stats() from 0003 is reused unchanged.

-- ---------------------------------------------------------------------------
-- sleep_rolling_stats (rebuilt: cluster-based bedtime/wake)
-- ---------------------------------------------------------------------------
DROP TRIGGER IF EXISTS sleep_session_refresh_stats ON sleep_session;
DROP FUNCTION IF EXISTS refresh_sleep_rolling_stats();
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

-- ---------------------------------------------------------------------------
-- sleep_hypnogram (named view for the Grafana "Sleep Stages" panel)
--
-- night_date uses the (-6h) bedtime-evening convention — intentionally DIFFERENT
-- from sleep_rolling_stats.sleep_day (18:00 wake-day cutoff). A 00:30 bedtime is
-- labelled the previous evening's date here, the wake morning in the matview.
-- The NULL-stage row at each session's end_at breaks the hypnogram line between
-- sessions/nights.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW sleep_hypnogram AS
SELECT
  (stage_item->>'startTime')::timestamptz AS time,
  ss.user_id,
  to_char(((ss.start_at - INTERVAL '6 hours') AT TIME ZONE 'Asia/Tokyo'), 'MM/DD') AS night_date,
  CASE (stage_item->>'stage')::int
    WHEN 1 THEN 'Awake'
    WHEN 4 THEN 'Light'
    WHEN 5 THEN 'Deep'
    WHEN 6 THEN 'REM'
    ELSE 'Unknown'
  END AS stage
FROM sleep_session ss, jsonb_array_elements(ss.stages) stage_item
UNION ALL
SELECT
  end_at AS time,
  user_id,
  to_char(((start_at - INTERVAL '6 hours') AT TIME ZONE 'Asia/Tokyo'), 'MM/DD') AS night_date,
  NULL::text AS stage
FROM sleep_session;
