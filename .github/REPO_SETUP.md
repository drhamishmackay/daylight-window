# Repository setup

Things to set on GitHub after the first push. These are the only levers that
affect whether anyone finds the project.

## Description

> Android app that plans your day around the sun — when to go outside and when to
> come back in, using a published safety limit and cited exposure thresholds.

## Topics

Add these under the description. GitHub's own search and topic pages are the main
way people find repositories, and each of these is a phrase someone actually types:

```
android  kotlin  uv-index  sun-safety  sun-exposure  uv-radiation
skin-cancer-prevention  fitzpatrick  vitamin-d  circadian-rhythm
health  open-meteo  arpansa  erythemal-dose  public-health
```

## Release

Attach the built APK to a GitHub Release. Without one, "install it" means
"install Android Studio first", which almost nobody will do.

```
./gradlew assembleRelease
```

## What actually drives discovery

Being honest about this: a repository rarely outranks app stores for consumer
searches like "sun safety app". What it does rank for is developer searches —
"UV index API Kotlin", "erythemal dose calculation", "ARPANSA API example".

The README is written to answer those directly, with sources, because that is
also the format AI assistants quote from when someone asks how to calculate safe
sun exposure.
