from flask import Blueprint, request, jsonify, g
import os
import json
import secrets
import datetime
import time
from dotenv import load_dotenv
load_dotenv()
from pyfcm import FCMNotification
from argon2 import PasswordHasher
from dateutil import parser as dateparser
from zoneinfo import ZoneInfo
from psycopg.types.json import Json

from db import pool, fetch_one, fetch_all
from method_schema import (
    METHOD_SCHEMA, NULLABLE_COLS,
    get_nested, normalize_method, pick_scalar, cast_for_col,
)

ph = PasswordHasher()

# Timezone used for /status calendar-day computations (streak, day boundaries).
# Default UTC matches the moromiso Worker's TIMEZONE so both ends share the
# same notion of "today". Threshold math (12h/24h staleness) is tz-invariant.
STATUS_TZ = ZoneInfo(os.environ.get("STATUS_TZ", "UTC"))

v2 = Blueprint('v2', __name__, url_prefix='/api/v2/')


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _coerce_time(v):
    """Accept ISO-8601 string or datetime; return aware datetime or None."""
    if isinstance(v, str):
        try:
            return dateparser.isoparse(v)
        except Exception:
            return None
    return v


# Streak cache: {(user_id_str, tz_key): (value, expiry_monotonic)}
_streak_cache = {}
_STREAK_TTL_SECONDS = 900  # > moromiso 5-min poll → cache hits dominate


def _heartrate_streak(user_id, tz=STATUS_TZ, max_days=365):
    """Consecutive tz-calendar-days (today inclusive) with heart_rate_sample
    data. If today has no data yet, count from yesterday so the streak doesn't
    drop to 0 in the early morning."""
    cache_key = (str(user_id), tz.key)
    cached = _streak_cache.get(cache_key)
    now_mono = time.monotonic()
    if cached and cached[1] > now_mono:
        return cached[0]

    now_local = datetime.datetime.now(tz)
    today_local = now_local.date()
    cutoff = now_local - datetime.timedelta(days=max_days)

    try:
        rows = fetch_all(
            "SELECT DISTINCT (time AT TIME ZONE %s)::date AS d "
            "FROM heart_rate_sample WHERE user_id = %s AND time >= %s",
            (tz.key, str(user_id), cutoff),
        )
        days_with_data = {r["d"] for r in rows}
    except Exception as e:
        print(f"  streak query failed: {e}")
        return 0

    start_offset = 0 if today_local in days_with_data else 1
    streak = 0
    for i in range(start_offset, max_days):
        d = today_local - datetime.timedelta(days=i)
        if d in days_with_data:
            streak += 1
        else:
            break

    _streak_cache[cache_key] = (streak, now_mono + _STREAK_TTL_SECONDS)
    return streak


# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------

@v2.before_request
def before_request():
    if request.endpoint in ('v2.login', 'v2.refresh', 'v2.health', 'v2.status'):
        return

    auth = request.headers.get('Authorization', '')
    if not auth:
        return jsonify({'error': 'no token provided'}), 400
    try:
        token = auth.split(' ')[1]
    except IndexError:
        return jsonify({'error': 'invalid Authorization header'}), 400

    user = fetch_one(
        "SELECT id, expiry FROM users WHERE token = %s",
        (token,),
    )
    if not user:
        return jsonify({'error': 'invalid token'}), 403

    expiry = user['expiry']
    if expiry is not None:
        if expiry.tzinfo is None:
            expiry = expiry.replace(tzinfo=datetime.timezone.utc)
        if datetime.datetime.now(datetime.timezone.utc) > expiry:
            return jsonify({'error': 'token expired. Use /api/v2/login to reauthenticate.'}), 403

    g.user = user['id']


# ---------------------------------------------------------------------------
# Login / refresh / revoke
# ---------------------------------------------------------------------------

