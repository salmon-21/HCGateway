# post-process/CLAUDE.md

Background aggregator. Reads here when working under `post-process/`.

## What this is

Computes derived collections on top of `hcgateway_<userid>` (the plain DB the API writes to). The historical name was `decrypt-sync` — back then it also decrypted Fernet-encrypted source data. E1 removed encryption; the service is now strictly post-processing.

## Loop

`run_sync()` iterates all users (`hcgateway.users` collection):

1. `compute_sleep_rolling_stats(dst_db)`

Each user's `dst_db` is `hcgateway_<userid>` (no `_decrypted` suffix since stage D).

The loop runs every `SYNC_INTERVAL` seconds (default 300 in code, 180 in docker-compose) and on `POST :7000/trigger`. A non-blocking `_sync_lock` keeps the periodic loop and trigger from colliding. The trigger handler is fire-and-forget — 200 returns immediately, the actual run happens in a background thread.

## sleepRollingStats

Rebuilt from scratch each run (no incremental merge). Cached by `(db_name -> session count)` to skip recompute when nothing changed.

Per-session math:
- **Sleep-day assignment**: bedtime 18:00–23:59 JST → next day; 00:00–17:59 → same day. The longest session of a day determines bedtime/wake/midpoint. Nap sessions are excluded from timing but their duration counts toward the daily total.
- **`actual_duration`**: sum of stage durations excluding awake stages (`{1: Awake, 3: OutOfBed}`). Falls back to `end - start` when no stages.

Per-day records get a 7-day rolling window. Timing fields (`bedtime`, `wake`, `midpoint`) use **circular statistics** (`_circular_stats`) because hour-of-day is modular. `duration` uses **linear statistics** (`_rolling_stats`).

Output schema: see CUSTOMIZE.md (local) or the field list in `compute_sleep_rolling_stats`.

## MongoDB 4.4 constraints

- No `$setWindowFields` — that's why the rolling window is computed in Python.
- No `$dateTrunc` — when bucketing by day for streaks etc., use `$dateToString` with the right `timezone` and then key into a Python set.

## Timezone convention

- Data persisted as UTC `Date`.
- All day-boundary logic is JST (`+09:00`). `JST = timedelta(hours=9)` at the top; `jst_day_start_utc(d)` converts a JST date to the UTC instant of that day's start.
- If you add a new aggregation, mirror this — do not introduce raw UTC day boundaries; sleep doesn't follow UTC midnight for anyone east of GMT+1.

## Sleep data shape

`sleepSession` documents have `start`, `end`, and `stages[]`. Each stage: `{startTime: Date, endTime: Date, stage: int}` where:

| stage | meaning |
|---|---|
| 1 | Awake |
| 3 | OutOfBed |
| 4 | Light |
| 5 | Deep |
| 6 | REM |
| 7 | AwakeInBed (some sources) |

Samsung Health frequently splits one night into multiple sessions separated by brief wakes. Treat sessions with gap ≤ 60 min as one episode if you do nap/main-sleep classification.

## Do NOT

- Add outbound HTTP — status push was moved to status.moromiso.com (pull model). This service should have no `requests`/`urllib` calls.
- Use `cryptography`/`Fernet` — encryption was removed in E1; do not reintroduce.
- Trigger from `/sync` per item — the periodic loop is sufficient. Per-item triggers caused the N×HTTP storm that motivated `bulk_write`.
