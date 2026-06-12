# HCGateway

> [!IMPORTANT]
> **This is a personal fork** of [ShuchirJ/HCGateway](https://github.com/ShuchirJ/HCGateway), kept here for the author's own deployment.
>
> - **I don't have the bandwidth to maintain this as a shared project in 2026.** Issues, discussions, and PRs from outside users are not triaged.
> - **Entirely vibecoded with [Claude Code](https://claude.com/claude-code)** — effectively every commit on this fork is AI-authored (the `Co-Authored-By` trailer is on most of them but undercounts; the work itself is AI throughout). I don't claim deep understanding of every line; treat this as a starting point, not a vetted reference implementation. Pay particular attention to anything security-sensitive (e.g. the Fernet key derivation flagged in [#63](https://github.com/ShuchirJ/HCGateway/issues/63) upstream — Fernet itself is gone from this fork, but audit before trusting).
> - **No upstream parity:** the Android app has been rewritten in Jetpack Compose, the API/DB has been ported from MongoDB to PostgreSQL/TimescaleDB on `personal`, and a Grafana Cloud dashboard plus JST-specific assumptions are bundled.
> - **Branches:**
>   - `personal` (default) — what I run. PostgreSQL/TimescaleDB; not upstream-compatible.
>   - `compose-rewrite` — Jetpack Compose Android app on top of the upstream MongoDB API (with a small `/counts` endpoint added). **Snapshot only**, no further updates planned.
> - **If you want a maintained version, fork it.** The upstream repo has also been quiet for several months. Please feel free.

HCGateway syncs Android Health Connect data to a self-hosted REST API. The upstream project and its documentation live at [hcgateway.shuchir.dev](https://hcgateway.shuchir.dev/); everything below describes **this fork**.

## How this fork works

```
Android (Samsung Health etc. → Health Connect)
  → HCGateway app (app/, Jetpack Compose + Kotlin)
  → Flask API (api/, gunicorn) — port 6644
  → PostgreSQL / TimescaleDB (pg/migrations/)
  → Grafana Cloud (via PDC agent, optional)
```

- The app syncs periodically via WorkManager (delta sync through the Health Connect Changes API) and supports manual Force Sync over a date range.
- **Every record type the app reads is persisted**, each in its own PostgreSQL table (`pg/migrations/0012`) — an unknown type would 400 and stall the client's Changes-token sync, so full coverage is load-bearing, not cosmetic.
- High-volume types (heart rate, speed samples, steps, distance, calories) are TimescaleDB hypertables with native compression on chunks older than 30 days.
- Data is stored **unencrypted** in PostgreSQL (upstream's Fernet layer was removed; protect the database itself). Passwords are Argon2-hashed in the `users` table and are never retrievable.
- Server → device push (upstream's Firebase/FCM `/push` and `/delete`) was removed: the upstream service account was never deployed and the device-side handler was a stub, so the path was dead end-to-end. Re-add both sides if you want two-way sync.

## REST API

The accurate endpoint reference for this fork is the table in [`api/CLAUDE.md`](api/CLAUDE.md) (login/refresh/revoke, `/sync/<method>`, `/fetch/<method>`, `/counts`, `/health`, `/status`). The upstream OpenAPI docs do not match this fork.

## Self hosting

### Server (Docker)

```bash
cp api/.env.example api/.env   # set POSTGRES_URI etc.
docker compose up -d           # postgres + api
```

The API listens on `http://localhost:6644`. Migrations in `pg/migrations/` are applied automatically on first database init; for migrations added after that, use `scripts/apply-pg-migrations.sh`. `scripts/backup-pg.sh` is a cron-ready weekly `pg_dump`.

### Android app

Prerequisites: Android Studio (SDK), Java 17, a device with Health Connect.

```bash
cd app
./gradlew installDebug      # build + install to a connected device
./gradlew assembleRelease   # or build an APK
```

Sentry is opt-in at runtime (in-app toggle); to use your own instance change the DSN in `AndroidManifest.xml`, and put the upload token in the gitignored `app/sentry.properties`.
