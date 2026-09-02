# Glucose Widget — design

2026-09-01. Personal Android app for Geoff's Pixel 10 Pro: a home-screen
widget showing the current Dexcom G7 reading. Sideloaded only; never
published. The phone is NOT the G7 collector phone — data comes from
Dexcom's Share cloud (Share/Follow is enabled on the account).

## Scope (v1)

- Home-screen widget: current glucose in **mmol/L** + trend arrow +
  reading age ("3m"). No chart, no notifications.
- Color by threshold: red below low, amber above high, white in range,
  gray when stale (>10 min). Defaults 3.9 / 10.0 mmol/L, editable.
- One setup screen: wearer's Dexcom credentials (stored encrypted,
  on-device only), thresholds, test-connection, permission helpers.
- No database. Last reading cached in DataStore (survives reboot,
  feeds the stale display). Decision record: Room/local accumulation
  was considered and rejected — historical data is retrievable any
  time via the official Dexcom API or Clarity export, so continuous
  collection buys nothing (Geoff's call, 2026-09-01).

## Explicit non-goals (possible later phases, decoupled)

- In-app history charts: would fetch up to 24 h from Share on demand.
- Multi-month/year analysis: separate tool against the **official**
  Dexcom API (OAuth, retrospective), run where the analysis runs.
  Not this app, no backend now.

## Data source — unofficial Dexcom Share API

Base `https://shareous1.dexcom.com/ShareWebServices/Services` (OUS
server — Canada). Three POSTs, JSON bodies, well-known application id
`d89443d2-327c-4a6f-89e5-496bbb0317db`:

1. `General/AuthenticatePublisherAccount` {accountName, password,
   applicationId} → account GUID
2. `General/LoginPublisherAccountById` {accountId, password,
   applicationId} → session GUID
3. `Publisher/ReadPublisherLatestGlucoseValues?sessionId=…&minutes=1440&maxCount=1`
   → `[{"WT":"Date(1693526400000)","Value":195,"Trend":"Flat"}]`

Value is mg/dL → mmol/L = value / 18.0182, 1 decimal. Trend strings →
arrows: DoubleUp ↑↑, SingleUp ↑, FortyFiveUp ↗, Flat →,
FortyFiveDown ↘, SingleDown ↓, DoubleDown ↓↓, else "–". Session
expiry → re-login once, then surface error state.

## Architecture

Single Kotlin module, minSdk 34 / target 35, same toolchain as the
watch face project (AGP 8.7.3, JDK 17, SDK 35 via Homebrew).

- **ShareClient** — OkHttp; auth/login/fetch; pure parsing functions
  split out (JVM-unit-testable).
- **GlucoseStore** — DataStore for thresholds + cached reading;
  EncryptedSharedPreferences for credentials.
- **Refresh pipeline** — exact alarm every 5 min
  (`setExactAndAllowWhileIdle`, self-rescheduling) → expedited
  one-time WorkManager job → fetch → store → update widgets.
  WorkManager periodic was rejected: 15-min minimum. Boot receiver
  reschedules. Widget-enabled starts the chain; widget-disabled stops
  it. Tap on widget = manual refresh.
- **GlucoseWidget** — Glance appwidget: number (size-dominant), arrow,
  age line; colors per state machine: LOW red / HIGH amber / IN_RANGE
  white / STALE gray / ERROR gray "!".
- **SetupActivity** — Compose: credentials, thresholds, test & save;
  buttons for exact-alarm permission and battery-optimization
  exemption (both needed for reliable 5-min updates on a sideloaded
  app).

## Error handling

- Network/auth failure: keep last cached reading; widget shows it with
  its true age (stale styling does the honest work). Auth failure also
  flags "sign-in needed" state.
- Clock-skew guard: age computed from reading timestamp vs now; never
  negative-clamped below 0.

## Testing

- JVM unit tests: response parsing (incl. `Date(…)` format), mmol
  conversion, trend→arrow map, color/state selection, stale logic.
- On-device: sideload via wireless ADB, screenshot the widget, iterate
  — same loop as the watch face.

---

# v2 addendum — history + journal (2026-09-02)

Decision reversal, deliberate: official Dexcom API is skipped, so the app
now accumulates its own history from Share (previous "no collection" call
is superseded by Geoff).

- **Room/SQLite**, on-device. `readings(timestampMs PK, mgdl, trend)`
  immutable/deduped; `journal(id PK, day "YYYY-MM-DD", text, createdAtMs,
  updatedAtMs)` — day-level free-text entries, many per day, no time of
  day (LLMs will infer times from text later — Geoff's call).
- **Collection**: each 5-min poll fetches the trailing 24 h
  (minutes=1440, maxCount=288) and upserts — gaps <24 h self-heal.
  Scale: ~105k rows/yr; SQLite is comfortable for decades.
- **Day view UI**: opens on today; ‹ › + date picker; midnight-to-midnight
  chart (target band 3.9–10, widget color language, today = partial);
  journal list with add/edit/delete below. Settings on a second screen.
- **No export feature.** Claude Code pulls the DB directly over wireless
  ADB via `run-as` (debug build): `tools/pull-db.sh` + repo CLAUDE.md
  document the recipe and schema. Each pull doubles as a full backup.
- Health-data note: DB lives unencrypted in app-private storage on a
  personal phone; accepted.
