-- Use the true circular standard deviation for the bedtime/wake bands instead of
-- the earlier "unwrap to mean ±12h, then linear stddev" approximation.
--
-- From the mean resultant length R = |mean unit vector| = sqrt(mean(sin)^2 + mean(cos)^2):
--   circular SD = sqrt(-2 * ln R) radians  ->  * 12/pi  hours.
-- R≈1 (tight cluster) => band ≈ 0; R→0 (dispersed) => band widens, capped at 12 h
-- (half the 24 h cycle). Only bedtime/wake upper/lower change; the circular mean
-- (bedtime_ma / wake_ma) and the linear stats (duration / actual / midpoint) are
-- unchanged. Reuses the existing matview definition (0004) — just the function body
-- and a refresh.
--
-- Design rationale: ../docs/sleep-processing.md

CREATE OR REPLACE FUNCTION circular_stats(vals double precision[])
RETURNS TABLE(mean double precision,
              upper double precision,
              lower double precision) AS $$
DECLARE
  sin_m double precision;
  cos_m double precision;
  m double precision;
  r double precision;
  sd double precision;
BEGIN
  IF vals IS NULL OR array_length(vals, 1) IS NULL THEN
    RETURN;
  END IF;

  SELECT avg(sin(x * pi() / 12.0)), avg(cos(x * pi() / 12.0))
    INTO sin_m, cos_m
    FROM unnest(vals) AS t(x);

  -- Circular mean (hour-of-day, wrapped to [0,24)).
  m := atan2(sin_m, cos_m) * 12.0 / pi();
  IF m < 0 THEN
    m := m + 24.0;
  END IF;

  -- Circular SD from the mean resultant length R.
  r := sqrt(sin_m * sin_m + cos_m * cos_m);
  IF r >= 1.0 THEN
    sd := 0;                                 -- all identical: no spread
  ELSIF r <= 0.0 THEN
    sd := 12;                                -- fully dispersed: span the cycle
  ELSE
    sd := sqrt(-2.0 * ln(r)) * 12.0 / pi();
    IF sd > 12.0 THEN
      sd := 12.0;
    END IF;
  END IF;

  mean := m;
  upper := m + sd;
  lower := m - sd;
  RETURN NEXT;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Recompute bedtime/wake bands with the new function.
REFRESH MATERIALIZED VIEW sleep_rolling_stats;
