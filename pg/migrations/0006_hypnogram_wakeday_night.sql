-- Trial: switch sleep_hypnogram night_date from the (-6h) bedtime-evening label
-- to the matview's 18:00 wake-day cutoff, so the Sleep Stages lanes match the
-- sleep_day used by the Bedtime/Wake/Duration trend panels (one shared label).
--
-- Effect vs 0005 (for post-midnight bedtimes, the usual pattern here):
--   night labelled by the calendar date it started — a post-midnight bedtime keeps
--   that date (was the previous evening under -6h); a daytime nap and the following
--   night land on different lanes. Evening bedtimes (start hour ≥18) still roll to
--   the next (wake) day, matching the trend.
--
-- night_date is still anchored to the cluster start so a night split across a
-- brief wake stays one lane (the 0005 fix is preserved).
--
-- Design rationale: ../docs/sleep-processing.md

CREATE OR REPLACE VIEW sleep_hypnogram AS
WITH ordered AS (
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
  SELECT *,
    SUM(is_new_cluster) OVER (PARTITION BY user_id ORDER BY start_at) AS cluster_id
  FROM flagged
),
clusters_start AS (
  SELECT
    user_id, start_at, end_at, stages,
    MIN(start_at) OVER (PARTITION BY user_id, cluster_id) AS cluster_start
  FROM clustered
),
with_night AS (
  SELECT
    user_id, start_at, end_at, stages,
    -- same 18:00 wake-day cutoff as sleep_rolling_stats.sleep_day, on cluster start
    to_char(
      CASE
        WHEN EXTRACT(HOUR FROM (cluster_start AT TIME ZONE 'Asia/Tokyo')) >= 18
          THEN ((cluster_start AT TIME ZONE 'Asia/Tokyo')::date + 1)
        ELSE   (cluster_start AT TIME ZONE 'Asia/Tokyo')::date
      END,
      'MM/DD'
    ) AS night_date
  FROM clusters_start
)
SELECT
  (stage_item->>'startTime')::timestamptz AS time,
  user_id,
  night_date,
  CASE (stage_item->>'stage')::int
    WHEN 1 THEN 'Awake'
    WHEN 4 THEN 'Light'
    WHEN 5 THEN 'Deep'
    WHEN 6 THEN 'REM'
    ELSE 'Unknown'
  END AS stage
FROM with_night, jsonb_array_elements(with_night.stages) stage_item
UNION ALL
SELECT
  end_at AS time,
  user_id,
  night_date,
  NULL::text AS stage
FROM with_night;
