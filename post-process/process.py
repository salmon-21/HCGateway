"""
Computes derived collections (sleepRollingStats) on top of the data the
API writes to hcgateway_<userid>. Runs in a loop every SYNC_INTERVAL
seconds and on POST /trigger.

Historically this service decrypted Fernet-encrypted source data into a
parallel *_decrypted DB; E1 removed the encryption layer and stage D
collapsed the two DBs into one, so only post-processing remains.
"""

import os
import time
import math
import statistics
import threading
import pymongo
from http.server import HTTPServer, BaseHTTPRequestHandler
from datetime import datetime, timezone, timedelta
from dateutil import parser as dateparser

MONGO_URI = os.environ["MONGO_URI"]
SYNC_INTERVAL = int(os.environ.get("SYNC_INTERVAL", "300"))
JST = timedelta(hours=9)

mongo = pymongo.MongoClient(MONGO_URI)


def get_users():
    db = mongo["hcgateway"]
    return list(db["users"].find())


def jst_day_start_utc(d):
    """Convert a date to the UTC datetime representing start of that JST day."""
    return datetime(d.year, d.month, d.day, tzinfo=timezone.utc) - JST


def _rolling_stats(vals):
    m = statistics.mean(vals)
    sd = statistics.stdev(vals) if len(vals) >= 2 else 0
    return round(m, 2), round(m + sd, 2), round(m - sd, 2)


def _circular_stats(vals):
    """Circular mean/stddev for hour-of-day values (0-24)."""
    rads = [v / 24 * 2 * math.pi for v in vals]
    sin_m = statistics.mean([math.sin(r) for r in rads])
    cos_m = statistics.mean([math.cos(r) for r in rads])
    m = (math.atan2(sin_m, cos_m) / (2 * math.pi) * 24) % 24
    if len(vals) >= 2:
        unwrapped = []
        for v in vals:
            diff = v - m
            while diff > 12: diff -= 24
            while diff < -12: diff += 24
            unwrapped.append(m + diff)
        sd = statistics.stdev(unwrapped)
    else:
        sd = 0
    return round(m, 2), round(m + sd, 2), round(m - sd, 2)


# Cache last session count to skip recompute when unchanged
_last_sleep_count = {}


