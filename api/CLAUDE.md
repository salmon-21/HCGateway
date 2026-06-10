# api/CLAUDE.md

Flask API server. Reads here when working under `api/`.

## Entry points

- `main.py` — app factory, v2 blueprint registration only. v1 was removed.
- `apiVersions/v2/routes.py` — all endpoints.
- `db.py` — `psycopg_pool.ConnectionPool` + `fetch_one`/`fetch_all`/`execute` helpers.
- `method_schema.py` — registry mapping Health Connect method name → PG table layout. Drives generic `/sync` / `/fetch` / `/counts` / `/sync DELETE` handlers.

## Auth

Argon2-hashed password is the source of truth (`users.password`). Tokens (`token`, `refresh`, `expiry`) live as columns on the `users` row. `before_request` looks up the bearer token unless the endpoint is in the skip list (`v2.login`, `v2.refresh`, `v2.health`, `v2.status`).

User `id` is `uuid` (PG-generated). The legacy Mongo ObjectId-as-string scheme was migrated 1:1 in E2; never alter the hash format — that would lock the device out.

## Endpoints (v2)

| Method | Path | Auth | Note |
|---|---|---|---|
| POST | `/login` | none | Argon2 verify or create |
| POST | `/refresh` | none | refresh token → new access token |
| DELETE | `/revoke` | bearer | clears tokens |
| GET | `/health` | none | liveness `{"status":"ok"}` |
| GET | `/status` | none (VPC binding) | DB/api/dataSync health + streak |
| GET | `/counts` | bearer | reads `record_counts` summary (0014), maintained by the write paths |
| POST | `/sync/<method>` | bearer | upsert records via generic `_insert_samples` / `_insert_records` |
| POST | `/fetch/<method>` | bearer | read records, response shape `{_id, id, app, start, end, data}` |
| DELETE | `/sync/<method>` | bearer | server-side delete |

FCM push/delete (`PUT /push/<method>` + `DELETE /delete/<method>`) and the `users.fcm_token` column were removed: the upstream service account was never deployed (only a 2-byte placeholder was committed) and the Android-side `handlePush` was a stub, so the server→device path was dead end-to-end. Re-add both sides if two-way sync is ever desired.

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

- **`samples`** (`heartRate`, `speed`, `power`, `stepsCadence`, `cyclingPedalingCadence`): flatten `item.samples[]` into N rows. Idempotency = `DELETE WHERE source_id = ANY(...) AND user_id = %s AND time BETWEEN batch-min−7d AND batch-max+7d` then bulk INSERT in one transaction. The `user_id` predicate is required — without it a sync would delete another user's rows that happen to share a Health Connect `source_id`. The time bounds are required too — an unbounded DELETE considers every chunk, and decompressing candidate batches for DML exceeds `timescaledb.max_tuples_decompressed_per_dml_transaction` (100k) on `heart_rate_sample`, 500-ing the whole `/sync` and freezing the client's Changes token.
- **`interval`** (steps, distance, sleepSession, …): one row per `metadata.id` in the destination table, with `start_at` + `end_at`. ON CONFLICT target is `(start_at, id)` for hypertables and `(id)` for plain tables — driven by `is_hypertable` in the schema dict.
- **`instant`** (oxygenSaturation, weight, vo2Max, …): same as interval but the source has only `time` (no `endTime`) and the table has only `start_at`.

`_pick_scalar` normalises Health Connect unit-object values (e.g. `distance.inMeters`) at write time — flattened to the SI scalar matching the column.

**Record counts (0014).** `/counts` does not aggregate live — the per-(user, method) totals live in `record_counts` and are bumped by `_bump_count` on the same cursor as each write (samples sync: ±distinct source_ids; record upsert: rows where `RETURNING (xmax = 0)` is true; server delete: −removed). Anything that writes the data tables *without* going through these paths (the `backfill/` importers COPY directly) leaves the counts stale — re-run the seed block in `pg/migrations/0014_record_counts.sql` afterwards (it's idempotent: `ON CONFLICT DO UPDATE` with a fresh recount). The seed SQL is generated from `METHOD_SCHEMA`; regenerate when adding types.

**Full type coverage (0012/0013).** `METHOD_SCHEMA` now has an entry for *every* record type the Android client sends (43 keys: all 41 client types + backfill-only `stress`/`vitalityScore`). This matters operationally: a `/sync/<type>` 400 ("unknown method") makes the client mark the type failed and *refuse to advance its Changes API token*, so the same window re-syncs forever. Each new type is a plain per-type table (0012). Gotchas baked in:
- A method's `kind` must match how Health Connect emits the record. `heartRateVariabilityRmssd` and `respiratoryRate` are **instant** (the client sends `time`, not `startTime`/`endTime`) — they were wrongly `interval` and skipped every row; their tables' `end_at` was made nullable (0013) so the instant path (no `end_at`) inserts.
- A `value_cols` source path must match the client's JSON key (per `RecordSerializer.kt`), e.g. `basalMetabolicRate`, `vo2MillilitersPerMinuteKilogram`, `heartRateVariabilityMillis`, `height` (meters → `height_m`, renamed from the 100x-mislabelled `height_cm`).
- A column the client never sends (e.g. `bloodPressure.pulse`) must be in `NULLABLE_COLS` **and** nullable in the DB — otherwise the row is silently skipped (not nullable in schema) or 500s (in `NULLABLE_COLS` but `NOT NULL` in PG, which re-blocks the token).

## PostgreSQL / TimescaleDB conventions

- All persisted times are `timestamptz` (UTC under the hood). Day-boundary logic uses `AT TIME ZONE 'Asia/Tokyo'` where relevant; never persist a tz-shifted timestamp.
- Token endpoints use UTC-aware `datetime.now(timezone.utc)` to avoid server-TZ surprises; legacy naive `expiry` values are coerced with `.replace(tzinfo=utc)` before comparing.
- Connection pool sized `min=1 max=10`; gunicorn runs 1 worker × 4 threads (Dockerfile). Keep workers at 1 — the in-process caches (streak, `_last_sleep_count`-style globals) assume a single process.
- **Compression (0015):** the five high-volume hypertables compress chunks older than 30 days (heart_rate_sample went 476 MB → 13 MB). Consequences: unbounded `max(time)`/`max(start_at)` scans walk every compressed chunk (~700 ms) — recency-floor such probes (`> now() - interval '35 days'`, see /status); and writes into >30-day-old ranges (Force Sync over old dates, `backfill/` importers) decompress touched batches in place and run slow — the policy recompresses them on its next pass.
- Don't reintroduce `print(request.json)`-style debug — keep handler logs to one `f"{method}: {n} records"` per call.

## Do NOT

- Reintroduce Mongo (`pymongo`, BSON, `hcgateway_<userid>` per-user DB naming) — E2 removed all of it.
- Reintroduce Fernet encryption — E1 removed it deliberately.
- Use `%s` placeholder for `jsonb_build_object(KEY, VALUE)` keys — `IndeterminateDatatype`. Inline schema-derived keys via f-string.
- Bypass `before_request` for new endpoints unless they're explicitly cross-tunnel reachable (status pull is the only current case).
