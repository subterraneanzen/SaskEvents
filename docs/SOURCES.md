# Event-source inventory and integration assessment

Researched 2026-11-18. Live status of every source is in `feed/saskatoon/report.md`, regenerated each ingest run.
The app's **Settings → Data sources** screen shows the same information.

## The five mandatory sources

| # | Source | Access method | Result | Notes |
|---|---|---|---|---|
| 1 | **Amigos Cantina** – amigoscantina.com/events-and-concerts | HTML list (`li.event-individual`) + per-event pages | ✅ Working | WordPress; robots.txt allows everything. Listing gives title, date/time, poster, Facebook RSVP link; detail page adds description, age ("19+ w/ Valid ID") and ticket link. Prices come from the venue's own ticket site (`tickets.amigoscantina.com`, same platform as Broadway Theatre), integrated as a second official source. Trivia nights are included. |
| 2 | **Black Cat Tavern** – blackcattavern.ca/#events | Elfsight Event Calendar widget JSON | ⚠️ Blocked by robots.txt, so it is respected | The events section is an empty `<div>` filled by an Elfsight widget (id `add1f5aa…`). The widget data (`core.service.elfsight.com/p/boot/`) is complete and well structured: dates, America/Regina times, artwork, ticket links, and band links the venue placed next to each act. The adapter is built and tested against captured data, but **elfsight.com's robots.txt disallows bots on that path**, so the job skips it. Black Cat shows still reach the app through Songkick, AllEvents and Broadway Theatre ticketing (Winterruption). The site's WordPress `event` post type is stale (last entries 2025) and is not used. See *Limitations* for options. |
| 3 | **AllEvents – Capitol** – allevents.in/saskatoon/capitol | schema.org JSON-LD embedded in the page | ✅ Working | Treated as an aggregator (tier 4): never marks an event as venue-confirmed. Auto-generated text "artwork" images are discarded. Venue names that include the address are split and matched by street address. An extra AllEvents Saskatoon music page is also monitored. |
| 4 | **Bandsintown – Saskatoon** – bandsintown.com/c/saskatoon-sk | schema.org `MusicEvent` JSON-LD | ❌ HTTP 403 from CI servers | robots.txt allows generic crawlers on city pages and the page carries JSON-LD (verified in a browser). However Cloudflare blocks GitHub's datacenter IPs. The official Bandsintown API is per-artist and needs an `app_id` granted under their terms; it cannot list a city. The adapter stays enabled and records failures, so it starts working if the block is lifted. |
| 5 | **Songkick – Saskatoon** – songkick.com/metro-areas/27391-canada-saskatoon | schema.org `MusicEvent` JSON-LD (2 pages) | ✅ Working (largest source) | Includes venue geo-coordinates, performer profiles and genres; covers The Bassment, Black Cat, Louis', SaskTel Centre, TCU Place and touring shows in Prince Albert and North Battleford. Songkick's partner API is closed to new applicants. robots.txt allows metro pages for generic agents. Songkick's city-centroid placeholder coordinates are detected and ignored. |

## Additional sources

| Source | Status | Why / next step |
|---|---|---|
| Broadway Theatre – broadwaytheatre.ca/events | ✅ Working (tier 1) | Server-rendered list of about 50 events with prices, posters and descriptions. Its ticketing also sells festival shows at other venues (Winterruption at Amigos, Black Cat, Art Bar…). The real venue is read from the card or description, not assumed. |
| Amigos ticket site – tickets.amigoscantina.com/events | ✅ Working (tier 3) | Same platform as Broadway Theatre; adds prices. |
| Ticketmaster Discovery API | 🔑 Adapter ready, needs key | Free key from developer.ticketmaster.com. Add it as repository secret `TICKETMASTER_API_KEY`; covers SaskTel Centre / TCU Place arena and theatre shows with prices. |
| Saskatoon Public Library (Communico, libnet.info) | ⏸ Disabled | Event pages expose calendar data in America/Regina time; the iCal adapter exists, but the public feed URL was not confirmed. Enable once a feed URL is verified. |
| SaskMusic – Live Music Listings | ⏸ Candidate | Province-wide; strong on small Saskatoon rooms (Somewhere Else, Coors open mics, Finns Pub at the Parktown). Needs an HTML adapter. |
| Tourism Saskatoon – discoversaskatoon.com/calendar-events | ⏸ Candidate | Best source for non-music events (markets, museums, tours, sports, family). Needs an HTML adapter. |
| Showpass public API | ⏸ Candidate | Ticketing for Capitol Music Club, Black Cat and some Amigos shows; public JSON used by its widgets. Venue filtering not yet verified. |
| Eventbrite (The Bassment organizer) | ⏸ Not automated | All Bassment tickets are on Eventbrite. Its API needs a private token; Songkick already lists most Bassment shows. |
| Facebook venue pages / events | ❌ Not possible | The Graph API no longer exposes public Page events to third parties, and scraping is against Facebook's terms. Facebook event links that venues include in their own listings (e.g. Amigos RSVP) are shown in the app. |
| Capitol Music Club website (capitolclubyxe.ca) | ❌ Unreachable during research | Covered via AllEvents and Songkick; tickets on Showpass. |
| The Bassment website (thebassment.ca) | ❌ Not used | Moved to Squarespace; its JSON endpoints (`?format=json`) are disallowed in robots.txt. |

## Access rules followed

* Identified user agent `SaskEventsBot/1.0 (+repo URL; personal non-commercial…)`.
* robots.txt checked for every host before fetching; disallowed paths are skipped and reported, never worked around.
* At most one request per 1.5 s per host; most sources are checked every 6–24 h.
* Official APIs (Ticketmaster, Nominatim geocoding) follow their own usage terms.
* No logins, private social-media data or paywalled content.

## Adding a source

1. Add an entry to `backend/config/sources.json` (id, name, url, adapter, tier, frequencyHours, enabled).
2. If the site has schema.org JSON-LD or an iCal feed, the existing `jsonld` / `ics` adapters work as is.
3. Otherwise add a small adapter in `backend/ingest/adapters/` and a fixture-based test.
4. Push to `main`: the ingest workflow runs immediately and the report shows how many events it found.