@v2.route("/login", methods=['POST'])
def login():
    if not request.json or 'username' not in request.json or 'password' not in request.json:
        return jsonify({'error': 'invalid request'}), 400

    username = request.json['username']
    password = request.json['password']
    fcm_token = request.json.get('fcmToken')

    user = fetch_one(
        "SELECT id, password, token, refresh, expiry FROM users WHERE username = %s",
        (username,),
    )

    now_utc = datetime.datetime.now(datetime.timezone.utc)
    expiry = now_utc + datetime.timedelta(hours=12)

    if not user:
        new_token = secrets.token_urlsafe(32)
        new_refresh = secrets.token_urlsafe(32)
        with pool.connection() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "INSERT INTO users (username, password, token, refresh, expiry, fcm_token) "
                    "VALUES (%s, %s, %s, %s, %s, %s)",
                    (username, ph.hash(password), new_token, new_refresh, expiry, fcm_token),
                )
        return jsonify({"token": new_token, "refresh": new_refresh, "expiry": expiry.isoformat()}), 201

    try:
        ph.verify(user['password'], password)
    except Exception:
        return jsonify({'error': 'invalid password'}), 403

    if fcm_token:
        with pool.connection() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "UPDATE users SET fcm_token = %s WHERE id = %s",
                    (fcm_token, user['id']),
                )

    existing_expiry = user['expiry']
    if existing_expiry is not None and existing_expiry.tzinfo is None:
        existing_expiry = existing_expiry.replace(tzinfo=datetime.timezone.utc)
    if user['token'] and existing_expiry and now_utc <= existing_expiry:
        return jsonify({
            "token": user['token'],
            "refresh": user['refresh'],
            "expiry": existing_expiry.isoformat(),
        }), 201

    new_token = secrets.token_urlsafe(32)
    new_refresh = secrets.token_urlsafe(32)
    with pool.connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE users SET token = %s, refresh = %s, expiry = %s WHERE id = %s",
                (new_token, new_refresh, expiry, user['id']),
            )
    return jsonify({"token": new_token, "refresh": new_refresh, "expiry": expiry.isoformat()}), 201


@v2.route("/refresh", methods=['POST'])
def refresh():
    if not request.json or 'refresh' not in request.json:
        return jsonify({'error': 'invalid request'}), 400

    refresh_tok = request.json['refresh']
    user = fetch_one("SELECT id FROM users WHERE refresh = %s", (refresh_tok,))
    if not user:
        return jsonify({'error': 'invalid refresh token'}), 403

    new_token = secrets.token_urlsafe(32)
    expiry = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=12)
    with pool.connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE users SET token = %s, expiry = %s WHERE id = %s",
                (new_token, expiry, user['id']),
            )
    return jsonify({"token": new_token, "refresh": refresh_tok, "expiry": expiry.isoformat()}), 200


@v2.route("/revoke", methods=['DELETE'])
def revoke():
    auth = request.headers.get('Authorization', '')
    try:
        token = auth.split(' ')[1]
    except IndexError:
        return jsonify({'error': 'invalid Authorization header'}), 400

    user = fetch_one("SELECT id FROM users WHERE token = %s", (token,))
    if not user:
        return jsonify({'error': 'invalid token'}), 403

    with pool.connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE users SET token = NULL, refresh = NULL, expiry = NULL WHERE id = %s",
                (user['id'],),
            )
    return jsonify({"success": True}), 200


# ---------------------------------------------------------------------------
# Health / status / counts
# ---------------------------------------------------------------------------

@v2.get("/health")
def health():
    return jsonify({"status": "ok"}), 200


