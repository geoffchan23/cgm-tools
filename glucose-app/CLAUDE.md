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

The journal text is the enrichment source: Geoff writes free-form food/
activity/sleep notes at day and week level, and expects Claude Code to
parse them into structured data (meals, activities, etc.) during
analysis sessions — structure lives in the analysis, not the app.

Two machine-friendly conventions ride inside ordinary day-scope entries:
- **Day tags**: an entry whose whole text is `#sick`, `#stress`,
  `#travel`, or `#cycle` (toggled via chips in the day view; the app
  hides them from the notes list). Treat as boolean day flags.
- **Doses**: `dose: <type> <units>u @ HH:mm` where type is
  `short-acting` or `long-acting` and units is 1–100. Quick-entry
  button; always logged to today at the tap time, e.g.
  `dose: short-acting 4u @ 13:05`. Parse type/units/time from the
  pattern. (Entries logged before 2026-09-02 may use the older
  `dose: <name> <amount> @ HH:mm` free-name form.)

## Journal → events extraction (Claude Code runs this)

Parse day notes into `event: <name> @ HH:mm` entries (plotted as teal
diamonds on the chart's top edge; hidden from the notes list). Insert
via the ADB ingest receiver — NOT by editing the SQLite file (WAL):

```bash
adb shell am broadcast -n com.geoffchan.glucosewidget/.IngestReceiver \
  --es op clear-events --es day 2026-09-01          # idempotent re-runs
adb shell am broadcast -n com.geoffchan.glucosewidget/.IngestReceiver \
  --es op insert --es day 2026-09-01 --es text "'event: coffee @ 10:30'"
# op=refresh also exists (manual data refresh)
```

Gotchas: add `</dev/null` when broadcasting inside a shell loop (adb
eats stdin); the receiver is DUMP-guarded because Android skips shell
broadcasts to non-exported receivers.

**Geoff's standing time rules** (use when a note gives no time):
- coffee → 10:30
- dinner → 17:15
- Keep event names short (chart labels): coffee, snack, dinner,
  ice cream, plus activities like "ikea trip".

**Ledger**: `analysis/parse-ledger.json` records parsed days. Skip a
day unless its notes' `updatedAtMs` is newer than its `parsedAt`; when
re-parsing, `clear-events` first, and update the ledger.

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
