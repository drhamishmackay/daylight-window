# Daylight Window

[![Build](https://github.com/drhamishmackay/daylight-window/actions/workflows/build.yml/badge.svg)](https://github.com/drhamishmackay/daylight-window/actions/workflows/build.yml)
[![Licence: MIT](https://img.shields.io/badge/licence-MIT-blue.svg)](LICENSE)
[![Download APK](https://img.shields.io/github/v/release/drhamishmackay/daylight-window?label=download%20apk)](https://github.com/drhamishmackay/daylight-window/releases/latest)

**An Android app that plans your day around the sun, so you can stop thinking about it.**

Most UV apps answer *how dangerous is the sun right now?* — which leaves you to do something with the number. This one answers a more useful question: **when should I go outside, and when should I come back in?**

That inversion is the whole idea. People with very fair skin often end up avoiding daylight entirely, not because any given day is dangerous, but because working out whether it is takes more effort than staying indoors. The app removes the decision.

---

## What it does

Reads the hour-by-hour UV forecast for wherever you are, corrects it against live ground measurements where they exist, and works out a plan:

> **Go outside now.**
> You have until 8:10am — 1h 39m from now. Come back in then and you stay inside your limit for the day.
>
> *Well inside the workplace safety limit, and less than half what it would take to turn your skin pink. No burn, and no meaningful addition to your lifetime risk.*

Then it sets alarms: go out, come back in, go out again. They shift each day as the sun moves.

## How this differs from other UV apps

There are several open-source UV projects, and they are mostly good at the same thing:
telling you the current UV index and how long you could stand in it before burning.

This one differs in two ways:

**It plans a day, not a moment.** Knowing "you could last 45 minutes at this UV" does
not tell you when to go out, because UV changes hourly. This app spends a whole day's
allowance across the gentlest hours and tells you the times.

**Every number is cited.** The daily limit is a published occupational standard; the
skin-type thresholds are measured medians from a named study; the unit conversions are
international definitions. They are listed in the app itself, with links, and in
[`Evidence.kt`](app/src/main/java/com/daylight/window/Evidence.kt). The README also
says where those numbers are weak — small samples, thresholds that overlap between
skin types — because advice you cannot check is advice you should not trust.

## The design goal: maximum safe minutes

Not maximum sun. Those pull in opposite directions — an hour at dawn and ten minutes at noon cost your skin the same. So the planner spends a fixed daily allowance on the **gentlest daylight available**, working inward from dawn and dusk.

That buys far more time than a naive "wait until UV drops below 3" rule:

| | Time outside | Shape |
|---|---|---|
| Melbourne, July | 3h 46m | morning + afternoon |
| Melbourne, September | 2h 23m | 99 min at dawn, 44 in the evening |
| Cairns, summer (UV 13) | 2h 10m | the fringes of the day |

Even in the tropics at UV 13 there are more than two safe hours. They are just not where most people look for them.

## Where the daily limit comes from

The default is the **ICNIRP occupational exposure limit**: 30 J/m² over an 8-hour day, which works out at 1.0 standard doses of sunlight. It is the international ceiling for people whose work puts them outdoors, set so that daily exposure does not meaningfully raise long-term skin cancer risk.

It is deliberately not a number this project invented. Two other settings are available, each labelled by the real population it describes rather than a vague word like "moderate":

| Setting | Daily dose | What it is |
|---|---|---|
| **Safest** (default) | 1.0 | The international workplace safety limit |
| Still within the limit | 1.33 | The top of that same limit |
| Like an outdoor worker | 2.0 | What British construction workers measurably average |

Every figure in the app has a source, listed in-app under *Where these numbers come from*, with links.

## Live measurements, not just a model

Forecasts are models, and models drift. Checked on 8 September 2026 in Melbourne:

- Open-Meteo forecast at noon: **5.4**
- ARPANSA station measured: **4.5**

About 20% high, almost certainly cloud the model did not predict. Following it uncorrected would have sent someone indoors earlier than necessary.

So where a ground station is within 75 km, the app scales the day's curve to match what is actually being measured. Three safeguards, because a measurement can mislead too:

- The correction is **capped between 0.5× and 1.5×** — one station under a passing cloud should not rewrite an afternoon
- A station **reporting a fault is ignored**, never read as "no sun", since that failure would call an unsafe day safe
- **Near-zero readings change nothing**, because dividing two tiny numbers amplifies noise rather than correcting it

The screen always says which of the three happened, so no number is unexplained. Australia only — [ARPANSA](https://uvdata.arpansa.gov.au/UVLevel) publishes 17 stations openly, and no equivalent free worldwide live feed exists. Everywhere else uses the forecast alone and says so.

## The exposure model

Two of the three inputs are international definitions, not choices:

- **UV index** is 40 × the sunburn-weighted irradiance in W/m², so UV 1 = 25 mW/m²
- One **standard erythema dose** = 100 J/m²

Both set by the [CIE](https://cie.co.at/publications/erythema-reference-action-spectrum-and-standard-erythema-dose-0) and used by the World Meteorological Organization for every public UV forecast.

The third is empirical — how much it takes to just visibly redden each skin type. Measured medians from [Young et al., *Journal of Investigative Dermatology* 2018](https://pmc.ncbi.nlm.nih.gov/articles/PMC6158343/), Table 1:

| Skin type | I | II | III | IV | V | VI |
|---|---|---|---|---|---|---|
| Reddening threshold (SED) | 2.1 | 2.6 | 3.2 | 5.6 | 7.5 | 15.2 |

### How much to trust those thresholds

Less than the decimal places suggest, and the app says so on its own screen.

The sample is small — six or seven people per skin type. And a separate series of 113 people found skin type predicts an individual's threshold only moderately (correlation 0.5–0.69), with thresholds **overlapping between types**: two people classified differently can burn at exactly the same dose ([Sánchez et al., *Actas Dermo-Sifiliográficas* 2020](https://www.actasdermo.org/en-minimal-erythema-dose-correlation-with-articulo-S1578219020301505)).

They are population medians. An individual can sit well either side of one.

There is also **no dose at which nothing happens**. In the same 2018 study, DNA damage was detectable at every dose tested, down to a fifth of a person's own sunburn threshold: *"No dose could be identified at which 25(OH)D was produced without detectable DNA damage."*

That is why the app talks about the gentlest daylight rather than safe daylight, and why the default budget is a published safety limit rather than a burn threshold.

## What it deliberately leaves out

**Solar flares.** The extra ultraviolet is absorbed ~130 km up; what reaches the ground is essentially unchanged, and NASA is explicit that flare UV does not contribute to sunburn. Solar activity can even thicken the ozone slightly. Including it would be a number that looks scientific and changes nothing.

## Alarms that do not pile up

Four fixed slots, overwritten each morning rather than added to. After a year you have four alarms that have drifted a few minutes with the sun — not a thousand.

A "come back inside" alarm is set whenever a session **ends while the sun is still up**. It is skipped when the session runs to sunset, because from there UV only falls and there is nothing to come inside from. That distinction matters for anyone who wants two shorter sessions rather than staying out until dark.

Alarm, notification, or nothing — alarm by default.

## Going out off-plan

A start/stop button for unplanned trips. Press it and the app tracks what you actually spend, and tells you how long you have:

> Only 5 min at this UV before you reach your limit. The sun is strong right now.

That five-minute answer at UV 13 is real. It is also what an earlier version got wrong — see the tests below.

## Honest limits

No amount of ultraviolet is completely free of risk — that is measured, not a disclaimer. Risk falls smoothly as UV falls; it never reaches zero. This app finds the gentlest daylight of your day and keeps the total under a published limit. It is not medical advice.

## Building it

Requires JDK 17 and the Android SDK (platform 34).

```
git clone https://github.com/drhamishmackay/daylight-window.git
cd daylight-window
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. To run the tests:

```
./gradlew test
```

### Installing on your own phone

```
./gradlew installDebug
```

Or [download the APK from the latest release](https://github.com/drhamishmackay/daylight-window/releases/latest), open it on your phone, and allow installation from unknown sources. There is no Play Store listing — this is a personal tool, and installing it needs no developer account.

## How it is put together

The reasoning is kept away from anything Android-specific, so it can be tested as plain arithmetic:

| File | What it does |
|---|---|
| [`SunModel.kt`](app/src/main/java/com/daylight/window/SunModel.kt) | Interpolates the forecast, converts UV to dose. No Android imports. |
| [`DayPlan.kt`](app/src/main/java/com/daylight/window/DayPlan.kt) | Spends the budget on the gentlest daylight. Also pure. |
| [`RiskProfile.kt`](app/src/main/java/com/daylight/window/RiskProfile.kt) | The three published daily limits. |
| [`Evidence.kt`](app/src/main/java/com/daylight/window/Evidence.kt) | Every external number, with its citation. |
| [`Advice.kt`](app/src/main/java/com/daylight/window/Advice.kt) | Turns a plan into plain English. |
| [`LiveUv.kt`](app/src/main/java/com/daylight/window/LiveUv.kt) | Ground-station readings and the correction. |
| [`Alerts.kt`](app/src/main/java/com/daylight/window/Alerts.kt) | Which alarms today needs, in fixed slots. |
| [`Reminders.kt`](app/src/main/java/com/daylight/window/Reminders.kt) | Scheduling, notifications, re-arming after reboot. |
| [`UvCurveView.kt`](app/src/main/java/com/daylight/window/UvCurveView.kt) | Draws the day with sessions shaded. |

Plain Android views rather than Compose, and no dependency injection — the app is small enough that neither would earn its weight.

## Tests

38 tests, all pure arithmetic, no device needed.

The one that matters most checks that **no plan ever exceeds its budget**, across every combination of climate, skin type, risk profile and plan shape. If that ever fails, the app is telling someone to overexpose themselves.

Two real bugs were caught this way:

**The skin-type thresholds were invented rather than sourced.** Type I happened to be right; type VI was 34% out. Now measured medians, with the test checking the arithmetic reproduces every one of them rather than one convenient case.

**Unplanned-trip time truncated to whole ten-minute blocks.** At UV 13 a single block costs nearly twice the daily budget, so the function returned "0 minutes" where the truthful answer was about 5 — losing precision exactly when the sun is most dangerous and precision matters most.

## Contributing

Forks and pull requests welcome. Two rules:

**No invented numbers.** Every threshold, limit and cut-point must trace to a published source, an international definition, or a measured value — and it goes in `Evidence.kt` with its citation. If you cannot cite it, it does not belong in the app.

**Safety promises are tested, not asserted.** If you change the planner, the budget test must still pass across every climate and skin type.

Things worth doing: live station feeds for other countries (the US, Canada and several European countries run comparable networks), a home-screen widget, translations, and a proper look at whether the 10-minute sampling resolution should be finer in strong sun.

## Data

Forecasts from [Open-Meteo](https://open-meteo.com/) — free, worldwide, no key, cloud cover already reflected. Live Australian measurements from [ARPANSA](https://uvdata.arpansa.gov.au/UVLevel). Your coordinates are sent to fetch a forecast and stored only on your own device. Nothing else leaves the phone.

## Licence

MIT. Do what you like with it.
