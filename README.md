# Big Glucose — a Wear OS watch face

The stock Pixel digital face, with the Dexcom glucose reading rendered
about 4x larger. Units and the "Now" timestamp are gone; the value (and
trend arrow, if Dexcom supplies it in the short-text form) stay.

```
   Mon Aug 31          <- date, unchanged
     9:50              <- time, unchanged
     9.9               <- was tiny, now the size of the clock
```

Built for a Pixel Watch 2 on Wear OS 6, paired with the Dexcom G7 app.
It uses the **Watch Face Format** — declarative XML, no code — which is
the only format Wear OS accepts as of January 2026.

## How the glucose gets there

This face does not talk to Dexcom. It draws a *complication slot*, and
the Dexcom G7 app fills it — the same complication already working on
your stock face. Nothing about your Dexcom setup changes.

The one meaningful design choice: the slot asks for **SHORT_TEXT** first.
Short text is capped around 7 characters, so Dexcom hands over the bare
value instead of `9.9 mmol/L ↗ Now`. That is what drops the units and the
timestamp — not any string manipulation on our side.

---

## Building and installing

Nothing here is published to any store. It installs directly onto your
watch over USB or Wi-Fi.

### 1. Tools (one time)

```bash
brew install --cask temurin android-platform-tools android-studio
```

Open Android Studio once and let it install the SDK, or point Gradle at
an existing one by creating `local.properties`:

```
sdk.dir=/Users/YOU/Library/Android/sdk
```

### 2. Put the watch in developer mode (one time)

On the watch: **Settings → System → About → Versions**, tap **Build
number** seven times. Then **Settings → Developer options → ADB
debugging** on.

### 3. Connect

**Over the charger** — the Pixel Watch 2's puck carries data. Plug it
into the Mac and tap **Allow** on the watch.

**Over Wi-Fi** — also enable **Wireless debugging**, tap **Pair new
device**, and use the address, port, and code it shows:

```bash
adb pair 192.168.1.50:37421      # pairing port + 6-digit code
adb connect 192.168.1.50:5555    # the other port on that screen
adb devices                      # should list the watch
```

Keep the watch on its charger; it drops the connection when it sleeps.

### 4. Build and install

```bash
./gradlew assembleDebug
adb install -r watchface/build/outputs/apk/debug/watchface-debug.apk
```

### 5. Pick it and attach Dexcom

Long-press the current face → swipe to **Big Glucose** → tap the number →
choose **Dexcom**.

That last step is once only. If Dexcom doesn't appear in the list, see
"Finding Dexcom's complication" below.

---

## Changing how it looks

Everything lives in `watchface/src/main/res/raw/watchface.xml`, laid out
on a 450x450 canvas that Wear OS scales to the real 384x384 screen. Do
not change the coordinates to match your watch — they are meant to be
resolution independent.

| To change | Edit |
|---|---|
| Glucose size | `size="100"` in the `SHORT_TEXT` complication |
| Colour | `#ff168bf4` (sampled off the stock face) |
| Time size | `size="118"` in the time `PartText` |
| 24-hour clock | `[HOUR_1_12]` → `[HOUR_0_23]` |

Then rebuild and reinstall — steps 4 above, about 20 seconds.

To see the result without taking your wrist off:

```bash
adb shell screencap -p /sdcard/w.png && adb pull /sdcard/w.png
```

## Finding Dexcom's complication

If you want the face to arrive with Dexcom already attached instead of
picking it by hand, find the service name:

```bash
adb shell cmd package query-services \
  -a android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST \
  | grep -i dexcom
```

Then add a `<DefaultProviderPolicy>` inside the `<ComplicationSlot>`
naming it. This also tells you which complication *types* Dexcom
publishes, which is worth knowing if the short-text form turns out not to
include the trend arrow.

## Known rough edges

- **Date is title case** (`Mon Aug 31`), not `MON AUG 31`. The format has
  no uppercase function, so matching the stock face exactly needs a
  lookup table of the 7 weekday and 12 month names. Easy to add, just
  verbose.
- **The trend arrow is untested.** If Dexcom's short text is only `9.9`,
  the arrow needs a separate image element next to the number.
- **The small white dot** on the stock face is the system notification
  indicator, drawn by Wear OS. It isn't part of the face and appears
  automatically.
