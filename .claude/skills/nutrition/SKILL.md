---
name: nutrition
description: Estimate macros, calories and activity energy for the food/exercise logs of a day (or all unparsed days) and store the result in analysis/nutrition/<day>.json. Use when asked to parse/estimate nutrition, carbs, calories or exercise for a day, or when the weekly report needs missing days filled in. Never uses the Claude API — Claude in this session does the parsing.
---

# Nutrition for a day's logs

Claude does the judgement (what was eaten, how much); `analysis/nutrition.py`
does the lookups and arithmetic. Everything is an estimate and is labelled so.

All paths below are relative to `glucose-app/`. Run scripts with `python3`
(the offline Canadian Nutrient File is bundled; only `--off`/`--usda` hit
the network).

## 1. Pick the day(s)

- `$ARGUMENTS` may be a day (`2026-09-05`), `missing`, or empty (= today).
- `python3 analysis/nutrition.py status` lists logged days and which lack a
  nutrition file. For `missing`, do each missing day in turn.
- If `data/glucose.db` is older than the day you need, pull it first:
  `tools/pull-db.sh <serial>` (serial via `adb mdns services`, or use
  `tools/weekly-report.sh` which does it all).

## 2. Read the day's logs

`python3 analysis/nutrition.py events --day D` → the `event:` entries with
their minute of day. Doses are not needed here.

## 3. Draft `analysis/nutrition/D.json`

For each event, split the text into items and decide a portion in grams.
Rules of thumb:
- Use the person's words as the item `name`; put the assumed portion in
  `qty` ("3 cookies", "half medium pizza", "1 mug") and the number in `grams`.
- Typical portions when unstated: coffee with milk ≈ 250 g (carbs come from
  milk/sugar — if the log just says "coffee", assume 1 tbsp 2% milk, no
  sugar, unless a standing rule says otherwise); a "snack" of chips ≈ 30 g;
  a cookie ≈ 11 g; a donut ≈ 60 g; a fast-food burger ≈ 150 g; a side salad
  ≈ 100 g; "half a medium pizza" ≈ 4 slices ≈ 400 g; a curry puff ≈ 80 g;
  ice cream "107g" is literal — always prefer a stated weight.
- Find the record: `python3 analysis/nutrition.py search "<terms>"` searches
  the Canadian Nutrient File offline (use the `measures` it prints to turn
  "1 slice" into grams). For branded/packaged foods add `--off` (Open Food
  Facts); as a last resort `--usda` (demo key, ~10 calls/hour). Prefer CNF
  for generic foods and restaurant staples; OFF for brands (Dunkaroos, Oreo).
  Non-CNF results are cached automatically so `resolve` can use them.
- If nothing fits, use `{"db":"manual","name":"...","per100g":{"kcal":..,"carb":..,"protein":..,"fat":..,"fibre":..,"sugar":..}}`
  with your own per-100 g estimate and say so in `note`.
- Activities: `python3 analysis/nutrition.py activities "<terms>"` → pick a
  `met:` code; set `minutes` from the log (e.g. "2h of raking" → 120; a
  bare "walk" → 30). Activities are entries whose text is exercise, not food.
  If a log is both ("walk then ice cream"), make one item and one activity.
- Coffee with nothing else is an event with one item, not an activity.
- Do not invent meals that were not logged. Unlogged meals stay unlogged;
  the report says "not parsed" rather than guessing a day.

Write the file with the schema in the `nutrition.py` docstring. Leave
`totals`, `kcal` etc. empty — the script fills them.

## 4. Resolve and check

`python3 analysis/nutrition.py resolve --day D` fills macros, kcal, activity
kcal (MET × weight from `analysis/config.json` × hours) and totals, and
prints a one-line summary. Sanity-check it: a day under 800 kcal or over
4000 kcal, or a single item over 150 g of carbs, means a portion is wrong —
fix the draft and resolve again.

## 5. Report back

Show a compact table per event (item, portion, carbs, kcal) and the day
totals, then note the two or three portion guesses you are least sure of so
Geoff can correct them. If he corrects one, edit the day file and resolve
again. Commit `analysis/nutrition/*.json` and `food-cache.json` afterwards
(they are part of the dataset; the raw report HTML stays gitignored).
