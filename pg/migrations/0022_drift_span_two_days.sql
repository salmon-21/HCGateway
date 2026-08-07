-- 0022_drift_span_two_days.sql
-- Let phase drift span a one-day hole in the sleep_day series, normalised per
-- day. Only the drift CTEs change; SRI and IS are byte-identical to 0021.
--
-- ---------------------------------------------------------------------------
-- The hole
-- ---------------------------------------------------------------------------
-- 0021 took a delta only between consecutive sleep_days. That left 42 of 856
-- rows (4.9%) with a NULL drift, and they arrived in *pairs* -- 2025-09-21 and
-- -22, 2025-10-09 and -10, 2025-10-25 and -26, and so on. One mechanism
-- explains all of them:
--
--   Sleep that starts at or after 18:00 JST is labelled the NEXT day by the
--   wake-day cutoff. If that is the only sleep of calendar day D, no cluster is
--   ever labelled sleep_day = D, so the sleep_day series skips D.
--     * day D:   sleep_regularity has a row (SRI/IS run on calendar days, and
--                there ARE asleep minutes on D), but no midpoint exists to join
--                to. NULL -- and correctly so, see below.
--     * day D+1: a midpoint exists, but its predecessor is D-1, so the gap is 2
--                and 0021 produced no delta at all. NULL -- avoidably.
--
-- Those NULLs are not spread evenly. All 25 in the last year fall between
-- 2025-08 and 2026-02, none after 2026-02-19, because skipping a sleep_day
-- requires an evening sleep onset -- which is what a rotating schedule does. So
-- the metric was thinnest over exactly the stretch it exists to describe.
--
-- ---------------------------------------------------------------------------
-- The change
-- ---------------------------------------------------------------------------
-- Accept a gap of 1 OR 2 days and divide the wrapped delta by the gap, giving a
-- per-day rate either way. Measured:
--
--                          deltas   mean |value|   NULL rows
--   gap = 1 only (0021)      842       155.1          42
--   gap <= 2, normalised     867       153.0          25
--
-- 25 more deltas, and the value distribution barely moves (155.1 -> 153.0), so
-- the two-day steps are not dragging the estimate around.
--
-- The 25 remaining NULLs are the *right* answer: they are calendar days with no
-- sleep_day at all, so there is no midpoint to difference. Filling them would be
-- invention. (Roughly 7 of the rest are the intended <8-deltas gate.)
--
-- COST: spanning two days assumes the drift is linear across the hole -- free
-- for a one-day step, an assumption for a two-day one -- and the +/-12 h
-- wrap ambiguity (0021's header) gets likelier the longer the step. Both are
-- why this stops at 2 and does not become a general "nearest neighbour" join.
--
-- Design rationale: ../docs/sleep-processing.md

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
jst AS (
  SELECT user_id,
    (minute_utc AT TIME ZONE 'Asia/Tokyo')::date AS d,
    EXTRACT(HOUR   FROM (minute_utc AT TIME ZONE 'Asia/Tokyo'))::int AS hh,
    (EXTRACT(HOUR   FROM (minute_utc AT TIME ZONE 'Asia/Tokyo')) * 60
     + EXTRACT(MINUTE FROM (minute_utc AT TIME ZONE 'Asia/Tokyo')))::int AS minute_of_day
  FROM asleep
),
-- --------------------------------------------------------------------------
-- SRI: 1-min epochs, adjacent-day concordance, trailing 7 days (6 pairs)
-- --------------------------------------------------------------------------
day_n AS (
  SELECT user_id, d, count(*) AS asleep_min FROM jst GROUP BY user_id, d
),
overlap AS (
  SELECT x.user_id, x.d AS d0, count(*) AS both_asleep
  FROM jst x
  JOIN jst y ON y.user_id = x.user_id
            AND y.minute_of_day = x.minute_of_day
            AND y.d = x.d + 1
  GROUP BY x.user_id, x.d
),
pair AS (
  SELECT a.user_id, b.d AS day,
         (1440 - (a.asleep_min + b.asleep_min - 2 * COALESCE(o.both_asleep, 0)))::double precision
           AS agree_min
  FROM day_n a
  JOIN day_n b ON b.user_id = a.user_id AND b.d = a.d + 1
  LEFT JOIN overlap o ON o.user_id = a.user_id AND o.d0 = a.d
),
sri AS (
  SELECT d.user_id, d.day,
    200.0 * SUM(p.agree_min) / (1440.0 * count(*)) - 100.0 AS sri,
    count(*)::int AS n_pairs
  FROM (SELECT DISTINCT user_id, day FROM pair) d
  JOIN pair p ON p.user_id = d.user_id AND p.day BETWEEN d.day - 5 AND d.day
  GROUP BY d.user_id, d.day
  HAVING count(*) >= 4
),
-- --------------------------------------------------------------------------
-- IS: 24 hourly bins vs the window's average profile, trailing 28 days
-- --------------------------------------------------------------------------
hour_sleep AS (
  SELECT user_id, d, hh, count(*) / 60.0 AS x FROM jst GROUP BY user_id, d, hh
),
grid AS (
  SELECT dd.user_id, dd.d, h.hh
  FROM (SELECT DISTINCT user_id, d FROM hour_sleep) dd,
       generate_series(0, 23) h(hh)
),
hb AS (
  SELECT g.user_id, g.d, g.hh, COALESCE(s.x, 0)::double precision AS x
  FROM grid g LEFT JOIN hour_sleep s USING (user_id, d, hh)
),
per_hour_roll AS (
  SELECT user_id, d, hh,
    SUM(x) OVER (PARTITION BY user_id, hh ORDER BY d
                 RANGE BETWEEN INTERVAL '27 days' PRECEDING AND CURRENT ROW) AS sh
  FROM hb
),
sh2 AS (
  SELECT user_id, d, SUM(sh * sh) AS sum_sh2 FROM per_hour_roll GROUP BY user_id, d
),
day_agg AS (
  SELECT user_id, d, SUM(x) AS sx, SUM(x * x) AS sxx FROM hb GROUP BY user_id, d
),
roll AS (
  SELECT user_id, d,
    COUNT(*) OVER w AS n_days,
    SUM(sx)  OVER w AS s_tot,
    SUM(sxx) OVER w AS ss_tot
  FROM day_agg
  WINDOW w AS (PARTITION BY user_id ORDER BY d
               RANGE BETWEEN INTERVAL '27 days' PRECEDING AND CURRENT ROW)
),
istab AS (
  SELECT r.user_id, r.d AS day, r.n_days::int AS is_n_days,
    CASE WHEN r.n_days >= 14 THEN
      ((r.n_days * 24) * (sh2.sum_sh2 / (r.n_days::double precision ^ 2)
                          - 24 * (r.s_tot / (24.0 * r.n_days)) ^ 2))
      / NULLIF(24 * (r.ss_tot - (r.s_tot ^ 2) / (24.0 * r.n_days)), 0)
    END AS is_28d
  FROM roll r JOIN sh2 USING (user_id, d)
),
-- --------------------------------------------------------------------------
-- Phase drift: signed midpoint movement, trailing 14 days.
-- A step may span 1 or 2 sleep_days (the wake-day cutoff can skip a calendar
-- date); dividing by the gap makes either a per-day rate. See the header.
-- --------------------------------------------------------------------------
mid AS (
  SELECT user_id, sleep_day,
    (mod(((midpoint
           - LAG(midpoint)  OVER (PARTITION BY user_id ORDER BY sleep_day)) + 36)::numeric,
         24) - 12) * 60 AS raw_delta_min,
    sleep_day - LAG(sleep_day) OVER (PARTITION BY user_id ORDER BY sleep_day) AS day_gap
  FROM sleep_rolling_stats
),
steps AS (
  SELECT user_id, sleep_day, raw_delta_min / day_gap AS delta_min
  FROM mid WHERE day_gap BETWEEN 1 AND 2
),
drift AS (
  SELECT user_id, sleep_day AS day,
    CASE WHEN COUNT(*) OVER w >= 8
      THEN (AVG(delta_min) OVER w)::double precision END AS drift_min_per_day,
    (COUNT(*) OVER w)::int AS drift_n
  FROM steps
  WINDOW w AS (PARTITION BY user_id ORDER BY sleep_day
               RANGE BETWEEN INTERVAL '13 days' PRECEDING AND CURRENT ROW)
)
SELECT
  s.user_id, s.day, s.sri, s.n_pairs,
  i.is_28d, i.is_n_days,
  f.drift_min_per_day, f.drift_n
FROM sri s
LEFT JOIN istab i ON i.user_id = s.user_id AND i.day = s.day
LEFT JOIN drift f ON f.user_id = s.user_id AND f.day = s.day;

CREATE UNIQUE INDEX sleep_regularity_ux ON sleep_regularity (user_id, day);

COMMENT ON MATERIALIZED VIEW sleep_regularity IS
  'sri: Sleep Regularity Index, 1-min epochs, trailing 7 days -- "does today '
  'repeat tomorrow". is_28d: Interdaily Stability, hourly bins, trailing 28 '
  'days -- "is there a stable 24 h profile at all". drift_min_per_day: signed '
  'midpoint movement, trailing 14 days, + = later -- the only one of the three '
  'with a direction. None has a clinical cutoff.';

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grafana_ro') THEN
    GRANT SELECT ON sleep_regularity TO grafana_ro;
  END IF;
END $$;
