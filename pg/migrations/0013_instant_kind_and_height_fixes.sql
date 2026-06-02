-- 0013_instant_kind_and_height_fixes.sql
-- Fixes for record types that WERE in METHOD_SCHEMA but silently dropped data.
--
-- 1. heartRateVariabilityRmssd & respiratoryRate are INSTANT records in Health
--    Connect (the client sends `time`, not startTime/endTime), but their schema
--    entries declared kind="interval" and their tables required end_at NOT NULL.
--    The interval insert path read `startTime` (absent) and skipped every row, so
--    no live HRV/respiratory data has landed since the PG cutover (existing rows
--    are backfill-era hourly intervals). The schema kind flips to "instant" in
--    method_schema.py; here we drop the NOT NULL so the instant path (which omits
--    end_at entirely) can insert. Existing rows keep their populated end_at.
--
-- 2. height stored the Health Connect `inMeters` value (e.g. 1.70) in a column
--    named height_cm, so values were 100x mislabelled. Standardise on the
--    canonical SI unit (meters, like distance) by renaming the column and
--    correcting the single legacy row that really was in cm (>10 m is impossible
--    for a human height, so it can only be the 160 cm backfill row).

ALTER TABLE heart_rate_variability ALTER COLUMN end_at DROP NOT NULL;

ALTER TABLE respiratory_rate ALTER COLUMN end_at DROP NOT NULL;

ALTER TABLE height RENAME COLUMN height_cm TO height_m;
UPDATE height SET height_m = height_m / 100.0 WHERE height_m > 10;

-- 3. bloodPressure: the client (Health Connect BloodPressureRecord) sends only
--    systolic + diastolic, never pulse, so every row was skipped (pulse was NOT
--    NULL and absent). pulse joins NULLABLE_COLS in method_schema.py; the column
--    must allow NULL too, otherwise the insert raises a 500 instead of skipping —
--    which would re-block the Changes token.
ALTER TABLE blood_pressure ALTER COLUMN pulse DROP NOT NULL;
