"""Import Samsung Health CSV export into PostgreSQL.

Fills gaps that Health Connect export doesn't cover. Reads CSVs under
SH_BASE/samsunghealth_<timestamp>/ and writes via COPY to the live PG
schema. Idempotent by datauuid + JST-day gap detection (re-running only
inserts records on days not yet covered).

Usage:
    python3 samsung-health.py                       # all 16 types
    python3 samsung-health.py heartRate stress       # selected types only
Env:
    SH_BASE              parent dir (default /tmp/shealth_export)
    POSTGRES_URI         PG connection (default localhost:5432)
    BACKFILL_USERNAME    user to target (default salmon21)
"""
import csv
import glob
import json
import os
import re
import sys
import uuid as _uuid
from collections import defaultdict
from datetime import datetime, timezone, timedelta

sys.path.insert(0, os.path.dirname(__file__))
from _pg_writer import (
    connect, get_user_id,
    existing_ids, existing_days_jst, is_gap_jst, copy_rows,
)


SH_BASE = os.environ.get("SH_BASE", "/tmp/shealth_export")
JST = timezone(timedelta(hours=9))


def _resolve_sh_dir():
    candidates = sorted(glob.glob(os.path.join(SH_BASE, "samsunghealth_*")))
    if not candidates:
        raise FileNotFoundError(f"No samsunghealth_* export under {SH_BASE}")
    return candidates[-1]


SH_DIR = _resolve_sh_dir()
_m = re.search(r"_(\d{14})$", os.path.basename(SH_DIR))
if not _m:
    raise ValueError(f"Cannot parse timestamp from {SH_DIR}")
SH_TS = _m.group(1)
print(f"Using export: {SH_DIR}")


# ---------------------------------------------------------------------------
# CSV / time helpers
# ---------------------------------------------------------------------------

def parse_sh_time(timestr, offset_str):
    """Samsung Health timestamp + UTC±HHMM offset → UTC datetime."""
    if not timestr:
        return None
    dt = datetime.strptime(timestr.strip(), "%Y-%m-%d %H:%M:%S.%f")
    if offset_str and offset_str.startswith("UTC"):
        sign = 1 if '+' in offset_str else -1
        nums = offset_str.replace("UTC", "").replace("+", "").replace("-", "")
        hours = int(nums[:2])
        mins = int(nums[2:4]) if len(nums) >= 4 else 0
        dt = dt.replace(tzinfo=timezone(timedelta(hours=hours, minutes=mins) * sign))
    else:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def read_csv(filename):
    """Read a Samsung Health CSV, skipping the first metadata line."""
    path = os.path.join(SH_DIR, filename.format(SH_TS=SH_TS))
    with open(path, encoding='utf-8-sig') as f:
        f.readline()
        return list(csv.DictReader(f))


# Globals populated in main() before importers run.
PG = None
USER_ID = None


# ---------------------------------------------------------------------------
# Per-method importers
# ---------------------------------------------------------------------------

def import_heart_rate():
    """Each HR reading → row in heart_rate_sample. Source records are
    grouped into 1-hour buckets so the source_id matches the live API's
    "1-hour POST" shape."""
    have_sources = existing_ids(PG, "heart_rate_sample", USER_ID,
                                id_col="DISTINCT source_id")
    have_days = existing_days_jst(PG, "heart_rate_sample", USER_ID, "time")

    rows = read_csv("com.samsung.shealth.tracker.heart_rate.{SH_TS}.csv")

    hourly = defaultdict(list)
    for r in rows:
        start_time = r.get("com.samsung.health.heart_rate.start_time", "").strip()
        hr_val = r.get("com.samsung.health.heart_rate.heart_rate", "").strip()
        offset = r.get("com.samsung.health.heart_rate.time_offset", "").strip()
        datauuid = r.get("com.samsung.health.heart_rate.datauuid", "").strip()
        pkg = r.get("com.samsung.health.heart_rate.pkg_name", "").strip()
        if not start_time or not hr_val:
            continue
        dt = parse_sh_time(start_time, offset)
        if dt is None:
            continue
        hour_key = dt.replace(minute=0, second=0, microsecond=0)
        hourly[hour_key].append({
            "bpm": int(float(hr_val)), "time": dt,
            "uuid": datauuid, "pkg": pkg,
        })

    out = []
    for hour_start, samples in hourly.items():
        source_id = samples[0]["uuid"] or str(_uuid.uuid4())
        if source_id in have_sources:
            continue
        if not is_gap_jst(hour_start, have_days):
            continue
        app = samples[0]["pkg"] or "com.sec.android.app.shealth"
        for s in sorted(samples, key=lambda x: x["time"]):
            out.append((s["time"], USER_ID, s["bpm"], source_id, app))

    n = copy_rows(PG, "heart_rate_sample",
                  ["time", "user_id", "bpm", "source_id", "app"], out)
    print(f"  heart_rate_sample: {n} samples")


