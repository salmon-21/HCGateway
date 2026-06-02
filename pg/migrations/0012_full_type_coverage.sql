-- 0012_full_type_coverage.sql
-- Add tables for the remaining Health Connect record types the Android client
-- sends but the API had no METHOD_SCHEMA entry for. Without these, /sync/<type>
-- returned 400 ("unknown method"); SyncRepository counts a 400 as a failed type
-- and then refuses to advance the Changes API token, so the same window was
-- re-synced forever. One plain table per type (the per-type design, matching the
-- existing 17 tables) keeps the generic METHOD_SCHEMA-driven /sync, /fetch,
-- /counts handlers unchanged.
--
-- Conventions (mirroring 0001_init.sql):
--   * Plain tables (low volume for this deployment) — PK (id) for interval/instant,
--     no PK for sample tables (DELETE+COPY keyed by source_id, like heart_rate_sample).
--   * Interval end_at is NULLABLE here (defensive): a malformed payload missing
--     endTime then inserts NULL instead of raising a 500 that would re-block the
--     Changes token. Genuine interval records always carry endTime.
--   * Value columns hold the canonical/SI unit the client already pre-extracts
--     (meters, kcal, kg, °C, mmol/L, …), matching the distance/weight pattern.
--   * No grafana_ro GRANTs here: this file also runs on a fresh container via
--     docker-entrypoint-initdb.d, before the manual grafana_ro role exists. The
--     ALTER DEFAULT PRIVILEGES from docs/grafana-datasource-role.md cascades SELECT
--     to new tables on the live DB.

