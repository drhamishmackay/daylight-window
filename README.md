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

The arithmetic is standard radiometry rather than anything invented here:

- UV index 1 means 25 milliwatts per square metre of sunburn-weighted energy
- One **standard erythema dose** is 100 joules per square metre
- The dose that just visibly reddens skin runs from about 2 of those doses for Fitzpatrick type I up to about 10 for type VI

The model is checked against published burn times: it puts type I skin at roughly 17 minutes to redden at UV 8 and about an hour at UV 2, which is what dermatology references give. Those checks are in [`SunModelTest.kt`](app/src/test/java/com/daylight/window/SunModelTest.kt).

### Why the default limit is UV 1

Running a real spring day in Melbourne through the model, for the fairest skin type:

| Limit | Time outside | Share of a reddening dose |
|------:|-------------:|--------------------------:|
| UV 1  | 2h 40m       | 53% |
| UV 2  | 4h 20m       | 166% |
| UV 3  | 5h 40m       | 318% |

UV 2 buys ninety more minutes outdoors but tips past the threshold where skin starts to redden. UV 1 is the setting that yields the most time outside per unit of accumulated exposure, so that is the default. Every other limit is one tap away.

## Honest limits

No amount of ultraviolet is completely free of risk. Risk falls smoothly as UV falls; it never reaches zero. This app finds the gentlest daylight of your day — it does not make sun exposure safe, and it is not medical advice.

The reddening thresholds are population averages for each skin type, not measurements of any individual.

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
