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
  dialog; logged to the day being viewed (today in week mode), e.g.
  `dose: short-acting 4u @ 13:05`.
  (Entries logged before 2026-09-02 may use the older
  `dose: <name> <amount> @ HH:mm` free-name form.)
- **Food/exercise logs**: `event: <text> @ HH:mm`, e.g.
  `event: lunch @ 12:40`, `event: 30 min walk @ 18:00`. Logged from the
  "Log food/exercise" dialog to the day being viewed. Plotted as teal
  diamonds on the chart; shown in the notes list as "12:40 PM · lunch".
- **Guesses** (Claude's inferences from the curve): the normal dose or
  event text plus the literal suffix ` (guess)`, e.g.
  `dose: short-acting 6u @ 17:30 (guess)`, `event: pizza @ 18:00 (guess)`.
  Both parsers accept it (`isGuess`); the chart draws them dashed and
  dimmer with a trailing `?` ("6u?", "pizza?"), the notes list labels
  them "guess" with a **Keep** button that strips the suffix, and editing
  one in the dose/log dialog saves it confirmed. The widget ignores
  guesses; routines treat them as covering (no double-logging). Insert
  them with the receiver like any row.
- **Describe in words** (FAB menu): a paragraph goes to on-device Gemini
  Nano (ML Kit GenAI Prompt API, AICore; nothing leaves the phone, never
  the Claude API). It answers JSON; `TextEntry.kt` validates strictly
  (`parseBreakdown`) and the confirm list shows each row with editable
  time/units/name. Rows matching an entry already logged (same dose type
  or event name within ±60 min, `matchExisting`) start unchecked as
  "already logged"; a row matching a guess starts checked and saving
  confirms that guess with the stated values. Saves to the day being
  viewed (today in week mode). Inference only runs with the app in the
  foreground (AICore error 30 otherwise); the model downloads on first
  use. Debug: `--es op describe-test --es text "'…'"` logs status, raw
  output and parsed rows to logcat tag `Describe` (read-only).
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

## Auto-logged routines

Every day at 10:30 (Sundays 14:00, since 2026-10-07) the app logs `dose: short-acting 4u @ 10:30`,
`dose: long-acting 19u @ 10:30` (19 every day since 2026-09-12) and
`event: coffee @ 10:30`. `MorningRoutine.ensure()` runs inside the
5-minute refresh worker: first run after 10:30 local inserts whatever is
missing and stamps `routineLoggedDay` in DataStore. A hand-logged dose of
the same type or an event named coffee that day suppresses its routine
twin (any time/units), so a 10:25 hand entry doesn't get a 10:30 double.
Wrong-day entries are just deleted in the app like any other row.

Mon-Thu also gets an **evening routine** at 17:30: `dose: short-acting 6u`
and `event: chicken burger` (added 2026-09-17). Fri-Sun dinner varies, so
nothing is logged. Dedupe is windowed at `ROUTINE_SPLIT_MINUTE` (15:00) so
the morning's short-acting doesn't suppress the evening's, and the evening
meal is suppressed by *any* food already logged after 15:00 rather than by
name — a logged pizza means no phantom burger. Both live in `Routines.kt`
with their own DataStore day-marker.

## Two phones: sync (from 2026-10)

Her Pixel 9 Pro is the **main** phone (runs the 10:30/17:30 routines, takes
watch entries); Geoff's is a **second** phone (`isMainPhone=false`, never
auto-logs). Both read Dexcom Share themselves, and both can add/edit/delete
journal rows: changes sync both ways, end-to-end encrypted, through an
ntfy.sh topic (no account). The relay only sees base64(nonce ‖ AES-256-GCM).

- Every journal write goes through `Journal` (dialogs, routines, receiver),
  which queues the row's `uid` in an outbox; `SyncWorker` publishes each
  uid's current row or tombstone (chunked under ntfy's 4 KB limit) and
  polls the topic. Polls also run on every 5-min refresh and app open.
- Merge (`SyncCore.kt`, unit-tested): by `uid`, last write wins on
  `updatedAtMs`, ties keep local; a delete beats any edit older than it.
  Remote rows are written straight to Room so they aren't re-published.
- ntfy.sh keeps messages ~12 h, so once a day each phone re-sends what
  changed in the last **7 days**. A phone offline longer than that needs
  Settings → "Resend 30 days" (or `op sync-resend`) on the other phone.
