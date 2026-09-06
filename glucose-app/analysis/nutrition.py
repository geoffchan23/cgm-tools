#!/usr/bin/env python3
"""Nutrition + activity enrichment for the food/exercise logs.

The split: Claude Code (in a session, never the API) reads a day's logs and
writes a *draft* day file with the items it recognised, their estimated
grams and which database record to use. This script does everything that
is lookup or arithmetic: searching the food tables, fetching per-100 g
values, multiplying out, and totalling. All numbers are estimates.

Files (all under analysis/):
  config.json                weightKg, targets
  nutrition/cnf.json         Canadian Nutrient File, per 100 g, offline (primary)
  nutrition/met.json         2024 Compendium MET values (activity kcal)
  nutrition/food-cache.json  resolved records from OFF / USDA, so lookups happen once
  nutrition/<day>.json       the per-day result (draft in, resolved out)

Subcommands:
  status [--days N]            days with logs but no nutrition file
  events --day D               that day's events/activities as JSON (input for the draft)
  search "query" [--off] [--usda]   candidate foods (CNF offline by default)
  activities "query"           candidate MET entries
  resolve --day D              fill macros/kcal/totals into nutrition/<day>.json
  summary --day D              one-line summary

Day-file schema (Claude writes name/qty/grams/source + activities; resolve fills the rest):
{
  "day": "2026-09-05", "parsedAt": "...", "weightKg": 65.8,
  "events": [{"text": "chips, dunkaroos, 3 oreos", "minute": 900,
              "items": [{"name": "Oreo cookies", "qty": "3 cookies", "grams": 33,
                         "source": {"db": "cnf", "code": 2345}, "note": "portion guessed"}],
              "totals": {...filled...}}],
  "activities": [{"text": "raking", "minute": 900, "minutes": 120,
                  "source": {"db": "met", "code": "08160"}, "kcal": ...filled...}],
  "totals": {...filled...}
}
"""
import argparse
import datetime as dt
import json
import re
import sqlite3
import sys
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
NUT = HERE / "nutrition"
CONFIG = HERE / "config.json"
DB = HERE.parent / "data" / "glucose.db"
CACHE = NUT / "food-cache.json"
UA = "cgm-tools/1.0 (personal analysis; github.com/geoffchan23/cgm-tools)"
MACROS = ("kcal", "carb", "protein", "fat", "fibre", "sugar")
EVENT_RE = re.compile(r"^event: (.+) @ (\d{1,2}):(\d{2})$")


def load_json(p, default=None):
    try:
        return json.loads(Path(p).read_text())
    except FileNotFoundError:
        return default


def save_json(p, obj):
    Path(p).write_text(json.dumps(obj, indent=2, ensure_ascii=False) + "\n")


def day_file(day):
    return NUT / f"{day}.json"


# ---------------------------------------------------------------- sources

def cnf_foods():
    return load_json(NUT / "cnf.json")["foods"]


def cnf_search(query, limit=12):
    """Cheap token match: every query token must appear; rank by how early/short."""
    # crude stemming so "cookies" finds "cookie" and vice versa
    toks = [t.rstrip("s") for t in re.split(r"[^a-z0-9%]+", query.lower()) if t]
    hits = []
    for f in cnf_foods():
        name = f["name"].lower()
        if all(t in name for t in toks):
            hits.append((len(name), f))
    hits.sort(key=lambda h: h[0])
    return [dict(db="cnf", code=f["code"], name=f["name"], per100g=f["per100g"], measures=f["measures"])
            for _, f in hits[:limit]]


def off_search(query, limit=6):
    """Open Food Facts (packaged/branded). Classic search endpoint; v2 search is flaky."""
    url = ("https://world.openfoodfacts.org/cgi/search.pl?search_simple=1&action=process&json=1"
           f"&page_size={limit}&fields=code,product_name,brands,serving_size,nutriments"
           f"&search_terms={urllib.parse.quote(query)}")
    data = _get(url)
    out = []
    for p in data.get("products", []):
        n = p.get("nutriments", {})
        per = dict(kcal=n.get("energy-kcal_100g"), carb=n.get("carbohydrates_100g"), protein=n.get("proteins_100g"),
                   fat=n.get("fat_100g"), fibre=n.get("fiber_100g"), sugar=n.get("sugars_100g"))
        if per["kcal"] is None or per["carb"] is None:
            continue
        out.append(dict(db="off", code=p.get("code"), name=f"{p.get('product_name','?')} ({p.get('brands','')})".strip(),
                        per100g={k: (round(v, 2) if isinstance(v, (int, float)) else None) for k, v in per.items()},
                        measures=[[p.get("serving_size"), None]] if p.get("serving_size") else []))
    return out


