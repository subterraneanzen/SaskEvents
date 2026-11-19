# Known limitations

## Sources
* **Black Cat Tavern (mandatory source) is not ingested automatically.** Its calendar is an Elfsight widget whose
  data host (`core.service.elfsight.com`) disallows bots in robots.txt. The adapter is written and tested, but the
  job respects the block. Black Cat shows still appear via Songkick, AllEvents and Broadway Theatre ticketing, just
  with less detail (no venue-supplied band links). Options: ask the venue for a feed (Elfsight can export iCal),
  or add another official source for the venue.
* **Bandsintown (mandatory source) returns HTTP 403** to GitHub's servers (Cloudflare bot protection). The adapter
  is enabled and will work if the block lifts; there is no city-wide official API.
* **Facebook events cannot be read.** Many small venues post only on Facebook/Instagram. Neither platform offers
  third-party access to public Page events.
* **Ticketmaster** needs a free API key added as the `TICKETMASTER_API_KEY` repository secret.
* Saskatoon Public Library, SaskMusic, Tourism Saskatoon and Showpass are researched and registered but **not yet
  automated** (see SOURCES.md). Non-music coverage (markets, museums, family, sports) is therefore thinner than
  music coverage for now.
* Aggregator data is lower quality: some Songkick/AllEvents listings have no start time, and AllEvents sometimes
  disagrees with itself on dates. Such events are labelled *unconfirmed* unless an official source agrees.

## Data quality
* Categories are inferred by rules (title words, schema.org type, venue type, then description). Unusual titles
  (e.g. an artist name plus a tour subtitle at a theatre) can land in *Other*.
* Performer names are split from titles conservatively ("A w/ B, C"). Names containing "&" or "and" are kept
  whole unless the venue lists the parts separately, so some co-bills appear as one performer.
* The curated artist list (`backend/config/artists.json`) starts empty, so no act is shown as *Verified artist*
  yet. Acts are shown as *Platform profile*, *Links from venue* or *Not verified* with explanations.
* Disappearance-based cancellation detection needs two consecutive successful checks (about 12–24 h).
* Coordinates for some venues come from Nominatim geocoding; venues without an address have none and are treated
  as inside the city they are listed in.

## App
* No GPS by design: distance is measured from the city centre or a chosen neighbourhood.
* One city (Saskatoon) is configured. The feed and app support more; adding one means a new venues/sources config
  and a city entry in the feed index.
* The release APK is signed with a key committed to the repo (fine for personal sideloading; use your own key
  before distributing it publicly).
* Notification delivery depends on the periodic sync running; Android may defer it on battery saver.
* The database uses destructive migration. A future schema change would reset cached events but keep the app
  working; bookmarks would be lost in that case. Add a Room migration before changing the schema.
