# Gap-filling scenarios

Patterns learned from the days Geoff logged by hand (2026-09-01 … 09-24),
used to infer what happened on days nobody logged. Each one has a
**signature** (what it looks like in `gaps.py day` output), what to
**write** (journal lines, always pushed with the ` (guess)` suffix), and how
sure it usually is. Times are local; "rise"/"fall" are `gaps.py` episodes.

Update this file when Geoff confirms or corrects a guess: that's how the
catalogue improves. Add the date and what changed under "Corrections log".
Keep it free of dated readings — the repo is public; evidence goes in the
gitignored ledgers in `data/guesses/`.

## How her days usually run

- **Morning:** coffee + 4u short + 19u long at 10:30. The app auto-logs
  only the coffee from 2026-10-11 (doses too until 10-10); she logs her
  doses herself, so a morning with coffee but no doses gets them as
  guesses (S1). 10:30 is right on weekdays (Geoff, 2026-10-05), even when the
  curve starts climbing ~08:30 (dawn rise). **Sundays it's ~14:00**; the app
  auto-logs Sundays at 14:00 from 2026-10-11 on. Earlier Sundays have
  10:30 rows that need moving (see S1).
- **Afternoon (14:00–15:30):** a small snack — quarter bagel with cream
  cheese, a slice or two of sourdough with cheddar/butter/jam, chips — often
  with 3u. Frequently eaten because she's drifting low after the morning.
- **Dinner (~17:30):** Mon–Thu is often a chicken burger + 6u (the app
  auto-logged both 09-17 … 10-08; since then dinner is never auto-logged). Fri/Sat is Pizza Hut, half a medium pizza, + 6–7u.
  Sunday varies (grilled cheese, chicken burger and fries).
- **Evening (19:00–19:30):** a cookie — usually half a chocolate chip
  cookie — typically no insulin.
- **Late (21:30–22:30):** weeknights 50 g Great Value onion ring chips + 3–5u;
  Fri/Sat a donut + a big dose (8–12u) that also corrects the pizza rise.
- **Night:** occasional 1u correction around 00:30; candy for lows.

## Scenarios

### S1 Morning routine hump — keep the routine rows
- **Signature:** rise starting 09:30–11:00, peak 11:30–13:00 about 3–6 above
  the start, then a fall into early afternoon.
- **Write:** nothing if her doses are logged. From 2026-10-11 only the
  coffee is auto-logged: if no morning short/long dose is logged, add
  `short-acting 4u` and `long-acting 19u` at the coffee's time as guesses.
- **Sundays before 2026-10-11:** the routine happened ~14:00 but was logged
  at 10:30. Put `coffee`, `short-acting 4u`
  and `long-acting 19u` at 14:00 as guesses and list the three 10:30
  routine rows under the ledger's `remove` (push deletes them; it only ever
  deletes auto-routine rows). Nudge to the actual rise if it's clearly
  earlier or later.
- **Weekdays:** don't move the routine for an early climb — that's the dawn
  rise. Ask only if the morning looks nothing like a hump.
- **Confidence:** high that it happened; time ±1 h.

### S2 Afternoon dip → snack
- **Signature:** a rise of ≥ 3 at ≥ 5/h starting 13:30–16:30 — usually
  from a post-morning dip to ≤ 5, but not always.
- **Write:** `event: snack @ <rise start − 15 min>`. If the nadir was < 3.9
  and no S3 dose follows, it was probably a low being treated:
  `event: candy @ …` (you don't dose for a low treatment, so a dosed
  rise is a snack).
- **Confidence:** high that she ate; what she ate is unknown, so keep the
  name generic rather than guessing "bagel".

### S3 Snack with insulin
- **Signature:** an S2 rise that turns into a fall of ≥ 3 within 2 h (instead
  of plateauing until dinner).
- **Write:** add `dose: short-acting 3u @ <snack time>` (her usual snack dose).
- **Confidence:** medium; units ±2.

### S4 Weeknight dinner (Mon–Thu)
- **Signature:** a fall starting 17:00–18:15 (her pre-dinner dose acting),
  often from a pre-dinner high, bottoming ~19:00; then a slow rise.
- **Write:** `event: chicken burger` + `dose: short-acting 6u`, at the fall's
  start rounded to :15 (17:30 when within 15 min). Skip the dose if one is
  logged; skip the meal if any dinner is logged.
- **Confidence:** medium-high on Mon–Thu (she eats it most weeknights;
  drumsticks + salad replaced it one week in September).

### S4b Sunday dinner
- **Signature:** Sunday, anything around 17:00–18:30 — a rise, or a flat
  stretch that a falling curve should not have.
- **Write:** generic `event: dinner` + `dose: short-acting 7u` (Sunday
  dinners vary; 7u is what she logged). Never name a food.