def import_sleep():
    """sleep + sleep_combined + sleep_stage → sleep_session.

    `sleep_combined` groups multiple sleep segments into one session.
    Segments without a combined_id are imported as standalone sessions.
    """
    have = existing_ids(PG, "sleep_session", USER_ID)
    have_days = existing_days_jst(PG, "sleep_session", USER_ID, "start_at")

    STAGE_MAP = {"40001": 1, "40002": 4, "40003": 5, "40004": 6}

    stage_rows = read_csv("com.samsung.health.sleep_stage.{SH_TS}.csv")
    stages_by_sleep = defaultdict(list)
    for r in stage_rows:
        sleep_id = r.get("sleep_id", "").strip()
        if sleep_id:
            stages_by_sleep[sleep_id].append(r)

    sleep_rows = read_csv("com.samsung.shealth.sleep.{SH_TS}.csv")
    combined_groups = defaultdict(list)
    standalone = []
    for r in sleep_rows:
        cid = r.get("combined_id", "").strip()
        (combined_groups[cid] if cid else standalone).append(r) if cid else standalone.append(r)

    combined_rows = read_csv("com.samsung.shealth.sleep_combined.{SH_TS}.csv")
    combined_map = {
        r["datauuid"].strip(): r for r in combined_rows
        if r.get("datauuid", "").strip()
    }

    def build_stages(sleep_datauuid):
        out = []
        for s in stages_by_sleep.get(sleep_datauuid, []):
            s_start = parse_sh_time(s.get("start_time", "").strip(),
                                    s.get("time_offset", "").strip())
            s_end = parse_sh_time(s.get("end_time", "").strip(),
                                  s.get("time_offset", "").strip())
            s_stage = s.get("stage", "").strip()
            if s_start and s_end and s_stage in STAGE_MAP:
                out.append({
                    "startTime": s_start.isoformat(),
                    "endTime": s_end.isoformat(),
                    "stage": STAGE_MAP[s_stage],
                })
        out.sort(key=lambda x: x["startTime"])
        return out

    rows_out = []

    for cid, children in combined_groups.items():
        if cid in have:
            continue
        meta = combined_map.get(cid)
        if meta:
            start, end, offset, pkg = (
                meta.get("start_time", "").strip(),
                meta.get("end_time", "").strip(),
                meta.get("time_offset", "").strip(),
                meta.get("pkg_name", "").strip(),
            )
        else:
            c0 = children[0]
            start, end, offset, pkg = (
                c0.get("com.samsung.health.sleep.start_time", "").strip(),
                c0.get("com.samsung.health.sleep.end_time", "").strip(),
                c0.get("com.samsung.health.sleep.time_offset", "").strip(),
                c0.get("com.samsung.health.sleep.pkg_name", "").strip(),
            )
        start_dt = parse_sh_time(start, offset)
        end_dt = parse_sh_time(end, offset)
        if not start_dt or not end_dt:
            continue
        if not is_gap_jst(start_dt, have_days):
            continue
        all_stages = []
        for child in children:
            child_uuid = child.get("com.samsung.health.sleep.datauuid", "").strip()
            all_stages.extend(build_stages(child_uuid))
        all_stages.sort(key=lambda x: x["startTime"])
        rows_out.append((
            start_dt, cid, USER_ID, end_dt,
            pkg or "com.sec.android.app.shealth",
            json.dumps(all_stages) if all_stages else None,
        ))

    for r in standalone:
        datauuid = r.get("com.samsung.health.sleep.datauuid", "").strip()
        if not datauuid or datauuid in have:
            continue
        start_dt = parse_sh_time(
            r.get("com.samsung.health.sleep.start_time", "").strip(),
            r.get("com.samsung.health.sleep.time_offset", "").strip(),
        )
        end_dt = parse_sh_time(
            r.get("com.samsung.health.sleep.end_time", "").strip(),
            r.get("com.samsung.health.sleep.time_offset", "").strip(),
        )
        if not start_dt or not end_dt:
            continue
        if not is_gap_jst(start_dt, have_days):
            continue
        pkg = r.get("com.samsung.health.sleep.pkg_name", "").strip()
        rows_out.append((
            start_dt, datauuid, USER_ID, end_dt,
            pkg or "com.sec.android.app.shealth",
            json.dumps(build_stages(datauuid)) or None,
        ))

    n = copy_rows(PG, "sleep_session",
                  ["start_at", "id", "user_id", "end_at", "app", "stages"], rows_out)
    print(f"  sleep_session: {n}")


