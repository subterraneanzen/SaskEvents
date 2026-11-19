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

from .artist_links import COUNTRY_OF_REGION, is_clear_place, place_keys, social_links_on_own_page
from .artists import link_type
from .normalize import norm_name

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
    return {"mbid": mbid, "name": art.get("name"), "disambiguation": art.get("disambiguation") or None,
            "links": mb_artist_links(fetcher, mbid)}


def mb_artist_links(fetcher, mbid: str) -> list[str]:
    r2 = fetcher.get(f"{MB}/artist/{mbid}", params={"inc": "url-rels", "fmt": "json"},
                     headers={"Accept": "application/json"}, check_robots=False)
    links = []
    for rel in r2.json().get("relations", []):
        u = (rel.get("url") or {}).get("resource")
        if not u or rel.get("ended") or rel.get("type") not in REL_OK:
            continue
        t = link_type(u)
        if t == "website" and rel.get("type") != "official homepage":
            continue  # store/streaming pages (iTunes, Deezer, Amazon...) are not useful previews
        if t in WANTED and u not in links:
            links.append(u)
    return links


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
                    hit = {**mb_links_for_profile(fetcher, prof), "v": 2, "checked": now.isoformat(timespec="seconds")}
                    cache[prof] = hit
                except Exception as e:  # keep the stale entry, try again next run
                    stats["errors"] += 1
                    log.warning("musicbrainz lookup failed for %s: %s", prof, e)
            if not hit or not hit.get("mbid"):
                continue
            if hit.get("v") != 2:  # entries from the first version kept store links; refresh them
                hit = None
                if budget > 0 and fetcher is not None:
                    budget -= 1
                    try:
                        hit = cache[prof] = {**mb_links_for_profile(fetcher, prof), "v": 2,
                                             "checked": now.isoformat(timespec="seconds")}
                    except Exception as e:
                        stats["errors"] += 1
                        log.warning("musicbrainz lookup failed for %s: %s", prof, e)
                if not hit or not hit.get("mbid"):
                    continue
            added = False
            for u in hit.get("links", []):
                t = link_type(u)
                if t not in WANTED or any(l["type"] == t or l["url"].rstrip("/") == u.rstrip("/") for l in a["links"]):
                    continue  # one link per platform is enough
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


# ---------------------------------------------------------------- links from listings

_OWN_PAGE_TYPES = {"bandcamp", "website", "linktree"}
_LISTING_TTL = timedelta(days=21)


def _fresh(hit: Optional[dict], now: datetime, ttl: timedelta = _LISTING_TTL) -> bool:
    return bool(hit) and now - _parse(hit["checked"]) < ttl


def _add_link(a: dict, url: str, provenance: str) -> bool:
    t = link_type(url)
    if t not in WANTED:
        return False
    if any(l["url"].rstrip("/").lower() == url.rstrip("/").lower() or l["type"] == t for l in a["links"]):
        return False  # one link per platform
    a["links"].append({"type": t, "url": url, "provenance": provenance})
    return True


def _area_names(art: dict) -> set[str]:
    out = set()
    for k in ("area", "begin-area"):
        n = (art.get(k) or {}).get("name")
        if n:
            out.add(n.lower())
    return out


def mb_match_by_hometown(fetcher, name: str, places: list[str]) -> dict:
    """One MusicBrainz artist with exactly this name AND from the place the listing names, or {"mbid": None}.

    A city in the listing also matches an artist recorded only at province/state level. When MusicBrainz records
    just the country, the match is accepted only if no other artist anywhere has that exact name.
    """
    q = 'artist:"%s"' % name.replace('"', " ").replace("\\", " ")
    r = fetcher.get(f"{MB}/artist", params={"query": q, "limit": 15, "fmt": "json"},
                    headers={"Accept": "application/json"}, check_robots=False)
    target = norm_name(name)
    exact = [x for x in r.json().get("artists", [])
             if norm_name(x.get("name", "")) == target
             or any(norm_name(al.get("name", "")) == target for al in x.get("aliases") or [])]
    keys: set[str] = set()
    countries: set[str] = set()
    for pl in places:
        k = place_keys(pl)
        keys |= k
        countries |= {COUNTRY_OF_REGION[x] for x in k if x in COUNTRY_OF_REGION}
    hits = [(x, "place") for x in exact if _area_names(x) & keys]
    if not hits and len(exact) == 1 and _area_names(exact[0]) & countries:
        hits = [(exact[0], "country")]
    if len(hits) != 1:
        return {"mbid": None, "candidates": len(exact)}
    art, how = hits[0]
    area = (art.get("begin-area") or art.get("area") or {}).get("name")
    return {"mbid": art["id"], "name": art.get("name"), "disambiguation": art.get("disambiguation") or None,
            "area": area, "how": how, "links": mb_artist_links(fetcher, art["id"])}


def _url_variants(u: str) -> list[str]:
    base = u.split("?")[0].rstrip("/")
    out = [base + "/", base]
    if base.startswith("https://www."):
        out += ["https://" + base[len("https://www."):] + "/"]
    elif base.startswith("https://") and base.count(".") == 1:
        out += ["https://www." + base[len("https://"):] + "/"]
    return list(dict.fromkeys(out))


