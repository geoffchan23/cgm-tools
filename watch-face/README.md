# Big Glucose — a Wear OS watch face

The stock Pixel digital face, kept as-is, with the Dexcom glucose reading
rendered about 4× larger. Units and the "Now" timestamp are gone; the value
and its trend arrow stay.

```
   MON AUG 31         <- date, unchanged (uppercase, two-tone)
     10:50            <- time, unchanged
     10.9 →           <- was tiny, now the size of the clock
```

Built for a **Pixel Watch 2** (Wear OS 6, 384×384), using the **Watch Face
Format** — declarative XML, no code, the only format Wear OS accepts as of
2026. Everything lives in [`watchface/src/main/res/raw/watchface.xml`](watchface/src/main/res/raw/watchface.xml).

## How the glucose gets there

This face doesn't talk to Dexcom. It draws a *complication slot*, and a
bridge app fills it — on this watch that's **Glucose Watch** by Sagitta
Software (`com.sagittasoftware.glucosewatch`), not the Dexcom app, which
publishes no complication. Its `SHORT_TEXT` form is `10.9 →` — value plus
arrow, no units, no timestamp — which is exactly what we render big. A
`DefaultProviderPolicy` pre-wires that provider, so the face arrives with
glucose already attached; no manual slot assignment.

## Build, validate, install

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
```

Gradle **cannot** schema-check the WFF XML (`res/raw` is opaque to aapt2), so
always run Google's validator after editing — it catches every real error and
names the line:

```bash
java -jar wff-validator.jar 2 watchface/src/main/res/raw/watchface.xml
# jar from https://github.com/google/watchface/releases (tag: latest)
```

Connect the watch over Wi-Fi ADB (`adb pair` once, then `adb connect`),
install, and activate from the shell without touching the wrist:

```bash
adb install -r watchface/build/outputs/apk/debug/watchface-debug.apk
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
  --es operation set-watchface --es watchFaceId com.geoffchan.bigglucose
adb shell screencap -p /sdcard/w.png && adb pull /sdcard/w.png   # iterate
```

## Tweaking the look

| To change | Edit (in `watchface.xml`) |
|---|---|
| Glucose size | `size="100"` in the `SHORT_TEXT` complication |
| Time size | `size="134"` in the time `PartText` |
| Colours | weekday `#ffaeb4ff`, clock `#ffa0a7ff`, glucose `#ffffffff` (sampled from an on-device screenshot of the stock face) |
| 24-hour clock | `[HOUR_1_12]` → `[HOUR_0_23]` |

The canvas is 450×450, scaled by the platform to the real 384×384 — the
coordinates are resolution independent, so don't "correct" them.

See [`CLAUDE.md`](CLAUDE.md) for the hard-won WFF gotchas and the exact
positioning math (uppercase date via `Condition` lookup tables, glucose
centering around the baked-in arrow).

## Known remaining deltas

- **Font**: `SYNC_TO_DEVICE` renders Roboto-ish; the stock clock uses Google's
  rounded flex font. Matching exactly would need embedding a font.
- **Ambient (AOD)** rendering hasn't been checked.
