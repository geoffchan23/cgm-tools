# cgm-tools

Personal continuous-glucose-monitor tooling for a Dexcom G7 setup, built
to sideload only — nothing here is published to any store.

## Why this exists

I built these for my wife, who lives with diabetes.

The **watch face** came first, out of frustration: the stock G7 watch face
renders the glucose number *so small* on her Pixel Watch 2 that it's hard to
read at a glance, and there was no way to enlarge it or swap in a better
option on the watch itself. So I made one that keeps her familiar stock Pixel
face exactly as-is and just renders the glucose reading big.

The **phone app** is the start of something larger: a place to blend her
glucose data with the context around it — when she takes insulin, when and
what she eats. Doses are logged as structured entries, but everything else is
kept as free-text **journal entries** on purpose. We use agents to parse that
unstructured text into structured datapoints that plot on the chart, so
capturing a day never means fighting a form.

The long-term vision is exactly that blend: unstructured journaling plus
structured readings and doses, interpreted by LLMs/agents, to build a deeper,
richer understanding of her diabetes management over time.

## The two pieces

| | | |
|---|---|---|
| [`watch-face/`](watch-face/) | **Big Glucose** — a Wear OS watch face | The stock Pixel digital face, kept as-is, with the glucose complication rendered ~4× larger (units and timestamp dropped). Watch Face Format XML, no code. Runs on a Pixel Watch 2. |
| [`glucose-app/`](glucose-app/) | **Glucose Widget** — an Android phone app | Home-screen widget showing the current reading (mmol/L + trend arrow), plus a day/week history chart with a food/insulin journal. Kotlin + Compose + Glance. Runs on a Pixel phone. |

The two are unrelated at runtime and build separately — each subdirectory is
a self-contained Gradle project with its own README and `CLAUDE.md`. They
share only a purpose and a toolchain.

## How the glucose data flows

- **On the watch**, the reading comes from a Wear OS *complication* published
  by a third-party bridge app ("Glucose Watch" by Sagitta Software), which the
  watch face simply draws bigger. The face never talks to Dexcom itself.
- **On the phone**, the app reads Dexcom's **Share** cloud directly (the same
  unofficial API xDrip/Nightscout use), since the phone is not the one paired
  to the sensor. It also accumulates its own history there for analysis.

Neither piece needs the Dexcom developer API, and neither stores anything off
the device.

## Toolchain (shared)

macOS, no Android Studio. Homebrew OpenJDK 17 + `android-commandlinetools`
(SDK 35) + `android-platform-tools`. Each project's README has its build and
sideload-over-Wi-Fi-ADB steps; both follow the same screenshot-and-iterate
loop against the real device.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd watch-face   && ./gradlew assembleDebug   # or:
cd glucose-app  && ./gradlew assembleDebug
```
