"""
Import Samsung Health CSV export into MongoDB.
Writes to hcgateway_<userid> in the shape the API produces post-E1.
Fills gaps that Health Connect export doesn't cover.
"""

import csv
import glob
import os
import re
import pymongo
import uuid
from datetime import datetime, timezone, timedelta
from collections import defaultdict

SH_BASE = os.environ.get("SH_BASE", "/tmp/shealth_export")
MONGO_URI = os.environ.get("MONGO_URI", "mongodb://root:example@localhost:27017/")
DB_NAME = os.environ.get("DB_NAME", "hcgateway_69b3d84d26c5efd2f4af0b53")


def _resolve_sh_dir():
    """Pick the most recent samsunghealth_* directory under SH_BASE."""
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

mongo = pymongo.MongoClient(MONGO_URI)
db = mongo[DB_NAME]


def parse_sh_time(timestr, offset_str):
    """Parse Samsung Health timestamp with timezone offset to UTC datetime."""
    if not timestr:
        return None
    dt = datetime.strptime(timestr.strip(), "%Y-%m-%d %H:%M:%S.%f")
    # Parse offset like "UTC+0900" or "UTC+0000"
    if offset_str and offset_str.startswith("UTC"):
        sign = 1 if '+' in offset_str else -1
        nums = offset_str.replace("UTC", "").replace("+", "").replace("-", "")
        hours = int(nums[:2])
        mins = int(nums[2:4]) if len(nums) >= 4 else 0
        offset = timedelta(hours=hours, minutes=mins) * sign
        dt = dt.replace(tzinfo=timezone(offset))
    else:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def read_csv(filename):
    """Read Samsung Health CSV, skipping the first metadata line.

    The filename may contain `{SH_TS}` which is substituted with the
    timestamp parsed from the export directory name.
    """
    path = os.path.join(SH_DIR, filename.format(SH_TS=SH_TS))
    with open(path, encoding='utf-8-sig') as f:
        # Skip first line (metadata)
        f.readline()
        reader = csv.DictReader(f)
        return list(reader)


JST = timezone(timedelta(hours=9))


def _existing_days(col, field='start'):
    """Return set of (Y, M, D) JST tuples for which col has any record.

    Uses MongoDB aggregation to avoid pulling all documents.
    """
    days = set()
    pipeline = [
        {"$match": {field: {"$type": "date"}}},
        {"$group": {"_id": {
            "$dateToString": {"format": "%Y-%m-%d", "date": f"${field}", "timezone": "+09:00"}
        }}},
    ]
    for doc in col.aggregate(pipeline):
        y, m, d = doc["_id"].split("-")
        days.add((int(y), int(m), int(d)))
    return days


def _is_gap(start_dt, existing_days):
    """True iff the JST date of start_dt is NOT in existing_days."""
    if start_dt.tzinfo is None:
        start_dt = start_dt.replace(tzinfo=timezone.utc)
    j = start_dt.astimezone(JST)
    return (j.year, j.month, j.day) not in existing_days


def _summary_gap(col_name, existing_days):
    if not existing_days:
        print(f"  {col_name}: no existing records (everything is gap)")
        return
    sorted_days = sorted(existing_days)
    print(f"  {col_name}: {len(sorted_days)} existing days "
          f"({sorted_days[0]!r} … {sorted_days[-1]!r})")


