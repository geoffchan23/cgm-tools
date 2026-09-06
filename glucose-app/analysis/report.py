#!/usr/bin/env python3
"""Weekly CGM report from the pulled database.

Usage:
  tools/pull-db.sh <phone-serial>            # refresh data/glucose.db
  python3 analysis/report.py [--days N] [--out analysis/report.html]

Reads data/glucose.db (readings + journal), computes the standard CGM
metrics (time in / below / above range, CV, GMI), a 24-hour overlay with
percentile bands, one strip per day with dose and food/activity markers,
every low with what preceded it, and a short list of observations worded
as questions for the endocrinologist. Writes a self-contained HTML page.

All glucose values are mmol/L. Target range 3.9-10.0 (the app's defaults).
"""
import argparse
import datetime as dt
import json
import re
import sqlite3
import statistics
from pathlib import Path
from zoneinfo import ZoneInfo

ZONE = ZoneInfo("America/Toronto")
MGDL_PER_MMOL = 18.0182
LOW, HIGH = 3.9, 10.0
VERY_LOW, VERY_HIGH = 3.0, 13.9
READINGS_PER_DAY = 288  # 5-minute cadence
BIN_MIN = 15            # overlay bin width

DOSE_RE = re.compile(r"^dose: (.+) (\d+)\S* @ (\d{1,2}):(\d{2})$")
EVENT_RE = re.compile(r"^event: (.+) @ (\d{1,2}):(\d{2})$")


def pct(v, p):
    """Linear-interpolated percentile of a non-empty list."""
    s = sorted(v)
    k = (len(s) - 1) * p
    f, c = int(k), min(int(k) + 1, len(s) - 1)
    return s[f] + (s[c] - s[f]) * (k - f)


def load(db, days):
    c = sqlite3.connect(db)
    readings = [(dt.datetime.fromtimestamp(t / 1000, ZONE), m / MGDL_PER_MMOL)
                for t, m in c.execute("SELECT timestampMs, mgdl FROM readings ORDER BY 1")]
    if not readings:
        raise SystemExit("no readings in the database")
    last_day = readings[-1][0].date()
    first_day = max(last_day - dt.timedelta(days=days - 1), readings[0][0].date())
    readings = [r for r in readings if r[0].date() >= first_day]
    journal = []
    for day, text in c.execute("SELECT day, text FROM journal WHERE scope='day' ORDER BY day"):
        d = dt.date.fromisoformat(day)
        if d < first_day:
            continue
        m = DOSE_RE.match(text)
        if m:
            name, units, hh, mm = m.groups()
            journal.append(dict(day=day, kind="dose", label=f"{units}u", short="long" not in name.lower(),
                                minute=int(hh) * 60 + int(mm)))
            continue
        m = EVENT_RE.match(text)
        if m:
            name, hh, mm = m.groups()
            journal.append(dict(day=day, kind="event", label=name.strip(), minute=int(hh) * 60 + int(mm)))
    return readings, journal, first_day, last_day


def metrics(values):
    n = len(values)
    if n == 0:
        return None
    mean = sum(values) / n
    sd = statistics.pstdev(values) if n > 1 else 0.0
    return dict(
        n=n,
        coverage=n / READINGS_PER_DAY,
        mean=mean,
        cv=sd / mean if mean else 0.0,
        gmi=3.31 + 0.02392 * mean * MGDL_PER_MMOL,   # Bergenstal 2018, % (A1c-equivalent)
        tbr=sum(v < LOW for v in values) / n,
        tbr2=sum(v < VERY_LOW for v in values) / n,
        tir=sum(LOW <= v <= HIGH for v in values) / n,
        tar=sum(v > HIGH for v in values) / n,
        tar2=sum(v > VERY_HIGH for v in values) / n,
        min=min(values), max=max(values),
    )