def import_calories():
    """Daily summary in kcal. PG `total_calories_burned.kcal` directly."""
    have = existing_ids(PG, "total_calories_burned", USER_ID)
    have_days = existing_days_jst(PG, "total_calories_burned", USER_ID, "start_at")

    rows = read_csv("com.samsung.shealth.calories_burned.details.{SH_TS}.csv")
    out = []
    for r in rows:
        datauuid = r.get("com.samsung.shealth.calories_burned.datauuid", "").strip()
        day_time = r.get("com.samsung.shealth.calories_burned.day_time", "").strip()
        if not datauuid or not day_time or datauuid in have:
            continue
        rest = float(r.get("com.samsung.shealth.calories_burned.rest_calorie", "") or 0)
        active = float(r.get("com.samsung.shealth.calories_burned.active_calorie", "") or 0)
        total = rest + active
        if total <= 0:
            continue
        start_dt = datetime.fromtimestamp(int(day_time) / 1000, tz=timezone.utc)
        if not is_gap_jst(start_dt, have_days):
            continue
        end_dt = start_dt + timedelta(days=1)
        pkg = r.get("com.samsung.shealth.calories_burned.pkg_name", "").strip()
        out.append((
            start_dt, datauuid, USER_ID, end_dt,
            pkg or "com.sec.android.app.shealth", total,
        ))
    n = copy_rows(PG, "total_calories_burned",
                  ["start_at", "id", "user_id", "end_at", "app", "kcal"], out)
    print(f"  total_calories_burned: {n}")


def import_steps_and_distance():
    """Step pedometer rows carry both step count and distance (meters).
    Split into two PG tables."""
    have_steps = existing_ids(PG, "steps", USER_ID)
    have_dist = existing_ids(PG, "distance", USER_ID)
    have_step_days = existing_days_jst(PG, "steps", USER_ID, "start_at")
    have_dist_days = existing_days_jst(PG, "distance", USER_ID, "start_at")

    rows = read_csv("com.samsung.shealth.tracker.pedometer_step_count.{SH_TS}.csv")
    step_out, dist_out = [], []
    for r in rows:
        datauuid = r.get("com.samsung.health.step_count.datauuid", "").strip()
        start = r.get("com.samsung.health.step_count.start_time", "").strip()
        end = r.get("com.samsung.health.step_count.end_time", "").strip()
        offset = r.get("com.samsung.health.step_count.time_offset", "").strip()
        count = r.get("com.samsung.health.step_count.count", "").strip()
        dist = r.get("com.samsung.health.step_count.distance", "").strip()
        pkg = r.get("com.samsung.health.step_count.pkg_name", "").strip() \
            or "com.sec.android.app.shealth"
        if not datauuid or not start or not end:
            continue
        start_dt = parse_sh_time(start, offset)
        end_dt = parse_sh_time(end, offset)
        if not start_dt or not end_dt:
            continue
        if count and datauuid not in have_steps and is_gap_jst(start_dt, have_step_days):
            step_out.append((start_dt, datauuid, USER_ID, end_dt, pkg, int(float(count))))
        if dist and datauuid not in have_dist and is_gap_jst(start_dt, have_dist_days):
            dist_out.append((start_dt, datauuid, USER_ID, end_dt, pkg, float(dist)))

    n1 = copy_rows(PG, "steps",
                   ["start_at", "id", "user_id", "end_at", "app", "count"], step_out)
    n2 = copy_rows(PG, "distance",
                   ["start_at", "id", "user_id", "end_at", "app", "meters"], dist_out)
    print(f"  steps: {n1}  distance: {n2}")


