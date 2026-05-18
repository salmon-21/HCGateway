# api/CLAUDE.md

Flask API server. Reads here when working under `api/`.

## Entry points

- `main.py` — app factory, v2 blueprint registration only. v1 was removed.
- `apiVersions/v2/routes.py` — all endpoints.

## Auth

Argon2-hashed password is the source of truth (`users.password`). Tokens (`token`, `refresh`, `expiry`) are stored on the user document. `before_request` checks the bearer token unless the endpoint is in the skip list (`v2.login`, `v2.refresh`, `v2.health`, `v2.status`).

The Argon2 hash used to also seed a Fernet key (E1 removed encryption). The hash is still needed for token verify, so `make_fernet`-style code can be deleted but **never alter the hash format** — that would lock everyone out.

## Endpoints (v2)

| Method | Path | Auth | Note |
|---|---|---|---|
| POST | `/login` | none | Argon2 verify or create |
| POST | `/refresh` | none | refresh token → new access token |
| DELETE | `/revoke` | bearer | clears tokens |
| GET | `/health` | none | liveness `{"status":"ok"}` |
| GET | `/status` | none (VPC binding) | DB/api/dataSync health + streak |
| GET | `/counts` | bearer | per-collection document counts |
| POST | `/sync/<method>` | bearer | upsert records (plain, no Fernet) into `hcgateway_<userid>` |
| POST | `/fetch/<method>` | bearer | read records, response shape `{_id, id, app, start, end, data}` |
| PUT | `/push/<method>` | bearer | FCM push to device (sync request) |
| DELETE | `/delete/<method>` | bearer | FCM push to device (delete request) |
| DELETE | `/sync/<method>` | bearer | server-side delete |

## /api/v2/status semantics

- `components.api.responseMs` — handler's self-measured wall time (int ms).
- `components.db` — heartRate find_one latency check; `{status, responseMs}`.
- `components.dataSync.status` — based on `heartRate` freshness only: `<12h ok / <24h degraded / else down / unknown`. Other realtime collections appear in `lastDataPerType` for debug but don't drive the verdict.
- `components.dataSync.streak` — consecutive `STATUS_TZ`-calendar-days (today inclusive) with heartRate data, capped at 365. Cached per (db, collection, tz) for 900 s.
- `STATUS_TZ` (env, default `UTC`) — calendar-day boundaries + `checkedAt` timezone. Must match the consumer's notion of "today".
- 12h/24h threshold math is timezone-invariant (tz-aware delta).

The endpoint is reachable from the Cloudflare Workers VPC binding only (no Service Token). HCGateway-level auth is intentionally bypassed in `before_request`.

## Data shape

`/sync` writes plain documents directly into `hcgateway_<userid>` with inner fields spread:

```
{ _id, app, start: Date, end: Date, ...inner fields }
```

`_normalize_nested_dates` converts nested ISO strings (`samples[].time`, `stages[].{startTime,endTime}`) to BSON Date. Add new nested-date fields to `NESTED_DATE_FIELDS` if you ship more collections.

## MongoDB 4.4 constraints

- No `$setWindowFields` (5.0+) — precompute in Python (see `post-process/`).
- No `$dateSubtract` — use `{ $expr: { $subtract: [ { $toLong: "$$NOW" }, <ms> ] } }`.
- `$$NOW`, `$dateToString`/`$dateFromString` with `timezone: "+09:00"` are available.

## Conventions

- All persisted times are UTC `Date` in Mongo. Display/grouping uses JST (`+09:00`) where relevant; never persist a TZ-shifted Date.
- Token endpoints use UTC-aware `datetime.now(timezone.utc)` to avoid server-TZ surprises; legacy naive `expiry` values are coerced with `.replace(tzinfo=utc)` before comparing.
- Don't reintroduce `print(request.json)`-style debug — keep handler logs to one `f"{method}: {n} records"` per call.

## Do NOT

- Reintroduce Fernet encryption on writes — E1 removed it deliberately; the `_decrypted` DB no longer exists.
- Trigger `post-process` from `/sync` — the periodic loop covers it; per-item triggers caused N×HTTP storms.
- Bypass `before_request` for new endpoints unless they're explicitly cross-tunnel reachable (status pull is the only current case).