def import_heart_rate():
    """
    Samsung Health HR: one reading per row.
    Group into 1-hour blocks with samples array to match Health Connect format.
    """
    col = db["heartRate"]
    existing_ids = {d["_id"] for d in col.find({}, {"_id": 1})}
    existing_days = _existing_days(col)
    _summary_gap("heartRate", existing_days)

    rows = read_csv("com.samsung.shealth.tracker.heart_rate.{SH_TS}.csv")

    # Group by hour
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

        # Hour key for grouping
        hour_key = dt.replace(minute=0, second=0, microsecond=0)
        hourly[hour_key].append({
            "bpm": int(float(hr_val)),
            "time": dt,
            "uuid": datauuid,
            "pkg": pkg,
        })

    ops = []
    for hour_start, samples in hourly.items():
        # Use first sample's uuid as record id, or generate one
        record_id = samples[0]["uuid"] if samples[0]["uuid"] else str(uuid.uuid4())
        if record_id in existing_ids:
            continue
        if not _is_gap(hour_start, existing_days):
            continue

        hour_end = hour_start + timedelta(hours=1) - timedelta(milliseconds=1)
        app = samples[0]["pkg"] or "com.sec.android.app.shealth"

        doc = {
            "_id": record_id,
            "app": app,
            "start": hour_start,
            "end": hour_end,
            "samples": [
                {
                    "beatsPerMinute": s["bpm"],
                    "time": s["time"],
                }
                for s in sorted(samples, key=lambda x: x["time"])
            ],
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        try:
            result = col.bulk_write(ops, ordered=False)
            print(f"  heartRate: {result.inserted_count} inserted ({len(ops)} blocks)")
        except pymongo.errors.BulkWriteError as e:
            inserted = e.details.get('nInserted', 0)
            print(f"  heartRate: {inserted} inserted (some duplicates skipped)")
    else:
        print("  heartRate: no new records")


def import_sleep():
    """
    Import sleep sessions with stages.

    Structure:
    - sleep table: individual sleep segments, linked to sleep_stage via datauuid
    - sleep_combined: groups multiple sleep records into one session (combined_id)
    - sleep_stage: stage data, linked by sleep_id -> sleep.datauuid

    Strategy: use sleep_combined as sessions where available (with stages gathered
    from all child sleep records). For sleep records without combined_id, import
    as standalone sessions.
    """
    col = db["sleepSession"]
    existing_ids = {d["_id"] for d in col.find({}, {"_id": 1})}
    existing_days = _existing_days(col)
    _summary_gap("sleepSession", existing_days)

    STAGE_MAP = {
        "40001": 1,   # Awake
        "40002": 4,   # Light
        "40003": 5,   # Deep
        "40004": 6,   # REM
    }

    # Read sleep stages indexed by sleep_id (= sleep.datauuid)
    stage_rows = read_csv("com.samsung.health.sleep_stage.{SH_TS}.csv")
    stages_by_sleep = defaultdict(list)
    for r in stage_rows:
        sleep_id = r.get("sleep_id", "").strip()
        if sleep_id:
            stages_by_sleep[sleep_id].append(r)

    # Read sleep table
    sleep_rows = read_csv("com.samsung.shealth.sleep.{SH_TS}.csv")

    # Group sleep records by combined_id
    combined_groups = defaultdict(list)
    standalone = []
    for r in sleep_rows:
        cid = r.get("combined_id", "").strip()
        if cid:
            combined_groups[cid].append(r)
        else:
            standalone.append(r)

    # Read sleep_combined for session metadata
    combined_rows = read_csv("com.samsung.shealth.sleep_combined.{SH_TS}.csv")
    combined_map = {}
    for r in combined_rows:
        uid = r.get("datauuid", "").strip()
        if uid:
            combined_map[uid] = r

    def build_stages(sleep_datauuid):
        stage_list = []
        for s in stages_by_sleep.get(sleep_datauuid, []):
            s_start = s.get("start_time", "").strip()
            s_end = s.get("end_time", "").strip()
            s_offset = s.get("time_offset", "").strip()
            s_stage = s.get("stage", "").strip()
            s_start_dt = parse_sh_time(s_start, s_offset)
            s_end_dt = parse_sh_time(s_end, s_offset)
            if s_start_dt and s_end_dt and s_stage in STAGE_MAP:
                stage_list.append({
                    "startTime": s_start_dt,
                    "endTime": s_end_dt,
                    "stage": STAGE_MAP[s_stage],
                })
        return stage_list

    ops = []

    # Import combined sessions
    for cid, children in combined_groups.items():
        if cid in existing_ids:
            continue

        combined_meta = combined_map.get(cid)
        if combined_meta:
            start = combined_meta.get("start_time", "").strip()
            end = combined_meta.get("end_time", "").strip()
            offset = combined_meta.get("time_offset", "").strip()
            pkg = combined_meta.get("pkg_name", "").strip()
        else:
            # Fallback: use min/max from children
            start = children[0].get("com.samsung.health.sleep.start_time", "").strip()
            end = children[0].get("com.samsung.health.sleep.end_time", "").strip()
            offset = children[0].get("com.samsung.health.sleep.time_offset", "").strip()
            pkg = children[0].get("com.samsung.health.sleep.pkg_name", "").strip()

        if not start or not end:
            continue

        start_dt = parse_sh_time(start, offset)
        end_dt = parse_sh_time(end, offset)
        if not start_dt or not end_dt:
            continue
        if not _is_gap(start_dt, existing_days):
            continue

        # Gather stages from all child sleep records
        all_stages = []
        for child in children:
            child_uuid = child.get("com.samsung.health.sleep.datauuid", "").strip()
            all_stages.extend(build_stages(child_uuid))
        all_stages.sort(key=lambda x: x["startTime"])

        doc = {
            "_id": cid,
            "app": pkg or "com.sec.android.app.shealth",
            "start": start_dt,
            "end": end_dt,
            "stages": all_stages,
        }
        ops.append(pymongo.UpdateOne({"_id": cid}, {"$set": doc}, upsert=True))

    # Import standalone sleep records (no combined_id)
    for r in standalone:
        datauuid = r.get("com.samsung.health.sleep.datauuid", "").strip()
        if not datauuid or datauuid in existing_ids:
            continue

        start = r.get("com.samsung.health.sleep.start_time", "").strip()
        end = r.get("com.samsung.health.sleep.end_time", "").strip()
        offset = r.get("com.samsung.health.sleep.time_offset", "").strip()
        pkg = r.get("com.samsung.health.sleep.pkg_name", "").strip()

        if not start or not end:
            continue

        start_dt = parse_sh_time(start, offset)
        end_dt = parse_sh_time(end, offset)
        if not start_dt or not end_dt:
            continue
        if not _is_gap(start_dt, existing_days):
            continue

        stage_list = build_stages(datauuid)
        stage_list.sort(key=lambda x: x["startTime"])

        doc = {
            "_id": datauuid,
            "app": pkg or "com.sec.android.app.shealth",
            "start": start_dt,
            "end": end_dt,
            "stages": stage_list,
        }
        ops.append(pymongo.UpdateOne({"_id": datauuid}, {"$set": doc}, upsert=True))

    if ops:
        result = col.bulk_write(ops, ordered=False)
        print(f"  sleepSession: upserted={result.upserted_count} modified={result.modified_count}")
    else:
        print("  sleepSession: no new records")


def import_calories():
    """Samsung Health calories are daily summaries (rest + active) in kcal."""
    col = db["totalCaloriesBurned"]
    existing_ids = {d["_id"] for d in col.find({}, {"_id": 1})}
    existing_days = _existing_days(col)
    _summary_gap("totalCaloriesBurned", existing_days)

    rows = read_csv("com.samsung.shealth.calories_burned.details.{SH_TS}.csv")
    ops = []
    for r in rows:
        datauuid = r.get("com.samsung.shealth.calories_burned.datauuid", "").strip()
        day_time = r.get("com.samsung.shealth.calories_burned.day_time", "").strip()
        rest = r.get("com.samsung.shealth.calories_burned.rest_calorie", "").strip()
        active = r.get("com.samsung.shealth.calories_burned.active_calorie", "").strip()
        pkg = r.get("com.samsung.shealth.calories_burned.pkg_name", "").strip()

        if not datauuid or not day_time:
            continue
        if datauuid in existing_ids:
            continue

        rest_kcal = float(rest) if rest else 0
        active_kcal = float(active) if active else 0
        total_kcal = rest_kcal + active_kcal

        start_dt = datetime.fromtimestamp(int(day_time) / 1000, tz=timezone.utc)
        end_dt = start_dt + timedelta(days=1)

        if not _is_gap(start_dt, existing_days):
            continue

        doc = {
            "_id": datauuid,
            "app": pkg or "com.sec.android.app.shealth",
            "start": start_dt,
            "end": end_dt,
            "energy": {
                "inKilocalories": total_kcal,
                "inJoules": total_kcal * 4184,
            },
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        try:
            result = col.bulk_write(ops, ordered=False)
            print(f"  totalCaloriesBurned: {result.inserted_count} inserted")
        except pymongo.errors.BulkWriteError as e:
            print(f"  totalCaloriesBurned: {e.details.get('nInserted', 0)} inserted (some duplicates skipped)")
    else:
        print("  totalCaloriesBurned: no new records")


def import_steps():
    """Import step count records. Also imports distance from step data."""
    steps_col = db["steps"]
    dist_col = db["distance"]
    existing_step_ids = {d["_id"] for d in steps_col.find({}, {"_id": 1})}
    existing_dist_ids = {d["_id"] for d in dist_col.find({}, {"_id": 1})}
    existing_step_days = _existing_days(steps_col)
    existing_dist_days = _existing_days(dist_col)
    _summary_gap("steps", existing_step_days)
    _summary_gap("distance", existing_dist_days)

    rows = read_csv("com.samsung.shealth.tracker.pedometer_step_count.{SH_TS}.csv")
    step_ops = []
    dist_ops = []
    for r in rows:
        datauuid = r.get("com.samsung.health.step_count.datauuid", "").strip()
        start = r.get("com.samsung.health.step_count.start_time", "").strip()
        end = r.get("com.samsung.health.step_count.end_time", "").strip()
        offset = r.get("com.samsung.health.step_count.time_offset", "").strip()
        count = r.get("com.samsung.health.step_count.count", "").strip()
        dist = r.get("com.samsung.health.step_count.distance", "").strip()
        pkg = r.get("com.samsung.health.step_count.pkg_name", "").strip()

        if not datauuid or not start or not end:
            continue

        start_dt = parse_sh_time(start, offset)
        end_dt = parse_sh_time(end, offset)
        if not start_dt or not end_dt:
            continue

        app = pkg or "com.sec.android.app.shealth"

        if (datauuid not in existing_step_ids and count
                and _is_gap(start_dt, existing_step_days)):
            step_ops.append(pymongo.InsertOne({
                "_id": datauuid,
                "app": app,
                "start": start_dt,
                "end": end_dt,
                "count": int(float(count)),
            }))

        if (datauuid not in existing_dist_ids and dist
                and _is_gap(start_dt, existing_dist_days)):
            meters = float(dist)
            dist_ops.append(pymongo.InsertOne({
                "_id": datauuid,
                "app": app,
                "start": start_dt,
                "end": end_dt,
                "distance": {
                    "inMeters": meters,
                    "inKilometers": meters / 1000,
                },
            }))

    for name, col, ops in [("steps", steps_col, step_ops), ("distance", dist_col, dist_ops)]:
        if ops:
            try:
                result = col.bulk_write(ops, ordered=False)
                print(f"  {name}: {result.inserted_count} inserted")
            except pymongo.errors.BulkWriteError as e:
                print(f"  {name}: {e.details.get('nInserted', 0)} inserted (some duplicates skipped)")
        else:
            print(f"  {name}: no new records")


def import_spo2():
    col = db["oxygenSaturation"]
    existing_ids = {d["_id"] for d in col.find({}, {"_id": 1})}
    existing_days = _existing_days(col)
    _summary_gap("oxygenSaturation", existing_days)

    rows = read_csv("com.samsung.shealth.tracker.oxygen_saturation.{SH_TS}.csv")
    ops = []
    for r in rows:
        datauuid = r.get("com.samsung.health.oxygen_saturation.datauuid", "").strip()
        start = r.get("com.samsung.health.oxygen_saturation.start_time", "").strip()
        offset = r.get("com.samsung.health.oxygen_saturation.time_offset", "").strip()
        spo2 = r.get("com.samsung.health.oxygen_saturation.spo2", "").strip()
        pkg = r.get("com.samsung.health.oxygen_saturation.pkg_name", "").strip()

        if not datauuid or not start or not spo2:
            continue
        if datauuid in existing_ids:
            continue

        start_dt = parse_sh_time(start, offset)
        if start_dt is None:
            continue
        if not _is_gap(start_dt, existing_days):
            continue

        doc = {
            "_id": datauuid,
            "app": pkg or "com.sec.android.app.shealth",
            "start": start_dt,
            "end": None,
            "percentage": float(spo2),
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        try:
            result = col.bulk_write(ops, ordered=False)
            print(f"  oxygenSaturation: {result.inserted_count} inserted")
        except pymongo.errors.BulkWriteError as e:
            print(f"  oxygenSaturation: {e.details.get('nInserted', 0)} inserted (some duplicates skipped)")
    else:
        print("  oxygenSaturation: no new records")


def import_exercise():
    col = db["exerciseSession"]
    existing_ids = {d["_id"] for d in col.find({}, {"_id": 1})}
    existing_days = _existing_days(col)
    _summary_gap("exerciseSession", existing_days)

    rows = read_csv("com.samsung.shealth.exercise.{SH_TS}.csv")
    ops = []
    for r in rows:
        datauuid = r.get("com.samsung.health.exercise.datauuid", "").strip()
        start = r.get("com.samsung.health.exercise.start_time", "").strip()
        end = r.get("com.samsung.health.exercise.end_time", "").strip()
        offset = r.get("com.samsung.health.exercise.time_offset", "").strip()
        etype = r.get("com.samsung.health.exercise.exercise_type", "").strip()
        pkg = r.get("com.samsung.health.exercise.pkg_name", "").strip()

        if not datauuid or not start or not end:
            continue
        if datauuid in existing_ids:
            continue

        start_dt = parse_sh_time(start, offset)
        end_dt = parse_sh_time(end, offset)
        if start_dt is None or end_dt is None:
            continue
        if not _is_gap(start_dt, existing_days):
            continue

        doc = {
            "_id": datauuid,
            "app": pkg or "com.sec.android.app.shealth",
            "start": start_dt,
            "end": end_dt,
            "exerciseType": int(etype) if etype else None,
            "title": None,
            "notes": None,
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        try:
            result = col.bulk_write(ops, ordered=False)
            print(f"  exerciseSession: {result.inserted_count} inserted")
        except pymongo.errors.BulkWriteError as e:
            print(f"  exerciseSession: {e.details.get('nInserted', 0)} inserted (some duplicates skipped)")
    else:
        print("  exerciseSession: no new records")


def import_hrv():
    """Import HRV data. RMSSD values are in per-record JSON binning files."""
    col = db["heartRateVariability"]
    existing_ids = {d["_id"] for d in col.find({}, {"_id": 1})}
    existing_days = _existing_days(col)
    _summary_gap("heartRateVariability", existing_days)

    rows = read_csv("com.samsung.health.hrv.{SH_TS}.csv")
    json_base = f"{SH_DIR}/jsons/com.samsung.health.hrv"

    import json as json_mod
    import os

    ops = []
    for r in rows:
        datauuid = r.get("datauuid", "").strip()
        start = r.get("start_time", "").strip()
        end = r.get("end_time", "").strip()
        offset = r.get("time_offset", "").strip()
        pkg = r.get("pkg_name", "").strip()

        if not datauuid or not start:
            continue
        if datauuid in existing_ids:
            continue

        start_dt = parse_sh_time(start, offset)
        end_dt = parse_sh_time(end, offset) if end else None
        if not start_dt:
            continue
        if not _is_gap(start_dt, existing_days):
            continue

        # Read RMSSD from binning JSON
        rmssd_avg = None
        json_path = None
        for subdir in os.listdir(json_base):
            candidate = os.path.join(json_base, subdir, f"{datauuid}.binning_data.json")
            if os.path.exists(candidate):
                json_path = candidate
                break

        if json_path:
            try:
                with open(json_path) as jf:
                    samples = json_mod.load(jf)
                if samples:
                    rmssd_values = [s["rmssd"] for s in samples if "rmssd" in s]
                    if rmssd_values:
                        rmssd_avg = sum(rmssd_values) / len(rmssd_values)
            except Exception:
                pass

        doc = {
            "_id": datauuid,
            "app": pkg or "com.sec.android.app.shealth",
            "start": start_dt,
            "end": end_dt,
            "rmssd": round(rmssd_avg, 2) if rmssd_avg else None,
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        try:
            result = col.bulk_write(ops, ordered=False)
            print(f"  heartRateVariability: {result.inserted_count} inserted")
        except pymongo.errors.BulkWriteError as e:
            print(f"  heartRateVariability: {e.details.get('nInserted', 0)} inserted (some duplicates skipped)")
    else:
        print("  heartRateVariability: no new records")


def import_stress():
    col = db["stress"]
    existing_ids = {d["_id"] for d in col.find({}, {"_id": 1})}
    existing_days = _existing_days(col)
    _summary_gap("stress", existing_days)

    rows = read_csv("com.samsung.shealth.stress.{SH_TS}.csv")
    ops = []
    for r in rows:
        datauuid = r.get("datauuid", "").strip()
        start = r.get("start_time", "").strip()
        offset = r.get("time_offset", "").strip()
        score = r.get("score", "").strip()
        pkg = r.get("pkg_name", "").strip()

        if not datauuid or not start or not score:
            continue
        if datauuid in existing_ids:
            continue

        start_dt = parse_sh_time(start, offset)
        if not start_dt:
            continue
        if not _is_gap(start_dt, existing_days):
            continue

        doc = {
            "_id": datauuid,
            "app": pkg or "com.sec.android.app.shealth",
            "start": start_dt,
            "end": None,
            "score": float(score),
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        try:
            result = col.bulk_write(ops, ordered=False)
            print(f"  stress: {result.inserted_count} inserted")
        except pymongo.errors.BulkWriteError as e:
            print(f"  stress: {e.details.get('nInserted', 0)} inserted (some duplicates skipped)")
    else:
        print("  stress: no new records")


def import_vitality():
    col = db["vitalityScore"]
    existing_ids = {d["_id"] for d in col.find({}, {"_id": 1})}
    existing_days = _existing_days(col)
    _summary_gap("vitalityScore", existing_days)

    rows = read_csv("com.samsung.shealth.vitality_score.{SH_TS}.csv")
    ops = []
    for r in rows:
        datauuid = r.get("datauuid", "").strip()
        day_time = r.get("day_time", "").strip()
        total = r.get("total_score", "").strip()
        sleep = r.get("sleep_score", "").strip()
        activity = r.get("activity_score", "").strip()
        shr = r.get("shr_score", "").strip()
        pkg = r.get("pkg_name", "").strip()

        if not datauuid or not day_time or not total:
            continue
        if datauuid in existing_ids:
            continue

        start_dt = parse_sh_time(day_time, "UTC+0900")
        if not start_dt:
            continue
        if not _is_gap(start_dt, existing_days):
            continue

        doc = {
            "_id": datauuid,
            "app": pkg or "com.sec.android.app.shealth",
            "start": start_dt,
            "end": None,
            "totalScore": round(float(total), 1),
            "sleepScore": round(float(sleep), 1) if sleep else None,
            "activityScore": round(float(activity), 1) if activity else None,
            "shrScore": round(float(shr), 1) if shr else None,
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        try:
            result = col.bulk_write(ops, ordered=False)
            print(f"  vitalityScore: {result.inserted_count} inserted")
        except pymongo.errors.BulkWriteError as e:
            print(f"  vitalityScore: {e.details.get('nInserted', 0)} inserted (some duplicates skipped)")
    else:
        print("  vitalityScore: no new records")


def import_respiratory_rate():
    col = db["respiratoryRate"]
    existing_ids = {d["_id"] for d in col.find({}, {"_id": 1})}
    existing_days = _existing_days(col)
    _summary_gap("respiratoryRate", existing_days)

    rows = read_csv("com.samsung.health.respiratory_rate.{SH_TS}.csv")
    ops = []
    for r in rows:
        datauuid = r.get("datauuid", "").strip()
        start = r.get("start_time", "").strip()
        end = r.get("end_time", "").strip()
        offset = r.get("time_offset", "").strip()
        avg = r.get("average", "").strip()
        pkg = r.get("pkg_name", "").strip()

        if not datauuid or not start or not avg:
            continue
        if datauuid in existing_ids:
            continue

        start_dt = parse_sh_time(start, offset)
        end_dt = parse_sh_time(end, offset) if end else None
        if not start_dt:
            continue
        if not _is_gap(start_dt, existing_days):
            continue

        doc = {
            "_id": datauuid,
            "app": pkg or "com.sec.android.app.shealth",
            "start": start_dt,
            "end": end_dt,
            "rate": float(avg),
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        try:
            result = col.bulk_write(ops, ordered=False)
            print(f"  respiratoryRate: {result.inserted_count} inserted")
        except pymongo.errors.BulkWriteError as e:
            print(f"  respiratoryRate: {e.details.get('nInserted', 0)} inserted (some duplicates skipped)")
    else:
        print("  respiratoryRate: no new records")


def import_skin_temperature():
    col = db["skinTemperature"]
    existing_ids = {d["_id"] for d in col.find({}, {"_id": 1})}
    existing_days = _existing_days(col)
    _summary_gap("skinTemperature", existing_days)

    rows = read_csv("com.samsung.health.skin_temperature.{SH_TS}.csv")
    ops = []
    for r in rows:
        datauuid = r.get("datauuid", "").strip()
        start = r.get("start_time", "").strip()
        end = r.get("end_time", "").strip()
        offset = r.get("time_offset", "").strip()
        temp = r.get("temperature", "").strip()
        baseline = r.get("baseline", "").strip()
        pkg = r.get("pkg_name", "").strip()

        if not datauuid or not start or not temp:
            continue
        if datauuid in existing_ids:
            continue

        start_dt = parse_sh_time(start, offset)
        end_dt = parse_sh_time(end, offset) if end else None
        if not start_dt:
            continue
        if not _is_gap(start_dt, existing_days):
            continue

        doc = {
            "_id": datauuid,
            "app": pkg or "com.sec.android.app.shealth",
            "start": start_dt,
            "end": end_dt,
            "temperature": float(temp),
            "baseline": float(baseline) if baseline else None,
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        try:
            result = col.bulk_write(ops, ordered=False)
            print(f"  skinTemperature: {result.inserted_count} inserted")
        except pymongo.errors.BulkWriteError as e:
            print(f"  skinTemperature: {e.details.get('nInserted', 0)} inserted (some duplicates skipped)")
    else:
        print("  skinTemperature: no new records")


def import_floors():
    col = db["floorsClimbed"]
    existing_ids = {d["_id"] for d in col.find({}, {"_id": 1})}
    existing_days = _existing_days(col)
    _summary_gap("floorsClimbed", existing_days)

    rows = read_csv("com.samsung.health.floors_climbed.{SH_TS}.csv")
    ops = []
    for r in rows:
        datauuid = r.get("datauuid", "").strip()
        start = r.get("start_time", "").strip()
        end = r.get("end_time", "").strip()
        offset = r.get("time_offset", "").strip()
        floor = r.get("floor", "").strip()
        pkg = r.get("pkg_name", "").strip()

        if not datauuid or not start or not floor:
            continue
        if datauuid in existing_ids:
            continue

        start_dt = parse_sh_time(start, offset)
        end_dt = parse_sh_time(end, offset) if end else None
        if not start_dt:
            continue
        if not _is_gap(start_dt, existing_days):
            continue

        doc = {
            "_id": datauuid,
            "app": pkg or "com.sec.android.app.shealth",
            "start": start_dt,
            "end": end_dt,
            "floors": int(float(floor)),
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        try:
            result = col.bulk_write(ops, ordered=False)
            print(f"  floorsClimbed: {result.inserted_count} inserted")
        except pymongo.errors.BulkWriteError as e:
            print(f"  floorsClimbed: {e.details.get('nInserted', 0)} inserted (some duplicates skipped)")
    else:
        print("  floorsClimbed: no new records")


def import_weight():
    """Import weight, body fat, and basal metabolic rate from weight CSV."""
    weight_col = db["weight"]
    bodyfat_col = db["bodyFat"]
    bmr_col = db["basalMetabolicRate"]
    existing_w = {d["_id"] for d in weight_col.find({}, {"_id": 1})}
    existing_bf = {d["_id"] for d in bodyfat_col.find({}, {"_id": 1})}
    existing_bmr = {d["_id"] for d in bmr_col.find({}, {"_id": 1})}

    rows = read_csv("com.samsung.health.weight.{SH_TS}.csv")
    w_ops, bf_ops, bmr_ops = [], [], []
    for r in rows:
        datauuid = r.get("datauuid", "").strip()
        start = r.get("start_time", "").strip()
        offset = r.get("time_offset", "").strip()
        weight = r.get("weight", "").strip()
        body_fat = r.get("body_fat", "").strip()
        bmr = r.get("basal_metabolic_rate", "").strip()
        height = r.get("height", "").strip()
        pkg = r.get("pkg_name", "").strip()

        if not datauuid or not start:
            continue

        start_dt = parse_sh_time(start, offset)
        if not start_dt:
            continue

        app = pkg or "com.sec.android.app.shealth"

        if weight and datauuid not in existing_w:
            w_ops.append(pymongo.InsertOne({
                "_id": datauuid,
                "app": app,
                "start": start_dt,
                "end": None,
                "weight": float(weight),
                "height": float(height) if height else None,
            }))

        if body_fat and datauuid not in existing_bf:
            bf_ops.append(pymongo.InsertOne({
                "_id": datauuid,
                "app": app,
                "start": start_dt,
                "end": None,
                "percentage": float(body_fat),
            }))

        if bmr and datauuid not in existing_bmr:
            bmr_ops.append(pymongo.InsertOne({
                "_id": datauuid,
                "app": app,
                "start": start_dt,
                "end": None,
                "bmr": float(bmr),
            }))

    for name, col, ops in [("weight", weight_col, w_ops), ("bodyFat", bodyfat_col, bf_ops), ("basalMetabolicRate", bmr_col, bmr_ops)]:
        if ops:
            try:
                result = col.bulk_write(ops, ordered=False)
                print(f"  {name}: {result.inserted_count} inserted")
            except pymongo.errors.BulkWriteError as e:
                print(f"  {name}: {e.details.get('nInserted', 0)} inserted (some duplicates skipped)")
        else:
            print(f"  {name}: no new records")


def import_height():
    col = db["height"]
    existing_ids = {d["_id"] for d in col.find({}, {"_id": 1})}

    rows = read_csv("com.samsung.health.height.{SH_TS}.csv")
    ops = []
    for r in rows:
        datauuid = r.get("datauuid", "").strip()
        start = r.get("start_time", "").strip()
        offset = r.get("time_offset", "").strip()
        height = r.get("height", "").strip()
        pkg = r.get("pkg_name", "").strip()

        if not datauuid or not start or not height:
            continue
        if datauuid in existing_ids:
            continue

        start_dt = parse_sh_time(start, offset)
        if not start_dt:
            continue

        doc = {
            "_id": datauuid,
            "app": pkg or "com.sec.android.app.shealth",
            "start": start_dt,
            "end": None,
            "height": float(height),
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        try:
            result = col.bulk_write(ops, ordered=False)
            print(f"  height: {result.inserted_count} inserted")
        except pymongo.errors.BulkWriteError as e:
            print(f"  height: {e.details.get('nInserted', 0)} inserted (some duplicates skipped)")
    else:
        print("  height: no new records")


def import_blood_pressure():
    col = db["bloodPressure"]
    existing_ids = {d["_id"] for d in col.find({}, {"_id": 1})}

    rows = read_csv("com.samsung.shealth.blood_pressure.{SH_TS}.csv")
    ops = []
    for r in rows:
        datauuid = r.get("com.samsung.health.blood_pressure.datauuid", "").strip()
        start = r.get("com.samsung.health.blood_pressure.start_time", "").strip()
        offset = r.get("com.samsung.health.blood_pressure.time_offset", "").strip()
        systolic = r.get("com.samsung.health.blood_pressure.systolic", "").strip()
        diastolic = r.get("com.samsung.health.blood_pressure.diastolic", "").strip()
        pulse = r.get("com.samsung.health.blood_pressure.pulse", "").strip()
        pkg = r.get("com.samsung.health.blood_pressure.pkg_name", "").strip()

        if not datauuid or not start:
            continue
        if datauuid in existing_ids:
            continue

        start_dt = parse_sh_time(start, offset)
        if not start_dt:
            continue

        doc = {
            "_id": datauuid,
            "app": pkg or "com.sec.android.app.shealth",
            "start": start_dt,
            "end": None,
            "systolic": float(systolic) if systolic else None,
            "diastolic": float(diastolic) if diastolic else None,
            "pulse": float(pulse) if pulse else None,
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        try:
            result = col.bulk_write(ops, ordered=False)
            print(f"  bloodPressure: {result.inserted_count} inserted")
        except pymongo.errors.BulkWriteError as e:
            print(f"  bloodPressure: {e.details.get('nInserted', 0)} inserted (some duplicates skipped)")
    else:
        print("  bloodPressure: no new records")


def import_vo2max():
    """Import VO2 Max from Health Connect export (not in Samsung Health CSV)."""
    col = db["vo2Max"]
    existing_ids = {d["_id"] for d in col.find({}, {"_id": 1})}

    import sqlite3 as sq3
    sq = sq3.connect("/tmp/hc_export/health_connect_export.db")
    sq.row_factory = sq3.Row

    rows = sq.execute("SELECT * FROM vo2_max_record_table").fetchall()
    ops = []
    for r in rows:
        uid = r["uuid"].hex()
        uid = f"{uid[0:8]}-{uid[8:12]}-{uid[12:16]}-{uid[16:20]}-{uid[20:32]}"
        if uid in existing_ids:
            continue

        start_dt = datetime.fromtimestamp(r["time"] / 1000, tz=timezone.utc)

        # Get app name
        app_row = sq.execute("SELECT package_name FROM application_info_table WHERE row_id = ?", (r["app_info_id"],)).fetchone()
        app = app_row["package_name"] if app_row else "unknown"

        doc = {
            "_id": uid,
            "app": app,
            "start": start_dt,
            "end": None,
            "vo2Max": r["vo2_milliliters_per_minute_kilogram"],
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        try:
            result = col.bulk_write(ops, ordered=False)
            print(f"  vo2Max: {result.inserted_count} inserted")
        except pymongo.errors.BulkWriteError as e:
            print(f"  vo2Max: {e.details.get('nInserted', 0)} inserted (some duplicates skipped)")
    else:
        print("  vo2Max: no new records")

    sq.close()


IMPORTERS = {
    "heartRate": import_heart_rate,
    "sleep": import_sleep,
    "steps": import_steps,         # also writes distance
    "distance": import_steps,      # alias: same source
    "calories": import_calories,
    "totalCaloriesBurned": import_calories,
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

DEFAULT_ORDER = [
    "heartRate", "sleep", "steps", "calories", "spo2", "exercise",
    "hrv", "stress", "vitality", "respiratoryRate", "skinTemperature",
    "floors", "weight", "height", "bloodPressure", "vo2Max",
]


def main():
    import sys
    args = sys.argv[1:]
    if args:
        unknown = [a for a in args if a not in IMPORTERS]
        if unknown:
            print(f"Unknown types: {unknown}")
            print(f"Available: {sorted(IMPORTERS.keys())}")
            sys.exit(1)
        # Dedup while preserving order, mapping aliases to the same function once.
        seen = set()
        funcs = []
        for a in args:
            f = IMPORTERS[a]
            if f not in seen:
                seen.add(f)
                funcs.append((a, f))
        print(f"Importing selected types: {[n for n, _ in funcs]}")
        for _, f in funcs:
            f()
    else:
        print("Importing Samsung Health export into MongoDB (gap-day filtered)...")
        for name in DEFAULT_ORDER:
            IMPORTERS[name]()
    print("Done. Run post-process trigger to recompute sleepRollingStats.")


if __name__ == "__main__":
    main()