def usda_search(query, limit=6, api_key="DEMO_KEY"):
    """USDA FoodData Central. DEMO_KEY is ~10 requests/hour: use sparingly, results are cached."""
    url = ("https://api.nal.usda.gov/fdc/v1/foods/search?"
           f"api_key={api_key}&pageSize={limit}&query={urllib.parse.quote(query)}")
    data = _get(url)
    names = {"Energy": "kcal", "Carbohydrate, by difference": "carb", "Protein": "protein",
             "Total lipid (fat)": "fat", "Fiber, total dietary": "fibre", "Sugars, total including NLEA": "sugar"}
    out = []
    for f in data.get("foods", []):
        per = {}
        for n in f.get("foodNutrients", []):
            k = names.get(n.get("nutrientName"))
            if k and (k != "kcal" or n.get("unitName") == "KCAL"):
                per[k] = round(n.get("value", 0), 2)
        if "kcal" not in per:
            continue
        m = [[f"{f['servingSize']} {f['servingSizeUnit']}", None]] if f.get("servingSize") else []
        out.append(dict(db="usda", code=f["fdcId"], name=f"{f['description']} {('· ' + f['brandOwner']) if f.get('brandOwner') else ''}".strip(),
                        per100g={k: per.get(k) for k in MACROS}, measures=m))
    return out


def _get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode())
    except Exception as e:  # OFF goes 503 fairly often; USDA demo key rate-limits
        print(f"lookup failed ({e}); try again later, use CNF, or a manual per100g record", file=sys.stderr)
        return {}


def record(source):
    """per-100 g record for {"db","code"}; CNF offline, others via cache (populated by search)."""
    db, code = source["db"], str(source.get("code", ""))
    if db == "cnf":
        for f in cnf_foods():
            if str(f["code"]) == code:
                return dict(name=f["name"], per100g=f["per100g"])
        raise SystemExit(f"CNF code {code} not found")
    if db == "manual":  # Claude supplies per100g directly when no database has the food
        return dict(name=source.get("name", "manual"), per100g=source["per100g"])
    cache = load_json(CACHE, {})
    rec = cache.get(f"{db}:{code}")
    if not rec:
        raise SystemExit(f"{db}:{code} not in food-cache.json — run `search` with --{db} first so it is cached")
    return rec


def cache_put(results):
    cache = load_json(CACHE, {})
    for r in results:
        if r["db"] != "cnf":
            cache[f"{r['db']}:{r['code']}"] = dict(name=r["name"], per100g=r["per100g"], measures=r["measures"],
                                                  cachedAt=dt.date.today().isoformat())
    save_json(CACHE, cache)


def met_search(query, limit=10):
    toks = [t for t in re.split(r"[^a-z0-9.]+", query.lower()) if t]
    acts = load_json(NUT / "met.json")["activities"]
    hits = [a for a in acts if all(t in (a["activity"] + " " + a["heading"]).lower() for t in toks)]
    return hits[:limit]


def met_record(code):
    for a in load_json(NUT / "met.json")["activities"]:
        if a["code"] == str(code):
            return a
    raise SystemExit(f"MET code {code} not found")


# ---------------------------------------------------------------- logs

def logged_days():
    c = sqlite3.connect(DB)
    return sorted({d for (d,) in c.execute("SELECT DISTINCT day FROM journal WHERE scope='day' AND text LIKE 'event: %'")})


def events_for(day):
    c = sqlite3.connect(DB)
    out = []
    for (text,) in c.execute("SELECT text FROM journal WHERE scope='day' AND day=? AND text LIKE 'event: %' ORDER BY id", (day,)):
        m = EVENT_RE.match(text)
        if m:
            name, hh, mm = m.groups()
            out.append(dict(text=name.strip(), minute=int(hh) * 60 + int(mm)))
    return sorted(out, key=lambda e: e["minute"])


# ---------------------------------------------------------------- resolve