@v2.get("/status")
def status():
    """External monitoring endpoint. Auth at the edge via Cloudflare Workers
    VPC binding; this handler intentionally skips HCGateway-level auth.

    dataSync.status tracks heart_rate_sample freshness only.
    """
    STALE_OK_HOURS = 12
    STALE_DEGRADED_HOURS = 24

    handler_t0 = time.monotonic()
    components = {"api": {"status": "ok"}, "db": {}, "dataSync": {}}

    # Pick the user with the freshest heart_rate_sample. Doing it this way
    # (instead of `SELECT id FROM users LIMIT 1`) handles multi-user setups
    # where some rows in `users` are empty/test accounts — the order of an
    # unqualified LIMIT 1 is undefined and historically returned the wrong
    # account here.
    user_id = None
    latest_per_type = {}
    try:
        t0 = time.time()
        row = fetch_one(
            "SELECT user_id::text AS user_id, max(time) AS latest "
            "FROM heart_rate_sample GROUP BY user_id "
            "ORDER BY max(time) DESC NULLS LAST LIMIT 1"
        )
        ms = int((time.time() - t0) * 1000)
        components["db"] = {"status": "ok", "responseMs": ms}
        if row and row["latest"]:
            user_id = row["user_id"]
            latest_per_type["heartRate"] = row["latest"]
    except Exception:
        components["db"] = {"status": "down"}

    if user_id is None:
        # No heart-rate data anywhere — fall back to any user to keep the
        # response shape stable; verdict will be "unknown".
        fallback = fetch_one("SELECT id::text AS id FROM users ORDER BY id LIMIT 1")
        if not fallback:
            components["dataSync"] = {"status": "unknown"}
            components["api"]["responseMs"] = int((time.monotonic() - handler_t0) * 1000)
            return jsonify({
                "components": components,
                "checkedAt": datetime.datetime.now(STATUS_TZ).isoformat(),
            }), 200
        user_id = fallback["id"]

    try:
        for method, table in (
            ("steps", "steps"),
            ("distance", "distance"),
            ("totalCaloriesBurned", "total_calories_burned"),
        ):
            row = fetch_one(
                f"SELECT max(start_at) AS latest FROM {table} WHERE user_id = %s",
                (user_id,),
            )
            if row and row["latest"]:
                latest_per_type[method] = row["latest"]

        primary_dt = latest_per_type.get("heartRate")
        if primary_dt:
            age_hours = (datetime.datetime.now(datetime.timezone.utc) - primary_dt).total_seconds() / 3600
            if age_hours < STALE_OK_HOURS:
                sync_status = "ok"
            elif age_hours < STALE_DEGRADED_HOURS:
                sync_status = "degraded"
            else:
                sync_status = "down"
            components["dataSync"] = {
                "status": sync_status,
                "lastData": primary_dt.isoformat(),
                "streak": _heartrate_streak(user_id),
                "lastDataPerType": {k: v.isoformat() for k, v in latest_per_type.items()},
            }
        else:
            components["dataSync"] = {"status": "unknown"}
    except Exception:
        components["dataSync"] = {"status": "down"}

    components["api"]["responseMs"] = int((time.monotonic() - handler_t0) * 1000)
    return jsonify({
        "components": components,
        "checkedAt": datetime.datetime.now(STATUS_TZ).isoformat(),
    }), 200


@v2.get("/counts")
def counts():
    user_id = str(g.user)
    result = {}
    with pool.connection() as conn:
        with conn.cursor() as cur:
            for method, schema in METHOD_SCHEMA.items():
                table = schema["table"]
                if schema["kind"] == "samples":
                    sql = f"SELECT count(DISTINCT source_id) FROM {table} WHERE user_id = %s"
                else:
                    sql = f"SELECT count(*) FROM {table} WHERE user_id = %s"
                try:
                    cur.execute(sql, (user_id,))
                    n = cur.fetchone()[0]
                except Exception as e:
                    print(f"counts {method} failed: {e}")
                    n = None
                if n is not None:
                    display = method[0].upper() + method[1:]
                    result[display] = n
    return jsonify(result), 200


# ---------------------------------------------------------------------------
# Sync (write) — generic dispatch driven by METHOD_SCHEMA
# ---------------------------------------------------------------------------

def _insert_samples(conn, schema, user_id, items):
    """For kind='samples': DELETE by source_id then INSERT flattened rows.

    Returns (written, skipped). Samples missing the value column or with
    invalid time are silently dropped — matches the migration script's
    behaviour for empty Health Connect records.
    """
    table = schema["table"]
    sample_path = schema["sample_path"]
    time_field = schema["sample_time_field"]
    val_cols = schema["value_cols"]

    source_ids, rows, skipped = [], [], 0
    for item in items:
        meta = item.get("metadata") or {}
        source_id = meta.get("id")
        app = meta.get("dataOrigin")
        if not source_id:
            skipped += 1
            continue
        source_ids.append(source_id)
        for s in item.get(sample_path) or []:
            t = _coerce_time(s.get(time_field))
            if t is None:
                skipped += 1
                continue
            values, missing = [], False
            for src, _, sql_type in val_cols:
                v = cast_for_col(pick_scalar(s.get(src)), sql_type)
                if v is None:
                    missing = True
                    break
                values.append(v)
            if missing:
                skipped += 1
                continue
            rows.append([t, str(user_id), source_id, app, *values])

    cols = ["time", "user_id", "source_id", "app"] + [c[1] for c in val_cols]
    with conn.cursor() as cur:
        if source_ids:
            cur.execute(
                f"DELETE FROM {table} WHERE source_id = ANY(%s::uuid[])",
                (source_ids,),
            )
        if rows:
            placeholders = ",".join(["%s"] * len(cols))
            cur.executemany(
                f"INSERT INTO {table} ({','.join(cols)}) VALUES ({placeholders})",
                rows,
            )
    return len(rows), skipped


