# Daylight Window

**An Android app that tells you when to go outside.**

Most UV apps answer the question *how dangerous is the sun right now?* This one answers a more useful question: *when today is it gentle enough that I can stop thinking about it and just go outside?*

That inversion is the whole idea. People with very fair skin often end up avoiding daylight entirely — not because any given day is dangerous, but because working out whether it is takes more effort than staying indoors. The app removes the decision.

---

## What it does

Reads the hour-by-hour ultraviolet forecast for wherever you are, anywhere in the world, and marks every stretch of daylight that stays under a limit you choose. Then it says one thing:

> **Go outside now.**
> UV is 0.8. The window runs 2h 10m more, until 9:40am. At this level you could stay out all of it without pinking.

or

> **Wait until 4:20pm.**
> UV is 4.1, over your limit of 1. It settles back at 4:20pm and stays gentle for 1h 45m.

Turn on reminders and your phone tells you when a window opens. The alarms survive reboots.

## The exposure model

Each window shows how long you could stay before your skin would just begin to redden, and a bar shows how much of that allowance the day's windows use up together. This matters because time alone is misleading — twenty minutes at UV 6 costs more than two hours at UV 1.

Two of the three inputs are international definitions, not choices made here:

- **UV index** is defined as 40 times the sunburn-weighted irradiance in watts per square metre, so UV 1 is 25 milliwatts per square metre
- One **standard erythema dose** is 100 joules per square metre

Both are set by the [CIE](https://cie.co.at/publications/erythema-reference-action-spectrum-and-standard-erythema-dose-0) and used by the World Meteorological Organization and ICNIRP for the public UV index.

The third input is empirical: how much of a dose it takes to just visibly redden each skin type. Those come from [Young et al., *Journal of Investigative Dermatology* 2018](https://pmc.ncbi.nlm.nih.gov/articles/PMC6158343/), Table 1 — measured medians from 39 people, six to seven per skin type:

| Skin type | I | II | III | IV | V | VI |
|---|---|---|---|---|---|---|
| Reddening threshold (SED) | 2.1 | 2.6 | 3.2 | 5.6 | 7.5 | 15.2 |

[`SunModelTest.kt`](app/src/test/java/com/daylight/window/SunModelTest.kt) checks the arithmetic reproduces every one of those thresholds, not just a convenient one.

### How much to trust the thresholds

Less than the two decimal places suggest, and the app says so on its own screen.

The sample behind them is small — six or seven people per skin type. And a separate series of 113 people in Colombia found that skin type predicts an individual's reddening threshold only moderately (correlation 0.5 to 0.69), with measured thresholds **overlapping between types**: two people classified differently can share an identical threshold ([Sánchez et al., *Actas Dermo-Sifiliográficas* 2020](https://www.actasdermo.org/en-minimal-erythema-dose-correlation-with-articulo-S1578219020301505)).

So the figures are population medians, and any individual can sit well either side of one. They are a conservative guide, not a measurement of you.

There is also no dose at which nothing happens. In the same 2018 study, DNA damage was detectable at every dose tested, down to a fifth of a person's own sunburn threshold — *"No dose could be identified at which 25(OH)D was produced without detectable DNA damage."* That is why the app talks about the gentlest daylight rather than safe daylight.

### Why the default limit is UV 1

Running a real spring day in Melbourne through the model, for the fairest skin type:

| Limit | Time outside | Share of a reddening dose |
|------:|-------------:|--------------------------:|
| UV 1  | 2h 40m       | 50% |
| UV 2  | 4h 20m       | 158% |
| UV 3  | 5h 40m       | 303% |

UV 2 buys ninety more minutes outdoors but tips past the threshold where skin starts to redden. UV 1 is the setting that yields the most time outside per unit of accumulated exposure, so that is the default. Every other limit is one tap away.

## Honest limits

No amount of ultraviolet is completely free of risk — that is measured, not a disclaimer. Risk falls smoothly as UV falls; it never reaches zero. This app finds the gentlest daylight of your day. It does not make sun exposure safe, and it is not medical advice.

The reddening thresholds are population medians that overlap between skin types, so treat them as a cautious guide rather than a reading of your own skin.

## Building it

Requires JDK 17 and the Android SDK (platform 34).

```
git clone https://github.com/<you>/daylight-window.git
cd daylight-window
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

To run the tests:

```
./gradlew test
```

### Installing it on your own phone

Enable developer options and USB debugging on the phone, plug it in, then:

```
./gradlew installDebug
```

Or copy `app/build/outputs/apk/debug/app-debug.apk` across and open it, allowing
installation from unknown sources when asked.

### Building a release for the Play Store

Create a signing key once and keep it somewhere safe — losing it means you can never
update the app under the same listing:

```
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias daylight
```

Then write a `keystore.properties` file next to `settings.gradle.kts`:

```
storeFile=release.jks
storePassword=<the password you chose>
keyAlias=daylight
keyPassword=<the password you chose>
```

Both that file and the key itself are listed in `.gitignore` and must never be
committed. Then:

```
./gradlew bundleRelease
```

The upload bundle lands in `app/build/outputs/bundle/release/app-release.aab`,
which is the file Google Play accepts.

## How it is put together

The reasoning is deliberately kept away from anything Android-specific, so it can be tested as plain arithmetic:

| File | What it does |
|------|--------------|
| [`SunModel.kt`](app/src/main/java/com/daylight/window/SunModel.kt) | Interpolates the hourly forecast, finds the windows, computes exposure. No Android imports. |
| [`Verdict.kt`](app/src/main/java/com/daylight/window/Verdict.kt) | Decides what the app says right now. Also pure. |
| [`Forecast.kt`](app/src/main/java/com/daylight/window/Forecast.kt) | Fetches and parses the forecast. |
| [`Reminders.kt`](app/src/main/java/com/daylight/window/Reminders.kt) | Alarms, notifications, and re-arming after reboot. |
| [`UvCurveView.kt`](app/src/main/java/com/daylight/window/UvCurveView.kt) | Draws the day's curve with the windows shaded. |
| [`MainActivity.kt`](app/src/main/java/com/daylight/window/MainActivity.kt) | The single screen. |

Plain Android views rather than Compose, and no dependency injection framework — the app is small enough that neither would earn its weight.

## Data

Forecasts come from [Open-Meteo](https://open-meteo.com/), which is free, needs no API key, and covers the whole world. Cloud cover is already reflected in the UV figures. Your coordinates are sent to fetch a forecast and are stored only on your own device.

## Licence

MIT.
