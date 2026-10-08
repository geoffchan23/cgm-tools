---
name: fill-gaps
description: Infer unlogged meals, snacks, doses and low treatments from the glucose curve on days (or evenings) nobody logged, and add them to the app as marked guesses. Use when Geoff asks to fill gaps, predict/guess what happened on a day, or backfill missing entries, and whenever gaps.py status shows days needing filling. Claude in this session does the reasoning.
---

# Fill gaps in the journal

Claude does the judgement; `analysis/gaps.py` does the arithmetic. Every
inferred row goes in as a **guess**: the normal `dose:`/`event:` text with
the suffix ` (guess)`. The app draws guesses hollow and lets Geoff **Keep**
(confirm) or delete each one, so a wrong guess costs a tap, but a guess
must still be defensible from the curve.

All paths are relative to `glucose-app/`. Use `/usr/bin/python3` or
`python3`.

## 1. Fresh data and the to-do list

1. Phone connected? `adb devices` (reconnect: `adb mdns services`, then
   `adb connect <ip:port>`). Back up and pull: `tools/pull-db.sh`, then
   `cp data/glucose.db data/glucose-backup-$(date +%F).db`.
2. `python3 analysis/gaps.py status` — days marked **NEEDS FILLING** (whole
   day, or `pm only` when the evening wasn't logged). Oldest first, and never
   today (the day isn't over). `$ARGUMENTS` may name specific days.

## 2. Read the patterns, then the day

- Read `scenarios.md` (next to this file) every time — it is the catalogue
  of her patterns, and it changes as Geoff corrects guesses.
- `python3 analysis/gaps.py learn` if you want to re-check how logged meals
  and doses show up on the curve (meal → rise lag; dose → fall).
- `python3 analysis/gaps.py day D` → 15-minute values, rises/falls ≥ 2.0,
  what's already logged (routine rows marked `[routine]`), and how the
  previous day ended. Look at the day before and after too: late-night
  doses and overnight lows straddle midnight.

## 3. Match each rise and fall to a scenario

Walk the day in order. For every rise or fall, name the scenario (S1–S12)
it fits, or none. Rules:

- Explain a fall before a rise only with something that happened earlier.
- Prefer the scenario whose weekday rule fits (S4 Mon–Thu, S5/S6 Fri/Sat).
- Don't duplicate what's already there: routine rows and earlier guesses
  count. If a dinner is already logged but the curve says something else
  (pizza vs. a light meal), don't add a second dinner — ask.
- Round guessed times to :00/:15/:30/:45; use the scenario's default units.
- S11 (sensor artifacts) and S12 (unexplained) produce **questions, not rows**.
- When torn between two scenarios, write the more conservative one (a
  generic `snack` beats a specific food) and ask.

## 4. Write the ledger

`data/guesses/D.json` (gitignored — it quotes readings and the repo is public):

```json
{
  "day": "2026-09-25",
  "halves": ["am", "pm"],
  "entries": [
    {"text": "event: half medium pizza hut @ 18:00", "scenario": "S5",
     "confidence": "medium", "why": "climb 4.1→16.0 from 18:00 to 21:20 on a Friday"}
  ],
  "remove": ["event: coffee @ 10:30"],
  "questions": ["21:00 10.3 → 21:30 2.3 in 30 min — real low, or sensor?"],
  "notes": "Optional: anything else noticed (sensor artifacts, long lows)."
}
```

`text` is WITHOUT the ` (guess)` suffix — the push adds it. `remove`
(optional) lists exact texts of auto-routine rows to delete — used when the
routine is known to be at the wrong time (Sundays: see S1). The push refuses
to delete anything a person typed.

## 5. Show Geoff, then push

1. For each day, show a short table (time · entry · scenario · confidence ·
   why) and the questions. Batch the questions across days and use
   AskUserQuestion when they're multiple-choice; otherwise ask in text.
2. `python3 analysis/gaps.py push D --dry-run`, then without `--dry-run`.
   It inserts through the app's IngestReceiver and skips anything already
   present, so re-running is safe. Then `tools/pull-db.sh` and
   `gaps.py status` should show the day as `filled`.
3. Guesses can be removed one at a time by journal id (see CLAUDE.md:
   IngestReceiver `op delete`) — there is no bulk delete, by design.

## 6. Learn from answers

When Geoff answers a question or confirms/corrects guesses (Keep in the app
strips the suffix; a pull shows which ones he kept or deleted):
- Update the ledger and re-push if needed.
- Update `scenarios.md` — adjust the signature, default, or confidence, and
  add a dated line to its Corrections log. A fixed fact (her correction
  factor, what she treats lows with) belongs in the scenario text itself.
- Kept/edited guesses are now human data and feed `gaps.py learn` next time.

Then run the `nutrition` skill for the filled days if Geoff wants carb
estimates (it treats guesses as estimates).
