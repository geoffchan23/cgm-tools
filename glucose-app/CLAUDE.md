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
```

There is deliberately no bulk-delete op: `event:` rows are user data now.
Gotchas: add `</dev/null` when broadcasting inside a shell loop (adb
eats stdin); the receiver is DUMP-guarded because Android skips shell
broadcasts to non-exported receivers. With several ADB devices attached
(phone, watch), pass the serial: `tools/pull-db.sh <ip:port>`.

## Widget

Three rows: reading + trend arrow with its age on the right; last dose
(▲ short / △ long, units) with "Xm/Xh/Xd ago"; last food/exercise log
likewise. Dose/log rows come straight from Room (last 3 days) at render
time; the app calls `GlucoseWidget().updateAll()` after every journal
save/delete, and the 5-min refresh keeps the relative ages current.

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
