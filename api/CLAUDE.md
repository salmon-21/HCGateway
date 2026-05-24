# api/CLAUDE.md

Flask API server. Reads here when working under `api/`.

## Entry points

- `main.py` — app factory, v2 blueprint registration only. v1 was removed.
- `apiVersions/v2/routes.py` — all endpoints.
- `db.py` — `psycopg_pool.ConnectionPool` + `fetch_one`/`fetch_all`/`execute` helpers.
- `method_schema.py` — registry mapping Health Connect method name → PG table layout. Drives generic `/sync` / `/fetch` / `/counts` / `/sync DELETE` handlers.

## Auth

Argon2-hashed password is the source of truth (`users.password`). Tokens (`token`, `refresh`, `expiry`, `fcm_token`) live as columns on the `users` row. `before_request` looks up the bearer token unless the endpoint is in the skip list (`v2.login`, `v2.refresh`, `v2.health`, `v2.status`).

User `id` is `uuid` (PG-generated). The legacy Mongo ObjectId-as-string scheme was migrated 1:1 in E2; never alter the hash format — that would lock the device out.

## Endpoints (v2)

| Method | Path | Auth | Note |
|---|---|---|---|
| POST | `/login` | none | Argon2 verify or create |
| POST | `/refresh` | none | refresh token → new access token |
| DELETE | `/revoke` | bearer | clears tokens |
| GET | `/health` | none | liveness `{"status":"ok"}` |
| GET | `/status` | none (VPC binding) | DB/api/dataSync health + streak |
| GET | `/counts` | bearer | per-table row counts (samples → `count(DISTINCT source_id)`) |
| POST | `/sync/<method>` | bearer | upsert records via generic `_insert_samples` / `_insert_records` |
| POST | `/fetch/<method>` | bearer | read records, response shape `{_id, id, app, start, end, data}` |
| PUT | `/push/<method>` | bearer | FCM push to device (sync request) |
| DELETE | `/delete/<method>` | bearer | FCM push to device (delete request) |
| DELETE | `/sync/<method>` | bearer | server-side delete |

## /api/v2/status semantics

- `components.api.handlerMs` — handler's self-measured wall time (int ms). End-to-end RTT (Worker → API → Worker) is injected as `components.api.responseMs` by the moromiso Worker at pull time; the handler itself never emits `responseMs`.
- `components.db` — `max(time) FROM heart_rate_sample` latency probe; `{status, responseMs}`. Handler-internal measurement of the single query, not an RTT.
- `components.dataSync.status` — based on `heart_rate_sample` freshness only: `<12h ok / <24h degraded / else down / unknown`. Other realtime tables (`steps`, `distance`, `total_calories_burned`) appear in `lastDataPerType` for debug but don't drive the verdict.
- `components.dataSync.reason` — present only when `status` is `unknown` or `down`. Codes: `no_users` (users table empty), `no_heart_rate_data` (no heart_rate_sample rows), `query_failed` (exception during freshness probe). Consumed by the moromiso Worker for human-readable detail labels.
- `components.dataSync.streak` — consecutive `STATUS_TZ`-calendar-days (today inclusive) with heart_rate data, capped at 365. Cached per (user_id, tz) for 900 s.
- `STATUS_TZ` (env, default `UTC`) — calendar-day boundaries + `checkedAt` timezone. Must match the consumer's notion of "today".
- 12h/24h threshold math is timezone-invariant (tz-aware delta).

The endpoint is reachable from the Cloudflare Workers VPC binding only (no Service Token). HCGateway-level auth is intentionally bypassed in `before_request`.

## /sync write semantics

Dispatched by `METHOD_SCHEMA[method].kind`:

- **`samples`** (`heartRate`, `speed`): flatten `item.samples[]` into N rows in `heart_rate_sample` / `speed_sample`. Idempotency = `DELETE WHERE source_id = ANY(...)` then bulk INSERT in one transaction.
- **`interval`** (steps, distance, sleepSession, …): one row per `metadata.id` in the destination table, with `start_at` + `end_at`. ON CONFLICT target is `(start_at, id)` for hypertables and `(id)` for plain tables — driven by `is_hypertable` in the schema dict.
- **`instant`** (oxygenSaturation, weight, vo2Max, …): same as interval but the source has only `time` (no `endTime`) and the table has only `start_at`.

`_pick_scalar` normalises Health Connect unit-object values (e.g. `distance.inMeters`) at write time — flattened to the SI scalar matching the column.

## PostgreSQL / TimescaleDB conventions

- All persisted times are `timestamptz` (UTC under the hood). Day-boundary logic uses `AT TIME ZONE 'Asia/Tokyo'` where relevant; never persist a tz-shifted timestamp.
- Token endpoints use UTC-aware `datetime.now(timezone.utc)` to avoid server-TZ surprises; legacy naive `expiry` values are coerced with `.replace(tzinfo=utc)` before comparing.
- Connection pool sized `min=1 max=10` for single-worker Flask. Bump if we move to gunicorn with workers.
- Don't reintroduce `print(request.json)`-style debug — keep handler logs to one `f"{method}: {n} records"` per call.

## Do NOT

- Reintroduce Mongo (`pymongo`, BSON, `hcgateway_<userid>` per-user DB naming) — E2 removed all of it.
- Reintroduce Fernet encryption — E1 removed it deliberately.
- Use `%s` placeholder for `jsonb_build_object(KEY, VALUE)` keys — `IndeterminateDatatype`. Inline schema-derived keys via f-string.
- Bypass `before_request` for new endpoints unless they're explicitly cross-tunnel reachable (status pull is the only current case).
