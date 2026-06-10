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
    "heartRateVariabilityRmssd": {
        # Client method is HeartRateVariabilityRmssd -> normalised
        # heartRateVariabilityRmssd (was mis-keyed "heartRateVariability").
        # HC's record is INSTANT (single `time`), not interval, and the client
        # sends the value under `heartRateVariabilityMillis`. end_at is nullable
        # since 0013 so the instant path (no end_at) inserts; legacy backfill
        # rows keep their populated end_at.
        "table": "heart_rate_variability",
        "kind": "instant",
        "is_hypertable": True,
        "value_cols": [("heartRateVariabilityMillis", "rmssd", "double precision")],
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
        # HC RespiratoryRateRecord is INSTANT (client sends `time`); was wrongly
        # kind="interval" so the interval path read absent `startTime` and skipped
        # every row. end_at made nullable in 0013 for the instant insert.
        "table": "respiratory_rate",
        "kind": "instant",
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
        # Client sends `basalMetabolicRate` (inKilocaloriesPerDay), not `bmr`.
        "value_cols": [("basalMetabolicRate", "bmr", "double precision")],
    },
    "height": {
        "table": "height",
        "kind": "instant",
        "is_hypertable": False,
        # Client sends meters (record.height.inMeters); column renamed to
        # height_m in 0013 (was height_cm but held metres -> 100x mislabel).
        "value_cols": [("height", "height_m", "double precision")],
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
        # Client sends `vo2MillilitersPerMinuteKilogram`, not `vo2Max`.
        "value_cols": [("vo2MillilitersPerMinuteKilogram", "vo2_max", "double precision")],
    },

    # -----------------------------------------------------------------------
    # Full Health Connect coverage (0012). Every type the Android client sends
    # now has an entry so /sync returns 200 (not 400) and the client's Changes
    # token can advance. Value-column source paths match RecordSerializer.kt.
    # -----------------------------------------------------------------------

    # --- samples ---
    "power": {
        "table": "power_sample",
        "kind": "samples",
        "sample_path": "samples",
        "sample_time_field": "time",
        "value_cols": [("power", "watts", "double precision")],
    },
    "stepsCadence": {
        "table": "steps_cadence_sample",
        "kind": "samples",
        "sample_path": "samples",
        "sample_time_field": "time",
        "value_cols": [("rate", "rate", "double precision")],
    },
    "cyclingPedalingCadence": {
        "table": "cycling_pedaling_cadence_sample",
        "kind": "samples",
        "sample_path": "samples",
        "sample_time_field": "time",
        "value_cols": [("revolutionsPerMinute", "rpm", "double precision")],
    },

    # --- interval ---
    "activeCaloriesBurned": {
        "table": "active_calories_burned",
        "kind": "interval",
        "is_hypertable": False,
        "value_cols": [("energy", "kcal", "double precision")],
    },
    "elevationGained": {
        "table": "elevation_gained",
        "kind": "interval",
        "is_hypertable": False,
        "value_cols": [("elevation", "meters", "double precision")],
    },
    "hydration": {
        "table": "hydration",
        "kind": "interval",
        "is_hypertable": False,
        "value_cols": [("volume", "liters", "double precision")],
    },
    "menstruationPeriod": {
        "table": "menstruation_period",
        "kind": "interval",
        "is_hypertable": False,
        "value_cols": [],  # start_at + end_at only
    },
    "mindfulnessSession": {
        "table": "mindfulness_session",
        "kind": "interval",
        "is_hypertable": False,
        "value_cols": [("mindfulnessSessionType", "session_type", "smallint")],
    },
    "nutrition": {
        # Flat top-level nutrient fields -> explicit nullable columns (lossless,
        # queryable). All nutrient cols are in NULLABLE_COLS so a record carrying
        # only some fields still inserts.
        "table": "nutrition",
        "kind": "interval",
        "is_hypertable": False,
        "value_cols": [
            ("name", "name", "text"),
            ("energy", "energy_kcal", "double precision"),
            ("totalFat", "total_fat_g", "double precision"),
            ("totalCarbohydrate", "total_carbohydrate_g", "double precision"),
            ("protein", "protein_g", "double precision"),
            ("dietaryFiber", "dietary_fiber_g", "double precision"),
            ("sugar", "sugar_g", "double precision"),
            ("sodium", "sodium_g", "double precision"),
            ("potassium", "potassium_g", "double precision"),
            ("cholesterol", "cholesterol_g", "double precision"),
            ("saturatedFat", "saturated_fat_g", "double precision"),
            ("unsaturatedFat", "unsaturated_fat_g", "double precision"),
            ("calcium", "calcium_g", "double precision"),
            ("iron", "iron_g", "double precision"),
            ("vitaminA", "vitamin_a_g", "double precision"),
            ("vitaminC", "vitamin_c_g", "double precision"),
            ("mealType", "meal_type", "smallint"),
        ],
    },
    "plannedExerciseSession": {
        "table": "planned_exercise_session",
        "kind": "interval",
        "is_hypertable": False,
        # blocks is a nested array under a single key -> jsonb (nullable).
        "value_cols": [
            ("exerciseType", "exercise_type", "smallint"),
            ("blocks", "blocks", "jsonb"),
        ],
    },
    "wheelchairPushes": {
        "table": "wheelchair_pushes",
        "kind": "interval",
        "is_hypertable": False,
        "value_cols": [("count", "count", "integer")],
    },

    # --- instant ---
    "basalBodyTemperature": {
        "table": "basal_body_temperature",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("temperature", "temperature_c", "double precision")],
    },
    "bloodGlucose": {
        "table": "blood_glucose",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("level", "level_mmol_l", "double precision")],
    },
    "bodyTemperature": {
        "table": "body_temperature",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("temperature", "temperature_c", "double precision")],
    },
    "bodyWaterMass": {
        "table": "body_water_mass",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("mass", "mass_kg", "double precision")],
    },
    "boneMass": {
        "table": "bone_mass",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("mass", "mass_kg", "double precision")],
    },
    "cervicalMucus": {
        "table": "cervical_mucus",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [
            ("appearance", "appearance", "smallint"),
            ("sensation", "sensation", "smallint"),
        ],
    },
    "intermenstrualBleeding": {
        "table": "intermenstrual_bleeding",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [],  # time only
    },
    "leanBodyMass": {
        "table": "lean_body_mass",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("mass", "mass_kg", "double precision")],
    },
    "menstruationFlow": {
        "table": "menstruation_flow",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("flow", "flow", "smallint")],
    },
    "ovulationTest": {
        "table": "ovulation_test",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("result", "result", "smallint")],
    },
    "restingHeartRate": {
        "table": "resting_heart_rate",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("beatsPerMinute", "bpm", "smallint")],
    },
    "sexualActivity": {
        "table": "sexual_activity",
        "kind": "instant",
        "is_hypertable": False,
        "value_cols": [("protectionUsed", "protection_used", "smallint")],
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
    # vitalityScore / nutrition: EVERY value column is optional in the payload,
    # so each nullable set is exactly its value_cols dst names — derive them
    # rather than hand-listing names that would silently drift (a dropped name
    # skips records → re-blocks the Changes token).
    "vitality_score": {dst for _, dst, _ in METHOD_SCHEMA["vitalityScore"]["value_cols"]},
    "nutrition": {dst for _, dst, _ in METHOD_SCHEMA["nutrition"]["value_cols"]},
    # bloodPressure: HC sends only systolic+diastolic, never pulse.
    "blood_pressure": {"pulse"},
    # planned exercise: blocks may be absent/empty (exercise_type is required,
    # so this is a strict subset of value_cols — keep it explicit).
    "planned_exercise_session": {"blocks"},
}


def normalize_method(method):
    """Apply the same first-letter-lowercase normalisation the old API did,
    and return the matched schema entry or None.
    """
    if not method:
        return None, None
    norm = method[0].lower() + method[1:]
    return norm, METHOD_SCHEMA.get(norm)