- **Confidence:** low-medium.

### S5 Fri/Sat pizza night
- **Signature:** Fri or Sat, a rise beginning 17:00–19:00 that keeps climbing
  for 2–4 h to ≥ 12 (fat-delayed), rather than the S4 fall.
- **Write:** `event: half medium pizza hut` + `dose: short-acting 6u` at the
  start of the climb (≈ 17:30–18:30). If a fall of ≥ 2 comes just before
  the climb, she dosed ahead of the pizza: put the dose at that fall's start.
- **Confidence:** medium (pizza most Fri/Sat; size varies 4 slices – half).

### S6 Late donut with a big dose (Fri/Sat)
- **Signature:** from ≥ 12 between 21:00 and 22:30, a fall of ≥ 6 at
  ≤ −8/h — usually right after a short rise (the donut).
- **Write:** `event: donut` + `dose: short-acting 10u` at the fall's start −
  15 min (logged range 8–12u).
- **Confidence:** medium-high on Fri/Sat, low otherwise (then use S8).

### S7 Evening cookie
- **Signature:** after the dinner dip, a rise of ≥ 3 starting 19:00–20:00,
  no fall right after (she doesn't dose for it).
- **Write:** `event: half chocolate chip cookie @ <rise start − 15>`. If the
  dip before it was < 3.9, write `event: candy` instead (low treatment).
- **Confidence:** medium.

### S8 Weeknight late snack with dose
- **Signature:** Sun–Thu, a rise starting 20:00–22:00 followed by a fall of ≥ 3,
  or a late fall from ≥ 9 with no other cause.
- **Write:** `event: onion ring chips` + `dose: short-acting 3u` at the rise
  start (or the fall start − 30 when there's no visible rise).
- **Confidence:** low-medium; often ask.

### S9 Overnight / early-morning correction
- **Signature:** a high ≥ 12 after midnight followed by a fall of ≥ 4 at
  ≤ −4/h with no logged dose.
- **Write:** `dose: short-acting <units> @ <fall start − 30>` using
  ~1u per 2 mmol/L of fall above the end value (her actual correction
  factor is still unknown).
- **Confidence:** medium-high that a dose happened — Geoff says she corrects
  highs most of the time now — low on units.

### S13 Daytime correction
- **Signature:** 07:00–21:00, a fall of ≥ 4 from ≥ 13 at ≤ −5/h that no meal
  dose explains (no meal in the 2 h before it).
- **Write:** `dose: short-acting <units> @ <fall start − 30>`, units as S9.
- **Confidence:** low on units. Ask.

### S10 Low treatment
- **Signature:** a nadir < 3.9 followed by a rise of ≥ 2 at ≥ 4/h, with no
  logged food — at any time of day.
- **Write:** `event: candy @ <nadir time>` — she treats lows with candy
  (Geoff, 2026-10-05). Covered by S2/S7 in the afternoon/evening.
- **Confidence:** medium.

### S11 Sensor artifact (compression low) — write nothing
- **Signature:** a drop to < 3.5 that rebounds within ~30–45 min to near
  the previous level, especially while asleep (23:00–07:00). A
  single-reading dip between normal neighbours counts too.
- **Speed alone is not enough:** an 8-point drop in 30 min after a big
  donut dose was a real low (Geoff confirmed). If the low lasts and the
  rebound overshoots, treat it as real (S6 dose + S10 candy).
- **Write:** nothing. Mention it to Geoff as a possible sensor artifact.

### S12 Unexplained — ask
Anything ≥ 3 that fits none of the above (a midday rise with no meal, a
long flat high, a prolonged overnight low). Don't invent; list it as a
question with the time and size.

## Corrections log

(Add dated entries as Geoff confirms or corrects guesses.)

- 2026-10-05 — first fill (09-24 pm … 10-04). Geoff: weekday routine 10:30
  is right; Sunday routine ~14:00; lows treated with candy; she corrects
  highs most of the time now (factor unknown). Added S4b (Sunday dinner),
  S13 (daytime correction), the Sunday `remove` rule, and S2 no longer
  requires a prior dip.
- 2026-10-07 — a very fast evening crash after a donut dose was a real low,
  not the sensor: S11 now needs a rebound to the prior level, not just
  speed. Sep 24's dinner time is unknown (Geoff was away) — guess stays.
  The app now auto-logs Sunday's routine at 14:00.
- 2026-10-08 — Geoff dropped the evening routine (burger and 6u); S4
  supplies both as guesses on unlogged weeknights.
- 2026-10-10 — Geoff dropped the auto-logged morning doses (coffee stays):
  the auto 19u hid her real 23u. S1 guesses doses on mornings without them.
