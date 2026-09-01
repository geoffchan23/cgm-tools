# CLAUDE.md — handoff notes

Read this first. It is the state of the work as of the last session, written
for a fresh Claude Code session with no memory of how we got here.

---

## The goal, in one sentence

Geoff wears a **Pixel Watch 2** and wants his stock Pixel digital watch face
kept exactly as-is, except the Dexcom glucose reading — currently a tiny line
at the bottom — rendered **much bigger**, with the `mmol/L` units and the
`Now` timestamp dropped.

See `docs/stock-face-reference.jpg` for the target. That is a photo of the
face he is replacing. `watchface/src/main/res/drawable-nodpi/preview.png` is
a mock of what this project should produce.

His exact words: *"we want it identical just with a bigger blood sugar
number. also, remove the mmol/L but keep the direction number. lose the time
last updated. make the blood sugar number bigger. everything else stays the
same."*

## Setup facts (confirmed, do not re-litigate)

| | |
|---|---|
| Watch | Pixel Watch 2, **Wear OS 6** (rolled out Oct 2025), 384x384 screen |
| Phone | Pixel 10 Pro |
| CGM | Dexcom G7, official Dexcom app on the phone |
| Units | **mmol/L** (Canada) — values are like `9.9`, not `178` |
| Data path | **Already working.** The Dexcom app publishes a Wear OS complication, and it is already showing on his stock face. |

**The data problem is solved and is not part of this project.** Do not build
a Dexcom Share poller, a Nightscout bridge, a companion phone app, or a
`ComplicationDataSourceService`. Earlier in the conversation we scoped all of
that out once he revealed the Dexcom complication already works. This repo
draws a complication *slot*; Dexcom fills it.

## Where things stand

Everything in the repo was written in a **cloud container with no Android
SDK** (Google's download hosts are blocked there). So:

- ✅ The project is complete and committed on `claude/custom-wearos-watch-face-5a4az6`
- ❌ **It has never been compiled. Not once.** No `gradlew` run, no WFF
  validation, no install, nothing has ever touched a real watch.

Treat the XML as a careful first draft, not as working code. The most likely
failure is a wrong attribute or element name in `watchface.xml` — the Watch
Face Format schema is strict and the build will name the offending line.

## Do this next, in this order

1. **Get the toolchain up.** `brew install --cask temurin android-platform-tools android-studio`,
   then `local.properties` with `sdk.dir=...`. README has the detail.
2. **Build it.** `./gradlew assembleDebug`. Expect failures in
   `watchface/src/main/res/raw/watchface.xml`. Fix them against the official
   spec at https://developer.android.com/training/wearables/wff — do not
   guess at element names, look them up.
3. **Connect the watch.** Developer options → ADB debugging. The charging
   puck carries data, which is more reliable than wireless. Geoff has to
   physically tap through this and tap "Allow"; ask him.
4. **Install.** `adb install -r watchface/build/outputs/apk/debug/watchface-debug.apk`
5. **Have him select the face and assign Dexcom to the slot.** One long-press,
   two taps. This is unavoidable the first time unless step 7 works.
6. **Screenshot and iterate.**
   `adb shell screencap -p /sdcard/w.png && adb pull /sdcard/w.png` — then
   look at it. This is the whole point of working locally; use it every time.

Then the open questions below.

## Open questions — none of these are answered yet

**Does Dexcom publish a SHORT_TEXT complication, and does it include the
trend arrow?** This is the big one. The whole design rests on it. Find out:

```bash
adb shell cmd package query-services \
  -a android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST \
  | grep -i -A20 dexcom
```

- If SHORT_TEXT exists and reads `9.9↗` → done, nothing to change.
- If SHORT_TEXT is only `9.9` → the arrow needs a separate element beside
  the number, probably `[COMPLICATION.MONOCHROMATIC_IMAGE]` in a `PartImage`.
  Check what Dexcom actually supplies before designing this.
- If there is no SHORT_TEXT at all → we fall back to LONG_TEXT, `mmol/L`
  comes back, and the units cannot be stripped (WFF has no string
  manipulation). Tell Geoff and discuss options rather than picking one.

**Is the blue right?** `#ff168bf4` was sampled off a *photograph* of the
stock face, so it is approximate. Once ADB works, screenshot the stock Pixel
face directly and read the exact value. A clean sample of the large clock
glyphs gave `#1470D7`; the whole-face 90th percentile gave `#168BF4`. Both
are plausible; the screenshot settles it.

Note: date, time, and glucose are **all the same blue** on the stock face.
An earlier read that the glucose line was white was wrong — the camera
overexposed the thin strokes. Verified by enlarging the crop.

**Is the size right?** Glucose is `size="100"` against a `size="118"` clock,
which makes them near-equal weight. Geoff may want it bigger still, which
means shrinking the clock. Show him a screenshot and ask.

## Design decisions — understand before changing

- **Watch Face Format, not AndroidX.** WFF has been mandatory since January
  2026. Do not port this to a Kotlin `WatchFaceService`; it will not install.
- **`android:hasCode="false"`, no source files.** That is correct and
  intentional. The service class in the manifest is supplied by the platform.
- **The slot requests SHORT_TEXT first.** This is the mechanism that drops
  `mmol/L` and `Now` — short text is capped around 7 chars, so Dexcom sends
  the bare value. There is no string trimming anywhere and there cannot be.
- **450x450 design canvas, scaled by the platform to 384x384.** Do not
  "correct" the coordinates to 384. They are resolution independent on purpose.
- **12-hour clock, no leading zero**, matching the photo. `[HOUR_1_12]`.

## Known gap, deliberately deferred

The date renders **`Mon Aug 31`**, but the stock face shows **`MON AUG 31`**.
WFF has no uppercase function. Matching it exactly needs a `<Condition>`
lookup table over 7 weekday and 12 month names — mechanical but verbose, and
it was not worth risking a syntax error before the first build ever ran.

**Do this once the build is green and installed.** It is a real part of
"identical" and Geoff will notice it.

## Things not to do

- Do not publish anything to the Play Store. He said explicitly: *"I don't
  want to submit to any store. I just want to change my watch face."*
  Sideloading via ADB is the entire distribution plan.
- Do not add Dexcom API credentials, OAuth, or network code of any kind.
- Do not open a pull request unless he asks.
- Do not widen the scope. He wants one number bigger.

## Repo map

```
watchface/src/main/res/raw/watchface.xml   <- the entire watch face; ~everything lives here
watchface/src/main/AndroidManifest.xml     <- WFF declaration, service, preview
watchface/build.gradle.kts                 <- compileSdk 35, minSdk 34 (WFF v2 needs Wear OS 5+)
docs/stock-face-reference.jpg              <- the target, photographed
README.md                                  <- build + install + ADB pairing, written for Geoff
```