def _insert_records(conn, schema, user_id, items):
    """For kind='interval' or 'instant': upsert 1 row per source doc.

    Returns (written, skipped). Rows missing a NOT NULL value column are
    dropped instead of raising 500 for the whole batch — same skip semantics
    as the migration script.
    """
    table = schema["table"]
    kind = schema["kind"]
    is_hyper = schema.get("is_hypertable", False)
    val_cols = schema["value_cols"]
    nullables = NULLABLE_COLS.get(table, set())

    rows, skipped = [], 0
    for item in items:
        meta = item.get("metadata") or {}
        item_id = meta.get("id")
        if not item_id:
            skipped += 1
            continue
        app = meta.get("dataOrigin")

        if kind == "instant":
            start_at = _coerce_time(item.get("time") or item.get("startTime"))
            end_at = None
        else:
            start_at = _coerce_time(item.get("startTime"))
            end_at = _coerce_time(item.get("endTime"))
        if start_at is None:
            skipped += 1
            continue

        row = {"id": item_id, "user_id": str(user_id), "start_at": start_at, "app": app}
        if kind != "instant":
            row["end_at"] = end_at
        row_skip = False
        for src, dst, sql_type in val_cols:
            v = get_nested(item, src)
            if sql_type == "jsonb":
                if v is not None:
                    v = Json(v)
            else:
                v = cast_for_col(pick_scalar(v), sql_type)
            if v is None and dst not in nullables:
                row_skip = True
                break
            row[dst] = v
        if row_skip:
            skipped += 1
            continue
        rows.append(row)

    if not rows:
        return 0, skipped

    cols = list(rows[0].keys())
    conflict = "(start_at, id)" if is_hyper else "(id)"
    updates = ", ".join(f"{c} = EXCLUDED.{c}" for c in cols if c not in ("id", "start_at"))
    placeholders = ",".join(["%s"] * len(cols))
    sql = (
        f"INSERT INTO {table} ({','.join(cols)}) VALUES ({placeholders}) "
        f"ON CONFLICT {conflict} DO UPDATE SET {updates}"
    )
    with conn.cursor() as cur:
        cur.executemany(sql, [[r[c] for c in cols] for r in rows])
    return len(rows), skipped


@v2.post("/sync/<method>")
def sync(method):
    norm, schema = normalize_method(method)
    if schema is None:
        return jsonify({'error': f'unknown method: {method}'}), 400
    if not request.json or "data" not in request.json:
        return jsonify({'error': 'no data provided'}), 400

    data = request.json["data"]
    if not isinstance(data, list):
        data = [data]
    print(f"{norm}: {len(data)} records", flush=True)

    try:
        with pool.connection() as conn:
            if schema["kind"] == "samples":
                written, skipped = _insert_samples(conn, schema, g.user, data)
            else:
                written, skipped = _insert_records(conn, schema, g.user, data)
    except Exception as e:
        print(f"sync {norm} failed: {e}", flush=True)
        return jsonify({'error': str(e)}), 500

    return jsonify({'success': True, 'count': written, 'skipped': skipped}), 200


# ---------------------------------------------------------------------------
# Fetch (read)
# ---------------------------------------------------------------------------

@v2.route("/fetch/<method>", methods=['POST'])
def fetch(method):
    norm, schema = normalize_method(method)
    if schema is None:
        return jsonify({'error': f'unknown method: {method}'}), 400

    user_id = str(g.user)
    table = schema["table"]
    body = request.json or {}
    limit = int(body.get("limit", 1000))

    docs = []
    if schema["kind"] == "samples":
        time_field = schema["sample_time_field"]
        src_col, dst_col, _ = schema["value_cols"][0]
        # time_field / src_col come from METHOD_SCHEMA constants (not user input),
        # so inlining them avoids the indeterminate-type pitfall with
        # jsonb_build_object placeholder keys.
        sql = (
            f"SELECT source_id::text AS id, max(app) AS app, "
            f"min(time) AS start_at, max(time) AS end_at, "
            f"jsonb_agg(jsonb_build_object('{time_field}', time, '{src_col}', {dst_col}) ORDER BY time) AS samples "
            f"FROM {table} WHERE user_id = %s "
            f"GROUP BY source_id ORDER BY start_at DESC LIMIT %s"
        )
        rows = fetch_all(sql, (user_id, limit))
        for r in rows:
            docs.append({
                "_id": r["id"], "id": r["id"], "app": r["app"],
                "start": r["start_at"].isoformat() if r["start_at"] else None,
                "end": r["end_at"].isoformat() if r["end_at"] else None,
                "data": {"samples": r["samples"]},
            })
    else:
        val_cols = schema["value_cols"]
        end_col = "end_at" if schema["kind"] != "instant" else "NULL::timestamptz AS end_at"
        select_cols = ["id::text AS id", "app", "start_at", end_col] + [c[1] for c in val_cols]
        sql = (
            f"SELECT {','.join(select_cols)} FROM {table} "
            f"WHERE user_id = %s ORDER BY start_at DESC LIMIT %s"
        )
        rows = fetch_all(sql, (user_id, limit))
        for r in rows:
            rec = {
                "_id": r["id"], "id": r["id"], "app": r["app"],
                "start": r["start_at"].isoformat() if r["start_at"] else None,
                "end": r["end_at"].isoformat() if r["end_at"] else None,
                "data": {},
            }
            for src, dst, _ in val_cols:
                rec["data"][src] = r[dst]
            docs.append(rec)

    return jsonify(docs), 200


