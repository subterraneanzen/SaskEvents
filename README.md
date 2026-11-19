# YXE Events – Saskatoon live music & local events

A lightweight Android app that answers one question: **what's happening around town this week?**
Live music comes first, with comedy, theatre, markets, festivals, community and family events alongside it.

* **App:** Kotlin, Jetpack Compose (Material 3), Room, WorkManager, DataStore, OkHttp, Coil.
* **Backend:** a Python job on GitHub Actions collects events every 6 hours from venue sites and public listings and
  publishes a static JSON feed on the `feed` branch. No server and no cost; the phone never crawls the web.

## Install

Download **YXE-Events.apk** from the [latest release](../../releases/tag/latest) on your Android phone (Android 8+),
open it, and allow installs from your browser when asked. Each push to `main` rebuilds it after the automated
emulator tests pass. Updates install over the previous version and keep your bookmarks.

## What's in the app
* **Discover** – compact weekly agenda grouped by day, previous/next week, Today, up to 3 highlighted events,
  search and combinable filters (type, date, time of day, audience/age, bar/restaurant/outdoor, cost and max
  price, venue, neighbourhood). Filters persist; you can save them as your defaults.
* **Calendar** – month grid with dots on days that have events; tap a day for its list or jump to that week's agenda.
* **Saved** – bookmarks, offline, chronological; cancelled, postponed and removed events are clearly flagged.
  Optional notifications (off by default).
* **Event details** – full description, price, age (or "Not specified"), doors and start time, tickets, add to
  calendar (no account needed), map, performers, listing links, every source and its verification status.
* **Venue & artist pages** – venue info and upcoming events. Artist pages explain how the act was identified
  and only link profiles that are attributable.
* **Settings** – city and search origin (no GPS), radius and surrounding communities, preferred categories, default
  view, update interval and Wi-Fi only, notifications, clear cache, data-source health.

## Repository layout
```
android/                 Android app (Gradle project)
backend/ingest/          Ingestion pipeline (adapters, venue matching, dedupe, verification, artists)
backend/config/          venues.json · sources.json · artists.json   ← edit these to add venues/sources
backend/tests/           Fixture-based tests built from real page structures
.github/workflows/       ingest.yml (cron) · android.yml (build, emulator tests, release)
docs/                    ARCHITECTURE · SCHEMA · SOURCES · VENUES · LIMITATIONS
```
The live feed and its health report: [`feed` branch](../../tree/feed) → `saskatoon/report.md`.

## Build locally
Requirements: JDK 17, Android SDK (API 35). Then:
```bash
cd android
./gradlew testDebugUnitTest assembleRelease       # APK: app/build/outputs/apk/release/app-release.apk
./gradlew connectedDebugAndroidTest               # with a device/emulator attached (uses the live feed)
```
Backend:
```bash
cd backend
pip install -r requirements.txt -r requirements-dev.txt
python -m pytest
python -m ingest.main --config config --prev ../feed --out ../feed --force   # writes ../feed/
```

## Deploying the backend
It is already deployed: `.github/workflows/ingest.yml` runs every 6 hours, on every push that changes `backend/`,
and on demand (**Actions → Ingest events → Run workflow**, with "force" to re-check every source now).
Optional: add a free Ticketmaster Discovery key as repository secret `TICKETMASTER_API_KEY` for arena and theatre listings.
To use a fork, change `FEED_BASE_URL` in `android/app/build.gradle.kts`.

## Dependencies
| Library | Why |
|---|---|
| Compose BOM 2025.01.01, Material 3, material-icons-extended | UI (icons are tree-shaken by R8 in release) |
| Navigation Compose 2.8.5, Lifecycle 2.8.7 | Screens and state |
| Room 2.6.1 (KSP) | Offline database |
| WorkManager 2.10.0 | Battery-friendly scheduled sync |
| DataStore Preferences 1.1.2 | Settings and persisted filters |
| OkHttp 4.12 + kotlinx.serialization 1.7.3 | Feed download with HTTP caching; JSON parsing |
| Coil 2.7 | Artwork loading with a 40 MB disk-cache cap |
| Python: requests, beautifulsoup4, lxml | Ingestion |

See **docs/LIMITATIONS.md** for what doesn't work yet and why.
