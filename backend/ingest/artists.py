"""Artist identification. Never links an act to profiles on name alone.

Identity levels:
  verified          - matches a curated record in config/artists.json on name AND at least one independent
                      corroborating detail (same platform profile, same official link domain, or a listed venue/city hint).
  venue_linked      - links come from the official venue listing, placed next to this performer's name.
  aggregator_linked - identified by a platform profile (Songkick/Bandsintown/Ticketmaster) that the platform
                      attached to this specific event. Only that profile page is linked.
  unverified        - name only. No external links are attached.
"""
from __future__ import annotations

import re
from typing import Optional
from urllib.parse import urlparse

from .models import Performer
from .normalize import norm_name, slugify

LINK_TYPES = [
    ("bandcamp", r"bandcamp\.com"), ("youtube", r"youtube\.com|youtu\.be"), ("facebook", r"facebook\.com|fb\.com"),
    ("instagram", r"instagram\.com"), ("spotify", r"open\.spotify\.com"), ("apple_music", r"music\.apple\.com"),
    ("soundcloud", r"soundcloud\.com"), ("tiktok", r"tiktok\.com"), ("linktree", r"linktr\.ee"),
    ("songkick", r"songkick\.com"), ("bandsintown", r"bandsintown\.com"), ("ticketmaster", r"ticketmaster\."),
]


def link_type(url: str) -> str:
    for t, rx in LINK_TYPES:
        if re.search(rx, url, re.I):
            return t
    return "website"


def _domain(url: str) -> str:
    return urlparse(url if "//" in url else "https://" + url).netloc.lower().removeprefix("www.")


def _profile_id(url: Optional[str]) -> Optional[str]:
    if not url:
        return None
    m = re.search(r"songkick\.com/artists/(\d+)", url) or re.search(r"bandsintown\.com/a/(\d+)", url) or \
        re.search(r"ticketmaster\.\w+/.*?/artist/(\d+)", url)
    if not m:
        return None
    plat = "sk" if "songkick" in url else "bit" if "bandsintown" in url else "tm"
    return f"{plat}{m.group(1)}"


class ArtistBook:
    def __init__(self, curated: list[dict]):
        self.curated = curated
        self.records: dict[str, dict] = {}

    def _curated_match(self, p: Performer, venue_id: Optional[str], municipality: Optional[str]) -> Optional[dict]:
        cands = [c for c in self.curated if norm_name(p.name) in {norm_name(n) for n in [c["name"]] + c.get("aliases", [])}]
        if not cands:
            return None
        matches = []
        for c in cands:
            hints = c.get("matchHints", {})
            evidence = []
            if p.profile_url and any(h in p.profile_url for h in hints.get("profileUrls", [])):
                evidence.append("same platform profile")
            if any(_domain(l) in {_domain(d) for d in hints.get("linkDomains", [])} for l in p.listed_links):
                evidence.append("venue listing links to the artist's official site")
            if venue_id and venue_id in hints.get("venues", []):
                evidence.append("plays this venue regularly")
            if municipality and municipality in hints.get("cities", []):
                evidence.append("based in this area")
            if evidence:
                matches.append((c, evidence))
        if len(matches) == 1:
            return {**matches[0][0], "_evidence": matches[0][1]}
        return None

    def resolve(self, p: Performer, *, event_id: str, venue_id: Optional[str], municipality: Optional[str],
                source_name: str, source_tier: int) -> str:
        cur = self._curated_match(p, venue_id, municipality)
        if cur:
            aid = cur["id"]
            rec = self.records.setdefault(aid, {
                "id": aid, "name": cur["name"], "identity": "verified",
                "disambiguation": cur.get("disambiguation"), "description": cur.get("description"),
                "genres": cur.get("genres", []), "hometown": cur.get("hometown"), "members": cur.get("members", []),
                "links": [{"type": link_type(u), "url": u, "provenance": "curated (checked by hand)"}
                          for u in cur.get("links", [])],
                "evidence": [],
            })
            for e in cur["_evidence"]:
                if e not in rec["evidence"]:
                    rec["evidence"].append(e)
            return aid
        pid = _profile_id(p.profile_url)
        listed = [l for l in p.listed_links if source_tier <= 2]
        if pid:
            aid = f"a-{slugify(p.name)}-{pid}"
            identity = "aggregator_linked"
        elif listed:
            aid = f"a-{slugify(p.name)}-{slugify(_domain(listed[0]))}"
            identity = "venue_linked"
        else:
            # Name only: scope the record to the venue so identically named acts elsewhere are never merged.
            aid = f"a-{slugify(p.name)}-u-{venue_id or 'nv'}"
            identity = "unverified"
        rec = self.records.get(aid)
        if not rec:
            rec = self.records[aid] = {
                "id": aid, "name": p.name, "identity": identity, "disambiguation": None, "description": None,
                "genres": [], "hometown": None, "members": [], "links": [], "evidence": [],
            }
        if listed and rec["identity"] in ("unverified", "aggregator_linked"):
            rec["identity"] = "venue_linked" if rec["identity"] == "unverified" else rec["identity"]
        rank = {"verified": 0, "venue_linked": 1, "aggregator_linked": 2, "unverified": 3}
        if pid and listed and rank[rec["identity"]] > rank["venue_linked"]:
            rec["identity"] = "venue_linked"
        for u in listed:
            if not any(l["url"] == u for l in rec["links"]):
                rec["links"].append({"type": link_type(u), "url": u, "provenance": f"listed next to this name by {source_name}"})
        if p.profile_url and pid and not any(l["url"] == p.profile_url for l in rec["links"]):
            rec["links"].append({"type": link_type(p.profile_url), "url": p.profile_url,
                                 "provenance": f"{p.profile_platform or 'platform'} profile attached to this event"})
        for g in p.genres:
            if g and g not in rec["genres"]:
                rec["genres"].append(g)
        ev_txt = {"aggregator_linked": f"{p.profile_platform} profile attached to a listing",
                  "venue_linked": f"links supplied by {source_name}",
                  "unverified": "name taken from an event listing only"}.get(identity)
        if ev_txt and ev_txt not in rec["evidence"]:
            rec["evidence"].append(ev_txt)
        return aid

    def export(self) -> list[dict]:
        return sorted(self.records.values(), key=lambda r: r["name"].lower())