# ---------------------------------------------------------------------------
# Server-side delete
# ---------------------------------------------------------------------------

@v2.delete("/sync/<method>")
def del_from_db(method):
    norm, schema = normalize_method(method)
    if schema is None:
        return jsonify({'error': f'unknown method: {method}'}), 400
    if not request.json or "uuid" not in request.json:
        return jsonify({'error': 'no uuid provided'}), 400

    uuids = request.json["uuid"]
    if not isinstance(uuids, list):
        uuids = [uuids]

    user_id = str(g.user)
    table = schema["table"]
    col = "source_id" if schema["kind"] == "samples" else "id"

    with pool.connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"DELETE FROM {table} WHERE {col} = ANY(%s::uuid[]) AND user_id = %s",
                (uuids, user_id),
            )
    return jsonify({'success': True}), 200


# ---------------------------------------------------------------------------
# Push / Delete via FCM (no DB writes)
# ---------------------------------------------------------------------------

@v2.route("/push/<method>", methods=['PUT'])
def push_data(method):
    if not method or not request.json or "data" not in request.json:
        return jsonify({'error': 'invalid request'}), 400
    data = request.json["data"]
    if not isinstance(data, list):
        data = [data]

    fixed = method[0].upper() + method[1:]
    for r in data:
        r["recordType"] = fixed
        if "time" not in r and ("startTime" not in r or "endTime" not in r):
            return jsonify({'error': 'no start time or end time provided. If only one time is to be used, then use the "time" attribute instead.'}), 400
        if ("startTime" in r and "endTime" not in r) or ("startTime" not in r and "endTime" in r):
            return jsonify({'error': 'start time and end time must be provided together.'}), 400

    user = fetch_one("SELECT fcm_token FROM users WHERE id = %s", (str(g.user),))
    fcm_token = user["fcm_token"] if user else None
    if not fcm_token:
        return jsonify({'error': 'no fcm token found'}), 404

    fcm = FCMNotification(service_account_file='service-account.json', project_id=os.environ['FCM_PROJECT_ID'])
    try:
        fcm.notify(fcm_token=fcm_token, data_payload={"op": "PUSH", "data": json.dumps(data)})
    except Exception:
        return jsonify({'error': 'Message delivery failed'}), 500
    return jsonify({'success': True, "message": "request has been sent to device."}), 200


@v2.route("/delete/<method>", methods=['DELETE'])
def del_data(method):
    if not method or not request.json or "uuid" not in request.json:
        return jsonify({'error': 'invalid request'}), 400

    uuids = request.json["uuid"]
    if not isinstance(uuids, list):
        uuids = [uuids]

    fixed = method[0].upper() + method[1:]
    user = fetch_one("SELECT fcm_token FROM users WHERE id = %s", (str(g.user),))
    fcm_token = user["fcm_token"] if user else None
    if not fcm_token:
        return jsonify({'error': 'no fcm token found'}), 404

    fcm = FCMNotification(service_account_file='service-account.json', project_id=os.environ['FCM_PROJECT_ID'])
    try:
        fcm.notify(fcm_token=fcm_token, data_payload={"op": "DEL", "data": json.dumps({"uuids": uuids, "recordType": fixed})})
    except Exception:
        return jsonify({'error': 'Message delivery failed'}), 500
    return jsonify({'success': True, "message": "request has been sent to device."}), 200
