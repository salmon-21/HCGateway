-- 0020_interdaily_stability.sql
-- Add Interdaily Stability (IS) to sleep_regularity, alongside SRI.
--
-- ---------------------------------------------------------------------------
-- Why
-- ---------------------------------------------------------------------------
-- SRI compares each clock minute to the SAME minute 24 h later, so it scores
-- "today resembles tomorrow". That makes it blind by construction to a schedule
-- that drifts steadily: shift 20 min later every day and every day still
-- resembles the next, so SRI stays high while the 24 h pattern dissolves.
--
-- IS (Witting et al. 1990) compares each bin to the window's AVERAGE 24 h
-- profile instead, so drift destroys it. Measured on this data over a common
-- 28-day window (n=844):
--
--                        vs each other   vs 28-day phase drift
--   SRI (28-day)              0.88              -0.41
--   IS  (28-day)              0.88              -0.68
--
-- They agree on "regularity" (r=0.88) but IS is ~1.7x more sensitive to drift,
-- exactly as the definitions predict. The divergence is not theoretical:
--
--   2025-12-18   SRI 39 (middling)   IS 0.07 (collapsed)   drift 8.2 h/28 d
--   2025-12-19   SRI 38              IS 0.07               drift 9.6 h/28 d
--
-- i.e. mid-December the sleep phase was rotating ~8-10 h per month. Each day
-- resembled the next, so SRI reported an unremarkable 39; there was essentially
-- no stable 24 h profile left. Nothing on the dashboard could see that before.
--
-- REJECTED alongside it, for the record:
--   * IV (Intradaily Variability, the fragmentation member of the same family):
--     r = -0.91 against mean main_share and +0.79 against the fragmented-day
--     count over the same window. It re-measures what main_share already shows,
--     continuously instead of as a flag. Good independent corroboration of
--     main_share; redundant as a panel.
--   * A 28-day SRI: r = 0.70 against the 7-day one (SD 17.2 -> 12.1). Smoother,
--     but the same signal on a slower clock.
--   * IS/IV/RA on `steps` (the classical activity-based form): plausible but a
--     separate project, and unvalidated here.
--
-- ---------------------------------------------------------------------------
-- Definition and caveats
-- ---------------------------------------------------------------------------
--   IS = [ N * SUM_h (xbar_h - xbar)^2 ] / [ p * SUM_i (x_i - xbar)^2 ]
--
-- p = 24 hourly bins (Witting's original grain; the SRI half of this view keeps
-- its 1-min epochs), x = fraction of that hour asleep, N = p * days in window.
-- Trailing 28 days, emitted only at >= 14 days of data so a tracking gap cannot
-- manufacture a profile. Days with no sleep record contribute nothing rather
-- than a day of "awake" — missing is not wake.
--
-- IS is a SLOW companion, not a replacement: 28 days vs SRI's 7. The two panel
-- series therefore differ in window as well as in what they measure, so the
-- vertical gap between them mixes timescale with drift. A `sri_28d` column
-- would make that gap clean; deliberately not added until it is asked for.
--
-- Like SRI, IS has no clinical cutoff -- read the trend, not the level.
--
-- Computed with window functions rather than the obvious correlated subqueries:
-- expanding
--   SUM_h (xbar_h - xbar)^2 = (1/D^2) * SUM_h Sh^2 - 24*xbar^2
--   SUM_i (x_i - xbar)^2    = SS - S^2/(24*D)
-- leaves only rolling sums (Sh per hour-of-day, S/SS per day), so the whole
-- thing is a couple of RANGE frames instead of a per-day rescan.
--
-- Design rationale: ../docs/sleep-processing.md

DROP MATERIALIZED VIEW IF EXISTS sleep_regularity;

CREATE MATERIALIZED VIEW sleep_regularity AS
-- ---------------------------------------------------------------------------
-- shared: minutes asleep (Light/Deep/REM; this data only ever holds 1/4/5/6,
-- so that is exactly "not Awake")
-- ---------------------------------------------------------------------------
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
-- ---------------------------------------------------------------------------
-- SRI: 1-min epochs, adjacent-day concordance, trailing 7 days (6 pairs)
-- ---------------------------------------------------------------------------
day_n AS (
  SELECT user_id, d, count(*) AS asleep_min FROM jst GROUP BY user_id, d
),
overlap AS (
  SELECT x.user_id, x.d AS d0, count(*) AS both_asleep
  FROM jst x
  JOIN jst y
    ON y.user_id = x.user_id
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
-- ---------------------------------------------------------------------------
-- IS: 24 hourly bins vs the window's average profile, trailing 28 days
-- ---------------------------------------------------------------------------
hour_sleep AS (
  SELECT user_id, d, hh, count(*) / 60.0 AS x FROM jst GROUP BY user_id, d, hh
),
grid AS (
  -- every hour of every day that HAS data (absent days stay absent)
  SELECT dd.user_id, dd.d, h.hh
  FROM (SELECT DISTINCT user_id, d FROM hour_sleep) dd,
       generate_series(0, 23) h(hh)
),
hb AS (
  SELECT g.user_id, g.d, g.hh, COALESCE(s.x, 0)::double precision AS x
  FROM grid g LEFT JOIN hour_sleep s USING (user_id, d, hh)
),
-- Sh = rolling 28-day sum for a given hour-of-day
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
    COUNT(*) OVER w  AS n_days,
    SUM(sx)  OVER w  AS s_tot,
    SUM(sxx) OVER w  AS ss_tot
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
)
SELECT
  s.user_id, s.day, s.sri, s.n_pairs,
  i.is_28d, i.is_n_days
FROM sri s
LEFT JOIN istab i ON i.user_id = s.user_id AND i.day = s.day;

CREATE UNIQUE INDEX sleep_regularity_ux ON sleep_regularity (user_id, day);

COMMENT ON MATERIALIZED VIEW sleep_regularity IS
  'sri: Sleep Regularity Index, 1-min epochs, trailing 7 days -- "does today '
  'repeat tomorrow". is_28d: Interdaily Stability, hourly bins, trailing 28 '
  'days -- "is there a stable 24 h profile at all". SRI is blind to steady '
  'phase drift; IS is not. Neither has a clinical cutoff.';

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grafana_ro') THEN
    GRANT SELECT ON sleep_regularity TO grafana_ro;
  END IF;
END $$;