def compute_sleep_rolling_stats(dst_db):
    """Compute 7-day rolling mean/stddev for sleep timing metrics."""
    col = dst_db["sleepSession"]
    stats_col = dst_db["sleepRollingStats"]

    current_count = col.count_documents({})
    db_name = dst_db.name
    if _last_sleep_count.get(db_name) == current_count and current_count > 0:
        return
    _last_sleep_count[db_name] = current_count

    sessions = list(col.find().sort("start", 1))
    if not sessions:
        return

    WAKE_STAGES = {1, 3}  # AWAKE, OUT_OF_BED

    valid = []
    for s in sessions:
        start, end = s.get("start"), s.get("end")
        if not isinstance(start, datetime) or not isinstance(end, datetime):
            continue
        # Calculate actual sleep time excluding wake stages
        actual_secs = 0
        stages = s.get("stages", [])
        if stages:
            for st in stages:
                if st.get("stage") not in WAKE_STAGES:
                    try:
                        st_start = dateparser.isoparse(st["startTime"]) if isinstance(st["startTime"], str) else st["startTime"]
                        st_end = dateparser.isoparse(st["endTime"]) if isinstance(st["endTime"], str) else st["endTime"]
                        actual_secs += (st_end - st_start).total_seconds()
                    except Exception:
                        pass
        actual_hours = actual_secs / 3600 if actual_secs > 0 else (end - start).total_seconds() / 3600
        valid.append((start, end, actual_hours))

    # Assign sessions to "sleep day" based on bedtime (JST)
    # 18:00-23:59 → next day; 00:00-17:59 → same day
    daily_sessions = {}
    for start, end, actual in valid:
        start_jst = start + JST
        if start_jst.hour >= 18:
            day_key = (start_jst + timedelta(days=1)).date()
        else:
            day_key = start_jst.date()
        daily_sessions.setdefault(day_key, []).append((start, end, actual))

    wake_daily = {}
    for start, end, actual in valid:
        wake_day = (end + JST).date()
        wake_daily.setdefault(wake_day, []).append((start, end, actual))

    all_days = sorted(set(list(daily_sessions.keys()) + list(wake_daily.keys())))
    merged = {}
    for day in all_days:
        if day in daily_sessions:
            merged[day] = daily_sessions[day]
        elif day in wake_daily:
            merged[day] = wake_daily[day]

    records = []
    for day_key in sorted(merged.keys()):
        sess = merged[day_key]
        total_duration = sum((end - start).total_seconds() / 3600 for start, end, actual in sess)
        actual_duration = sum(actual for start, end, actual in sess)
        # Use longest session for timing (bedtime/wake/midpoint) to exclude naps
        longest = max(sess, key=lambda s: (s[1] - s[0]).total_seconds())
        start_jst = longest[0] + JST
        end_jst = longest[1] + JST
        bedtime = start_jst.hour + start_jst.minute / 60
        wake = end_jst.hour + end_jst.minute / 60
        mid = ((bedtime + wake + 24) / 2) % 24 if bedtime > wake else (bedtime + wake) / 2
        records.append({
            "time": jst_day_start_utc(day_key),
            "bedtime": round(bedtime, 1),
            "wake": round(wake, 1),
            "midpoint": round(mid, 1),
            "duration": round(total_duration, 1),
            "actual_duration": round(actual_duration, 1),
        })

    if not records:
        return

    window = 7
    half = window // 2
    result_docs = []

    for i, rec in enumerate(records):
        lo = max(0, i - half)
        hi = min(len(records), i + half + 1)
        w = records[lo:hi]

        b_ma, b_up, b_lo = _circular_stats([r["bedtime"] for r in w])
        w_ma, w_up, w_lo = _circular_stats([r["wake"] for r in w])
        m_ma, m_up, m_lo = _rolling_stats([r["midpoint"] for r in w])
        d_ma, d_up, d_lo = _rolling_stats([r["duration"] for r in w])
        a_ma, a_up, a_lo = _rolling_stats([r["actual_duration"] for r in w])

        result_docs.append({
            "time": rec["time"],
            "bedtime": rec["bedtime"],
            "bedtime_ma": b_ma, "bedtime_upper": b_up, "bedtime_lower": b_lo,
            "wake": rec["wake"],
            "wake_ma": w_ma, "wake_upper": w_up, "wake_lower": w_lo,
            "midpoint": rec["midpoint"],
            "midpoint_ma": m_ma, "midpoint_upper": m_up, "midpoint_lower": m_lo,
            "duration": rec["duration"],
            "duration_ma": d_ma, "duration_upper": d_up, "duration_lower": d_lo,
            "actual_duration": rec["actual_duration"],
            "actual_duration_ma": a_ma, "actual_duration_upper": a_up, "actual_duration_lower": a_lo,
        })

    stats_col.drop()
    if result_docs:
        stats_col.insert_many(result_docs)
        print(f"  sleepRollingStats: {len(result_docs)} docs computed")


def run_sync():
    try:
        users = get_users()
        for user in users:
            print(f"Processing user: {user['_id']}")
            dst_db = mongo[f"hcgateway_{user['_id']}"]
            compute_sleep_rolling_stats(dst_db)
    except Exception as e:
        print(f"Error: {e}")


_sync_lock = threading.Lock()


def _run_sync_locked():
    if not _sync_lock.acquire(blocking=False):
        return
    try:
        run_sync()
    finally:
        _sync_lock.release()


class TriggerHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path == "/trigger":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"ok":true}')
            threading.Thread(target=_run_sync_locked, daemon=True).start()
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass


def main():
    port = int(os.environ.get("TRIGGER_PORT", "7000"))
    server = HTTPServer(("0.0.0.0", port), TriggerHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    print(f"Post-process started. Trigger: :{port}/trigger, Fallback interval: {SYNC_INTERVAL}s")

    while True:
        _run_sync_locked()
        time.sleep(SYNC_INTERVAL)


if __name__ == "__main__":
    main()