# ---------------------------------------------------------------------------
# Simple per-method importers (driven by config). Each entry maps:
#   "csv_basename": (
#       prefix,        # CSV column prefix (None for short-name columns)
#       fields,        # source CSV column suffixes  → output values
#       pg_table,
#       pg_cols,
#       row_builder,   # callable(parsed_csv_row) → (values…) or None to skip
#       kind,          # "interval" | "instant"
#   )
# Built explicitly below.
# ---------------------------------------------------------------------------

def _short(row, key):
    return row.get(key, "").strip()


def _prefixed(row, prefix, key):
    return row.get(f"{prefix}.{key}", "").strip()


def import_spo2():
    have = existing_ids(PG, "oxygen_saturation", USER_ID)
    have_days = existing_days_jst(PG, "oxygen_saturation", USER_ID, "start_at")
    p = "com.samsung.health.oxygen_saturation"
    out = []
    for r in read_csv("com.samsung.shealth.tracker.oxygen_saturation.{SH_TS}.csv"):
        datauuid = _prefixed(r, p, "datauuid")
        start = _prefixed(r, p, "start_time")
        offset = _prefixed(r, p, "time_offset")
        spo2 = _prefixed(r, p, "spo2")
        if not datauuid or not start or not spo2 or datauuid in have:
            continue
        start_dt = parse_sh_time(start, offset)
        if not start_dt or not is_gap_jst(start_dt, have_days):
            continue
        pkg = _prefixed(r, p, "pkg_name") or "com.sec.android.app.shealth"
        out.append((datauuid, USER_ID, start_dt, int(float(spo2)), pkg))
    n = copy_rows(PG, "oxygen_saturation",
                  ["id", "user_id", "start_at", "percentage", "app"], out)
    print(f"  oxygen_saturation: {n}")


def import_exercise():
    have = existing_ids(PG, "exercise_session", USER_ID)
    have_days = existing_days_jst(PG, "exercise_session", USER_ID, "start_at")
    p = "com.samsung.health.exercise"
    out = []
    for r in read_csv("com.samsung.shealth.exercise.{SH_TS}.csv"):
        datauuid = _prefixed(r, p, "datauuid")
        start = _prefixed(r, p, "start_time")
        end = _prefixed(r, p, "end_time")
        offset = _prefixed(r, p, "time_offset")
        etype = _prefixed(r, p, "exercise_type")
        if not datauuid or not start or not end or not etype or datauuid in have:
            continue
        start_dt = parse_sh_time(start, offset)
        end_dt = parse_sh_time(end, offset)
        if not start_dt or not end_dt or not is_gap_jst(start_dt, have_days):
            continue
        pkg = _prefixed(r, p, "pkg_name") or "com.sec.android.app.shealth"
        out.append((start_dt, datauuid, USER_ID, end_dt, pkg, int(etype)))
    n = copy_rows(PG, "exercise_session",
                  ["start_at", "id", "user_id", "end_at", "app", "exercise_type"], out)
    print(f"  exercise_session: {n}")


def import_hrv():
    """RMSSD values live in per-record JSON binning files."""
    have = existing_ids(PG, "heart_rate_variability", USER_ID)
    have_days = existing_days_jst(PG, "heart_rate_variability", USER_ID, "start_at")
    json_base = f"{SH_DIR}/jsons/com.samsung.health.hrv"
    out = []
    for r in read_csv("com.samsung.health.hrv.{SH_TS}.csv"):
        datauuid = _short(r, "datauuid")
        start = _short(r, "start_time")
        end = _short(r, "end_time")
        offset = _short(r, "time_offset")
        if not datauuid or not start or datauuid in have:
            continue
        start_dt = parse_sh_time(start, offset)
        end_dt = parse_sh_time(end, offset) if end else None
        if not start_dt or not end_dt or not is_gap_jst(start_dt, have_days):
            continue
        rmssd_avg = None
        if os.path.isdir(json_base):
            for subdir in os.listdir(json_base):
                p = os.path.join(json_base, subdir, f"{datauuid}.binning_data.json")
                if os.path.exists(p):
                    try:
                        with open(p) as jf:
                            vals = [s.get("rmssd") for s in json.load(jf) if s.get("rmssd") is not None]
                        if vals:
                            rmssd_avg = sum(vals) / len(vals)
                    except Exception:
                        pass
                    break
        if rmssd_avg is None:
            continue
        pkg = _short(r, "pkg_name") or "com.sec.android.app.shealth"
        out.append((start_dt, datauuid, USER_ID, end_dt, pkg, round(rmssd_avg, 2)))
    n = copy_rows(PG, "heart_rate_variability",
                  ["start_at", "id", "user_id", "end_at", "app", "rmssd"], out)
    print(f"  heart_rate_variability: {n}")