-- ---------------------------------------------------------------------------
-- Sample-style (1 source record -> N rows; DELETE+INSERT keyed by source_id).
-- ---------------------------------------------------------------------------
CREATE TABLE power_sample (
  time        timestamptz NOT NULL,
  user_id     uuid        NOT NULL,
  watts       double precision NOT NULL,
  source_id   uuid        NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX power_sample_user_time_idx ON power_sample (user_id, time DESC);
CREATE INDEX power_sample_source_id_idx ON power_sample (source_id);

CREATE TABLE steps_cadence_sample (
  time        timestamptz NOT NULL,
  user_id     uuid        NOT NULL,
  rate        double precision NOT NULL,
  source_id   uuid        NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX steps_cadence_sample_user_time_idx ON steps_cadence_sample (user_id, time DESC);
CREATE INDEX steps_cadence_sample_source_id_idx ON steps_cadence_sample (source_id);

CREATE TABLE cycling_pedaling_cadence_sample (
  time        timestamptz NOT NULL,
  user_id     uuid        NOT NULL,
  rpm         double precision NOT NULL,
  source_id   uuid        NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX cycling_pedaling_cadence_sample_user_time_idx ON cycling_pedaling_cadence_sample (user_id, time DESC);
CREATE INDEX cycling_pedaling_cadence_sample_source_id_idx ON cycling_pedaling_cadence_sample (source_id);

-- ---------------------------------------------------------------------------
-- Interval (1 row per source record, start_at + end_at).
-- ---------------------------------------------------------------------------
CREATE TABLE active_calories_burned (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  end_at      timestamptz,
  kcal        double precision NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX active_calories_burned_user_start_idx ON active_calories_burned (user_id, start_at DESC);

CREATE TABLE elevation_gained (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  end_at      timestamptz,
  meters      double precision NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX elevation_gained_user_start_idx ON elevation_gained (user_id, start_at DESC);

CREATE TABLE hydration (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  end_at      timestamptz,
  liters      double precision NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX hydration_user_start_idx ON hydration (user_id, start_at DESC);

CREATE TABLE menstruation_period (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  end_at      timestamptz,
  app         text        NOT NULL
);
CREATE INDEX menstruation_period_user_start_idx ON menstruation_period (user_id, start_at DESC);

CREATE TABLE mindfulness_session (
  id            uuid PRIMARY KEY,
  user_id       uuid        NOT NULL,
  start_at      timestamptz NOT NULL,
  end_at        timestamptz,
  session_type  smallint    NOT NULL,
  app           text        NOT NULL
);
CREATE INDEX mindfulness_session_user_start_idx ON mindfulness_session (user_id, start_at DESC);

-- Nutrition: Health Connect sends each nutrient as a flat top-level field (no
-- single wrapping key), so explicit nullable columns keep it lossless and
-- queryable without a special handler path. All nutrient columns are nullable
-- (each is optional in the payload) and listed in NULLABLE_COLS.
CREATE TABLE nutrition (
  id                  uuid PRIMARY KEY,
  user_id             uuid        NOT NULL,
  start_at            timestamptz NOT NULL,
  end_at              timestamptz,
  name                text,
  energy_kcal         double precision,
  total_fat_g         double precision,
  total_carbohydrate_g double precision,
  protein_g           double precision,
  dietary_fiber_g     double precision,
  sugar_g             double precision,
  sodium_g            double precision,
  potassium_g         double precision,
  cholesterol_g       double precision,
  saturated_fat_g     double precision,
  unsaturated_fat_g   double precision,
  calcium_g           double precision,
  iron_g              double precision,
  vitamin_a_g         double precision,
  vitamin_c_g         double precision,
  meal_type           smallint,
  app                 text        NOT NULL
);
CREATE INDEX nutrition_user_start_idx ON nutrition (user_id, start_at DESC);

-- Planned exercise: exercise_type is a scalar; blocks is a nested array under a
-- single "blocks" key, stored as jsonb (same pattern as sleep_session.stages).
CREATE TABLE planned_exercise_session (
  id            uuid PRIMARY KEY,
  user_id       uuid        NOT NULL,
  start_at      timestamptz NOT NULL,
  end_at        timestamptz,
  exercise_type smallint    NOT NULL,
  blocks        jsonb,
  app           text        NOT NULL
);
CREATE INDEX planned_exercise_session_user_start_idx ON planned_exercise_session (user_id, start_at DESC);

CREATE TABLE wheelchair_pushes (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  end_at      timestamptz,
  count       integer     NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX wheelchair_pushes_user_start_idx ON wheelchair_pushes (user_id, start_at DESC);

-- ---------------------------------------------------------------------------
-- Instant (1 row per source record, start_at only — source has `time`).
-- ---------------------------------------------------------------------------
CREATE TABLE basal_body_temperature (
  id            uuid PRIMARY KEY,
  user_id       uuid        NOT NULL,
  start_at      timestamptz NOT NULL,
  temperature_c double precision NOT NULL,
  app           text        NOT NULL
);
CREATE INDEX basal_body_temperature_user_start_idx ON basal_body_temperature (user_id, start_at DESC);

CREATE TABLE blood_glucose (
  id            uuid PRIMARY KEY,
  user_id       uuid        NOT NULL,
  start_at      timestamptz NOT NULL,
  level_mmol_l  double precision NOT NULL,
  app           text        NOT NULL
);
CREATE INDEX blood_glucose_user_start_idx ON blood_glucose (user_id, start_at DESC);

CREATE TABLE body_temperature (
  id            uuid PRIMARY KEY,
  user_id       uuid        NOT NULL,
  start_at      timestamptz NOT NULL,
  temperature_c double precision NOT NULL,
  app           text        NOT NULL
);
CREATE INDEX body_temperature_user_start_idx ON body_temperature (user_id, start_at DESC);

CREATE TABLE body_water_mass (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  mass_kg     double precision NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX body_water_mass_user_start_idx ON body_water_mass (user_id, start_at DESC);

CREATE TABLE bone_mass (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  mass_kg     double precision NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX bone_mass_user_start_idx ON bone_mass (user_id, start_at DESC);

CREATE TABLE cervical_mucus (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  appearance  smallint    NOT NULL,
  sensation   smallint    NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX cervical_mucus_user_start_idx ON cervical_mucus (user_id, start_at DESC);

CREATE TABLE intermenstrual_bleeding (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX intermenstrual_bleeding_user_start_idx ON intermenstrual_bleeding (user_id, start_at DESC);

CREATE TABLE lean_body_mass (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  mass_kg     double precision NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX lean_body_mass_user_start_idx ON lean_body_mass (user_id, start_at DESC);

CREATE TABLE menstruation_flow (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  flow        smallint    NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX menstruation_flow_user_start_idx ON menstruation_flow (user_id, start_at DESC);

CREATE TABLE ovulation_test (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  result      smallint    NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX ovulation_test_user_start_idx ON ovulation_test (user_id, start_at DESC);

CREATE TABLE resting_heart_rate (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  bpm         smallint    NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX resting_heart_rate_user_start_idx ON resting_heart_rate (user_id, start_at DESC);

CREATE TABLE sexual_activity (
  id              uuid PRIMARY KEY,
  user_id         uuid        NOT NULL,
  start_at        timestamptz NOT NULL,
  protection_used smallint    NOT NULL,
  app             text        NOT NULL
);
CREATE INDEX sexual_activity_user_start_idx ON sexual_activity (user_id, start_at DESC);
