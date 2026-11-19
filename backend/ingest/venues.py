"""Venue registry: curated venues + provisional venues discovered from structured event data."""
from __future__ import annotations

import logging
import math
import re
import time
from typing import Optional

from .models import RawEvent
from .normalize import norm_name, norm_tokens, slugify

log = logging.getLogger(__name__)


def haversine_km(a_lat, a_lon, b_lat, b_lon) -> float:
    r = 6371.0
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    dphi = math.radians(b_lat - a_lat)
    dl = math.radians(b_lon - a_lon)
    h = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


_ORD = {"first": "1", "second": "2", "third": "3", "fourth": "4", "fifth": "5"}


def address_key(addr: Optional[str]) -> Optional[str]:
    """'244 1st Ave N, Saskatoon' and '244 1st Ave North' -> '244|1|ave'. None if no civic number."""
    if not addr:
        return None
    m = re.match(r"\s*(?:\w+-)?(\d{1,5})\s+([A-Za-z0-9]+)\s*([A-Za-z]+)?", addr)
    if not m:
        return None
    street = m.group(2).lower()
    street = _ORD.get(street, re.sub(r"(st|nd|rd|th)$", "", street) if re.match(r"\d", street) else street)
    kind = (m.group(3) or "").lower()[:2]
    kind = {"av": "av", "st": "st", "dr": "dr", "cr": "cr", "bl": "bl", "rd": "rd"}.get(kind, kind)
    return f"{m.group(1)}|{street}|{kind}"


