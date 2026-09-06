# Glucose Widget — notes for Claude Code

Personal Android app on Geoff's phone (reports as "Pixel 9 Pro"). Widget
shows the current Dexcom G7 reading; the app accumulates history and a
day-level journal. Sideloaded debug build only — never publish, never
add accounts or telemetry. Wearer's Dexcom Share login is a PHONE NUMBER
(+1416…), not an email.

## Exploring the data (no export feature — by design)

```bash
adb connect <phone-ip:port>   # port from Wireless debugging screen; rotates
tools/pull-db.sh              # → ./data/glucose.db (full snapshot = backup)
sqlite3 data/glucose.db
```

Schema:
- `readings(timestampMs INTEGER PK, mgdl INTEGER, trend TEXT)` — one row
  per CGM reading (~5 min cadence), epoch ms UTC, deduped, immutable.
  mmol/L = mgdl / 18.0182. Trend strings: Flat, FortyFiveUp/Down,
  SingleUp/Down, DoubleUp/Down, None, NotComputable, RateOutOfRange.
- `journal(id INTEGER PK, day TEXT 'YYYY-MM-DD', text TEXT,
  createdAtMs INTEGER, updatedAtMs INTEGER, scope TEXT 'day'|'week')` —
  free-text notes, many per key, no intra-day time (LLM infers times
  from text). For scope='day', `day` is the local date; for
  scope='week', `day` is the MONDAY of the ISO week (weeks run Mon–Sun).
  All local dates are the phone's timezone (America/Toronto).

Day-scope entries are structured logs (since 2026-09-05); week-scope
entries are free-text summaries. Conventions inside `journal.text`:
- **Doses**: `dose: <type> <units>u @ HH:mm` where type is
  `short-acting` or `long-acting` and units is 1–100. Quick-entry
  dialog; always logged to today, e.g. `dose: short-acting 4u @ 13:05`.
  (Entries logged before 2026-09-02 may use the older
  `dose: <name> <amount> @ HH:mm` free-name form.)
- **Food/exercise logs**: `event: <text> @ HH:mm`, e.g.
  `event: lunch @ 12:40`, `event: 30 min walk @ 18:00`. Logged from the
  "Log food/exercise" dialog to the day being viewed. Plotted as teal
  diamonds on the chart; shown in the notes list as "12:40 PM · lunch".
- **Legacy day tags**: an entry whose whole text is `#sick` etc. Hidden
  from the list; treat as boolean day flags. Chips UI removed 2026-09-02.
- Times are stored 24-hour; the UI shows 12-hour with AM/PM.

There are no free-text day notes left: the 2026-09-01 … 09-05 notes were
parsed into `event:` rows and then deleted (originals preserved in
`analysis/parse-ledger.json`). No further parsing is expected. To insert
or delete a single row, use the ADB receiver — NOT the SQLite file (WAL):

```bash
adb shell am broadcast -n com.geoffchan.glucosewidget/.IngestReceiver \
  --es op insert --es day 2026-09-01 --es text "'event: coffee @ 10:30'"
adb shell am broadcast -n com.geoffchan.glucosewidget/.IngestReceiver \
  --es op delete --es day x --el id 42                # one row, by journal.id
# op=refresh also exists (manual data refresh + widget redraw)
# op=report-ready --es name <file> posts the "report ready" notification
#   (tools/push-report.sh sends it; needs POST_NOTIFICATIONS, granted via
#   `pm grant` on 2026-09-06 and requested on launch as a fallback)
```

There is deliberately no bulk-delete op: `event:` rows are user data now.
Gotchas: add `</dev/null` when broadcasting inside a shell loop (adb
eats stdin); the receiver is DUMP-guarded because Android skips shell
broadcasts to non-exported receivers. With several ADB devices attached
(phone, watch), pass the serial: `tools/pull-db.sh <ip:port>`.

## Morning routine (auto-logged)

Every day at 10:30 the app logs `dose: short-acting 4u @ 10:30`,
`dose: long-acting <19|25>u @ 10:30` (19 weekdays, 25 Fri–Sun) and
`event: coffee @ 10:30`. `MorningRoutine.ensure()` runs inside the
5-minute refresh worker: first run after 10:30 local inserts whatever is
missing and stamps `routineLoggedDay` in DataStore. A hand-logged dose of
the same type or an event named coffee that day suppresses its routine
twin (any time/units), so a 10:25 hand entry doesn't get a 10:30 double.
Wrong-day entries are just deleted in the app like any other row.

## Widget

Three rows: reading + trend arrow with its age on the right; last dose
(▲ short / △ long, units) with its clock time ("10:30 AM"); last food/exercise log
likewise. Dose/log rows come straight from Room (last 3 days) at render
time; the app calls `GlucoseWidget().updateAll()` after every journal
save/delete, and the 5-min refresh keeps the reading age current.

## Nutrition & activity estimates (analysis/nutrition/)

Per-day JSON (`analysis/nutrition/<day>.json`) with each logged event
broken into items (grams, kcal, carb, protein, fat, fibre, sugar) and
activities (MET, minutes, kcal). Claude in a session does the parsing —
**never the Claude API** (Geoff's rule) — via the `nutrition` skill;
`analysis/nutrition.py` does search/lookup/arithmetic. Sources, in order:
`nutrition/cnf.json` (Canadian Nutrient File, offline, primary),
Open Food Facts (`search --off`, brands; flaky 503s), USDA FoodData
Central (`search --usda`, DEMO_KEY ≈10 req/h). Non-CNF hits are cached in
`nutrition/food-cache.json`. Activities use `nutrition/met.json` (2024
Compendium) × `config.json` weightKg (65.8 kg ≈ 145 lb). All committed;
they are part of the dataset. `report.py` shows carbs on meal markers, a
per-day line and weekly averages, labelled as estimates; unparsed days say
so. `weekly-report.sh` prints which days are unparsed; the weekly-report
skill fills them in.

## Weekly report

`analysis/report.py` turns `data/glucose.db` into a self-contained HTML
report (consensus metrics, 24 h overlay, day strips, every low with its
preceding 3 h, observations for the endo). `tools/weekly-report.sh` runs
the whole cycle: find phone via mDNS → pull DB → generate → push into the
app's **Reports** screen (`files/reports/report-<first>_<last>.html`,
list icon in the header, WebView; a notification is posted when a new
report arrives and opens it directly). The `weekly-report` skill (repo
`.claude/skills/`) wraps that plus republishing the artifact
(`https://claude.ai/code/artifact/42522e03-0809-4e84-b7cb-7ff7eebf974a`).
A launchd job (`tools/install-weekly-job.sh`, Sundays 20:00) runs the
shell pipeline only — not Claude: under launchd `claude` (and Homebrew
python3) hang on macOS Desktop-folder protection (TCC) while the repo
lives under `~/Desktop`; Apple's `/usr/bin/python3`, bash, adb, sqlite3
are exempt (verified 2026-09-06; a repo outside Desktop avoids all of
it). Log:
`data/weekly-report.log`. The generated report is gitignored (health data).

## Build & deploy

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Data source: unofficial Dexcom Share API, `shareous1.dexcom.com` (Canada).
Each 5-min poll (exact alarm → worker) fetches the trailing 24 h and
upserts, so gaps under 24 h self-heal; longer gaps are lost (accepted —
official API deliberately not used). Widget reads a DataStore cache;
history lives in Room. `docs/superpowers/specs/` has the full design
history including decisions and their reversals.
