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
| **`sleep_rolling_stats`** matview → Grafana Bedtime/Wake/Midpoint/Duration + `get_sleep_trend` | "nightly trend over time" | `sleep_day` = JST **18:00 cutoff on cluster start** (→ wake-day) | **day-sum of ALL sessions** (naps included) | from the **longest cluster** (≤120 min merged) of that sleep_day, with `main_share` saying how representative it is |
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
   morning nap's end). Only the **longest ≤120-min cluster's** start/end is robust to
   both — so timing uses clustering even though duration does not. On ~10% of days
   *no* cluster dominates and no rule can pick a meaningful one; `main_share`
   reports that rather than hiding it (see "Representativeness" below).

### Worked example (illustrative — split night + morning nap)

Raw rows: ① `00:30–02:00`, ② `02:30–07:30` (30 min after ①), ③ `10:00–11:30` (2.5 h after ②).

| | result |
|---|---|
| MCP `get_sleep_sessions` | 3 raw rows ①②③ (no merge) |
| matview `duration` | **8.0 h** = ①+②+③ summed (nap included) |
| matview `bedtime` / `wake` | **00:30 / 07:30** — longest cluster is ①②; ③ is >120 min later so it doesn't affect timing |
| matview `main_share` | ~0.8 — ①② hold most of the day's sleep, so the pair above does describe a night |
| Sleep Stages `night_date` | ①②③ → the night's wake-day (18:00 cutoff = the matview's sleep_day) |

Note `duration` (8.0 h, incl. nap) intentionally exceeds the `bedtime→wake` span
(7.0 h): duration = total sleep that day, bedtime/wake = the main night's edges.

### Representativeness (`main_share`)

`main_share` = the chosen cluster's actual sleep ÷ the sleep_day's total actual
sleep. 1.0 = one consolidated block (553 of 881 days); below ~0.65 the day was
split into comparable pieces and bedtime/wake describe one of them, not a night.

This is not a defect to be tuned away. The clinical mid-sleep point is defined on
a *main sleep period*, and on 88 days (10%) this data has no such thing — the
inter-cluster gap distribution has no valley to split "fragmented night" from
"separate sleep" (1-2h: 92, 2-3h: 84, 3-4h: 78, 4-5h: 55 …), so every merge rule
is a judgement call. Grafana therefore marks those days with a **full-height red
line** on Bedtime/Wake/Midpoint instead of implying a single night happened, and
`sleep_regularity` (below) carries the regularity signal for those days because
it needs no main sleep period at all.

