-- 0019_sleep_period_threshold_and_sri.sql
-- Widen the sleep-period gap threshold 60 → 120 min, expose how representative
-- each day's bedtime/wake actually is (main_share), and add the Sleep
-- Regularity Index as a first-class metric.
--
-- ---------------------------------------------------------------------------
-- Why the threshold moves (measured on 883 sleep_days, 2022-06 → 2026-08)
-- ---------------------------------------------------------------------------
-- The clinical mid-sleep point is the midpoint of the *sleep period* (sleep
-- onset → final awakening), which by definition keeps WASO inside the period.
-- "How long a wake bout terminates the period" is therefore a free parameter of
-- the definition itself, not a deviation from it — 60 min was simply the
-- conservative end of the usual actigraphy range.
--
-- The inter-cluster gap distribution has no valley (1-2h: 92, 2-3h: 84,
-- 3-4h: 78, 4-5h: 55, 5-6h: 38, 6-7h: 36 …), so no threshold is "the natural
-- one". It was picked on a measured trade-off instead — leave-one-out outlier
-- deviation of the midpoint against its ±3-day circular neighbourhood
-- (lower = better), plus the count of days where no cluster dominates:
--
--   threshold   avg dev   p90    outliers(>4h)   main_share<0.65   span>14h
--   60 min      2.002     4.90   121             126               10
--   120 min     1.967     4.68   119              88               19
--   150 min       —         —      —              76               39
--   180 min       —         —      —              62               56   (max span 32.8 h!)
--
-- 120 min improves every aggregate metric and cuts ambiguous days by 30%, at
-- the cost of 9 more over-long spans. 150 min+ degrades fast. The 2026-07-21
-- case that motivated this (00:17-04:32 + 05:58-10:55, an 86-min break) goes
-- from midpoint 08:26 — the later block winning a near-tie on span — to 05:36.
--
-- REJECTED here, for the record: ranking clusters by actual sleep instead of
-- span. It measures *worse* on every metric (avg dev 2.002 → 2.025 at 60 min,
-- 1.967 → 2.000 at 120 min) because actual-sleep differences between blocks are
-- small and noisy, so the pick flips on low-efficiency nights: 2025-09-29 has a
-- 03:09-13:02 block (8.78 h in bed, 3.57 h asleep) that loses to a short evening
-- block, swinging the midpoint 06:55 → 19:59. Span differences are larger and
-- more stable. Ranking stays on span.
--
-- 120 min still leaves 88 days (10%) where the main cluster holds <65% of the
-- day's sleep and bedtime/wake genuinely do not represent a night. Those are not
-- fixable by any merge rule — hence main_share, so Grafana can plot them in a
-- different colour rather than silently implying a single night happened.
--
-- ---------------------------------------------------------------------------
-- What changes
-- ---------------------------------------------------------------------------
--   * sleep_gap_threshold(): the shared night definition becomes one named
--     constant instead of three copies of INTERVAL '60 minutes' that can drift.
--   * sleep_rolling_stats: threshold + new main_share column. All existing
--     columns keep their names and meaning; Grafana needs no edits to keep
--     working (the new colour split is opt-in, see docs/sleep-processing.md).
--   * sleep_stage_daily / sleep_hypnogram: threshold only — the three surfaces
--     MUST share one cluster definition or the trend and Sleep Stages disagree.
--   * sleep_regularity: new matview (SRI).
--
-- Impact vs the 0018 values: midpoint moves on 95 days (54 land closer to their
-- neighbourhood, 41 further), bedtime on 35, wake on 79, and duration on 13
-- (those are sleep_day relabels — 883 → 881 days).
--
-- Design rationale: ../docs/sleep-processing.md

-- ---------------------------------------------------------------------------
-- The shared night definition, as one constant
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sleep_gap_threshold() RETURNS interval
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT INTERVAL '120 minutes' $$;

COMMENT ON FUNCTION sleep_gap_threshold() IS
  'Wake bout that terminates a sleep period. Shared by sleep_rolling_stats, '
  'sleep_stage_daily and sleep_hypnogram so all three agree on what one night '
  'is. Changing it changes every one of them on the next REFRESH.';