def overlay(readings):
    """Percentile bands per BIN_MIN slot across all days."""
    bins = {}
    for t, v in readings:
        bins.setdefault((t.hour * 60 + t.minute) // BIN_MIN, []).append(v)
    out = []
    for b in range(24 * 60 // BIN_MIN):
        vals = bins.get(b)
        if not vals:
            continue
        out.append(dict(minute=b * BIN_MIN + BIN_MIN // 2, n=len(vals),
                        p10=pct(vals, .10), p25=pct(vals, .25), p50=pct(vals, .50),
                        p75=pct(vals, .75), p90=pct(vals, .90)))
    return out


def low_episodes(readings, journal):
    """Runs of readings < LOW lasting >= 15 min; gaps < 15 min merge."""
    episodes, cur = [], None
    for t, v in readings:
        if v < LOW:
            if cur and (t - cur["end"]).total_seconds() <= 15 * 60:
                cur["end"] = t
                cur["vals"].append(v)
            else:
                if cur:
                    episodes.append(cur)
                cur = dict(start=t, end=t, vals=[v])
        elif cur and (t - cur["end"]).total_seconds() > 15 * 60:
            episodes.append(cur)
            cur = None
    if cur:
        episodes.append(cur)
    out = []
    by_min = {t: v for t, v in readings}
    for e in episodes:
        minutes = (e["end"] - e["start"]).total_seconds() / 60 + 5
        if minutes < 15:
            continue
        start = e["start"]
        before = []
        for j in journal:
            jt = dt.datetime.combine(dt.date.fromisoformat(j["day"]),
                                     dt.time(j["minute"] // 60, j["minute"] % 60), ZONE)
            delta = (start - jt).total_seconds() / 60
            if 0 <= delta <= 180:
                before.append(dict(kind=j["kind"], label=j["label"], minutes_before=round(delta),
                                   short=j.get("short")))
        before.sort(key=lambda b: -b["minutes_before"])
        # glucose one hour before the low began
        target = start - dt.timedelta(hours=1)
        prior = min(by_min, key=lambda t: abs((t - target).total_seconds()), default=None)
        prior_v = by_min[prior] if prior and abs((prior - target).total_seconds()) < 15 * 60 else None
        out.append(dict(day=start.date().isoformat(), start=start.strftime("%H:%M"), end=e["end"].strftime("%H:%M"),
                        minutes=int(minutes), nadir=min(e["vals"]), before=before, hour_before=prior_v))
    return out


def observations(days, overall, lows, journal, readings):
    obs = []
    # 1. Overnight vs daytime
    night = [v for t, v in readings if t.hour < 6]
    day = [v for t, v in readings if 9 <= t.hour < 21]
    if night and day:
        nm, dm = sum(night) / len(night), sum(day) / len(day)
        night_low = sum(v < LOW for v in night) / len(night)
        obs.append(("Overnight",
                    f"Midnight to 6 AM averages {nm:.1f} against {dm:.1f} during the day, "
                    f"with {night_low:.0%} of overnight readings below 3.9. "
                    "Worth asking whether the evening correction doses and the long-acting timing are the right pair."))
    # 2. Late food + correction
    late = {}
    for j in journal:
        if j["minute"] >= 21 * 60:
            late.setdefault(j["day"], []).append(j)
    late_days = [d for d, js in late.items() if any(j["kind"] == "event" for j in js)]
    if late_days:
        obs.append(("Late evenings",
                    f"Food after 9 PM on {len(late_days)} of {len(days)} days, usually followed by a short-acting dose. "
                    "Those nights are where the highest overnight readings sit; the next morning's lows are the flip side."))
    # 3. Stacked corrections
    stacks = 0
    for d in days:
        doses = sorted(m for j in journal if j["day"] == d["day"] and j["kind"] == "dose" and j.get("short")
                       for m in [j["minute"]])
        stacks += sum(1 for a, b in zip(doses, doses[1:]) if b - a <= 120)
    if stacks:
        obs.append(("Stacked doses",
                    f"{stacks} pair(s) of short-acting doses within two hours of each other. "
                    "Stacking is a common cause of a low three to four hours later; check whether any of the lows above line up."))
    # 4. Days with high time below range
    bad = [d for d in days if d["m"] and d["m"]["tbr"] > 0.04]
    if bad:
        obs.append(("Lows",
                    f"{len(bad)} day(s) exceeded the 4% time-below-range guideline: "
                    + ", ".join(f"{d['day'][5:]} ({d['m']['tbr']:.0%})" for d in bad) + "."))
    # 5. Variability
    if overall and overall["cv"] > 0.36:
        obs.append(("Variability",
                    f"Coefficient of variation is {overall['cv']:.0%}; the consensus target is under 36%. "
                    "High variability usually points at dose timing relative to meals rather than dose size."))
    return obs


def build(db, days_wanted, out):
    readings, journal, first_day, last_day = load(db, days_wanted)
    days = []
    d = first_day
    while d <= last_day:
        vals = [v for t, v in readings if t.date() == d]
        series = [(t.hour * 60 + t.minute + t.second / 60, round(v, 1)) for t, v in readings if t.date() == d]
        days.append(dict(day=d.isoformat(), weekday=d.strftime("%a"), m=metrics(vals), series=series,
                         markers=[j for j in journal if j["day"] == d.isoformat()]))
        d += dt.timedelta(days=1)
    overall = metrics([v for _, v in readings])
    lows = low_episodes(readings, journal)
    obs = observations(days, overall, lows, journal, readings)
    data = dict(
        first=first_day.isoformat(), last=last_day.isoformat(), generated=dt.datetime.now(ZONE).isoformat(timespec="minutes"),
        low=LOW, high=HIGH, overall=overall, days=days, overlay=overlay(readings), lows=lows,
        observations=obs,
    )
    html = TEMPLATE.replace("__DATA__", json.dumps(data, default=float))
    Path(out).write_text(html)
    print(f"wrote {out}: {len(readings)} readings, {first_day} → {last_day}, {len(lows)} low episodes")
    print(f"  in range {overall['tir']:.0%} · below 3.9 {overall['tbr']:.0%} · below 3.0 {overall['tbr2']:.0%} "
          f"· CV {overall['cv']:.0%} · mean {overall['mean']:.1f}")
    if obs:
        print(f"  {obs[0][0]}: {obs[0][1]}")
    Path(out).with_suffix(".range").write_text(f"{first_day} {last_day}\n")  # for tools/weekly-report.sh


TEMPLATE = r"""<meta charset="utf-8"><title>Her Glucose Week</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Sora:wght@500;600&family=IBM+Plex+Sans:wght@400;500&family=IBM+Plex+Mono:wght@400;500&display=swap">
<style>
:root{
  color-scheme:light;
  --bg:#f5f7f9; --surface:#ffffff; --line:#d9dee4; --grid:#e8ecf0;
  --ink:#151a20; --ink-2:#4f5b67; --ink-3:#7d8893;
  --trace:#2a78d6; --dose:#eb6834; --event:#1baf7a;
  --low:#c8362f; --high:#b7791f; --band:#e5f2e9; --band-line:#bcdcc6;
  --p50:#2a78d6; --p25:rgba(42,120,214,.28); --p10:rgba(42,120,214,.12);
  --sans:"IBM Plex Sans",system-ui,sans-serif; --mono:"IBM Plex Mono",ui-monospace,monospace; --head:"Sora",var(--sans);
}
@media (prefers-color-scheme:dark){ :root:not([data-theme="light"]){
  color-scheme:dark;
  --bg:#121518; --surface:#1a1e23; --line:#2c333b; --grid:#232930;
  --ink:#eef1f4; --ink-2:#b4bcc5; --ink-3:#7f8994;
  --trace:#3987e5; --dose:#d95926; --event:#199e70;
  --low:#e66767; --high:#eda100; --band:#1b2620; --band-line:#2d4536;
  --p50:#3987e5; --p25:rgba(57,135,229,.32); --p10:rgba(57,135,229,.14);
}}
:root[data-theme="dark"]{
  color-scheme:dark;
  --bg:#121518; --surface:#1a1e23; --line:#2c333b; --grid:#232930;
  --ink:#eef1f4; --ink-2:#b4bcc5; --ink-3:#7f8994;
  --trace:#3987e5; --dose:#d95926; --event:#199e70;
  --low:#e66767; --high:#eda100; --band:#1b2620; --band-line:#2d4536;
  --p50:#3987e5; --p25:rgba(57,135,229,.32); --p10:rgba(57,135,229,.14);
}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.55 var(--sans)}
main{max-width:920px;margin:0 auto;padding:40px 24px 64px}
h1{font:600 30px/1.15 var(--head);margin:0 0 6px;text-wrap:balance;letter-spacing:-.01em}
h2{font:500 19px/1.3 var(--head);margin:44px 0 14px;text-wrap:balance}
.sub{color:var(--ink-2);margin:0}
.eyebrow{font:500 11px/1 var(--sans);letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3)}
.num{font-family:var(--mono);font-variant-numeric:tabular-nums}
.tiles{display:grid;grid-template-columns:repeat(6,1fr);gap:10px;margin-top:22px}
@media (max-width:700px){.tiles{grid-template-columns:repeat(3,1fr)}}
.tile{background:var(--surface);border:1px solid var(--line);border-radius:8px;padding:12px 14px 10px}
.tile .v{font:500 24px/1.1 var(--mono);margin:6px 0 2px}
.tile .t{font-size:12px;color:var(--ink-3)}
.tile.good .v{color:var(--event)} .tile.warn .v{color:var(--high)} .tile.bad .v{color:var(--low)}
.legend{display:flex;gap:18px;flex-wrap:wrap;font-size:12.5px;color:var(--ink-2);margin:6px 0 10px}
.legend span{display:inline-flex;align-items:center;gap:6px}
.sw{width:14px;height:3px;border-radius:2px;background:var(--trace)}
.sw.band{height:10px;background:var(--p25)}
.sw.tri{width:0;height:0;border-left:6px solid transparent;border-right:6px solid transparent;border-bottom:10px solid var(--dose);background:none;border-radius:0}
.sw.dia{width:9px;height:9px;background:var(--event);transform:rotate(45deg);border-radius:1px}
.chart{background:var(--surface);border:1px solid var(--line);border-radius:8px;padding:10px 6px 4px;position:relative}
.chart svg{display:block;width:100%;height:auto;overflow:visible}
.strip{display:grid;grid-template-columns:150px 1fr;gap:14px;align-items:stretch;margin-bottom:10px}
@media (max-width:700px){.strip{grid-template-columns:1fr}}
.strip .side{background:var(--surface);border:1px solid var(--line);border-radius:8px;padding:10px 12px;font-size:12.5px;color:var(--ink-2)}
.strip .side b{display:block;font:500 15px var(--head);color:var(--ink);margin-bottom:6px}
.strip .side .row{display:flex;justify-content:space-between;gap:8px;line-height:1.7}
.tip{position:absolute;pointer-events:none;background:var(--ink);color:var(--bg);font:12px var(--mono);padding:4px 7px;border-radius:4px;white-space:nowrap;transform:translate(-50%,-130%);display:none}
table{width:100%;border-collapse:collapse;font-size:13.5px;background:var(--surface);border:1px solid var(--line);border-radius:8px;overflow:hidden}
th,td{padding:9px 10px;text-align:left;border-top:1px solid var(--line);vertical-align:top}
th{font:500 11px/1 var(--sans);letter-spacing:.06em;text-transform:uppercase;color:var(--ink-3);border-top:0;background:var(--bg)}
td.num,th.num{text-align:right}
.wrap{overflow-x:auto}
.before{color:var(--ink-2)}
.before .d{color:var(--dose)} .before .e{color:var(--event)}
.obs{display:grid;gap:12px}
.obs div{background:var(--surface);border:1px solid var(--line);border-left:3px solid var(--trace);border-radius:8px;padding:12px 16px;max-width:70ch}
.obs b{font:500 14px var(--head);display:block;margin-bottom:3px}
.foot{color:var(--ink-3);font-size:12.5px;margin-top:40px;max-width:70ch}
</style>
<main>
  <p class="eyebrow" id="period"></p>
  <h1>Her Glucose Week</h1>
  <p class="sub">Dexcom G7 readings with logged doses, food and activity. mmol/L, target 3.9–10.0.</p>
  <div class="tiles" id="tiles"></div>

  <h2>All days, laid over one clock</h2>
  <div class="legend"><span><i class="sw"></i>median</span><span><i class="sw band"></i>25–75%</span><span><i class="sw band" style="opacity:.5"></i>10–90%</span><span><i class="sw" style="background:var(--band-line)"></i>target range</span></div>
  <div class="chart" id="agp"></div>

  <h2>Day by day</h2>
  <div class="legend"><span><i class="sw"></i>glucose</span><span><i class="sw tri"></i>short-acting (outlined = long-acting)</span><span><i class="sw dia"></i>food / activity</span></div>
  <div id="days"></div>

  <h2>Every low, and what came before it</h2>
  <div class="wrap"><table id="lows"></table></div>

  <h2>Things to raise with the endocrinologist</h2>
  <div class="obs" id="obs"></div>

  <p class="foot" id="foot"></p>
</main>
<script>
const D = __DATA__;
const $ = s => document.querySelector(s);
const f1 = v => v == null ? '–' : v.toFixed(1);
const pc = v => Math.round(v * 100) + '%';
const hm = m => { m = Math.round(m); return String(Math.floor(m/60)).padStart(2,'0') + ':' + String(m%60).padStart(2,'0'); };
const h12 = m => { const h = Math.floor(m/60), mm = String(Math.round(m)%60).padStart(2,'0'); return ((h+11)%12+1) + ':' + mm + (h < 12 ? ' AM' : ' PM'); };
const fmtDay = s => new Date(s + 'T12:00:00').toLocaleDateString('en-CA', {weekday:'short', month:'short', day:'numeric'});

$('#period').textContent = fmtDay(D.first) + ' – ' + fmtDay(D.last) + ' · ' + D.days.length + ' days';
$('#foot').textContent = 'Generated ' + D.generated.replace('T',' ').slice(0,16) + ' from the phone database. Time in range, CV and GMI follow the 2019 international consensus targets (>70% in range, <4% below 3.9, <1% below 3.0, CV <36%). GMI is an A1c estimate from mean glucose; it needs 14+ days to mean much. This page is for conversation with her care team, not for dosing decisions.';

// ---- tiles
const o = D.overall;
const tiles = [
  ['In range', pc(o.tir), '3.9–10.0', o.tir >= .7 ? 'good' : o.tir >= .5 ? 'warn' : 'bad'],
  ['Below range', pc(o.tbr), pc(o.tbr2) + ' below 3.0', o.tbr < .04 ? 'good' : o.tbr < .1 ? 'warn' : 'bad'],
  ['Above range', pc(o.tar), pc(o.tar2) + ' above 13.9', o.tar < .25 ? 'good' : o.tar < .4 ? 'warn' : 'bad'],
  ['Mean', f1(o.mean), 'mmol/L', ''],
  ['Variability', pc(o.cv), 'CV, target <36%', o.cv < .36 ? 'good' : 'warn'],
  ['GMI', o.gmi.toFixed(1) + '%', 'A1c estimate', ''],
];
$('#tiles').innerHTML = tiles.map(([t,v,s,c]) => `<div class="tile ${c}"><div class="t">${t}</div><div class="v">${v}</div><div class="t">${s}</div></div>`).join('');

// ---- shared scales
const YMAX = 20, YMIN = 2;
function yScale(h, top, bottom){ return v => top + (YMAX - Math.min(YMAX, Math.max(YMIN, v))) / (YMAX - YMIN) * (h - top - bottom); }
function xScale(w, left, right){ return m => left + m / 1440 * (w - left - right); }
function frame(w, h, L, R, T, B, y, x, id){
  let s = '';
  // target band
  s += `<rect x="${L}" y="${y(D.high)}" width="${w-L-R}" height="${y(D.low)-y(D.high)}" fill="var(--band)"/>`;
  s += `<line x1="${L}" x2="${w-R}" y1="${y(D.high)}" y2="${y(D.high)}" stroke="var(--band-line)" stroke-width="1"/>`;
  s += `<line x1="${L}" x2="${w-R}" y1="${y(D.low)}" y2="${y(D.low)}" stroke="var(--band-line)" stroke-width="1"/>`;
  for (const v of [5, 10, 15, 20]) {
    s += `<line x1="${L}" x2="${w-R}" y1="${y(v)}" y2="${y(v)}" stroke="var(--grid)" stroke-width="1"/>`;
    s += `<text x="${L-6}" y="${y(v)+4}" text-anchor="end" font-size="11" fill="var(--ink-3)" font-family="var(--mono)">${v}</text>`;
  }
  for (let hh = 0; hh <= 24; hh += 6) {
    const xx = x(hh*60);
    s += `<line x1="${xx}" x2="${xx}" y1="${T}" y2="${h-B}" stroke="var(--grid)" stroke-width="1"/>`;
    s += `<text x="${xx}" y="${h-B+13}" text-anchor="${hh===0?'start':hh===24?'end':'middle'}" font-size="11" fill="var(--ink-3)" font-family="var(--mono)">${hh===24?'24:00':hm(hh*60)}</text>`;
  }
  return s;
}
function path(pts, x, y){
  let d = '', prev = null;
  for (const [m, v] of pts) {
    d += (prev == null || m - prev > 20 ? 'M' : 'L') + x(m).toFixed(1) + ' ' + y(v).toFixed(1) + ' ';
    prev = m;
  }
  return d;
}
function hover(el, w, L, R, series, x, extra){
  const tip = document.createElement('div'); tip.className = 'tip'; el.appendChild(tip);
  const svg = el.querySelector('svg');
  const cross = document.createElementNS('http://www.w3.org/2000/svg','line');
  cross.setAttribute('stroke','var(--ink-3)'); cross.setAttribute('stroke-dasharray','3 3'); cross.setAttribute('y1', 0); cross.setAttribute('y2', svg.viewBox.baseVal.height); cross.style.display='none';
  svg.appendChild(cross);
  svg.addEventListener('mousemove', ev => {
    const r = svg.getBoundingClientRect(); const px = (ev.clientX - r.left) / r.width * w;
    if (px < L || px > w - R) { tip.style.display = cross.style.display = 'none'; return; }
    const m = (px - L) / (w - L - R) * 1440;
    let best = null; for (const p of series) if (!best || Math.abs(p[0]-m) < Math.abs(best[0]-m)) best = p;
    if (!best || Math.abs(best[0]-m) > 20) { tip.style.display = cross.style.display = 'none'; return; }
    cross.setAttribute('x1', x(best[0])); cross.setAttribute('x2', x(best[0])); cross.style.display='';
    tip.textContent = h12(best[0]) + '  ' + (extra ? extra(best) : f1(best[1]));
    tip.style.left = (x(best[0]) / w * r.width) + 'px'; tip.style.top = (r.height * .12) + 'px'; tip.style.display='block';
  });
  svg.addEventListener('mouseleave', () => { tip.style.display = cross.style.display = 'none'; });
}

// ---- overlay (AGP)
{
  const w = 900, h = 300, L = 34, R = 10, T = 10, B = 24;
  const y = yScale(h, T, B), x = xScale(w, L, R);
  const band = (lo, hi) => { const a = D.overlay.map(p => [p.minute, p[hi]]), b = D.overlay.slice().reverse().map(p => [p.minute, p[lo]]);
    return path(a, x, y) + path(b, x, y).replace(/^M/, 'L') + 'Z'; };
  let s = frame(w, h, L, R, T, B, y, x, 'agp');
  s += `<path d="${band('p10','p90')}" fill="var(--p10)"/>`;
  s += `<path d="${band('p25','p75')}" fill="var(--p25)"/>`;
  s += `<path d="${path(D.overlay.map(p => [p.minute, p.p50]), x, y)}" fill="none" stroke="var(--p50)" stroke-width="2.2" stroke-linejoin="round"/>`;
  $('#agp').innerHTML = `<svg viewBox="0 0 ${w} ${h}">${s}</svg>`;
  hover($('#agp'), w, L, R, D.overlay.map(p => [p.minute, p.p50, p]), x, b => `median ${f1(b[1])}  ·  ${f1(b[2].p25)}–${f1(b[2].p75)}`);
}

// ---- day strips
$('#days').innerHTML = D.days.map((d, i) => {
  const m = d.m; const w = 900, h = 220, L = 34, R = 10, T = 40, B = 52;
  const y = yScale(h, T, B), x = xScale(w, L, R);
  let s = frame(w, h, L, R, T, B, y, x, 'd'+i);
  // low shading
  for (const lo of D.lows.filter(l => l.day === d.day)) {
    const [sh, sm] = lo.start.split(':').map(Number), [eh, em] = lo.end.split(':').map(Number);
    s += `<rect x="${x(sh*60+sm)}" y="${T}" width="${Math.max(2, x(eh*60+em+5)-x(sh*60+sm))}" height="${h-T-B}" fill="var(--low)" opacity=".10"/>`;
  }
  s += `<path d="${path(d.series, x, y)}" fill="none" stroke="var(--trace)" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>`;
  // markers: doses below the axis line, events above the plot
  const doses = d.markers.filter(k => k.kind === 'dose'), events = d.markers.filter(k => k.kind === 'event');
  doses.forEach((k, j) => { const xx = x(k.minute), yy = h - B + 24;
    s += `<path d="M${xx-5} ${yy+9} L${xx} ${yy} L${xx+5} ${yy+9} Z" fill="${k.short ? 'var(--dose)' : 'none'}" stroke="var(--dose)" stroke-width="1.5"/>`;
    s += `<text x="${xx+7}" y="${yy+8}" font-size="10.5" fill="var(--ink-2)" font-family="var(--mono)">${k.label}</text>`; });
  // Stagger a label onto the upper row when it would collide with the previous one.
  let lastX = -1e9, row = 0;
  events.forEach(k => { const xx = x(k.minute); row = (xx - lastX < 110) ? 1 - row : 0; lastX = xx;
    const yy = T - 12 - row * 15, label = k.label.length > 22 ? k.label.slice(0, 21) + '…' : k.label;
    s += `<rect x="${xx-4}" y="${yy-4}" width="8" height="8" transform="rotate(45 ${xx} ${yy})" fill="var(--event)"/>`;
    s += `<text x="${xx+7}" y="${yy+4}" font-size="10.5" fill="var(--ink-2)" font-family="var(--sans)">${label}</text>`; });
  const side = m ? `<b>${fmtDay(d.day)}</b>
      <div class="row"><span>In range</span><span class="num">${pc(m.tir)}</span></div>
      <div class="row"><span>Below</span><span class="num" style="${m.tbr>=.04?'color:var(--low)':''}">${pc(m.tbr)}</span></div>
      <div class="row"><span>Above</span><span class="num">${pc(m.tar)}</span></div>
      <div class="row"><span>Mean</span><span class="num">${f1(m.mean)}</span></div>
      <div class="row"><span>Low · high</span><span class="num">${f1(m.min)} · ${f1(m.max)}</span></div>
      <div class="row"><span>Coverage</span><span class="num">${pc(Math.min(1, m.coverage))}</span></div>` : `<b>${fmtDay(d.day)}</b>No readings`;
  return `<div class="strip"><div class="side">${side}</div><div class="chart" id="day${i}"><svg viewBox="0 0 ${w} ${h}">${s}</svg></div></div>`;
}).join('');
D.days.forEach((d, i) => { const w = 900, L = 34, R = 10; hover($('#day'+i), w, L, R, d.series, xScale(w, L, R)); });

// ---- lows table
{
  const rows = D.lows.map(l => {
    const before = l.before.length ? l.before.map(b => `<span class="${b.kind==='dose'?'d':'e'}">${b.kind==='dose' ? (b.short?'▲':'△')+' '+b.label : '◆ '+b.label}</span> <span class="num">${b.minutes_before}m before</span>`).join('<br>') : '<span class="before">nothing logged in the prior 3 h</span>';
    return `<tr><td>${fmtDay(l.day)}</td><td class="num">${h12(+l.start.slice(0,2)*60 + +l.start.slice(3))}</td><td class="num">${l.minutes} min</td><td class="num" style="color:var(--low)">${f1(l.nadir)}</td><td class="num">${f1(l.hour_before)}</td><td class="before">${before}</td></tr>`;
  });
  $('#lows').innerHTML = `<thead><tr><th>Day</th><th class="num">Began</th><th class="num">Lasted</th><th class="num">Lowest</th><th class="num">1 h earlier</th><th>Logged in the 3 h before</th></tr></thead><tbody>${rows.join('') || '<tr><td colspan="6">No episodes below 3.9 lasting 15 minutes or more.</td></tr>'}</tbody>`;
}

// ---- observations
$('#obs').innerHTML = D.observations.map(([t, body]) => `<div><b>${t}</b>${body}</div>`).join('') || '<div>Nothing stands out yet. More days will change that.</div>';
</script>
"""

if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(Path(__file__).resolve().parent.parent / "data" / "glucose.db"))
    ap.add_argument("--days", type=int, default=7, help="how many days back from the latest reading")
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent / "report.html"))
    a = ap.parse_args()
    build(a.db, a.days, a.out)
