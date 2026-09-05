# Timed food/exercise logs, 12-hour time entry, last-dose/last-log widget

**Date:** 2026-09-05 · **Status:** approved in chat, implementing

## Why

Free-text day notes needed a nightly Claude Code pass to become plottable
`event:` markers, so the chart and any "last meal" display lagged a day.
Geoff now wants to log food/exercise directly with a time, the way doses
are logged, and see the last dose and last log on the home-screen widget.
The 24-hour clock in the dose dialog was also confusing.

## Decisions

1. **Day entries become timed logs.** "Add journal entry" (day mode) is
   replaced by "Log food/exercise": one text field + the dose dialog's
   time row, defaulting to now, saved to the *viewed* day. Stored as the
   existing `event: <text> @ HH:mm` convention (24-hour on disk, so the
   chart and analysis parsers are unchanged). Doses still log to today.
2. **Logs are first-class in the notes list.** `event:` entries are no
   longer hidden. Doses and logs render as `12:40 PM · lunch` /
   `12:21 PM · 1u short-acting`, sorted by time; untimed notes sort after
   them by creation time. Edit opens the matching dialog (dose → dose,
   event → log, anything else → plain text).
3. **Week notes and legacy day notes stay free text.** A week summary has
   no timestamp; the three pre-existing free-text day notes keep the text
   editor rather than being silently given a time.
4. **12-hour time everywhere the user sees it.** Material `TimePicker`
   in 12-hour mode (AM/PM toggle); buttons show `h:mm a` in `Locale.US`
   so it reads "PM" not "p.m.". Both dialogs share one `TimeField`.
5. **Widget shows last dose and last log.** Left column unchanged
   (reading, arrow, age). Right column: `▲ 1u · 2h ago` (△ for
   long-acting) and `◆ lunch · 1h ago`, relative times, from the last 3
   days of day-scope journal rows. Refreshes on the existing 5-minute
   cycle and immediately after any journal save/delete. Default width
   2 → 3 cells; existing placements keep their size.
6. **`clear-events` is removed** from `IngestReceiver` and `GlucoseDao`.
   It bulk-deleted `event:` rows, which are now user data. `insert` and
   `refresh` stay for backfilling legacy days.

## Docs to update

`CLAUDE.md` (extraction section → events are user-authored; parsing only
for legacy free-text days), `README.md` and the umbrella README sentence
about agent parsing, `analysis/parse-ledger.json` comment.

## Testing

Pure helpers in `DayMath.kt` get unit tests: event text round-trip,
12-hour formatting, list label + ordering, latest dose/event selection,
relative age. Dialogs and widget verified on the phone by screenshot.
