"""Mapping from Health Connect method names → PostgreSQL table layout.

Drives the generic /sync, /fetch, /counts, and /sync DELETE handlers.

Schema entries:
  table:          PG table name.
  kind:           "samples" | "interval" | "instant"
                   - samples: flatten incoming `samples[]` into N rows;
                              upsert by DELETE WHERE source_id = ?; INSERT.
                   - interval: 1 source doc → 1 row with start_at + end_at.
                   - instant:  1 source doc → 1 row with start_at only.
  is_hypertable:  True for tables created with create_hypertable.
                  ON CONFLICT target differs: (start_at, id) vs (id).
  value_cols:     [(source_path, dst_column, sql_type), ...]
                   source_path supports "a.b" dotted access for nested JSON.
  sample_*:       only for kind="samples" (where in the source doc the
                   sample array lives and which field gives the per-sample
                   timestamp).
"""

METHOD_SCHEMA = {
    "heartRate": {
        "table": "heart_rate_sample",
        "kind": "samples",
        "sample_path": "samples",
        "sample_time_field": "time",
        "value_cols": [("beatsPerMinute", "bpm", "smallint")],
    },
    "speed": {
        "table": "speed_sample",
        "kind": "samples",
        "sample_path": "samples",
        "sample_time_field": "time",
        "value_cols": [("speed", "speed_mps", "double precision")],
    },
    "heartRateVariability": {
        "table": "heart_rate_variability",
        "kind": "interval",
        "is_hypertable": True,
        "value_cols": [("rmssd", "rmssd", "double precision")],
    },
    "steps": {
        "table": "steps",
        "kind": "interval",
        "is_hypertable": True,
        "value_cols": [("count", "count", "integer")],
    },
    "distance": {
        "table": "distance",
        "kind": "interval",
        "is_hypertable": True,
        # Modern app sends a plain number in meters; legacy Mongo data had
        # `{"inMeters":…}` unit-objects. `_pick_scalar` handles both.
        "value_cols": [("distance", "meters", "double precision")],
    },
    "totalCaloriesBurned": {
        "table": "total_calories_burned",
        "kind": "interval",
        "is_hypertable": True,
        # Same pattern — app sends a plain kcal number; legacy was `{"inKilocalories":…}`.
        "value_cols": [("energy", "kcal", "double precision")],
    },
    "oxygenSaturation": {
        "table": "oxygen_saturation",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("percentage", "percentage", "smallint")],
    },
    "respiratoryRate": {
        "table": "respiratory_rate",
        "kind": "interval",
        "is_hypertable": False,
        "value_cols": [("rate", "rate", "double precision")],
    },
    "skinTemperature": {
        "table": "skin_temperature",
        "kind": "interval",
        "is_hypertable": False,
        "value_cols": [
            ("temperature", "temperature_c", "double precision"),
            ("baseline", "baseline_c", "double precision"),
        ],
    },
    "stress": {
        "table": "stress",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("score", "score", "smallint")],
    },
    "vitalityScore": {
        "table": "vitality_score",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [
            ("totalScore", "total_score", "double precision"),
            ("sleepScore", "sleep_score", "double precision"),
            ("activityScore", "activity_score", "double precision"),
            ("shrScore", "shr_score", "double precision"),
        ],
    },
    "floorsClimbed": {
        "table": "floors_climbed",
        "kind": "interval",
        "is_hypertable": False,
        "value_cols": [("floors", "floors", "smallint")],
    },
    "sleepSession": {
        "table": "sleep_session",
        "kind": "interval",
        "is_hypertable": True,
        "value_cols": [("stages", "stages", "jsonb")],
    },
    "exerciseSession": {
        "table": "exercise_session",
        "kind": "interval",
        "is_hypertable": True,
        "value_cols": [("exerciseType", "exercise_type", "smallint")],
    },
    "weight": {
        "table": "weight",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("weight", "weight_kg", "double precision")],
    },
    "bodyFat": {
        "table": "body_fat",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("percentage", "percentage", "double precision")],
    },
    "basalMetabolicRate": {
        "table": "basal_metabolic_rate",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("bmr", "bmr", "double precision")],
    },
    "height": {
        "table": "height",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("height", "height_cm", "double precision")],
    },
    "bloodPressure": {
        "table": "blood_pressure",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [
            ("systolic", "systolic", "smallint"),
            ("diastolic", "diastolic", "smallint"),
            ("pulse", "pulse", "smallint"),
        ],
    },
    "vo2Max": {
        "table": "vo2_max",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("vo2Max", "vo2_max", "double precision")],
    },
}


def get_nested(d, dotted):
    """Walk `d` by dotted path. Returns None if any segment is missing."""
    cur = d
    for p in dotted.split("."):
        if not isinstance(cur, dict):
            return None
        cur = cur.get(p)
    return cur


# Health Connect emitted some scalar fields as unit-objects in older payloads
# (e.g. `distance: {inMeters: x, inKilometers: y}`). The modern app pre-extracts
# the canonical unit, but legacy data still flows through /fetch and through
# the migration script. Order = preferred SI / canonical unit per known field.
_UNIT_PRIORITY = (
    "inMetersPerSecond", "inMeters", "inKilometers",
    "inKilocalories", "inCalories", "inJoules", "inKilojoules",
    "inCelsius", "inFahrenheit",
    "inMillimetersOfMercury",
    "inKilocaloriesPerDay",
)


def pick_scalar(v):
    """If v is a scalar, return it; if a Health Connect unit-object dict,
    pick the preferred unit; else None."""
    if v is None or not isinstance(v, dict):
        return v
    for k in _UNIT_PRIORITY:
        if k in v:
            return v[k]
    return None


def cast_for_col(v, sql_type):
    """smallint/integer columns reject float (e.g. `98.0` from oxygenSaturation).
    Coerce numeric-typed columns so COPY/INSERT serialization succeeds."""
    if v is None:
        return None
    if sql_type in ("smallint", "integer"):
        return int(v)
    return v


# PG columns that are nullable per pg/migrations/0001_init.sql.
# A None source value for any other column forces a row skip on /sync.
NULLABLE_COLS = {
    "skin_temperature": {"baseline_c"},
    "sleep_session": {"stages"},
    "vitality_score": {"total_score", "sleep_score", "activity_score", "shr_score"},
}


def normalize_method(method):
    """Apply the same first-letter-lowercase normalisation the old API did,
    and return the matched schema entry or None.
    """
    if not method:
        return None, None
    norm = method[0].lower() + method[1:]
    return norm, METHOD_SCHEMA.get(norm)
