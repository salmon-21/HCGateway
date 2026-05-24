-- Fix unnatural night splits in the Sleep Stages hypnogram.
--
-- 0004's sleep_hypnogram computed night_date per session as date(start_at − 6h).
-- A single night recorded as two records (a brief wake, then resuming after
-- 06:00 JST) got split across two night_date labels — the resumed part, starting
-- after 06:00, rolled to the next day, so one night appeared in two lanes.
--
-- Fix: derive night_date from the session's *cluster* start (≤60 min gaps merged,
-- same gaps-and-islands as sleep_rolling_stats), so every session of one night
-- shares one label. The (−6h) bedtime-evening convention (intentionally != the
-- matview's 18:00 sleep_day) is unchanged — it's just anchored to the night's
-- first session instead of each session.
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
with_night AS (
  SELECT
    user_id, start_at, end_at, stages,
    -- night label from the cluster's first session, -6h evening convention
    to_char(
      ((MIN(start_at) OVER (PARTITION BY user_id, cluster_id) - INTERVAL '6 hours')
        AT TIME ZONE 'Asia/Tokyo'),
      'MM/DD'
    ) AS night_date
  FROM clustered
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
