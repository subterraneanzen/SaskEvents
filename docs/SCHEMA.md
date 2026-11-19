# Data model

## Feed (`feed/<city>/bundle.json`, schemaVersion 1)

```jsonc
{
  "schemaVersion": 1,
  "generatedAt": "2026-11-19T01:26:20+00:00",
  "city":   { "id": "saskatoon", "name": "Saskatoon", "province": "SK", "timezone": "America/Regina", "lat": 52.1333, "lon": -106.6833 },
  "events": [ Event ],
  "venues": [ Venue ],
  "artists":[ Artist ],
  "sources":[ SourceStatus ]
}
```

### Event
| Field | Type | Notes |
|---|---|---|
| `id` | string | Stable across runs (reused through `sourceKeys`) |
| `title`, `description` | string, string? | Title from the highest-priority source |
| `category` | enum | `live_music, other_music, festival, market, comedy, theatre, arts_culture, workshop, community, family, sports, seasonal, other` |
| `tags` | string[] | e.g. `dj, open_mic, jam, karaoke, trivia, festival, tribute, sold_out, jazz…` |
| `performerIds` | string[] | → `artists[].id` |
| `venueId` | string? | → `venues[].id`; `venueNameRaw` when unmatched |
| `municipality` | string? | From the venue (used for out-of-town labelling) |
| `startDate` / `endDate` | `YYYY-MM-DD` | Local date, America/Regina |
| `startTime` / `endTime` / `doorsTime` | `HH:mm`? | Local time; null = not specified |
| `allDay`, `timezone` | bool, string | |
| `priceText`, `priceMin`, `priceMax`, `isFree` | string?, number?, number?, bool? | `isFree` null = unknown (not "paid") |
| `ticketUrl` | string? | |
| `ageRestriction` | `all_ages`/`16+`/`18+`/`19+`/null | null = not specified (≠ all ages) |
| `familyFriendly` | true/null | Only true when stated |
| `indoorOutdoor` | `indoor`/`outdoor`/null | |
| `imageUrl` | string? | |
| `sourceUrl`, `sourceName` | | Primary (highest-priority) source |
| `sources[]` | `{sourceId,name,url,tier}` | Every source that listed it |
| `fieldSources` | map field → sourceId | Provenance of each merged field |
| `links[]` | `{label,url,sourceId}` | e.g. Facebook RSVP, video |
| `verification` | `verified`/`cross_checked`/`unconfirmed` | |
| `status` | `scheduled`/`cancelled`/`postponed`/`rescheduled`/`unlisted` | `statusNote` explains |
| `highlightScore` | int | Used to pick ≤ 3 "worth a look" events per week |
| `firstSeen`, `lastVerified`, `updatedAt`, `missCount`, `sourceKeys`, `contentHash` | | Continuity bookkeeping |

### Venue
`id, name, aliases[], address, municipality, neighbourhood, lat, lon, coordSource, website, eventsUrl, facebook,
instagram, type (music_venue|bar|bar_restaurant|bar_campus|brewery|nightclub|theatre|performing_arts_centre|arena|gallery|market|community_arts|church_hall|exhibition_grounds),
hostsLiveMusic, indoor, typicalAge, notes, status (open|closed|unknown), verified, provisional, verificationNotes, evidence[], lastVerified`

### Artist
`id, name, identity (verified|venue_linked|aggregator_linked|unverified), disambiguation, description, genres[],
hometown, members[], links[{type,url,provenance}], evidence[], eventCount`

### SourceStatus (Source Manager)
`id, name, url, adapter, tier, enabled, frequencyHours, status (ok|failed|robots_disallowed|needs_api_key|disabled|manual),
lastSuccess, lastAttempt, eventsDiscovered, eventsInFeed, consecutiveFailures, totalFailures, lastError, notes`

## App database (Room, `saskevents.db`)

| Table | Key | Notes |
|---|---|---|
| `events` | `id` | Dates as `startEpochDay`/`endEpochDay` (Long), times as minutes after midnight; lists stored as JSON. `inFeed=false` marks bookmarked events that left the feed. `isFixture` marks test fixtures (never shown). Indexed on `startEpochDay`, `venueId`. |
| `venues` | `id` | Replaced each sync, except venues still needed by kept bookmarks |
| `artists` | `id` | Same retention rule |
| `bookmarks` | `eventId` | `savedAt`, `lastKnownStatus`, `lastKnownStartEpochDay` (detects cancellations/date changes once) |
| `sources` | `id` | Latest source health for the Data sources screen |

DataStore (preferences): city and centre, search origin (neighbourhood centroid, no GPS), radius, include
surrounding communities, preferred categories, default view, sync interval, Wi-Fi only, notifications,
last sync/attempt/error, feed `generatedAt`, current filters and saved default filters (JSON).
