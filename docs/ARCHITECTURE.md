# Architecture

```
 ┌──────────────── GitHub (free) ─────────────────┐            ┌──────────── Android phone ────────────┐
 │                                                │            │                                        │
 │  backend/config/sources.json  (source registry)│            │  WorkManager (every 12/24/48 h,        │
 │  backend/config/venues.json   (venue directory)│            │  battery-not-low, optional Wi-Fi only) │
 │  backend/config/artists.json  (curated artists)│            │        │                               │
 │            │                                   │            │        ▼                               │
 │  Ingest workflow (cron once a day)             │  HTTPS     │  FeedApi (OkHttp + HTTP cache/ETag)    │
 │   adapters → venue match → dedupe → verify →   │──────────▶ │        │                               │
 │   artists → continuity → feed/ branch          │  raw JSON  │        ▼                               │
 │                                                │            │  EventRepository → Room (offline)      │
 │  Android workflow (on push)                    │            │        │                               │
 │   unit tests → APKs → emulator tests →         │            │        ▼                               │
 │   screenshots → "latest" release               │            │  ViewModels (StateFlow) → Compose UI   │
 └────────────────────────────────────────────────┘            └────────────────────────────────────────┘
```

## Why this shape

* **No server to pay for or babysit.** Event collection runs as a scheduled GitHub Actions job (free for public
  repos). The result is a static JSON file on the `feed` branch, served by `raw.githubusercontent.com` with ETags.
* **The phone never crawls the web.** It downloads one ~100–300 KB JSON bundle about once a day, only when the
  bundle changed (HTTP 304 otherwise), and works fully offline from Room afterwards.
* **Editable without an app update.** Venues, sources and curated artists live in `backend/config/*.json`.
  Editing them on GitHub triggers a fresh ingest; the app picks up the new feed on its next sync.
* **Multi-city ready.** `feed/index.json` lists cities; each city has its own bundle. Nothing in the app hard-codes
  Saskatoon except defaults; the venue file's `city` block defines centre, timezone and name.

## Backend (`backend/ingest`)

| Module | Role |
|---|---|
| `http.py` | Identified user agent, robots.txt check per host, per-host rate limit (1.5 s), retries |
| `adapters/amigos.py` | Amigos Cantina WordPress list + detail pages |
| `adapters/elfsight.py` | Elfsight Event Calendar widget JSON (Black Cat Tavern), venue-supplied artist links |
| `adapters/broadway.py` | Broadway Theatre / Amigos ticketing platform (prices, posters, real venue from description) |
| `adapters/jsonld.py` | Generic schema.org `Event`/`MusicEvent` JSON-LD (Songkick, AllEvents, Bandsintown, any venue site) |
| `adapters/ics.py` | iCalendar feeds (library, community calendars) |
| `adapters/ticketmaster.py` | Official Ticketmaster Discovery API (needs free key) |
| `venues.py` | Name/alias, street-address and geo matching; provisional venues for unknown places; Nominatim geocoding |
| `pipeline.py` | Duplicate consolidation, field-level source priority, verification status, categories, continuity |
| `artists.py` | Conservative artist identification (see below) |
| `main.py` | Orchestration, per-source scheduling and caching, health report |

**Source priority.** Fields are taken from the highest-priority source that has them
(tier 1 official venue → 2 artist → 3 official ticketing → 4 aggregator → 5 other). `fieldSources` records which
source supplied each field; `sources[]` keeps every URL.

**Duplicate detection.** Events are grouped by resolved venue and date, then clustered when titles overlap
(token Jaccard ≥ 0.5 or containment), a performer name is shared, and start times are within 3.5 h.
Two different events from the same source are never merged.

**Verification.** `verified` = an official venue/ticketing source lists it; `cross_checked` = ≥ 2 independent
platforms; `unconfirmed` = one secondary listing.

**Cancellations & changes.** Statuses come from schema.org `eventStatus`, explicit words in titles
("CANCELLED", "POSTPONED"), or disappearance: if a future event vanishes from all of its (successfully checked)
sources for two consecutive runs, it is marked `unlisted` instead of being silently deleted. The app keeps
bookmarked events locally regardless and notifies (if enabled) on cancellation, postponement, date change or
removal.

**Artist identification.** Names are never matched to profiles by name alone.
`verified` requires a curated record plus an independent corroborating detail (same platform profile, official
link domain listed by the venue, regular venue, or home city). `venue_linked` means the official venue listing put
the link next to that name (in the description too: anchor text is the name, the account name spells the act's
name, or the link sits in a paragraph about only that act). `matched` means the listing's stated hometown ("from
Winnipeg", "Regina-based") matched exactly one MusicBrainz artist of that exact name. `aggregator_linked` means a platform attached its own profile to this event; only
that profile page is linked. `unverified` acts get no external links, and their records are scoped per venue so
identically named acts elsewhere are never merged.

## App (`android/`)

Kotlin, Jetpack Compose (Material 3, custom sage/off-white theme), Room, WorkManager, DataStore, OkHttp,
kotlinx.serialization, Coil (40 MB artwork disk cache, thumbnails requested at 256 px).
Retrofit was not used: the app makes two GET requests to static files, so OkHttp alone is simpler and smaller.

```
ca.saskevents.app
├─ data/remote      FeedApi, DTOs (mirror the feed JSON)
├─ data/local       Room entities, DAOs, converters
├─ data/prefs       DataStore settings + persisted filters
├─ data/repository  EventRepository: sync (atomic apply, bookmark retention, change detection), queries
├─ domain           Categories, labels, FilterState + pure filter logic, distance, time zone helpers
├─ sync             SyncWorker (periodic + one-off)
├─ notify           Notifications for saved-event changes (off by default)
└─ ui               Discover, Calendar, Saved, Settings, Sources, Event/Venue/Artist details, FilterSheet
```

All dates and times are stored as local `America/Regina` wall-clock values (epoch day + minutes), which avoids
time zone drift because Saskatchewan does not observe DST.
