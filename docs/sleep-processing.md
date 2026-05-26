# Sleep data processing

How `sleep_session` is turned into the numbers shown by each consumer. The two SQL
surfaces (matview + hypnogram) share one night definition — JST 18:00 wake-day cutoff
on the session cluster — so the trend and Sleep Stages agree; the MCP deliberately
does no grouping at all and hands raw rows to the agent. This doc is the canonical
record of each surface's job and conventions.

Last redesigned 2026-05-24.

## Source

`sleep_session` (TimescaleDB): one row per device-recorded sleep record.

| column | note |
|---|---|
| `start_at` / `end_at` | timestamptz |
| `stages` | jsonb array of `{stage, startTime, endTime}`; stage 1=Awake 3=OutOfBed 4=Light 5=Deep 6=REM 7=AwakeInBed |
| `app` | source app (e.g. `com.sec.android.app.shealth`) |

**The device splits and fragments.** One night is often several rows (a brief wake
splits it), and naps are their own rows. There is no "one row = one night" guarantee,
so every consumer has to decide how to treat adjacent rows.

## Consumers & conventions

| Surface | What it answers | Night grouping | Duration | Bedtime / Wake |
|---|---|---|---|---|
| **MCP `get_sleep_sessions` / `get_daily_summary`** (hcgateway-mcp) | "show me the raw nights, I'll reason" (LLM agent) | **none** — raw rows by start date | per-session | per-session |
| **`sleep_rolling_stats`** matview → Grafana Bedtime/Wake/Midpoint/Duration + `get_sleep_trend` | "nightly trend over time" | `sleep_day` = JST **18:00 cutoff on cluster start** (→ wake-day) | **day-sum of ALL sessions** (naps included) | from the **longest cluster** (≤60 min merged) of that sleep_day |
| **`sleep_hypnogram`** view → Grafana Sleep Stages | "hypnogram of one night" | `night_date` = **same 18:00 cutoff on cluster start** as the matview (→ wake-day, one shared label) | n/a | n/a |

Three deliberate choices behind this:

1. **MCP does no clustering.** Its consumer is an LLM agent, which reasons over raw
   rows better than over a baked 60-min heuristic (and the heuristic is opaque /
   a drift source). So `get_sleep_sessions` returns raw rows, each *summarised*
   (duration, JST bedtime/wake, `actual_sleep_hours` = non-wake stage time,
   `stage_minutes`) but never merged. Grouping is the agent's job.

2. **Grafana clustering lives in SQL only.** Grafana can only consume SQL and needs
   one-row-per-night, so the ≤60-min clustering + 18:00 wake-day labelling lives in
   the SQL views (matview + hypnogram), not in panel SQL and not in the MCP. The two
   views share that night label so the trend and Sleep Stages agree.

3. **Duration sums blindly; bedtime/wake don't.** You can sum durations, but a
   *time* needs a single representative, so bedtime/wake must pick one. Picking the
   longest single session breaks on fragmented nights (`00:30`+`02:30` → shows
   `02:30`); picking earliest-start/latest-end breaks on naps (`wake` grabs the
   morning nap's end). Only the **longest ≤60-min cluster's** start/end is robust to
   both — so timing uses clustering even though duration does not.

### Worked example (illustrative — split night + morning nap)

Raw rows: ① `00:30–02:00`, ② `02:30–07:30` (30 min after ①), ③ `09:00–10:30` (90 min after ②).

| | result |
|---|---|
| MCP `get_sleep_sessions` | 3 raw rows ①②③ (no merge) |
| matview `duration` | **8.0 h** = ①+②+③ summed (nap included) |
| matview `bedtime` / `wake` | **00:30 / 07:30** — longest cluster is ①②; ③ doesn't affect timing |
| Sleep Stages `night_date` | ①②③ → the night's wake-day (18:00 cutoff = the matview's sleep_day) |

