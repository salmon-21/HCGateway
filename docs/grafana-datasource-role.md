# Grafana datasource role (`grafana_ro`)

Grafana's **hcgateway-postgres-datasource** (uid `dfmiqfj3w1s00c`) connects to this
database as a dedicated **read-only role `grafana_ro`** whose **session timezone is
`Asia/Tokyo`**. This is a manual, documented setup — not a pg migration (see the last
section). Recreate it if the database is rebuilt.

## Why

1. **Least privilege.** Grafana only needs `SELECT`; it should not connect as the
   `hcgateway` superuser.
2. **JST session timezone — convenience only, NOT the "today's sleep" fix.** The
   `Asia/Tokyo` session makes bare `timestamptz→date` casts (e.g. `now()::date`) and
   ad-hoc exploration resolve to the JST calendar day, which is handy. But it does
   **not** fix the "today's sleep not showing" bug, despite an earlier belief that it
   did. Sleep panels filter `sleep_day` (a JST date) against Grafana's `$__timeFrom()`/
   `$__timeTo()` macros, and Grafana renders those as **string literals** (`'…Z'`). A
   bare `$__timeTo()::date` is therefore a *text→date* cast that just reads the date
   field of the UTC ISO string and **ignores the session timezone entirely** — so in
   the early JST morning (UTC still the previous calendar day) today's `sleep_day` is
   dropped until 09:00 JST. The fix lives **in the panel SQL**, which casts explicitly:
   `($__timeTo()::timestamptz AT TIME ZONE 'Asia/Tokyo')::date` (the `::timestamptz`
   makes it a *timestamptz→date* cast that honors the JST conversion). This is
   session-timezone-independent, matching the already-correct `now() AT TIME ZONE
   'Asia/Tokyo'` style in the Avg-Sleep stat panels.
   - **Do NOT** set the timezone on the shared `hcgateway` role. The MCP
     (`~/Dev/hcgateway-mcp/server.py`) assumes psycopg returns UTC and adds +9h in
     Python (e.g. `start_jst = s + JST`); a JST session there **double-shifts**
     bedtime/wake by 9 h. Only Grafana's dedicated role gets JST; `hcgateway` stays UTC.

## Setup (run as a superuser such as `hcgateway`)

Pick a strong password; it is stored only in the Grafana datasource, never in this repo.

```sql
CREATE ROLE grafana_ro LOGIN PASSWORD '<PASSWORD>';
GRANT CONNECT ON DATABASE hcgateway TO grafana_ro;
GRANT USAGE  ON SCHEMA public       TO grafana_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO grafana_ro;   -- tables + views
GRANT SELECT ON sleep_rolling_stats TO grafana_ro;           -- matview (NOT covered by ALL TABLES)
GRANT SELECT ON sleep_stage_daily   TO grafana_ro;           -- matview (since 0010)
GRANT SELECT ON heart_rate_hourly   TO grafana_ro;           -- TimescaleDB continuous aggregate
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO grafana_ro;
ALTER ROLE grafana_ro SET timezone = 'Asia/Tokyo';
```

Apply, for example:

```bash
docker exec -i hcgateway_postgres psql -U hcgateway -d hcgateway -c "<the SQL above>"
```

Verify (as `grafana_ro`, over TCP like Grafana does):

```bash
docker exec -i -e PGPASSWORD='<PASSWORD>' hcgateway_postgres \
  psql -U grafana_ro -h 127.0.0.1 -d hcgateway \
  -c "SHOW timezone;" -c "SELECT now()::date;" \
  -c "SELECT count(*) FROM sleep_rolling_stats;" \
  -c "SELECT count(*) FROM heart_rate_hourly WHERE hour > now() - interval '2 days';"
```

Expect `Asia/Tokyo`, the JST date, and non-error counts (the continuous-aggregate
count confirms the explicit `heart_rate_hourly` grant cascaded to its internals).

## Grafana datasource config

In **Connections → Data sources → hcgateway-postgres-datasource**:
- **User**: `grafana_ro`
- **Password**: the `<PASSWORD>` set above (lives in the datasource's secureJsonData)
- Leave everything else (PDC / Secure Socks Proxy, `localhost:5432`, database
  `hcgateway`, PostgreSQL version `1700` = PG 17) unchanged.

Rotate the password anytime in the UI (and `ALTER ROLE grafana_ro PASSWORD '…'`).

## Why not a pg migration

pg migrations auto-run on a fresh container via `docker-entrypoint-initdb.d` and are
committed to the repo. A login role needs a password (must not be committed), and
auto-creating a credentialed role on init would be a security hole. So this stays a
manual step. New tables/views are still readable automatically thanks to the
`ALTER DEFAULT PRIVILEGES … GRANT SELECT` above.
