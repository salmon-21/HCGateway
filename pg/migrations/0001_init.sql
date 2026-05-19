-- HCGateway PostgreSQL schema (PG 17 + TimescaleDB)
-- All persisted times are timestamptz (UTC under the hood).
-- Day-boundary logic uses AT TIME ZONE 'Asia/Tokyo' where applicable.

CREATE EXTENSION IF NOT EXISTS timescaledb;

-- ---------------------------------------------------------------------------
-- Auth
-- ---------------------------------------------------------------------------
CREATE TABLE users (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  username    text UNIQUE NOT NULL,
  password    text NOT NULL,
  token       text,
  refresh     text,
  expiry      timestamptz,
  fcm_token   text
);
CREATE INDEX users_token_idx   ON users (token);
CREATE INDEX users_refresh_idx ON users (refresh);

-- ---------------------------------------------------------------------------
-- Sample-style (flattened): 1 source record → N rows.
-- No primary key on sample tables — DELETE+INSERT keyed by source_id.
-- source_id index is required for the per-source DELETE.
-- ---------------------------------------------------------------------------
CREATE TABLE heart_rate_sample (
  time        timestamptz NOT NULL,
  user_id     uuid        NOT NULL,
  bpm         smallint    NOT NULL,
  source_id   uuid        NOT NULL,
  app         text        NOT NULL
);
SELECT create_hypertable('heart_rate_sample', 'time',
                         chunk_time_interval => INTERVAL '7 days');
CREATE INDEX heart_rate_sample_user_time_idx
  ON heart_rate_sample (user_id, time DESC);
CREATE INDEX heart_rate_sample_source_id_idx
  ON heart_rate_sample (source_id);

CREATE TABLE speed_sample (
  time        timestamptz NOT NULL,
  user_id     uuid        NOT NULL,
  speed_mps   double precision NOT NULL,
  source_id   uuid        NOT NULL,
  app         text        NOT NULL
);
SELECT create_hypertable('speed_sample', 'time',
                         chunk_time_interval => INTERVAL '30 days');
CREATE INDEX speed_sample_user_time_idx
  ON speed_sample (user_id, time DESC);
CREATE INDEX speed_sample_source_id_idx
  ON speed_sample (source_id);

-- ---------------------------------------------------------------------------
-- Interval hypertables (1 row per source record).
-- Hypertable PK must include partition key → (start_at, id).
-- id is UUID and effectively unique on its own; the composite just satisfies TS.
-- ---------------------------------------------------------------------------
CREATE TABLE heart_rate_variability (
  start_at    timestamptz NOT NULL,
  id          uuid        NOT NULL,
  user_id     uuid        NOT NULL,
  end_at      timestamptz NOT NULL,
  rmssd       double precision NOT NULL,
  app         text        NOT NULL,
  PRIMARY KEY (start_at, id)
);
SELECT create_hypertable('heart_rate_variability', 'start_at',
                         chunk_time_interval => INTERVAL '30 days');
CREATE INDEX heart_rate_variability_user_start_idx
  ON heart_rate_variability (user_id, start_at DESC);

CREATE TABLE steps (
  start_at    timestamptz NOT NULL,
  id          uuid        NOT NULL,
  user_id     uuid        NOT NULL,
  end_at      timestamptz NOT NULL,
  count       integer     NOT NULL,
  app         text        NOT NULL,
  PRIMARY KEY (start_at, id)
);
SELECT create_hypertable('steps', 'start_at',
                         chunk_time_interval => INTERVAL '30 days');
CREATE INDEX steps_user_start_idx ON steps (user_id, start_at DESC);

CREATE TABLE distance (
  start_at    timestamptz NOT NULL,
  id          uuid        NOT NULL,
  user_id     uuid        NOT NULL,
  end_at      timestamptz NOT NULL,
  meters      double precision NOT NULL,
  app         text        NOT NULL,
  PRIMARY KEY (start_at, id)
);
SELECT create_hypertable('distance', 'start_at',
                         chunk_time_interval => INTERVAL '30 days');
CREATE INDEX distance_user_start_idx ON distance (user_id, start_at DESC);

CREATE TABLE total_calories_burned (
  start_at    timestamptz NOT NULL,
  id          uuid        NOT NULL,
  user_id     uuid        NOT NULL,
  end_at      timestamptz NOT NULL,
  kcal        double precision NOT NULL,
  app         text        NOT NULL,
  PRIMARY KEY (start_at, id)
);
SELECT create_hypertable('total_calories_burned', 'start_at',
                         chunk_time_interval => INTERVAL '30 days');
