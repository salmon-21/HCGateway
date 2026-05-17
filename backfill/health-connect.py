"""
Import Health Connect SQLite export into MongoDB.
Writes to hcgateway_<userid> in the shape the API produces post-E1.
"""

import sqlite3
import pymongo
from datetime import datetime, timezone

SQLITE_PATH = "/tmp/hc_export/health_connect_export.db"
MONGO_URI = "mongodb://root:example@localhost:27017/"
DB_NAME = "hcgateway_69b3d84d26c5efd2f4af0b53"

mongo = pymongo.MongoClient(MONGO_URI)
db = mongo[DB_NAME]
sq = sqlite3.connect(SQLITE_PATH)
sq.row_factory = sqlite3.Row


def blob_to_uuid(b):
    h = b.hex()
    return f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"


def ms_to_dt(ms):
    if ms is None:
        return None
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc)


def get_app_name(app_info_id):
    row = sq.execute("SELECT package_name FROM application_info_table WHERE row_id = ?", (app_info_id,)).fetchone()
    return row["package_name"] if row else "unknown"


def import_heart_rate():
    col = db["heartRate"]
    existing = {d["_id"] for d in col.find({}, {"_id": 1})}

    rows = sq.execute("SELECT * FROM heart_rate_record_table").fetchall()
    ops = []
    for r in rows:
        uid = blob_to_uuid(r["uuid"])
        if uid in existing:
            continue

        samples_rows = sq.execute(
            "SELECT beats_per_minute, epoch_millis FROM heart_rate_record_series_table WHERE parent_key = ?",
            (r["row_id"],)
        ).fetchall()

        samples = [{"beatsPerMinute": s["beats_per_minute"], "time": ms_to_dt(s["epoch_millis"])} for s in samples_rows]

        doc = {
            "_id": uid,
            "app": get_app_name(r["app_info_id"]),
            "start": ms_to_dt(r["start_time"]),
            "end": ms_to_dt(r["end_time"]),
            "samples": samples,
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        result = col.bulk_write(ops, ordered=False)
        print(f"  heartRate: {result.inserted_count} inserted")
    else:
        print("  heartRate: no new records")


def import_sleep():
    col = db["sleepSession"]
    existing = {d["_id"] for d in col.find({}, {"_id": 1})}

    rows = sq.execute("SELECT * FROM sleep_session_record_table").fetchall()
    ops = []
    for r in rows:
        uid = blob_to_uuid(r["uuid"])
        if uid in existing:
            continue

        stages_rows = sq.execute(
            "SELECT stage_start_time, stage_end_time, stage_type FROM sleep_stages_table WHERE parent_key = ?",
            (r["row_id"],)
        ).fetchall()

        stages = [{
            "startTime": ms_to_dt(s["stage_start_time"]),
            "endTime": ms_to_dt(s["stage_end_time"]),
            "stage": s["stage_type"],
        } for s in stages_rows]

        doc = {
            "_id": uid,
            "app": get_app_name(r["app_info_id"]),
            "start": ms_to_dt(r["start_time"]),
            "end": ms_to_dt(r["end_time"]),
            "stages": stages,
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        result = col.bulk_write(ops, ordered=False)
        print(f"  sleepSession: {result.inserted_count} inserted")
    else:
        print("  sleepSession: no new records")


def import_steps():
    col = db["steps"]
    existing = {d["_id"] for d in col.find({}, {"_id": 1})}

    rows = sq.execute("SELECT * FROM steps_record_table").fetchall()
    ops = []
    for r in rows:
        uid = blob_to_uuid(r["uuid"])
        if uid in existing:
            continue
        doc = {
            "_id": uid,
            "app": get_app_name(r["app_info_id"]),
            "start": ms_to_dt(r["start_time"]),
            "end": ms_to_dt(r["end_time"]),
            "count": r["count"],
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        result = col.bulk_write(ops, ordered=False)
        print(f"  steps: {result.inserted_count} inserted")
    else:
        print("  steps: no new records")


def import_distance():
    """SQLite distance is in meters. Grafana uses $distance.inKilometers."""
    col = db["distance"]
    existing = {d["_id"] for d in col.find({}, {"_id": 1})}

    rows = sq.execute("SELECT * FROM distance_record_table").fetchall()
    ops = []
    for r in rows:
        uid = blob_to_uuid(r["uuid"])
        if uid in existing:
            continue
        meters = r["distance"]
        doc = {
            "_id": uid,
            "app": get_app_name(r["app_info_id"]),
            "start": ms_to_dt(r["start_time"]),
            "end": ms_to_dt(r["end_time"]),
            "distance": {
                "inMeters": meters,
                "inKilometers": meters / 1000,
            },
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        result = col.bulk_write(ops, ordered=False)
        print(f"  distance: {result.inserted_count} inserted")
    else:
        print("  distance: no new records")


def import_calories():
    """SQLite energy is in joules. Grafana uses $energy.inKilocalories."""
    col = db["totalCaloriesBurned"]
    existing = {d["_id"] for d in col.find({}, {"_id": 1})}

    rows = sq.execute("SELECT * FROM total_calories_burned_record_table").fetchall()
    ops = []
    for r in rows:
        uid = blob_to_uuid(r["uuid"])
        if uid in existing:
            continue
        joules = r["energy"]
        doc = {
            "_id": uid,
            "app": get_app_name(r["app_info_id"]),
            "start": ms_to_dt(r["start_time"]),
            "end": ms_to_dt(r["end_time"]),
            "energy": {
                "inJoules": joules,
                "inKilocalories": joules / 4184,
            },
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        result = col.bulk_write(ops, ordered=False)
        print(f"  totalCaloriesBurned: {result.inserted_count} inserted")
    else:
        print("  totalCaloriesBurned: no new records")


def import_spo2():
    col = db["oxygenSaturation"]
    existing = {d["_id"] for d in col.find({}, {"_id": 1})}

    rows = sq.execute("SELECT * FROM oxygen_saturation_record_table").fetchall()
    ops = []
    for r in rows:
        uid = blob_to_uuid(r["uuid"])
        if uid in existing:
            continue

        doc = {
            "_id": uid,
            "app": get_app_name(r["app_info_id"]),
            "start": ms_to_dt(r["time"]),
            "end": None,
            "percentage": r["percentage"],
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        result = col.bulk_write(ops, ordered=False)
        print(f"  oxygenSaturation: {result.inserted_count} inserted")
    else:
        print("  oxygenSaturation: no new records")


def import_speed():
    col = db["speed"]
    existing = {d["_id"] for d in col.find({}, {"_id": 1})}

    # Speed is a series table linked to exercise sessions
    # But in HCGateway it's stored per-record with samples
    # Check if there's a parent speed record table
    rows = sq.execute("SELECT * FROM speed_record_table").fetchall()
    if not rows:
        print("  speed: no records in SQLite")
        return

    # speed_record_table is a series table with parent_key
    # Need to find the parent - check SpeedRecordTable
    parent_rows = sq.execute("SELECT * FROM SpeedRecordTable").fetchall()
    ops = []
    for r in parent_rows:
        uid = blob_to_uuid(r["uuid"])
        if uid in existing:
            continue

        samples_rows = sq.execute(
            "SELECT speed, epoch_millis FROM speed_record_table WHERE parent_key = ?",
            (r["row_id"],)
        ).fetchall()

        samples = [{"metersPerSecond": s["speed"], "time": ms_to_dt(s["epoch_millis"])} for s in samples_rows]

        doc = {
            "_id": uid,
            "app": get_app_name(r["app_info_id"]),
            "start": ms_to_dt(r["start_time"]),
            "end": ms_to_dt(r["end_time"]),
            "samples": samples,
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        result = col.bulk_write(ops, ordered=False)
        print(f"  speed: {result.inserted_count} inserted")
    else:
        print("  speed: no new records")


def import_exercise():
    col = db["exerciseSession"]
    existing = {d["_id"] for d in col.find({}, {"_id": 1})}

    rows = sq.execute("SELECT * FROM exercise_session_record_table").fetchall()
    ops = []
    for r in rows:
        uid = blob_to_uuid(r["uuid"])
        if uid in existing:
            continue

        doc = {
            "_id": uid,
            "app": get_app_name(r["app_info_id"]),
            "start": ms_to_dt(r["start_time"]),
            "end": ms_to_dt(r["end_time"]),
            "exerciseType": r["exercise_type"],
            "title": r["title"],
            "notes": r["notes"],
        }
        ops.append(pymongo.InsertOne(doc))

    if ops:
        result = col.bulk_write(ops, ordered=False)
        print(f"  exerciseSession: {result.inserted_count} inserted")
    else:
        print("  exerciseSession: no new records")


def main():
    print("Importing Health Connect export into MongoDB...")
    import_heart_rate()
    import_sleep()
    import_steps()
    import_distance()
    import_calories()
    import_spo2()
    import_speed()
    import_exercise()
    print("Done.")


if __name__ == "__main__":
    main()
