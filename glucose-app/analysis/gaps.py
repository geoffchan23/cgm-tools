#!/usr/bin/env python3
"""Find the shape of a day's glucose so Claude can infer unlogged meals/doses.

Usage (from glucose-app/, after tools/pull-db.sh):
  python3 analysis/gaps.py status              # which days need filling
  python3 analysis/gaps.py day 2026-09-25      # timeline + episodes + logs for one day
  python3 analysis/gaps.py learn               # how logged meals/doses show up on the curve
  python3 analysis/gaps.py push 2026-09-25 [--dry-run] [--serial S]
                                               # insert a ledger's guesses on the phone

Claude does the judgement (see the fill-gaps skill and
analysis/scenarios.md); this script only does the arithmetic: smoothing,
turning points, and matching logged entries to rises and falls.

A day "needs filling" when everything logged is either an auto-routine
entry (exact routine text) or a guess. Guesses are journal
rows with the suffix " (guess)"; the app draws them hollow and lets Geoff
Keep (confirm) or delete each one.

Runs under Apple's /usr/bin/python3 (3.9) too.
"""
import datetime as dt
import json
import re
import shlex
import subprocess
import sqlite3
import statistics
import sys
from pathlib import Path
from zoneinfo import ZoneInfo

ZONE = ZoneInfo("America/Toronto")
MGDL_PER_MMOL = 18.0182
DATA = Path(__file__).resolve().parent.parent / "data"
DB = DATA / "glucose.db"
# Per-day guess ledgers: what was inferred and why. Gitignored with the rest of
# data/ — they quote her readings and the repo is public.
LEDGER = DATA / "guesses"
GUESS = " (guess)"

DOSE_RE = re.compile(r"^dose: (.+) (\d+)\S* @ (\d{1,2}):(\d{2})( \(guess\))?$")
EVENT_RE = re.compile(r"^event: (.+?) @ (\d{1,2}):(\d{2})( \(guess\))?$")

# Exactly what Routines.kt inserts; such a row says nothing about the day.
# The morning doses were auto-logged only until 2026-10-10 (coffee still is);
# after that she logs them, so identical dose rows are hers.
# The evening routine only ran 2026-09-17 … 10-08 (the burger stopped on the
# last day too) — identical rows outside that were typed by hand.
MORNING_ROUTINE = {
    "event: coffee @ 10:30",
}
MORNING_ROUTINE_DOSES = {
    "dose: short-acting 4u @ 10:30",
    "dose: long-acting 19u @ 10:30",
}
MORNING_ROUTINE_DOSES_UNTIL = dt.date(2026, 10, 10)
EVENING_ROUTINE = {
    "dose: short-acting 6u @ 17:30",
    "event: chicken burger @ 17:30",
}
EVENING_ROUTINE_FROM = dt.date(2026, 9, 17)
EVENING_ROUTINE_UNTIL = dt.date(2026, 10, 8)


def is_routine(day, text):
    return text in MORNING_ROUTINE or (
        text in MORNING_ROUTINE_DOSES and day <= MORNING_ROUTINE_DOSES_UNTIL) or (
        text in EVENING_ROUTINE and EVENING_ROUTINE_FROM <= day <= EVENING_ROUTINE_UNTIL)

SWING = 2.0  # mmol/L a turning point must move to count as a rise or fall


