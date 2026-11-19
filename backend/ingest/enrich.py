"""Listening links for artists (Bandcamp, YouTube, Facebook, Spotify, ...) from MusicBrainz.

Identity is never decided by name. An act is enriched only when MusicBrainz's community-edited database
already links the exact platform profile that the event listing attached (e.g. the act's Songkick page) to
one MusicBrainz artist. That artist's own URL relations (Bandcamp, YouTube, Facebook, ...) are then added
with provenance. Results are cached (also negative ones) so each act costs at most two API calls every
few weeks. MusicBrainz API rules: identified User-Agent, max 1 request/second.
"""
from __future__ import annotations

import json
import logging
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional

from .artists import link_type

log = logging.getLogger(__name__)

MB = "https://musicbrainz.org/ws/2"
TTL_FOUND = timedelta(days=30)
TTL_MISSING = timedelta(days=10)
WANTED = {"bandcamp", "youtube", "facebook", "instagram", "spotify", "soundcloud", "apple_music", "website", "tiktok", "linktree"}
# MusicBrainz relation types worth showing; others (discogs, wikidata, lyrics sites...) are skipped.
REL_OK = {"bandcamp", "youtube", "social network", "streaming", "free streaming", "official homepage", "soundcloud",
          "purchase for download", "video channel", "linktree", "myspace"}


def canonical_profile(url: str) -> Optional[str]:
    """The form MusicBrainz stores platform profile URLs in."""
    m = re.search(r"songkick\.com/artists/(\d+)", url or "")
    if m:
        return f"https://www.songkick.com/artists/{m.group(1)}"
    m = re.search(r"bandsintown\.com/a/(\d+)", url or "")
    if m:
        return f"https://www.bandsintown.com/a/{m.group(1)}"
    return None


def _parse(ts: str) -> datetime:
    return datetime.fromisoformat(ts)


def mb_links_for_profile(fetcher, profile: str) -> dict:
    """Returns {"mbid", "name", "links": [url,...]} or {"mbid": None} when MusicBrainz has no single match."""
    try:
        r = fetcher.get(f"{MB}/url", params={"resource": profile, "inc": "artist-rels", "fmt": "json"},
                        headers={"Accept": "application/json"}, check_robots=False)
    except Exception as e:
        if "404" in str(e):  # MusicBrainz has no entry for this profile URL
            return {"mbid": None}
        raise
    rels = [x for x in r.json().get("relations", []) if x.get("artist")]
    artists = {x["artist"]["id"]: x["artist"] for x in rels}
    if len(artists) != 1:  # unlinked or ambiguous: add nothing
        return {"mbid": None}
    mbid, art = next(iter(artists.items()))
    r2 = fetcher.get(f"{MB}/artist/{mbid}", params={"inc": "url-rels", "fmt": "json"},
                     headers={"Accept": "application/json"}, check_robots=False)
    links = []
    for rel in r2.json().get("relations", []):
        u = (rel.get("url") or {}).get("resource")
        if not u or rel.get("ended") or rel.get("type") not in REL_OK:
            continue
        if link_type(u) in WANTED and u not in links:
            links.append(u)
    return {"mbid": mbid, "name": art.get("name"), "disambiguation": art.get("disambiguation") or None, "links": links}


def enrich_artists(artists: list[dict], fetcher, cache_path: Path, prev_cache_path: Path, *, now: datetime,
                   budget: int = 120) -> dict:
    """Adds MusicBrainz-sourced links to artists in place. Returns stats."""
    cache = {}
    for p in (prev_cache_path, cache_path):
        try:
            cache.update(json.loads(p.read_text()))
        except (OSError, ValueError):
            pass
    stats = {"looked_up": 0, "enriched": 0, "errors": 0}
    for a in artists:
        profiles = [canonical_profile(l["url"]) for l in a.get("links", []) if l.get("type") in ("songkick", "bandsintown")]
        profiles = [p for p in profiles if p]
        for prof in profiles:
            hit = cache.get(prof)
            fresh = hit and now - _parse(hit["checked"]) < (TTL_FOUND if hit.get("mbid") else TTL_MISSING)
            if not fresh and budget > 0 and fetcher is not None:
                budget -= 1
                stats["looked_up"] += 1
                try:
                    hit = {**mb_links_for_profile(fetcher, prof), "checked": now.isoformat(timespec="seconds")}
                    cache[prof] = hit
                except Exception as e:  # keep the stale entry, try again next run
                    stats["errors"] += 1
                    log.warning("musicbrainz lookup failed for %s: %s", prof, e)
            if not hit or not hit.get("mbid"):
                continue
            added = False
            for u in hit.get("links", []):
                if any(l["url"].rstrip("/") == u.rstrip("/") for l in a["links"]):
                    continue
                a["links"].append({"type": link_type(u), "url": u,
                                   "provenance": "MusicBrainz entry that links to this act's " +
                                                 ("Songkick" if "songkick" in prof else "Bandsintown") + " profile"})
                added = True
            mb_url = f"https://musicbrainz.org/artist/{hit['mbid']}"
            ev = "MusicBrainz links this act's platform profile to its official pages"
            if added or ev not in a["evidence"]:
                if ev not in a["evidence"]:
                    a["evidence"].append(ev)
                a.setdefault("musicbrainz", mb_url)
                if not a.get("disambiguation") and hit.get("disambiguation"):
                    a["disambiguation"] = hit["disambiguation"]
                stats["enriched"] += 1
            break
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(json.dumps(cache, indent=1, sort_keys=True))
    return stats
