-- 0014_record_counts.sql
-- Materialised per-(user, method) record counts, maintained transactionally by
-- the /sync and DELETE /sync write paths in routes.py.
--
-- Why: /counts used to run 43 live aggregate queries per request; the
-- count(DISTINCT source_id) over heart_rate_sample (1.4M rows, 100+ chunks)
-- alone took ~1.2 s on the RPi, putting the endpoint at ~1.5 s. Counts are
-- knowable at write time, so they are bumped there instead:
--   - samples sync:   +(distinct source_ids written) -(distinct deleted)
--   - record upsert:  +(rows actually INSERTed, via RETURNING xmax = 0)
--   - server delete:  -(distinct source_ids / rows removed)
--
-- n means what /counts always reported: distinct source_id for kind=samples
-- (= Health Connect records), row count for interval/instant.
--
-- The seed below is RE-RUNNABLE (ON CONFLICT DO UPDATE overwrites with a fresh
-- recount). Re-run it after any write that bypasses the API — e.g. the
-- backfill/ importers, which COPY straight into the tables.
-- Statements are generated from METHOD_SCHEMA (api/method_schema.py); regenerate
-- when types are added rather than appending by hand.

CREATE TABLE IF NOT EXISTS record_counts (
    user_id uuid   NOT NULL,
    method  text   NOT NULL,
    n       bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, method)
);

INSERT INTO record_counts (user_id, method, n)
SELECT user_id, 'heartRate', count(DISTINCT source_id) FROM heart_rate_sample GROUP BY user_id
UNION ALL
SELECT user_id, 'speed', count(DISTINCT source_id) FROM speed_sample GROUP BY user_id
UNION ALL
SELECT user_id, 'heartRateVariabilityRmssd', count(*) FROM heart_rate_variability GROUP BY user_id
UNION ALL
SELECT user_id, 'steps', count(*) FROM steps GROUP BY user_id
UNION ALL
SELECT user_id, 'distance', count(*) FROM distance GROUP BY user_id
UNION ALL
SELECT user_id, 'totalCaloriesBurned', count(*) FROM total_calories_burned GROUP BY user_id
UNION ALL
SELECT user_id, 'oxygenSaturation', count(*) FROM oxygen_saturation GROUP BY user_id
UNION ALL
SELECT user_id, 'respiratoryRate', count(*) FROM respiratory_rate GROUP BY user_id
UNION ALL
SELECT user_id, 'skinTemperature', count(*) FROM skin_temperature GROUP BY user_id
UNION ALL
SELECT user_id, 'stress', count(*) FROM stress GROUP BY user_id
UNION ALL
SELECT user_id, 'vitalityScore', count(*) FROM vitality_score GROUP BY user_id
UNION ALL
SELECT user_id, 'floorsClimbed', count(*) FROM floors_climbed GROUP BY user_id
UNION ALL
SELECT user_id, 'sleepSession', count(*) FROM sleep_session GROUP BY user_id
UNION ALL
SELECT user_id, 'exerciseSession', count(*) FROM exercise_session GROUP BY user_id
UNION ALL
SELECT user_id, 'weight', count(*) FROM weight GROUP BY user_id
UNION ALL
SELECT user_id, 'bodyFat', count(*) FROM body_fat GROUP BY user_id
UNION ALL
SELECT user_id, 'basalMetabolicRate', count(*) FROM basal_metabolic_rate GROUP BY user_id
UNION ALL
SELECT user_id, 'height', count(*) FROM height GROUP BY user_id
UNION ALL
SELECT user_id, 'bloodPressure', count(*) FROM blood_pressure GROUP BY user_id
UNION ALL
SELECT user_id, 'vo2Max', count(*) FROM vo2_max GROUP BY user_id
UNION ALL
SELECT user_id, 'power', count(DISTINCT source_id) FROM power_sample GROUP BY user_id
UNION ALL
SELECT user_id, 'stepsCadence', count(DISTINCT source_id) FROM steps_cadence_sample GROUP BY user_id
UNION ALL
SELECT user_id, 'cyclingPedalingCadence', count(DISTINCT source_id) FROM cycling_pedaling_cadence_sample GROUP BY user_id
UNION ALL
SELECT user_id, 'activeCaloriesBurned', count(*) FROM active_calories_burned GROUP BY user_id
UNION ALL
SELECT user_id, 'elevationGained', count(*) FROM elevation_gained GROUP BY user_id
UNION ALL
SELECT user_id, 'hydration', count(*) FROM hydration GROUP BY user_id
UNION ALL
SELECT user_id, 'menstruationPeriod', count(*) FROM menstruation_period GROUP BY user_id
UNION ALL
SELECT user_id, 'mindfulnessSession', count(*) FROM mindfulness_session GROUP BY user_id
UNION ALL
SELECT user_id, 'nutrition', count(*) FROM nutrition GROUP BY user_id
UNION ALL
SELECT user_id, 'plannedExerciseSession', count(*) FROM planned_exercise_session GROUP BY user_id
UNION ALL
SELECT user_id, 'wheelchairPushes', count(*) FROM wheelchair_pushes GROUP BY user_id
UNION ALL
SELECT user_id, 'basalBodyTemperature', count(*) FROM basal_body_temperature GROUP BY user_id
UNION ALL
SELECT user_id, 'bloodGlucose', count(*) FROM blood_glucose GROUP BY user_id
UNION ALL
SELECT user_id, 'bodyTemperature', count(*) FROM body_temperature GROUP BY user_id
UNION ALL
SELECT user_id, 'bodyWaterMass', count(*) FROM body_water_mass GROUP BY user_id
UNION ALL
SELECT user_id, 'boneMass', count(*) FROM bone_mass GROUP BY user_id
UNION ALL
SELECT user_id, 'cervicalMucus', count(*) FROM cervical_mucus GROUP BY user_id
UNION ALL
SELECT user_id, 'intermenstrualBleeding', count(*) FROM intermenstrual_bleeding GROUP BY user_id
UNION ALL
SELECT user_id, 'leanBodyMass', count(*) FROM lean_body_mass GROUP BY user_id
UNION ALL
SELECT user_id, 'menstruationFlow', count(*) FROM menstruation_flow GROUP BY user_id
UNION ALL
SELECT user_id, 'ovulationTest', count(*) FROM ovulation_test GROUP BY user_id
UNION ALL
SELECT user_id, 'restingHeartRate', count(*) FROM resting_heart_rate GROUP BY user_id
UNION ALL
SELECT user_id, 'sexualActivity', count(*) FROM sexual_activity GROUP BY user_id
ON CONFLICT (user_id, method) DO UPDATE SET n = EXCLUDED.n;