def hm(minute):
    return "%02d:%02d" % (minute // 60, minute % 60)


def parse(text):
    m = DOSE_RE.match(text)
    if m:
        name, units, hh, mm, g = m.groups()
        return dict(kind="dose", short="long" not in name.lower(), units=int(units),
                    minute=int(hh) * 60 + int(mm), guess=bool(g), text=text)
    m = EVENT_RE.match(text)
    if m:
        name, hh, mm, g = m.groups()
        return dict(kind="event", name=name.strip(), minute=int(hh) * 60 + int(mm), guess=bool(g), text=text)
    return None


def load():
    c = sqlite3.connect(DB)
    readings = {}
    for t, mg in c.execute("SELECT timestampMs, mgdl FROM readings ORDER BY 1"):
        local = dt.datetime.fromtimestamp(t / 1000, ZONE)
        readings.setdefault(local.date(), []).append((local.hour * 60 + local.minute + local.second / 60, mg / MGDL_PER_MMOL))
    journal = {}
    for jid, day, text in c.execute("SELECT id, day, text FROM journal WHERE scope='day' ORDER BY day"):
        e = parse(text)
        if e:
            e["id"] = jid
            e["routine"] = is_routine(dt.date.fromisoformat(day), text)
            journal.setdefault(dt.date.fromisoformat(day), []).append(e)
    for es in journal.values():
        es.sort(key=lambda e: e["minute"])
    return readings, journal


SPLIT = 15 * 60  # same split as ROUTINE_SPLIT_MINUTE in the app


def human(entries, lo=0, hi=24 * 60):
    """Entries a person logged or confirmed (not routine text, not guesses)."""
    return [e for e in entries if not e["routine"] and not e["guess"] and lo <= e["minute"] < hi]


def is_gap_day(entries):
    """No entry that a human logged or confirmed."""
    return not human(entries)


def gap_halves(entries):
    """Which halves of the day need inferring: 'am' (<15:00) and/or 'pm'.

    A morning holding only the routine is normal on a day Geoff was logging
    (he leaves it when it's right), so the morning is a gap only when the
    whole day is. The evening is a gap whenever nothing human is after 15:00."""
    if not human(entries):
        return ["am", "pm"]
    return [] if human(entries, SPLIT) else ["pm"]


def smooth(rs):
    """Median of 3 neighbours; CGM noise otherwise makes false turning points."""
    out = []
    for i in range(len(rs)):
        w = [v for _, v in rs[max(0, i - 1):i + 2]]
        out.append((rs[i][0], sorted(w)[len(w) // 2]))
    return out


def episodes(rs, swing=SWING):
    """Zig-zag turning points → rises and falls of at least `swing` mmol/L."""
    s = smooth(rs)
    if len(s) < 3:
        return []
    # Until the first swing, track both extremes; whichever is left behind by
    # a swing becomes the first turning point.
    hi = lo = s[0]
    i = 1
    direction = 0
    while i < len(s) and direction == 0:
        p = s[i]
        if p[1] > hi[1]:
            hi = p
        if p[1] < lo[1]:
            lo = p
        if hi[1] - p[1] >= swing:
            direction, ext = -1, p
        elif p[1] - lo[1] >= swing:
            direction, ext = 1, p
        i += 1
    if direction == 0:
        return []
    pts = [hi if direction == -1 else lo]
    for p in s[i:]:
        if direction == 1:
            if p[1] > ext[1]:
                ext = p
            elif ext[1] - p[1] >= swing:
                pts.append(ext); direction = -1; ext = p
        else:
            if p[1] < ext[1]:
                ext = p
            elif p[1] - ext[1] >= swing:
                pts.append(ext); direction = 1; ext = p
    pts.append(ext)
    out = []
    for a, b in zip(pts, pts[1:]):
        delta = b[1] - a[1]
        if abs(delta) < swing or b[0] <= a[0]:
            continue
        # steepest 30-minute stretch inside the move, mmol/L per hour
        inside = [p for p in s if a[0] <= p[0] <= b[0]]
        slope = 0.0
        for i, p in enumerate(inside):
            later = [q for q in inside[i:] if q[0] - p[0] >= 30]
            if later:
                q = later[0]
                r = (q[1] - p[1]) / (q[0] - p[0]) * 60
                slope = max(slope, r) if delta > 0 else min(slope, r)
        out.append(dict(kind="rise" if delta > 0 else "fall", start=int(a[0]), end=int(b[0]),
                        frm=a[1], to=b[1], delta=delta, slope=slope))
    return out


def value_at(rs, minute, tol=15):
    near = [v for m, v in rs if abs(m - minute) <= tol]
    return statistics.median(near) if near else None


def describe(e):
    flag = " (guess)" if e["guess"] else (" [routine]" if e["routine"] else "")
    if e["kind"] == "dose":
        return "%s  %s %du%s" % (hm(e["minute"]), "short" if e["short"] else "LONG", e["units"], flag)
    return "%s  %s%s" % (hm(e["minute"]), e["name"], flag)


def cmd_status(readings, journal):
    print("day         wkday  readings  logged  guesses  state")
    today = dt.datetime.now(ZONE).date()
    for d in sorted(readings):
        es = journal.get(d, [])
        cov = len(readings[d]) / 288
        guesses = sum(1 for e in es if e["guess"])
        halves = gap_halves(es)
        ledger = (LEDGER / ("%s.json" % d)).exists()
        if d == today:
            state = "today (wait until it's over)"
        elif not halves:
            state = "logged"
        elif ledger:
            state = "filled %s (ledger)" % "+".join(halves) + ("" if guesses else " — NOT PUSHED")
        else:
            state = "NEEDS FILLING: %s" % ("whole day" if len(halves) == 2 else halves[0] + " only")
        print("%s  %s    %4.0f%%   %3d     %3d     %s" % (d, d.strftime("%a"), cov * 100, len(human(es)), guesses, state))


def cmd_day(readings, journal, day):
    rs = readings.get(day, [])
    es = journal.get(day, [])
    print("== %s (%s) — %d readings" % (day, day.strftime("%A"), len(rs)))
    # yesterday's last value: tells whether the day starts high from a late snack
    prev = readings.get(day - dt.timedelta(days=1), [])
    if prev:
        print("previous day ended at %.1f (%s)" % (prev[-1][1], hm(int(prev[-1][0]))))
    print("\n-- every 15 min (mmol/L; . = no data)")
    for h in range(24):
        cells = []
        for q in range(4):
            v = value_at(rs, h * 60 + q * 15 + 7.5, tol=7.5)
            cells.append("%5.1f" % v if v is not None else "    .")
        print("%02d:00 %s" % (h, " ".join(cells)))
    print("\n-- rises and falls (>= %.1f mmol/L)" % SWING)
    for ep in episodes(rs):
        print("%-4s %s→%s  %4.1f → %4.1f  (%+.1f, steepest %+.1f/h)" % (
            ep["kind"], hm(ep["start"]), hm(ep["end"]), ep["frm"], ep["to"], ep["delta"], ep["slope"]))
    print("\n-- logged")
    for e in es:
        print("  #%d %s" % (e["id"], describe(e)))
    if not es:
        print("  (nothing)")


def cmd_learn(readings, journal):
    """For every logged (non-routine-looking) day: which rise follows each meal,
    which fall follows each short dose. Prints the raw matches and summaries."""
    meal_rows, dose_rows = [], []
    for d in sorted(journal):
        es = journal[d]
        if is_gap_day(es) or d not in readings:
            continue
        eps = episodes(readings[d])
        rises = [x for x in eps if x["kind"] == "rise"]
        falls = [x for x in eps if x["kind"] == "fall"]
        for e in es:
            if e["guess"]:
                continue
            if e["kind"] == "event":
                cand = [r for r in rises if -60 <= r["start"] - e["minute"] <= 75]
                r = min(cand, key=lambda r: abs(r["start"] - e["minute"])) if cand else None
                meal_rows.append((d, e, r))
            elif e["short"]:
                cand = [f for f in falls if -30 <= f["start"] - e["minute"] <= 150]
                f = min(cand, key=lambda f: abs(f["start"] - e["minute"])) if cand else None
                meal_near = any(x["kind"] == "event" and abs(x["minute"] - e["minute"]) <= 30 for x in es)
                dose_rows.append((d, e, f, meal_near))
    print("== meals → rise (onset lag = rise start − logged time)")
    for d, e, r in meal_rows:
        if r:
            print("%s %-3s %-45s lag %+4d  %4.1f→%4.1f (%+.1f) peak %s" % (
                d, d.strftime("%a"), describe(e)[:45], r["start"] - e["minute"], r["frm"], r["to"], r["delta"], hm(r["end"])))
        else:
            print("%s %-3s %-45s no rise ≥%.1f" % (d, d.strftime("%a"), describe(e)[:45], SWING))
    lags = [r["start"] - e["minute"] for _, e, r in meal_rows if r]
    if lags:
        print("matched %d/%d; lag median %+d min, rise median %+.1f" % (
            len(lags), len(meal_rows), statistics.median(lags), statistics.median([r["delta"] for _, _, r in meal_rows if r])))
    print("\n== short-acting doses → fall")
    for d, e, f, meal in dose_rows:
        tag = "with meal" if meal else "CORRECTION"
        if f:
            print("%s %-3s %-22s %-10s fall starts %+4d  %4.1f→%4.1f (%+.1f) by %s" % (
                d, d.strftime("%a"), describe(e), tag, f["start"] - e["minute"], f["frm"], f["to"], f["delta"], hm(f["end"])))
        else:
            print("%s %-3s %-22s %-10s no fall ≥%.1f" % (d, d.strftime("%a"), describe(e), tag, SWING))


def cmd_push(journal, day, dry_run, serial):
    """Insert the ledger's entries as guesses via the app's IngestReceiver.
    Skips any whose text (with or without the suffix) is already in the journal,
    so re-running after a fresh pull is safe.

    A ledger may also list `remove`: exact texts of routine rows that the
    curve (or Geoff) says are wrong — e.g. a Sunday's 10:30 routine when it
    really happened at 14:00. Only auto-routine rows can be removed this way;
    anything a person typed is left alone."""
    path = LEDGER / ("%s.json" % day)
    ledger = json.loads(path.read_text())
    have = {e["text"] for e in journal.get(day, [])}
    adb = ["adb"] + (["-s", serial] if serial else [])
    receiver = ["shell", "am", "broadcast", "-n", "com.geoffchan.glucosewidget/.IngestReceiver"]
    for text in ledger.get("remove", []):
        rows = [e for e in journal.get(day, []) if e["text"] == text]
        if not rows:
            print("skip (gone)     remove " + text)
            continue
        if not rows[0]["routine"]:
            raise SystemExit("refusing to remove a non-routine row: %r" % text)
        print(("would remove    " if dry_run else "remove          ") + "#%d %s" % (rows[0]["id"], text))
        if not dry_run:
            subprocess.run(adb + receiver + ["--es", "op", "delete", "--es", "day", str(day),
                                             "--el", "id", str(rows[0]["id"])],
                           check=True, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
    for item in ledger["entries"]:
        text = item["text"]
        if parse(text) is None:
            raise SystemExit("not a dose/event line: %r" % text)
        guess = text + GUESS
        if text in have or guess in have:
            print("skip (present)  " + guess)
            continue
        print(("would insert    " if dry_run else "insert          ") + guess)
        if not dry_run:
            subprocess.run(adb + receiver + ["--es", "op", "insert", "--es", "day", str(day),
                                  "--es", "text", shlex.quote(guess)],
                           check=True, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL)


def main():
    readings, journal = load()
    args = sys.argv[1:] or ["status"]
    if args[0] == "status":
        cmd_status(readings, journal)
    elif args[0] == "day" and len(args) > 1:
        for a in args[1:]:
            cmd_day(readings, journal, dt.date.fromisoformat(a))
            print()
    elif args[0] == "learn":
        cmd_learn(readings, journal)
    elif args[0] == "push" and len(args) > 1:
        serial = args[args.index("--serial") + 1] if "--serial" in args else None
        cmd_push(journal, dt.date.fromisoformat(args[1]), "--dry-run" in args, serial)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