CREATE INDEX total_calories_burned_user_start_idx
  ON total_calories_burned (user_id, start_at DESC);

CREATE TABLE sleep_session (
  start_at    timestamptz NOT NULL,
  id          uuid        NOT NULL,
  user_id     uuid        NOT NULL,
  end_at      timestamptz NOT NULL,
  stages      jsonb,
  app         text        NOT NULL,
  PRIMARY KEY (start_at, id)
);
SELECT create_hypertable('sleep_session', 'start_at',
                         chunk_time_interval => INTERVAL '90 days');
CREATE INDEX sleep_session_user_start_idx
  ON sleep_session (user_id, start_at DESC);

CREATE TABLE exercise_session (
  start_at        timestamptz NOT NULL,
  id              uuid        NOT NULL,
  user_id         uuid        NOT NULL,
  end_at          timestamptz NOT NULL,
  exercise_type   smallint    NOT NULL,
  app             text        NOT NULL,
  PRIMARY KEY (start_at, id)
);
SELECT create_hypertable('exercise_session', 'start_at',
                         chunk_time_interval => INTERVAL '90 days');
CREATE INDEX exercise_session_user_start_idx
  ON exercise_session (user_id, start_at DESC);

-- ---------------------------------------------------------------------------
-- Plain tables (low volume — hypertable overhead not worth it).
-- end_at nullable where source records have null (oxygenSaturation, weight, etc.)
-- ---------------------------------------------------------------------------
CREATE TABLE oxygen_saturation (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  percentage  smallint    NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX oxygen_saturation_user_start_idx
  ON oxygen_saturation (user_id, start_at DESC);

CREATE TABLE respiratory_rate (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  end_at      timestamptz NOT NULL,
  rate        double precision NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX respiratory_rate_user_start_idx
  ON respiratory_rate (user_id, start_at DESC);

CREATE TABLE skin_temperature (
  id            uuid PRIMARY KEY,
  user_id       uuid        NOT NULL,
  start_at      timestamptz NOT NULL,
  end_at        timestamptz NOT NULL,
  temperature_c double precision NOT NULL,
  baseline_c    double precision,
  app           text        NOT NULL
);
CREATE INDEX skin_temperature_user_start_idx
  ON skin_temperature (user_id, start_at DESC);

CREATE TABLE stress (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  score       smallint    NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX stress_user_start_idx ON stress (user_id, start_at DESC);

CREATE TABLE vitality_score (
  id              uuid PRIMARY KEY,
  user_id         uuid        NOT NULL,
  start_at        timestamptz NOT NULL,
  total_score     double precision,
  sleep_score     double precision,
  activity_score  double precision,
  shr_score       double precision,
  app             text        NOT NULL
);
CREATE INDEX vitality_score_user_start_idx
  ON vitality_score (user_id, start_at DESC);

CREATE TABLE floors_climbed (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  end_at      timestamptz NOT NULL,
  floors      smallint    NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX floors_climbed_user_start_idx
  ON floors_climbed (user_id, start_at DESC);

CREATE TABLE weight (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  weight_kg   double precision NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX weight_user_start_idx ON weight (user_id, start_at DESC);

CREATE TABLE body_fat (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  percentage  double precision NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX body_fat_user_start_idx ON body_fat (user_id, start_at DESC);

CREATE TABLE basal_metabolic_rate (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  bmr         double precision NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX basal_metabolic_rate_user_start_idx
  ON basal_metabolic_rate (user_id, start_at DESC);

CREATE TABLE height (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  height_cm   double precision NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX height_user_start_idx ON height (user_id, start_at DESC);

CREATE TABLE blood_pressure (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  systolic    smallint    NOT NULL,
  diastolic   smallint    NOT NULL,
  pulse       smallint    NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX blood_pressure_user_start_idx
  ON blood_pressure (user_id, start_at DESC);

CREATE TABLE vo2_max (
  id          uuid PRIMARY KEY,
  user_id     uuid        NOT NULL,
  start_at    timestamptz NOT NULL,
  vo2_max     double precision NOT NULL,
  app         text        NOT NULL
);
CREATE INDEX vo2_max_user_start_idx ON vo2_max (user_id, start_at DESC);