Note `duration` (8.0 h, incl. nap) intentionally exceeds the `bedtime→wake` span
(7.0 h): duration = total sleep that day, bedtime/wake = the main night's edges.

### 0:00-crossing nights

Day assignment is **start-based** everywhere — `end_at` is never used to pick the
day. Both SQL surfaces use the same JST 18:00 cutoff on the cluster start: an evening
bedtime (start hour ≥18) rolls to the next (wake) day, a post-midnight bedtime stays
on its calendar date. A night crossing midnight gets one consistent label shared by
the trend and the hypnogram.

## Implementation

- **matview:** `pg/migrations/0004_sleep_cluster_bedtime.sql`
  (clusters via gaps-and-islands with running-max-end; supersedes the
  `sleep_rolling_stats` definition from `0003_bedtime_wake_bands.sql`; reuses
  `circular_stats()` from 0003). Apply with `scripts/apply-pg-migrations.sh`.
- **MCP:** `~/Dev/hcgateway-mcp/server.py` (`_raw_sleep_sessions`; no
  `_classify_episode` anymore). Rebuild: `docker compose up -d --build hcgateway-mcp`.
- **Grafana** "Health Connect" dashboard (Grafana Cloud):
  - Bedtime/Wake/Midpoint/Sleep Duration → unchanged SQL (auto-reflect the new matview).
  - Avg Sleep 7d/30d → `avg(duration)` over `sleep_rolling_stats` (per-night, not per raw session).
  - Sleep Stages → `SELECT … FROM sleep_hypnogram WHERE $__timeFilter("time")`.
  - Connects as read-only `grafana_ro` whose session timezone is `Asia/Tokyo`, so
    `$__timeTo()::date` matches the JST `sleep_day` (today's row shows; not the
    `hcgateway` role, which stays UTC for the MCP). Setup: `docs/grafana-datasource-role.md`.
- **`sleep_hypnogram`** `night_date` = the matview's 18:00 wake-day `sleep_day`,
  computed on the **cluster** start so a night that resumes after a brief wake stays
  one lane. Evolved `0005_hypnogram_cluster_night.sql` (cluster anchor) →
  `0006_hypnogram_wakeday_night.sql` (18:00 cutoff, replacing the earlier −6h label).
- **Bedtime/wake bands:** `circular_stats()` (from `0003`) gives the circular mean
  (atan2 over hour-of-day). `0007_circular_sd.sql` switched the band from an
  unwrap-then-linear-stddev approximation to the true circular SD
  `sqrt(-2·ln R)·12/π` (R = mean resultant length; capped at 12 h). Tight band when
  bedtimes cluster, wide when dispersed. Mean and the linear stats are unchanged.
- **Stage panels:** `sleep_stage_daily` **matview** (`0008` view + `0009` ±stddev bands,
  materialized in `0010_sleep_stage_daily_matview.sql` — the cluster + jsonb explosion
  was ~1.2 s/query × 5 panels; matview makes reads ~2 ms, refreshed by the
  sleep_session trigger alongside sleep_rolling_stats) — per sleep_day light/deep/rem/awake
  minutes, % (the four sum to 100), 7-day MA, and upper/lower bands, on the same
  cluster sleep_day. Feeds, in the Grafana **Sleep section**: "Sleep Efficiency"
  (actual/duration from the matview, no MA), "Sleep Stages (%)" stacked-area, and the
  four per-stage % line panels in Bedtime/Wake/Midpoint style (point line + MA + band).

## History

The 9-hour-shifted **phantom duplicate** `sleep_session` rows (a retired legacy
Python ingest applied the JST↔UTC offset twice) were a *data* bug, separate from the
processing conventions above. 65 rows deleted 2026-05-23; the live ingest path is
healthy. Signature: a daytime (12:00–20:00 JST) sleep ≥3 h, or a pair exactly 9.0 h
apart with identical stage counts. They had inflated matview days to an impossible
18–20 h (the day-sum double-counted them).