def resolve(day):
    p = day_file(day)
    d = load_json(p)
    if not d:
        raise SystemExit(f"{p} missing: write the draft first (see module docstring)")
    weight = d.get("weightKg") or load_json(CONFIG)["weightKg"]
    d["weightKg"] = weight
    day_tot = {k: 0.0 for k in MACROS}
    for ev in d.get("events", []):
        tot = {k: 0.0 for k in MACROS}
        for it in ev.get("items", []):
            rec = record(it["source"])
            it["match"] = rec["name"]
            g = float(it["grams"])
            for k in MACROS:
                v = rec["per100g"].get(k)
                it[k] = round(v * g / 100, 1) if v is not None else None
                if v is not None:
                    tot[k] += it[k]
        ev["totals"] = {k: round(v, 1) for k, v in tot.items()}
        for k in MACROS:
            day_tot[k] += tot[k]
    act_kcal = 0.0
    for a in d.get("activities", []):
        m = met_record(a["source"]["code"])
        a["match"], a["met"] = m["activity"], m["met"]
        a["kcal"] = round(m["met"] * weight * a["minutes"] / 60)
        act_kcal += a["kcal"]
    d["totals"] = {**{k: round(v) for k, v in day_tot.items()}, "activityKcal": round(act_kcal),
                   "activityMinutes": sum(a["minutes"] for a in d.get("activities", []))}
    d["resolvedAt"] = dt.datetime.now().astimezone().isoformat(timespec="minutes")
    d["estimate"] = "Portions estimated from free-text logs; database values per 100 g; activity kcal = MET × kg × h."
    save_json(p, d)
    return d


def summary(d):
    t = d["totals"]
    return (f"{d['day']}: {t['kcal']} kcal · {t['carb']} g carb · {t['protein']} g protein · {t['fat']} g fat "
            f"· {t['fibre']} g fibre · activity {t['activityMinutes']} min ≈ {t['activityKcal']} kcal "
            f"({len(d.get('events', []))} meals/snacks, {sum(len(e.get('items', [])) for e in d.get('events', []))} items)")


# ---------------------------------------------------------------- cli

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("status"); s.add_argument("--days", type=int, default=14)
    s = sub.add_parser("events"); s.add_argument("--day", required=True)
    s = sub.add_parser("search"); s.add_argument("query"); s.add_argument("--off", action="store_true"); s.add_argument("--usda", action="store_true")
    s = sub.add_parser("activities"); s.add_argument("query")
    s = sub.add_parser("resolve"); s.add_argument("--day", required=True)
    s = sub.add_parser("summary"); s.add_argument("--day", required=True)
    a = ap.parse_args()

    if a.cmd == "status":
        cutoff = (dt.date.today() - dt.timedelta(days=a.days)).isoformat()
        days = [d for d in logged_days() if d >= cutoff]
        missing = [d for d in days if not day_file(d).exists()]
        print(json.dumps(dict(loggedDays=days, missing=missing, today=dt.date.today().isoformat())))
    elif a.cmd == "events":
        print(json.dumps(dict(day=a.day, events=events_for(a.day)), indent=2))
    elif a.cmd == "search":
        res = usda_search(a.query) if a.usda else off_search(a.query) if a.off else cnf_search(a.query)
        cache_put(res)
        for r in res:
            per = r["per100g"]
            print(f"{r['db']}:{r['code']}  {r['name'][:70]}")
            print(f"      per 100 g: {per.get('kcal')} kcal, carb {per.get('carb')}, protein {per.get('protein')}, fat {per.get('fat')}, fibre {per.get('fibre')}, sugar {per.get('sugar')}")
            if r["measures"]:
                print("      measures: " + "; ".join(f"{m[0]} = {m[1]} ×100g" if m[1] else f"{m[0]}" for m in r["measures"][:5]))
        if not res:
            print("no matches")
    elif a.cmd == "activities":
        for m in met_search(a.query):
            print(f"met:{m['code']}  {m['met']:>4} MET  {m['heading']} · {m['activity'][:80]}")
    elif a.cmd == "resolve":
        print(summary(resolve(a.day)))
    elif a.cmd == "summary":
        d = load_json(day_file(a.day))
        print(summary(d) if d and d.get("totals") else f"{a.day}: not parsed")


if __name__ == "__main__":
    main()