def enrich_from_listings(artists: list[dict], fetcher, cache_path: Path, prev_cache_path: Path, *, now: datetime,
                         budget: int = 150) -> dict:
    """Adds links using what the listings said: the act's own linked pages and its stated hometown.

    Per act, in order (each step cached): MusicBrainz lookup of a linked Bandcamp/website, reading the act's own
    Bandcamp/website/Linktree for its social links, then a MusicBrainz name search confirmed by hometown.
    """
    cache: dict = {}
    for p in (prev_cache_path, cache_path):
        try:
            cache.update(json.loads(p.read_text()))
        except (OSError, ValueError):
            pass
    stats = {"own_pages_read": 0, "mb_url_hits": 0, "hometown_matches": 0, "links_added": 0, "errors": 0}
    state = {"budget": budget}

    def cached(key: str, fn, ttl: timedelta = _LISTING_TTL):
        hit = cache.get(key)
        if _fresh(hit, now, ttl) or fetcher is None or state["budget"] <= 0:
            return hit
        state["budget"] -= 1
        try:
            hit = cache[key] = {**fn(), "checked": now.isoformat(timespec="seconds")}
        except Exception as e:
            if "404" in str(e) or "robots" in str(e).lower():
                hit = cache[key] = {"mbid": None, "links": [], "checked": now.isoformat(timespec="seconds"),
                                    "note": str(e)[:120]}
            else:
                stats["errors"] += 1
                log.warning("artist link lookup failed for %s: %s", key, e)
        return hit

    for a in artists:
        places = a.pop("_places", [])
        if a.get("identity") == "verified":
            continue
        name = a["name"]
        own = [l for l in a["links"] if l["type"] in _OWN_PAGE_TYPES and l.get("provenance", "").startswith("listed")]
        # 1) MusicBrainz knows the act's Bandcamp/website the venue linked.
        for l in own:
            def by_url(u=l["url"]):
                for v in _url_variants(u):
                    res = mb_links_for_profile(fetcher, v)
                    if res.get("mbid"):
                        return res
                return {"mbid": None}
            hit = cached("mburl:" + l["url"].rstrip("/").lower(), by_url)
            if hit and hit.get("mbid"):
                stats["mb_url_hits"] += 1
                for u in hit.get("links", []):
                    if _add_link(a, u, f"MusicBrainz entry for the {l['type']} page the venue linked"):
                        stats["links_added"] += 1
                a.setdefault("musicbrainz", f"https://musicbrainz.org/artist/{hit['mbid']}")
                break
        # 2) The act's own page lists its social accounts.
        for l in own:
            def read_page(u=l["url"]):
                r = fetcher.get(u)
                return {"links": social_links_on_own_page(r.text, u, name)}
            hit = cached("page:" + l["url"].rstrip("/").lower(), read_page)
            if hit and hit.get("links") is not None and "note" not in hit:
                stats["own_pages_read"] += 1
                label = {"bandcamp": "Bandcamp page", "linktree": "Linktree"}.get(l["type"], "website")
                for u in hit["links"]:
                    if _add_link(a, u, f"linked from the act's own {label}"):
                        stats["links_added"] += 1
        # 3) Name + stated hometown matches exactly one MusicBrainz artist.
        if places and not a.get("musicbrainz"):
            key = "home:" + norm_name(name) + "|" + "|".join(sorted({k for p in places for k in place_keys(p)}))
            hit = cached(key, lambda: mb_match_by_hometown(fetcher, name, places))
            if hit and hit.get("mbid"):
                stats["hometown_matches"] += 1
                where = hit.get("area") or places[0]
                prov = f"MusicBrainz artist '{hit.get('name') or name}' from {where}, matching the listing"
                added = sum(_add_link(a, u, prov) for u in hit.get("links", []))
                stats["links_added"] += added
                a["musicbrainz"] = f"https://musicbrainz.org/artist/{hit['mbid']}"
                if a.get("identity") in ("unverified", "aggregator_linked"):
                    a["identity"] = "matched"
                ev = (f"listing says the act is from {places[0]}; exactly one MusicBrainz artist with this name is from "
                      f"{where}" if hit.get("how") == "place" else
                      f"listing says the act is from {places[0]}; the only MusicBrainz artist with this name is from {where}")
                if ev not in a["evidence"]:
                    a["evidence"].append(ev)
                if not a.get("disambiguation") and hit.get("disambiguation"):
                    a["disambiguation"] = hit["disambiguation"]
                if not a.get("hometown") and hit.get("how") == "place":
                    a["hometown"] = next((p for p in places if is_clear_place(p)), where)
        if places and not a.get("hometown"):
            clear = [p for p in places if is_clear_place(p)]
            if len(clear) == 1:
                a["hometown"] = clear[0]
                ev = f"hometown as stated in the event listing ({clear[0]})"
                if ev not in a["evidence"]:
                    a["evidence"].append(ev)
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(json.dumps(cache, indent=1, sort_keys=True))
    return stats