def import_stress():
    have = existing_ids(PG, "stress", USER_ID)
    have_days = existing_days_jst(PG, "stress", USER_ID, "start_at")
    out = []
    for r in read_csv("com.samsung.shealth.stress.{SH_TS}.csv"):
        datauuid = _short(r, "datauuid")
        start = _short(r, "start_time")
        offset = _short(r, "time_offset")
        score = _short(r, "score")
        if not datauuid or not start or not score or datauuid in have:
            continue
        start_dt = parse_sh_time(start, offset)
        if not start_dt or not is_gap_jst(start_dt, have_days):
            continue
        pkg = _short(r, "pkg_name") or "com.sec.android.app.shealth"
        out.append((datauuid, USER_ID, start_dt, int(float(score)), pkg))
    n = copy_rows(PG, "stress",
                  ["id", "user_id", "start_at", "score", "app"], out)
    print(f"  stress: {n}")


def import_vitality():
    have = existing_ids(PG, "vitality_score", USER_ID)
    have_days = existing_days_jst(PG, "vitality_score", USER_ID, "start_at")
    out = []
    for r in read_csv("com.samsung.shealth.vitality_score.{SH_TS}.csv"):
        datauuid = _short(r, "datauuid")
        day_time = _short(r, "day_time")
        total = _short(r, "total_score")
        if not datauuid or not day_time or not total or datauuid in have:
            continue
        start_dt = parse_sh_time(day_time, "UTC+0900")
        if not start_dt or not is_gap_jst(start_dt, have_days):
            continue
        pkg = _short(r, "pkg_name") or "com.sec.android.app.shealth"
        sleep = _short(r, "sleep_score")
        activity = _short(r, "activity_score")
        shr = _short(r, "shr_score")
        out.append((
            datauuid, USER_ID, start_dt,
            round(float(total), 1),
            round(float(sleep), 1) if sleep else None,
            round(float(activity), 1) if activity else None,
            round(float(shr), 1) if shr else None,
            pkg,
        ))
    n = copy_rows(PG, "vitality_score",
                  ["id", "user_id", "start_at",
                   "total_score", "sleep_score", "activity_score", "shr_score", "app"],
                  out)
    print(f"  vitality_score: {n}")


def import_respiratory_rate():
    have = existing_ids(PG, "respiratory_rate", USER_ID)
    have_days = existing_days_jst(PG, "respiratory_rate", USER_ID, "start_at")
    out = []
    for r in read_csv("com.samsung.health.respiratory_rate.{SH_TS}.csv"):
        datauuid = _short(r, "datauuid")
        start = _short(r, "start_time")
        end = _short(r, "end_time")
        offset = _short(r, "time_offset")
        avg = _short(r, "average")
        if not datauuid or not start or not avg or datauuid in have:
            continue
        start_dt = parse_sh_time(start, offset)
        end_dt = parse_sh_time(end, offset) if end else start_dt
        if not start_dt or not is_gap_jst(start_dt, have_days):
            continue
        pkg = _short(r, "pkg_name") or "com.sec.android.app.shealth"
        out.append((start_dt, datauuid, USER_ID, end_dt, pkg, float(avg)))
    n = copy_rows(PG, "respiratory_rate",
                  ["start_at", "id", "user_id", "end_at", "app", "rate"], out)
    print(f"  respiratory_rate: {n}")


