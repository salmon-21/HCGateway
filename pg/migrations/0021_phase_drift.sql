-- 0021_phase_drift.sql
-- Add signed phase-drift rate to sleep_regularity: how fast, and in which
-- direction, the sleep midpoint is moving around the clock.
--
-- ---------------------------------------------------------------------------
-- Why a third measure
-- ---------------------------------------------------------------------------
-- SRI says "does today repeat tomorrow". IS (0020) says "is there a stable 24 h
-- profile at all". Neither says which WAY the schedule is moving, or how fast --
-- IS only collapses when something is wrong. Measured here:
--
--   |drift| vs IS   -0.50      -- overlaps, but only halfway
--   |drift| vs SRI  -0.22      -- SRI barely sees it at all
--
-- so drift is largely its own signal, and it is the only one of the three that
-- carries a sign. That sign is what separates a schedule sliding later from one
-- sliding earlier, which the monthly numbers show alternating on this data
-- (2025-09 +53 min/day, 2025-12 -66 min/day) rather than holding one direction.
--
-- ---------------------------------------------------------------------------
-- Estimator: 14-day trailing MEAN of wrapped day-to-day midpoint change
-- ---------------------------------------------------------------------------
-- delta = mod(midpoint_i - midpoint_(i-1) + 36, 24) - 12, in [-12, +12) hours,
-- positive = the midpoint moved later. Consecutive calendar days only; a gap
-- yields no delta rather than a fake jump. Emitted at >= 8 deltas in the window.
--
-- The mean, not the median. The median looks like the robust choice and is
-- measurably worse: day-to-day jitter 30.2 vs 23.9 min, peak |value| 379 vs 271,
-- and it returns implausible levels (2025-09: +107 min/day, i.e. 53 h of drift
-- in a month). The daily deltas are asymmetric, so the median lands on one lobe
-- while the mean is what "drift rate" actually means -- the sum telescopes, so
-- mean = net phase displacement / days.
--
-- KNOWN LIMIT: a true change beyond +/-12 h wraps to the wrong sign, which the
-- mean then carries. 29 of 842 deltas (3.4%) exceed 9 h, so the estimate is
-- reliable for entrained or slowly drifting stretches and should be read
-- loosely during the most irregular ones -- precisely where IS is already near
-- zero and reporting that fact more honestly. Read the two together.
--
-- ---------------------------------------------------------------------------
-- Dependency note
-- ---------------------------------------------------------------------------
-- This is the first thing in sleep_regularity that does NOT come straight from
-- sleep_session: midpoint comes from the sleep_rolling_stats matview, so the
-- clustering, the 18:00 wake-day cutoff and the circular midpoint are inherited
-- rather than reimplemented. That makes sleep_regularity depend on a matview
-- refreshed by a *different* job (0016's 5-min one, vs its own hourly one), so
-- drift can lag sleep_rolling_stats by up to an hour. Acceptable for a 14-day
-- rolling rate; do not reimplement the clustering here to avoid it.
--
-- sleep_regularity.day is a JST calendar day (SRI/IS) while sleep_day is the
-- 18:00 wake-day. They coincide except for sleep starting 18:00-24:00, which is
-- labelled the same wake morning by both, so joining them is sound.
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
-- Phase drift: signed rate the midpoint moves, trailing 14 days
-- --------------------------------------------------------------------------
mid AS (
  SELECT user_id, sleep_day,
    (mod(((midpoint
           - LAG(midpoint)  OVER (PARTITION BY user_id ORDER BY sleep_day)) + 36)::numeric,
         24) - 12) * 60 AS delta_min,
    sleep_day - LAG(sleep_day) OVER (PARTITION BY user_id ORDER BY sleep_day) AS day_gap
  FROM sleep_rolling_stats
),
consecutive AS (
  SELECT user_id, sleep_day, delta_min FROM mid WHERE day_gap = 1
),
drift AS (
  SELECT user_id, sleep_day AS day,
    CASE WHEN COUNT(*) OVER w >= 8
      THEN (AVG(delta_min) OVER w)::double precision END AS drift_min_per_day,
    (COUNT(*) OVER w)::int AS drift_n
  FROM consecutive
  WINDOW w AS (PARTITION BY user_id ORDER BY sleep_day
               RANGE BETWEEN INTERVAL '13 days' PRECEDING AND CURRENT ROW)
)
SELECT
  s.user_id, s.day, s.sri, s.n_pairs,
  i.is_28d, i.is_n_days,
  f.drift_min_per_day, f.drift_n
FROM sri s
LEFT JOIN istab i ON i.user_id = s.user_id AND i.day  = s.day
LEFT JOIN drift f ON f.user_id = s.user_id AND f.day  = s.day;

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