-- ---------------------------------------------------------------------------
-- sleep_rolling_stats (0018 definition + threshold constant + main_share)
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
      WHEN prev_max_end IS NULL OR start_at - prev_max_end > sleep_gap_threshold()
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
  -- each session's duration/actual, tagged with its cluster and its sleep_day
  SELECT
    c.user_id,
    c.cluster_id,
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
cluster_totals AS (
  -- per-cluster actual sleep, for main_share
  SELECT user_id, cluster_id, sleep_day, SUM(actual_h) AS cluster_actual
  FROM per_session
  GROUP BY user_id, cluster_id, sleep_day
),
day_totals AS (
  SELECT user_id, sleep_day,
         SUM(duration_h) AS duration_total,
         SUM(actual_h)   AS actual_total
  FROM per_session
  GROUP BY user_id, sleep_day
),
day_main AS (
  -- bedtime/wake from the LONGEST cluster (by span) per sleep_day.
  -- Span, not actual sleep — see the header note on the rejected alternative.
  SELECT DISTINCT ON (user_id, sleep_day)
    user_id, sleep_day,
    EXTRACT(HOUR   FROM (cluster_start AT TIME ZONE 'Asia/Tokyo'))
    + EXTRACT(MINUTE FROM (cluster_start AT TIME ZONE 'Asia/Tokyo'))::double precision / 60.0
      AS bedtime_jst,
    EXTRACT(HOUR   FROM (cluster_end AT TIME ZONE 'Asia/Tokyo'))
    + EXTRACT(MINUTE FROM (cluster_end AT TIME ZONE 'Asia/Tokyo'))::double precision / 60.0
      AS wake_jst,
    ct.cluster_actual
  FROM cluster_day
  JOIN cluster_totals ct USING (user_id, cluster_id, sleep_day)
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
    t.actual_total   AS actual_total,
    -- share of the sleep_day's sleep that sits in the cluster bedtime/wake came
    -- from. 1.0 = one consolidated block; low = the day was split and this
    -- bedtime/wake pair does not describe a single night.
    (m.cluster_actual / NULLIF(t.actual_total, 0))::double precision AS main_share
  FROM day_main m
  JOIN day_totals t USING (user_id, sleep_day)
),
windowed AS (
  SELECT
    user_id, sleep_day,
    bedtime, wake, midpoint, duration, actual_total AS actual_duration, main_share,
    COUNT(*)             OVER w AS n_w,
    AVG(duration)        OVER w AS duration_ma,
    STDDEV(duration)     OVER w AS duration_sd,
    AVG(actual_total)    OVER w AS actual_duration_ma,
    STDDEV(actual_total) OVER w AS actual_duration_sd,
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
  bedtime, wake, midpoint, duration, actual_duration, main_share,
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

COMMENT ON MATERIALIZED VIEW sleep_rolling_stats IS
  'Per-sleep_day sleep trend. bedtime/wake/midpoint come from the longest '
  'cluster; main_share says how much of the day''s sleep that cluster holds.';

-- ---------------------------------------------------------------------------
-- sleep_stage_daily (0018 definition; threshold constant only)
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
    CASE WHEN prev_max_end IS NULL OR start_at - prev_max_end > sleep_gap_threshold()
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

CREATE UNIQUE INDEX sleep_stage_daily_ux ON sleep_stage_daily (user_id, sleep_day);

-- ---------------------------------------------------------------------------
-- sleep_hypnogram (0006 definition; threshold constant only)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW sleep_hypnogram AS
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
    CASE WHEN prev_max_end IS NULL OR start_at - prev_max_end > sleep_gap_threshold()
         THEN 1 ELSE 0 END AS is_new_cluster
  FROM ordered
),
clustered AS (
  SELECT *,
    SUM(is_new_cluster) OVER (PARTITION BY user_id ORDER BY start_at) AS cluster_id
  FROM flagged
),
clusters_start AS (
  SELECT user_id, start_at, end_at, stages,
    MIN(start_at) OVER (PARTITION BY user_id, cluster_id) AS cluster_start
  FROM clustered
),
with_night AS (
  SELECT user_id, start_at, end_at, stages,
    to_char(
      CASE
        WHEN EXTRACT(HOUR FROM (cluster_start AT TIME ZONE 'Asia/Tokyo')) >= 18
          THEN ((cluster_start AT TIME ZONE 'Asia/Tokyo')::date + 1)
        ELSE   (cluster_start AT TIME ZONE 'Asia/Tokyo')::date
      END::timestamptz, 'MM/DD') AS night_date
  FROM clusters_start
)
SELECT
  (stage_item->>'startTime')::timestamptz AS time,
  user_id, night_date,
  CASE (stage_item->>'stage')::int
    WHEN 1 THEN 'Awake'
    WHEN 4 THEN 'Light'
    WHEN 5 THEN 'Deep'
    WHEN 6 THEN 'REM'
    ELSE 'Unknown'
  END AS stage
FROM with_night, jsonb_array_elements(stages) stage_item
UNION ALL
-- NULL-stage row at each session end breaks the hypnogram line between sessions
SELECT end_at AS time, user_id, night_date, NULL::text AS stage
FROM with_night;

-- ---------------------------------------------------------------------------
-- sleep_regularity — Sleep Regularity Index (SRI)
--
--   SRI = -100 + (200 / (M·(N-1))) · Σ δ(s(i,j), s(i+1,j))
--
-- i.e. the probability that the sleep/wake state at a given clock minute is the
-- same as 24 h later, rescaled to [-100, +100]. 100 = perfectly repeating
-- schedule, 0 = coin flip. It needs no "main sleep period", which is exactly
-- why it is the right regularity metric for this data: 10% of days have no
-- dominant sleep block at all (see main_share), so a bedtime/wake-based
-- regularity measure would be reading noise on those days.
--
-- Conventions here:
--   * 1-minute epochs, JST calendar day (midnight→midnight). NOT sleep_day —
--     SRI is a 24 h-cycle measure and the standard boundary is midnight.
--   * asleep = stages Light/Deep/REM (4/5/6). This data only ever contains
--     stages 1/4/5/6, so that is exactly the complement of Awake.
--   * trailing 7-day window = 6 adjacent day pairs; a value is emitted only
--     with >= 4 pairs, so tracking gaps do not fake a regular schedule
--     (a missing day yields no pair rather than a day of "wake").
--
-- Cost: ~8.5 s full recompute on the RPi4 — too slow for the 5-min job, hence
-- its own hourly one below. A 7-day rolling regularity index does not move
-- meaningfully inside an hour.
-- ---------------------------------------------------------------------------
DROP MATERIALIZED VIEW IF EXISTS sleep_regularity;

