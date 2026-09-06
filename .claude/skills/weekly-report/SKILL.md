---
name: weekly-report
description: End-of-week CGM report — pull the phone database, generate the report, push it into the app's Reports screen, republish the artifact, summarize. Use when asked for the weekly report or to run/refresh the report. (The Sunday launchd job runs only the shell pipeline; this skill is the interactive follow-up.)
---

# Weekly glucose report

Runs the whole end-of-week cycle for the glucose app. The heavy lifting is
one script; this skill adds the artifact republish and the summary.

## Steps

1. From the repo root run:

   ```bash
   glucose-app/tools/weekly-report.sh
   ```

   It finds the phone on the LAN (wireless debugging port rotates; the
   script reads it from mDNS), pulls `data/glucose.db` (also the backup),
   writes `glucose-app/analysis/report.html` for the last 7 days, and pushes
   it into the app's Reports screen as `report-<first>_<last>.html`.

   Exit code 3 means the phone was not reachable. Say so and stop; the next
   run catches up because the report always covers the trailing 7 days.

2. Fill in nutrition for any day in the window that has logs but no
   `glucose-app/analysis/nutrition/<day>.json`:
   `python3 glucose-app/analysis/nutrition.py status` lists them; for each,
   follow the `nutrition` skill (Claude parses, script resolves). Then
   regenerate and re-push so the report carries the numbers:
   `glucose-app/tools/weekly-report.sh` again (it is idempotent; the
   phone gets a "report ready" notification each time — say so). Skip
   this step only if the phone is unreachable and the DB is stale.

3. Republish the report to the existing artifact so the link Geoff has stays
   current. **Only if the Artifact tool is available** — it is not in
   headless (`claude -p`) runs; in that case skip this step silently, the
   app's Reports screen is the delivery.
   Use the Artifact tool with:
   - `file_path`: `glucose-app/analysis/report.html`
   - `url`: `https://claude.ai/code/artifact/42522e03-0809-4e84-b7cb-7ff7eebf974a`
   - `label`: the date range printed by the script, e.g. `Sep 1 – Sep 6`
   - no `favicon` (keeps the existing one)

4. Reply with the headline numbers from the run: time in range, time below
   3.9, number of low episodes, estimated carbs/day, and the one observation
   most worth raising.
   Read them from `glucose-app/analysis/report.html` (`const D = {...}` near
   the bottom holds `overall` and `lows`) rather than recomputing. Keep it
   to five lines; the page has the detail.

## Notes

- Never edit the SQLite file on the phone; everything goes through ADB and
  the app. See `glucose-app/CLAUDE.md`.
- The report page contains her health data. It is gitignored; do not commit
  it or paste its contents into the repo.
- To change the window: `tools/weekly-report.sh --days 14`.