def import_skin_temperature():
    have = existing_ids(PG, "skin_temperature", USER_ID)
    have_days = existing_days_jst(PG, "skin_temperature", USER_ID, "start_at")
    out = []
    for r in read_csv("com.samsung.health.skin_temperature.{SH_TS}.csv"):
        datauuid = _short(r, "datauuid")
        start = _short(r, "start_time")
        end = _short(r, "end_time")
        offset = _short(r, "time_offset")
        temp = _short(r, "temperature")
        baseline = _short(r, "baseline")
        if not datauuid or not start or not temp or datauuid in have:
            continue
        start_dt = parse_sh_time(start, offset)
        end_dt = parse_sh_time(end, offset) if end else start_dt
        if not start_dt or not is_gap_jst(start_dt, have_days):
            continue
        pkg = _short(r, "pkg_name") or "com.sec.android.app.shealth"
        out.append((
            start_dt, datauuid, USER_ID, end_dt, pkg,
            float(temp), float(baseline) if baseline else None,
        ))
    n = copy_rows(PG, "skin_temperature",
                  ["start_at", "id", "user_id", "end_at", "app",
                   "temperature_c", "baseline_c"], out)
    print(f"  skin_temperature: {n}")


def import_floors():
    have = existing_ids(PG, "floors_climbed", USER_ID)
    have_days = existing_days_jst(PG, "floors_climbed", USER_ID, "start_at")
    out = []
    for r in read_csv("com.samsung.health.floors_climbed.{SH_TS}.csv"):
        datauuid = _short(r, "datauuid")
        start = _short(r, "start_time")
        end = _short(r, "end_time")
        offset = _short(r, "time_offset")
        floor = _short(r, "floor")
        if not datauuid or not start or not floor or datauuid in have:
            continue
        start_dt = parse_sh_time(start, offset)
        end_dt = parse_sh_time(end, offset) if end else start_dt
        if not start_dt or not is_gap_jst(start_dt, have_days):
            continue
        pkg = _short(r, "pkg_name") or "com.sec.android.app.shealth"
        out.append((start_dt, datauuid, USER_ID, end_dt, pkg, int(float(floor))))
    n = copy_rows(PG, "floors_climbed",
                  ["start_at", "id", "user_id", "end_at", "app", "floors"], out)
    print(f"  floors_climbed: {n}")


def import_weight():
    """Weight CSV carries weight / body_fat / basal_metabolic_rate.
    Splits into 3 PG tables."""
    have_w = existing_ids(PG, "weight", USER_ID)
    have_bf = existing_ids(PG, "body_fat", USER_ID)
    have_bmr = existing_ids(PG, "basal_metabolic_rate", USER_ID)
    w_out, bf_out, bmr_out = [], [], []
    for r in read_csv("com.samsung.health.weight.{SH_TS}.csv"):
        datauuid = _short(r, "datauuid")
        start = _short(r, "start_time")
        offset = _short(r, "time_offset")
        weight = _short(r, "weight")
        body_fat = _short(r, "body_fat")
        bmr = _short(r, "basal_metabolic_rate")
        if not datauuid or not start:
            continue
        start_dt = parse_sh_time(start, offset)
        if not start_dt:
            continue
        pkg = _short(r, "pkg_name") or "com.sec.android.app.shealth"
        if weight and datauuid not in have_w:
            w_out.append((datauuid, USER_ID, start_dt, float(weight), pkg))
        if body_fat and datauuid not in have_bf:
            bf_out.append((datauuid, USER_ID, start_dt, float(body_fat), pkg))
        if bmr and datauuid not in have_bmr:
            bmr_out.append((datauuid, USER_ID, start_dt, float(bmr), pkg))
    n_w = copy_rows(PG, "weight",
                    ["id", "user_id", "start_at", "weight_kg", "app"], w_out)
    n_bf = copy_rows(PG, "body_fat",
                     ["id", "user_id", "start_at", "percentage", "app"], bf_out)
    n_bmr = copy_rows(PG, "basal_metabolic_rate",
                      ["id", "user_id", "start_at", "bmr", "app"], bmr_out)
    print(f"  weight: {n_w}  body_fat: {n_bf}  basal_metabolic_rate: {n_bmr}")


def import_height():
    have = existing_ids(PG, "height", USER_ID)
    out = []
    for r in read_csv("com.samsung.health.height.{SH_TS}.csv"):
        datauuid = _short(r, "datauuid")
        start = _short(r, "start_time")
        offset = _short(r, "time_offset")
        height = _short(r, "height")
        if not datauuid or not start or not height or datauuid in have:
            continue
        start_dt = parse_sh_time(start, offset)
        if not start_dt:
            continue
        pkg = _short(r, "pkg_name") or "com.sec.android.app.shealth"
        out.append((datauuid, USER_ID, start_dt, float(height), pkg))
    n = copy_rows(PG, "height",
                  ["id", "user_id", "start_at", "height_cm", "app"], out)
    print(f"  height: {n}")