- `journal.id` is per phone; use ids from a pull of the phone you target.
  Because edits sync, `pull-db.sh`, the IngestReceiver and the fill-gaps
  push can target either phone — Geoff's is usually the one on ADB.
- Settings shows sync status (role, last check/send, waiting, last error).

Configure both phones with the same key and topic (keep them out of git):

```bash
KEY=$(python3 -c "import os,base64;print(base64.b64encode(os.urandom(32)).decode())")
TOPIC=cgm-$(python3 -c "import secrets;print(secrets.token_hex(16))")
R="shell am broadcast -n com.geoffchan.glucosewidget/.IngestReceiver"
adb -s <her-phone>   $R --es op sync-setup --es key "$KEY" --es topic "$TOPIC" --ez main true
adb -s <geoff-phone> $R --es op sync-setup --es key "$KEY" --es topic "$TOPIC" --ez main false
# also: --es op sync-now | sync-resend | sync-reset   (logcat tag "Sync")
```

## Widget

Three rows: reading + trend arrow with its age on the right; last dose
(▲ short / △ long, units) with its clock time ("10:30 AM"); last food/exercise log
likewise. Dose/log rows come straight from Room (last 3 days) at render
time; the app calls `GlucoseWidget().updateAll()` after every journal
save/delete, and the 5-min refresh keeps the reading age current.

## Watch (voice log from her Pixel Watch 2)

`wear/` is a Wear OS module: launcher app **Glucose Log** plus a tile
with one big mic button. She speaks ("took 6 units and a chicken
burger"); the watch sends the transcript to her phone (the main phone)
over the Wearable Data Layer; the phone replies with rows; she unticks
anything wrong and taps Save; the phone writes them to today via
`Journal` and the watch buzzes ✓. Paths and JSON are in
`WatchProtocol.kt` (phone) and `wear/…/Protocol.kt` (watch) — change
both together. The wear APK must keep applicationId
`com.geoffchan.glucosewidget` and the same debug key as the phone app,
or the phone never hears it. Only a phone with `isMainPhone` answers.

Parsing: `WatchListenerService` tries Gemini Nano first (5 s), but
AICore refuses background use (error 30) — the usual case with the
phone in a pocket — so `parseSpoken` (`SpokenEntry.kt`, deterministic,
unit-tested) does most of the work: number words, "19 and 4" (19/20 →
long), insulin brand/slang words, "at 5:30" / "an hour ago" /
"this morning", "walked 20 minutes" → `20 min walk`; anything without a
time is stamped now. Rows matching an existing entry come back
"already logged" (unticked); a match on a guess confirms it. Logcat tag
`WatchLog` shows which parser ran and the rows.

```bash
adb -s <watch-ip:port> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Then on the watch: swipe to the end of the tiles → **+ Add tile** →
**Glucose log**. (Watch ADB: Settings → Developer options → Wireless
debugging → pair once; it drops when the watch sleeps off-charger.)

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
Compendium) × `config.json` weightKg (65.8 kg ≈ 145 lb). Standing recipes
in `nutrition/recipes.json` — `coffee` is always creamer + 1 cup 2% milk +
3 Sweet'N Low + Pike Place (~30 g carbs), never black; `chicken burger` is
always GV bun + Janes patty + 2 tbsp Chick-fil-A sauce + 20 g cheddar. All committed;
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
A launchd job (`tools/install-weekly-job.sh`, Sundays 22:00) runs the
shell pipeline only — not Claude: under launchd `claude` (and Homebrew
python3) hang on macOS Desktop-folder protection (TCC) while the repo
lives under `~/Desktop`; Apple's `/usr/bin/python3`, bash, adb, sqlite3
are exempt (verified 2026-09-06; a repo outside Desktop avoids all of
it). Log:
`data/weekly-report.log`. The generated report is gitignored (health data).

## Build & deploy

Kotlin 2.1.21 + KSP1 (`ksp.useKSP2=false`: Room 2.6.1 breaks under KSP2).
ML Kit `genai-prompt` is pinned at 1.0.0-beta2 — beta3+ pulls the Kotlin
2.3 stdlib; beta2's 2.2 metadata needs Kotlin ≥ 2.1. `maxOutputTokens` is
capped at 256 by the API.

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
