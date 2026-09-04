# Glucose Widget — an Android phone app

A home-screen widget showing the current Dexcom G7 reading, plus a day/week
history chart with a food-and-insulin journal. Personal, sideloaded only.
This phone is **not** the one paired to the sensor, so all data comes from
Dexcom's **Share** cloud.

- **Widget**: current value in mmol/L + trend arrow + reading age. White in
  range, red low, amber high, grey when stale. Tap opens the app on today.
- **Day / Week view**: midnight-to-midnight (or Mon–Sun) dot chart with a
  shaded target band; pinch to zoom, drag to pan, double-tap to reset, and a
  fullscreen landscape mode. The current reading is labelled on the now-line.
- **Journal**: free-text notes per day and per week. A quick-entry dialog logs
  insulin doses (short/long-acting, units, time). Meals and doses plot on the
  chart as markers riding the glucose curve.

Kotlin + Jetpack Compose + Glance + Room. minSdk 34 / target 35.

## Build & install

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app once, enter the wearer's Dexcom Share login (a phone number on
this account, not an email), set thresholds, and grant the two reliability
permissions the setup screen offers (exact alarms + battery-unrestricted) so
the 5-minute refresh survives Doze.

## How data flows

The app polls the unofficial Dexcom Share API (`shareous1.dexcom.com`, the
Canada server) every 5 minutes via an exact-alarm chain. Each poll fetches the
trailing 24 h and upserts into a local Room database, so gaps shorter than a
day self-heal. The widget renders a cached snapshot.

There is **no export feature** — instead, Claude Code pulls the SQLite file
directly over ADB (the app is a debug build, so `run-as` works) for analysis,
and free-text journal notes are parsed into structured `event:` markers by
extraction runs. See [`CLAUDE.md`](CLAUDE.md) for the schema, the
`tools/pull-db.sh` recipe, the parsing conventions, and the standing rules.

## Notes on privacy

The database lives unencrypted in app-private storage; Dexcom credentials are
kept in `EncryptedSharedPreferences`. Nothing leaves the phone except the
Share API calls. Fine for a personal device — just be aware before sharing a
pulled DB.