def import_blood_pressure():
    have = existing_ids(PG, "blood_pressure", USER_ID)
    p = "com.samsung.health.blood_pressure"
    out = []
    for r in read_csv("com.samsung.shealth.blood_pressure.{SH_TS}.csv"):
        datauuid = _prefixed(r, p, "datauuid")
        start = _prefixed(r, p, "start_time")
        offset = _prefixed(r, p, "time_offset")
        systolic = _prefixed(r, p, "systolic")
        diastolic = _prefixed(r, p, "diastolic")
        pulse = _prefixed(r, p, "pulse")
        if not datauuid or not start or datauuid in have:
            continue
        if not (systolic and diastolic and pulse):
            continue
        start_dt = parse_sh_time(start, offset)
        if not start_dt:
            continue
        pkg = _prefixed(r, p, "pkg_name") or "com.sec.android.app.shealth"
        out.append((
            datauuid, USER_ID, start_dt,
            int(float(systolic)), int(float(diastolic)), int(float(pulse)), pkg,
        ))
    n = copy_rows(PG, "blood_pressure",
                  ["id", "user_id", "start_at", "systolic", "diastolic", "pulse", "app"], out)
    print(f"  blood_pressure: {n}")


def import_vo2max():
    """VO2 Max from the Health Connect SQLite export (not in Samsung CSV)."""
    import sqlite3
    sqlite_path = os.environ.get("HC_SQLITE", "/tmp/hc_export/health_connect_export.db")
    if not os.path.exists(sqlite_path):
        print(f"  vo2_max: skipped (no SQLite export at {sqlite_path})")
        return
    have = existing_ids(PG, "vo2_max", USER_ID)
    sq = sqlite3.connect(sqlite_path)
    sq.row_factory = sqlite3.Row
    out = []
    for r in sq.execute("SELECT * FROM vo2_max_record_table").fetchall():
        h = r["uuid"].hex()
        uid = f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"
        if uid in have or r["vo2_milliliters_per_minute_kilogram"] is None:
            continue
        start_dt = datetime.fromtimestamp(r["time"] / 1000, tz=timezone.utc)
        app_row = sq.execute(
            "SELECT package_name FROM application_info_table WHERE row_id = ?",
            (r["app_info_id"],),
        ).fetchone()
        app = app_row["package_name"] if app_row else "unknown"
        out.append((uid, USER_ID, start_dt,
                    float(r["vo2_milliliters_per_minute_kilogram"]), app))
    sq.close()
    n = copy_rows(PG, "vo2_max",
                  ["id", "user_id", "start_at", "vo2_max", "app"], out)
    print(f"  vo2_max: {n}")


# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------

IMPORTERS = {
    "heartRate": import_heart_rate,
    "sleep": import_sleep,
    "calories": import_calories,
    "stepsAndDistance": import_steps_and_distance,
    "spo2": import_spo2,
    "exercise": import_exercise,
    "hrv": import_hrv,
    "stress": import_stress,
    "vitality": import_vitality,
    "respiratoryRate": import_respiratory_rate,
    "skinTemperature": import_skin_temperature,
    "floors": import_floors,
    "weight": import_weight,
    "height": import_height,
    "bloodPressure": import_blood_pressure,
    "vo2Max": import_vo2max,
}

DEFAULT_ORDER = list(IMPORTERS.keys())


def main():
    global PG, USER_ID
    args = sys.argv[1:]
    if args:
        unknown = [a for a in args if a not in IMPORTERS]
        if unknown:
            print(f"Unknown types: {unknown}")
            print(f"Available: {sorted(IMPORTERS)}")
            sys.exit(1)
        plan = args
    else:
        plan = DEFAULT_ORDER

    PG = connect()
    USER_ID = get_user_id(PG)
    print(f"Importing Samsung Health export → user {USER_ID}")
    for name in plan:
        IMPORTERS[name]()
    PG.close()
    print("Done.")


if __name__ == "__main__":
    main()