The line is a `Fragmented` series, not an annotation: `CASE WHEN main_share <
0.65 THEN 24 END` (24 = the panels' axis max, so it spans the full height),
rendered as a thin `bars` series — `dark-red`, `barWidthFactor 0.08`,
`fillOpacity 80`, `lineWidth 0`, hidden from the tooltip (a "Fragmented: 24 h"
row would be nonsense), and ordered **first** in `indexByName` so it draws behind
the data rather than over it. Annotations were the obvious alternative and are
wrong here: the dashboard already spends its vertical-line channel on the
user-authored `Life` (cyan) and `Med` (orange) annotation queries, which render
on every panel. Solid red vs. those dashed lines is what keeps the three
readable — keep it solid if you restyle.

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
- **Write path:** the `sleep_session` AFTER-STATEMENT trigger only bumps a dirty counter (<1 ms); background jobs do the refreshing (0016 for `sleep_rolling_stats` + `sleep_stage_daily`, 0019/0023 for `sleep_regularity`). *Before* 0016 the trigger ran both `REFRESH … CONCURRENTLY` inline, so a per-row `executemany` (API `_insert_records`) fired 2 refreshes per row — ~98 s for 42 rows. Writes should still be single-statement, but for the usual bulk-insert reasons now, not to avoid that cliff.
- **MCP:** `~/Dev/hcgateway-mcp/server.py` (`_raw_sleep_sessions`; no
  `_classify_episode` anymore). Rebuild: `docker compose up -d --build hcgateway-mcp`.
- **Grafana** "Health Connect" dashboard (Grafana Cloud):
  - Bedtime/Wake/Midpoint/Sleep Duration → unchanged SQL (auto-reflect the new matview).
  - Avg Sleep 7d/30d → `avg(duration)` over `sleep_rolling_stats` (per-night, not per raw session).
  - Sleep Stages → `SELECT … FROM sleep_hypnogram WHERE $__timeFilter("time")`.
  - Connects as read-only `grafana_ro` (least privilege). **`sleep_day` filters must
    cast the time macros explicitly:** `WHERE sleep_day >= ($__timeFrom()::timestamptz
    AT TIME ZONE 'Asia/Tokyo')::date AND sleep_day <= ($__timeTo()::timestamptz AT TIME
    ZONE 'Asia/Tokyo')::date`. Grafana renders `$__timeTo()` as a **string literal**, so
    a bare `$__timeTo()::date` is a *text→date* cast that ignores the session timezone
    and drops today's JST `sleep_day` until 09:00 JST — the role's `Asia/Tokyo` session
    does **not** fix this (it only affects `timestamptz→date`, e.g. `now()::date`).
    Setup: `docs/grafana-datasource-role.md`.
- **`sleep_hypnogram`** `night_date` = the matview's 18:00 wake-day `sleep_day`,
  computed on the **cluster** start so a night that resumes after a brief wake stays
  one lane. Evolved `0005_hypnogram_cluster_night.sql` (cluster anchor) →
  `0006_hypnogram_wakeday_night.sql` (18:00 cutoff, replacing the earlier −6h label).
- **Bedtime/wake/midpoint bands:** `circular_stats()` (from `0003`) gives the circular
  mean (atan2 over hour-of-day). `0007_circular_sd.sql` switched the band from an
  unwrap-then-linear-stddev approximation to the true circular SD
  `sqrt(-2·ln R)·12/π` (R = mean resultant length; capped at 12 h). Tight band when
  bedtimes cluster, wide when dispersed. `0017_midpoint_circular.sql` extended this to
  the midpoint MA/bands — 0007 left midpoint linear on the assumption it stays ~2-3 AM,
  but 14% of nights had a pre-midnight midpoint and windows straddling the wrap
  averaged to impossible daytime values (32% of nights). Duration/actual stats stay
  linear (not clock-of-day quantities).
- **MA/band window (0018, both matviews):** `RANGE BETWEEN INTERVAL '3 days'
  PRECEDING AND INTERVAL '3 days' FOLLOWING` — a true calendar ±3-day window. The
  old ROWS frame counted observations, so the "7-day MA" bridged tracking gaps
  (up to 150 days) as if contiguous. Bands (upper/lower, linear and circular) are
  NULL when the frame has <3 points — STDDEV of 1 point is NULL and the old
  `COALESCE(…, 0)` rendered it as a zero-width band (false certainty); Grafana
  skips NULLs. The MA itself is kept at any n. Accepted asymmetries: linear bands
  use sample SD (n−1) while the circular SD is population-style (no standard
  small-sample correction exists); the centered window means the newest 3 days'
  MA revises as data lands.
- **Sleep-period threshold (0019, all three surfaces):** the wake bout that ends a
  sleep period moved 60 → 120 min, and now lives in one place —
  `sleep_gap_threshold()`, an IMMUTABLE SQL function the two matviews and the
  hypnogram all call, so the shared night definition cannot drift between them.
  This is a parameter *of* the clinical definition (mid-sleep keeps WASO inside
  the period), not a departure from it. Chosen on a measured trade-off, since the
  gap distribution offers no natural cut: leave-one-out midpoint deviation
  against the ±3-day circular neighbourhood improves 2.002 → 1.967 (p90 4.90 →
  4.68), days with no dominant cluster drop 126 → 88, at the price of over-long
  spans (>14 h) rising 10 → 19. 150 min+ degrades fast (39 then 56 such days;
  180 min produces a 32.8 h "night"). Net effect vs 0018: midpoint moves on 95
  days (54 closer to their neighbourhood, 41 further), bedtime 35, wake 79,
  duration 13 (sleep_day relabels, 883 → 881 days).
- **Rejected in 0019 — ranking clusters by actual sleep instead of span.** It
  measures worse (deviation 2.002 → 2.025 at 60 min, 1.967 → 2.000 at 120 min):
  actual-sleep differences between blocks are small and noisy, so the pick flips
  on low-efficiency nights (2025-09-29's 03:09-13:02 block, 8.78 h in bed but
  3.57 h asleep, loses to a short evening block → midpoint 06:55 → 19:59). Span
  differences are larger and more stable. Ranking stays on span.
- **`sleep_regularity` (0019):** Sleep Regularity Index, `−100 + 200/(M·(N−1)) ·
  Σ δ(s(i,j), s(i+1,j))` — the chance the sleep/wake state at a clock minute
  repeats 24 h later. 1-min epochs, **JST calendar days** (not `sleep_day`: SRI is
  a 24 h-cycle measure and the standard boundary is midnight), asleep = stages
  4/5/6, trailing 7-day window (6 adjacent pairs), emitted only at ≥4 pairs so a
  tracking gap cannot fake regularity. It needs no main sleep period, which is
  exactly why it covers the days `main_share` flags. Grafana panel 59 "Sleep
  Regularity (SRI / IS)" at the bottom of the Sleep row — **plain lines, no fill
  and no reference line**. A faint dashed personal baseline at 40 (this user's
  all-time median; 365 d: 34.7, 90 d: 57.0) was tried, in the same style as
  Midpoint's 2.75 and Wake's 7, and removed once `is_28d` joined the panel: one
  horizontal line that applies to only one of two series reads as a shared
  threshold. Don't re-add it without also solving that. What stays true is the
  reason it could never be a *clinical* line — SRI has no established cutoff (the
  literature analyses it by within-sample percentiles), so coloured good/bad zones
  would invent precision that does not exist.
  Full recompute is ~10 s on
  the RPi4, so it gets its own `sleep_regularity_refresh_if_dirty` job rather
  than riding 0016's, with its own `sri_refreshed_changes` watermark. **0019 ran
  it hourly; 0023 moved it to 5 min** — see below.
- **Refresh cadence (0023).** 0019's hourly interval assumed ~10 s per run was
  too heavy for a 5-minute tick, which confused cost per *run* with cost per
  *day*: the procedure refreshes only when the dirty counter moved, so the run
  count is set by how often sleep data arrives, not by how often the job ticks.
  Measured: 5 `sleepSession` syncs in 7 days (~24 h apart), ~3.2 counter bumps a
  day, 1 actual SRI refresh in 24 h. An hourly window coalesces almost nothing,
  so ticking every 5 min runs the same handful of refreshes *earlier* — bounded
  by ~20 s of extra CPU a day, with non-dirty ticks still costing 0.03 s.
  **The gate had to change with it.** `drift_min_per_day` reads
  `sleep_rolling_stats`, both jobs watch the same counter, and
  `REFRESH … CONCURRENTLY` keeps the pre-refresh snapshot visible until commit —
  so on a shared 5-minute schedule a simultaneous SRI run would compute drift
  from the *old* midpoints and sit permanently one sync behind. Gating on
  `refreshed_changes` (the watermark 0016 stamps *after* its refresh commits)
  instead of `changes` makes the ordering structural: SRI can only become dirty
  once the rolling stats are current. Costs one extra tick — worst case 1 h 5 min
  → **10 min** — and closes a rare pre-existing race (the hourly job fired at
  :46:55, a 5-min tick at :47:13, so a sync landing just before :46:55 already
  made SRI read stale midpoints).
  **Rejected in 0023 — folding SRI into `sleep_stats_refresh_if_dirty`.** One
  job, sequential by construction, 5-minute worst case. It couples the failures:
  the watermark is stamped after all refreshes, so an SRI failure would leave
  `refreshed_changes` unstamped and the 5-min job would redo the ~9 s
  rolling-stats refresh every tick and fail again — freezing Bedtime/Wake/
  Duration because the more fragile view broke. 0016 split refreshes out of the
  write path so one surface's maintenance cannot take another down.
- **`is_28d` — Interdaily Stability (0020), in the same matview and panel.** SRI
  compares each minute to the same minute 24 h later, so it is blind *by
  construction* to a schedule that drifts steadily: shift 20 min later every day
  and each day still resembles the next. IS (Witting et al. 1990) compares
  against the window's **average 24 h profile** instead, so drift destroys it.
  Over a common 28-day window (n=844) the two correlate 0.88 with each other but
  −0.41 (SRI) vs −0.68 (IS) with measured phase drift — IS is ~1.7× more
  drift-sensitive, as the definitions predict. It is not theoretical: 2025-12-18
  scored SRI 46 / **IS 0.073** while the phase rotated ~8 h per 28 days, and
  2025-11 → 2026-03 sat at IS 0.05–0.12 throughout — four months with essentially
  no 24 h rhythm, which the SRI-only panel showed as unremarkable 15–50 noise.
  24 hourly bins (Witting's grain; the SRI half keeps 1-min epochs), trailing 28
  days, gated at ≥14 days of data. Plotted ×100 to share the 0-100 axis; stored
  natively 0..1. Computed with RANGE-frame window functions — expanding
  `Σ_h(x̄_h−x̄)² = (1/D²)Σ_h Sh² − 24x̄²` leaves only rolling sums, so it costs
  ~2 s instead of a per-day rescan.
  **Caveats:** IS is the *slow* companion (28 d vs SRI's 7 d), so the vertical gap
  between the two series mixes timescale with drift — a `sri_28d` column would
  make it clean and is deliberately not added until wanted. IS has no clinical
  cutoff either. IS says drift happened, not how fast or which way; for that,
  regress midpoint on time. Splitting the two into separate panels was considered
  and rejected: their empirical ranges nearly coincide (SRI 4.2–85.6, IS×100
  5.4–83.8) so the shared 0-100 axis distorts neither, and the *divergence* is
  the whole reason IS exists — two panels would hide it.
- **`drift_min_per_day` — signed phase drift (0021), Grafana panel 61 "Phase
  Drift".** SRI and IS both say *that* regularity broke; neither says which way
  the schedule is moving or how fast, and IS only responds when something is
  already wrong. `|drift|` correlates −0.50 with IS and just −0.22 with SRI, so
  it is largely its own signal — and the only one of the three carrying a sign.
  14-day trailing **mean** of `mod(Δmidpoint + 36, 24) − 12` in min/day, gated at
  ≥8 deltas; positive = moving later. Plotted zero-centred, orange above / blue
  below, so the sign reads at a glance.
  A step may span **1 or 2 sleep_days**, divided by the gap to stay a per-day
  rate (**0022**). 0021 required strictly consecutive days and left 42 of 856
  rows (4.9%) NULL, arriving in *pairs* — 2025-09-21/22, 10-09/10, 10-25/26 …
  One mechanism produced all of them: sleep starting ≥18:00 JST is labelled the
  next day, so if that is calendar day D's only sleep, no cluster is ever
  labelled `sleep_day = D`. Day D then has an SRI/IS row but no midpoint to
  difference, and day D+1's predecessor is D−1, so the gap is 2 and 0021 emitted
  nothing. Spanning two days recovers the second half of every pair: 842 → 867
  deltas, NULLs 42 → 25, and the value distribution barely moves (mean |value|
  155.1 → 153.0). The 25 that remain are correct — 17 are calendar days with no
  `sleep_day` at all (no midpoint exists to difference; filling them would be
  invention), 7 are the intended <8-deltas gate, 1 is a hole wider than 2 days.
  This mattered because the NULLs were not evenly spread: all 25 in the last year
  fall in 2025-08 → 2026-02 and none after 2026-02-19, since skipping a sleep_day
  needs an evening onset — which is what a rotating schedule does. The metric was
  thinnest over exactly the stretch it exists to describe. **Stop at 2:** the span
  assumes drift is linear across the hole (free for one day, an assumption for
  two) and the ±12 h wrap ambiguity grows with step length.
  The **mean, not the median** — the median looks like the robust choice and
  measures worse (day-to-day jitter 30.2 vs 23.9 min, peak |value| 379 vs 271,
  and implausible levels like 2025-09 = +107 min/day). The daily deltas are
  asymmetric so the median lands on one lobe, while the mean is what a drift
  *rate* means: the sum telescopes, so mean = net displacement ÷ days.
  **Limit:** a real change beyond ±12 h wraps to the wrong sign and the mean
  carries it. 29 of 842 deltas (3.4%) exceed 9 h, so trust it on entrained or
  slowly drifting stretches and read it loosely on the wildest ones — where IS
  is already near zero and saying so more honestly. Read the two together.
  **Dependency:** this is the first thing in `sleep_regularity` not derived
  straight from `sleep_session` — midpoint comes from `sleep_rolling_stats`, so
  the clustering, 18:00 cutoff and circular midpoint are inherited rather than
  reimplemented. That means it depends on a matview refreshed by a *different*
  job, so drift lands one tick behind the rolling stats — ≤10 min since 0023
  ordered the two watermarks, ≤1 h 5 min before it. Sharing a schedule *without*
  that ordering would have made drift wrong rather than merely late; see 0023.
  Fine for a 14-day rate; don't duplicate the clustering to avoid it.
- **Rejected in 0020:** *IV* (Intradaily Variability, the fragmentation member of
  the same family) correlates −0.91 with mean `main_share` and +0.79 with the
  fragmented-day count over the same window — good independent corroboration of
  `main_share`, redundant as a panel. A *28-day SRI* correlates 0.70 with the
  7-day one (SD 17.2 → 12.1): same signal, slower clock. *IS/IV/RA on `steps`*
  (the classical activity-based form) is plausible but a separate project.
- **Stage panels:** `sleep_stage_daily` **matview** (`0008` view + `0009` ±stddev bands,
  materialized in `0010_sleep_stage_daily_matview.sql` — the cluster + jsonb explosion
  was ~1.2 s/query × 5 panels; matview makes reads ~2 ms, refreshed by 0016's
  5-min job alongside sleep_rolling_stats) — per sleep_day light/deep/rem/awake
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