CREATE MATERIALIZED VIEW sleep_regularity AS
WITH asleep AS (
  SELECT DISTINCT ss.user_id, g AS minute_utc
  FROM sleep_session ss,
       jsonb_array_elements(ss.stages) st,
       LATERAL generate_series(
         date_trunc('minute', (st->>'startTime')::timestamptz),
         (st->>'endTime')::timestamptz - INTERVAL '1 microsecond',
         INTERVAL '1 minute'
       ) g
  WHERE (st->>'stage')::int IN (4, 5, 6)
),
binned AS (
  SELECT user_id,
    (minute_utc AT TIME ZONE 'Asia/Tokyo')::date AS d,
    (EXTRACT(HOUR   FROM (minute_utc AT TIME ZONE 'Asia/Tokyo')) * 60
     + EXTRACT(MINUTE FROM (minute_utc AT TIME ZONE 'Asia/Tokyo')))::int AS minute_of_day
  FROM asleep
),
day_n AS (
  SELECT user_id, d, count(*) AS asleep_min FROM binned GROUP BY user_id, d
),
overlap AS (
  -- minutes-of-day asleep on BOTH day d and day d+1
  SELECT x.user_id, x.d AS d0, count(*) AS both_asleep
  FROM binned x
  JOIN binned y
    ON y.user_id = x.user_id
   AND y.minute_of_day = x.minute_of_day
   AND y.d = x.d + 1
  GROUP BY x.user_id, x.d
),
pair AS (
  -- agreeing minutes = 1440 - symmetric difference of the two days' sleep sets
  SELECT a.user_id, b.d AS day,
         (1440 - (a.asleep_min + b.asleep_min - 2 * COALESCE(o.both_asleep, 0)))::double precision
           AS agree_min
  FROM day_n a
  JOIN day_n b ON b.user_id = a.user_id AND b.d = a.d + 1
  LEFT JOIN overlap o ON o.user_id = a.user_id AND o.d0 = a.d
)
SELECT
  d.user_id,
  d.day,
  200.0 * SUM(p.agree_min) / (1440.0 * count(*)) - 100.0 AS sri,
  count(*)::int AS n_pairs
FROM (SELECT DISTINCT user_id, day FROM pair) d
JOIN pair p ON p.user_id = d.user_id AND p.day BETWEEN d.day - 5 AND d.day
GROUP BY d.user_id, d.day
HAVING count(*) >= 4;

CREATE UNIQUE INDEX sleep_regularity_ux ON sleep_regularity (user_id, day);

COMMENT ON MATERIALIZED VIEW sleep_regularity IS
  'Sleep Regularity Index over a trailing 7-day window, 1-min epochs, JST '
  'calendar days. 100 = identical schedule day to day, 0 = chance.';

-- ---------------------------------------------------------------------------
-- Refresh: SRI gets its own hourly job off the same dirty counter (0016)
-- ---------------------------------------------------------------------------
ALTER TABLE sleep_stats_refresh_state
  ADD COLUMN IF NOT EXISTS sri_refreshed_changes bigint NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS sri_last_refreshed    timestamptz;

CREATE OR REPLACE PROCEDURE sleep_regularity_refresh_if_dirty(job_id int, config jsonb)
LANGUAGE plpgsql AS $$
DECLARE
    c bigint;
BEGIN
    SELECT changes INTO c FROM sleep_stats_refresh_state
    WHERE changes > sri_refreshed_changes;
    IF c IS NULL THEN
        RETURN;
    END IF;
    REFRESH MATERIALIZED VIEW CONCURRENTLY sleep_regularity;
    -- Stamp the pre-refresh counter, as 0016 does: changes landing mid-refresh
    -- stay > sri_refreshed_changes and trigger the next run.
    UPDATE sleep_stats_refresh_state
    SET sri_refreshed_changes = c, sri_last_refreshed = now();
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM timescaledb_information.jobs
                   WHERE proc_name = 'sleep_regularity_refresh_if_dirty') THEN
        PERFORM add_job('sleep_regularity_refresh_if_dirty', INTERVAL '1 hour');
    END IF;
END $$;

-- DROP loses the matviews' explicit grants (docs/grafana-datasource-role.md);
-- conditional so a fresh-DB init without the role does not fail (as in 0010/0017/0018).
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grafana_ro') THEN
    GRANT SELECT ON sleep_rolling_stats TO grafana_ro;
    GRANT SELECT ON sleep_stage_daily   TO grafana_ro;
    GRANT SELECT ON sleep_regularity    TO grafana_ro;
  END IF;
END $$;