class VenueRegistry:
    def __init__(self, config: dict, previous_venues: Optional[list[dict]] = None):
        self.city = config["city"]
        self.venues: dict[str, dict] = {}
        self.closed = config.get("closedOrRenamed", [])
        for v in config["venues"]:
            v = dict(v)
            v["verified"] = True
            v["provisional"] = False
            self.venues[v["id"]] = v
        self._index: dict[str, str] = {}
        self._addr: dict[str, str] = {}
        for vid, v in self.venues.items():
            k = address_key(v.get("address"))
            if k:
                self._addr[k] = vid
        for vid, v in self.venues.items():
            for n in [v["name"]] + v.get("aliases", []):
                self._index[norm_name(n)] = vid
        # Carry forward geocodes from the previous run so we do not re-query.
        self.prev = {v["id"]: v for v in (previous_venues or [])}
        for vid, v in self.venues.items():
            pv = self.prev.get(vid)
            if v.get("lat") is None and pv and pv.get("lat") is not None and pv.get("coordSource"):
                v["lat"], v["lon"], v["coordSource"] = pv["lat"], pv["lon"], pv["coordSource"]
        self.used: set[str] = set()

    def closed_match(self, name: Optional[str]) -> Optional[dict]:
        if not name:
            return None
        n = norm_name(name)
        for c in self.closed:
            if norm_name(c["name"]) == n:
                return c
        return None

    def resolve(self, ev: RawEvent) -> Optional[str]:
        if ev.venue_id and ev.venue_id in self.venues:
            self._absorb_geo(ev.venue_id, ev)
            return ev.venue_id
        name = ev.venue_name
        if name:
            n = norm_name(name)
            vid = self._index.get(n)
            if not vid:
                # prefix match e.g. "Louis' Pub University of Saskatchewan" -> "louispub..."
                for key, cand in self._index.items():
                    if len(key) >= 6 and (n.startswith(key) or key.startswith(n) and len(n) >= 6):
                        vid = cand
                        break
            if vid:
                self._absorb_geo(vid, ev)
                return vid
        # Street-address match ("244 1st Ave North" -> Capitol Music Club).
        for cand in (ev.venue_address, name):
            k = address_key(cand)
            if k and k in self._addr:
                self._absorb_geo(self._addr[k], ev)
                return self._addr[k]
        # Geo proximity with a shared name token.
        if ev.venue_lat is not None and name:
            toks = norm_tokens(name)
            for vid, v in self.venues.items():
                if v.get("lat") is None or v.get("provisional"):
                    continue
                if haversine_km(ev.venue_lat, ev.venue_lon, v["lat"], v["lon"]) < 0.08 and toks & norm_tokens(v["name"]):
                    return vid
        if not name:
            return None
        closed = self.closed_match(name)
        return self._provisional(ev, closed)

    def _absorb_geo(self, vid: str, ev: RawEvent):
        v = self.venues[vid]
        if v.get("lat") is None and ev.venue_lat is not None and v.get("provisional") is False:
            v["lat"], v["lon"] = ev.venue_lat, ev.venue_lon
            v["coordSource"] = f"event listing ({ev.source_id})"

    def _provisional(self, ev: RawEvent, closed: Optional[dict]) -> str:
        locality = ev.venue_locality or None
        vid = "p-" + slugify(ev.venue_name + "-" + (locality or ""))
        if vid not in self.venues:
            self.venues[vid] = {
                "id": vid, "name": ev.venue_name, "aliases": [], "address": ev.venue_address,
                "municipality": locality, "neighbourhood": None,
                "lat": ev.venue_lat, "lon": ev.venue_lon,
                "coordSource": f"event listing ({ev.source_id})" if ev.venue_lat is not None else None,
                "website": None, "eventsUrl": None, "facebook": None, "instagram": None,
                "type": None, "hostsLiveMusic": None, "indoor": None, "typicalAge": None,
                "notes": None, "status": "closed" if closed else "unknown",
                "verificationNotes": (closed["note"] if closed else
                                      f"Discovered automatically from {ev.source_id}; not yet reviewed."),
                "evidence": [ev.source_url], "verified": False, "provisional": True,
                "lastVerified": None,
            }
            self._index.setdefault(norm_name(ev.venue_name), vid)
        else:
            v = self.venues[vid]
            if v.get("lat") is None and ev.venue_lat is not None:
                v["lat"], v["lon"], v["coordSource"] = ev.venue_lat, ev.venue_lon, f"event listing ({ev.source_id})"
            if not v.get("address") and ev.venue_address:
                v["address"] = ev.venue_address
        return vid

    def geocode_missing(self, fetcher, limit: int = 20):
        """Fill missing coordinates for venues that have a street address via OSM Nominatim (1 req/s, cached)."""
        done = 0
        for vid in sorted(self.used):
            v = self.venues.get(vid)
            if not v or v.get("lat") is not None or not v.get("address") or done >= limit:
                continue
            q = v["address"]
            if "saskatoon" not in q.lower() and v.get("municipality"):
                q += f", {v['municipality']}"
            if " sk" not in q.lower() and "saskatchewan" not in q.lower():
                q += ", SK"
            q += ", Canada"
            try:
                r = fetcher.get("https://nominatim.openstreetmap.org/search",
                                params={"q": q, "format": "jsonv2", "limit": 1, "countrycodes": "ca"},
                                check_robots=False)  # Nominatim usage policy: identified UA, <=1 req/s, cached
                res = r.json()
                done += 1
                if res:
                    v["lat"], v["lon"] = float(res[0]["lat"]), float(res[0]["lon"])
                    v["coordSource"] = "nominatim (OpenStreetMap) geocode of address"
                time.sleep(1.1)
            except Exception as e:
                log.warning("geocode failed for %s: %s", vid, e)

    def export(self) -> list[dict]:
        out = []
        for vid, v in self.venues.items():
            if v.get("provisional") and vid not in self.used:
                continue
            out.append({k: v.get(k) for k in [
                "id", "name", "aliases", "address", "municipality", "neighbourhood", "lat", "lon", "coordSource",
                "website", "eventsUrl", "facebook", "instagram", "type", "hostsLiveMusic", "indoor", "typicalAge",
                "notes", "status", "verified", "provisional", "verificationNotes", "evidence", "lastVerified"]})
        return sorted(out, key=lambda x: (x["provisional"], x["name"].lower()))
