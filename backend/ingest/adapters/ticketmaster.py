"""Ticketmaster Discovery API v2 (official; free key from developer.ticketmaster.com)."""
from __future__ import annotations

import os
from datetime import date, time

from ..models import Performer, RawEvent


class MissingApiKey(Exception):
    pass


def parse(data: dict, source: dict) -> list[RawEvent]:
    out = []
    for ev in (data.get("_embedded") or {}).get("events", []):
        dates = (ev.get("dates") or {}).get("start") or {}
        if not dates.get("localDate"):
            continue
        sd = date.fromisoformat(dates["localDate"])
        st = None
        if dates.get("localTime") and not dates.get("timeTBA"):
            h, m, *_ = dates["localTime"].split(":")
            st = time(int(h), int(m))
        venue = ((ev.get("_embedded") or {}).get("venues") or [{}])[0]
        loc = venue.get("location") or {}
        prices = ev.get("priceRanges") or []
        pmin = min((p.get("min") for p in prices if p.get("min") is not None), default=None)
        pmax = max((p.get("max") for p in prices if p.get("max") is not None), default=None)
        imgs = sorted(ev.get("images") or [], key=lambda i: i.get("width", 0))
        img = next((i["url"] for i in imgs if i.get("width", 0) >= 300), imgs[-1]["url"] if imgs else None)
        cls = (ev.get("classifications") or [{}])[0]
        seg = ((cls.get("segment") or {}).get("name") or "").lower()
        hint = {"music": "live_music", "sports": "sports", "arts & theatre": "theatre", "family": "family"}.get(seg)
        if hint == "theatre" and "comedy" in ((cls.get("genre") or {}).get("name") or "").lower():
            hint = "comedy"
        status_code = ((ev.get("dates") or {}).get("status") or {}).get("code", "")
        status = {"cancelled": "cancelled", "postponed": "postponed", "rescheduled": "rescheduled"}.get(status_code, "scheduled")
        perfs = []
        for a in (ev.get("_embedded") or {}).get("attractions", []) or []:
            perfs.append(Performer(name=a.get("name", "").strip(), profile_url=a.get("url"), profile_platform="ticketmaster"))
        out.append(RawEvent(
            source_id=source["id"], source_key=ev.get("id"), source_url=ev.get("url") or source["url"],
            title=ev.get("name", "").strip(), start_date=sd, start_time=st,
            description=ev.get("info") or ev.get("pleaseNote"),
            venue_name=venue.get("name"),
            venue_address=", ".join(x for x in [(venue.get("address") or {}).get("line1"), (venue.get("city") or {}).get("name")] if x) or None,
            venue_locality=(venue.get("city") or {}).get("name"),
            venue_lat=float(loc["latitude"]) if loc.get("latitude") else None,
            venue_lon=float(loc["longitude"]) if loc.get("longitude") else None,
            image_url=img, ticket_url=ev.get("url"),
            price_text=(f"${pmin:g}–${pmax:g}" if pmin is not None and pmax is not None and pmax != pmin else (f"${pmin:g}" if pmin is not None else None)),
            price_min=pmin, price_max=pmax, is_free=(pmax == 0) if pmax is not None else None,
            age_text=None, performers=[p for p in perfs if p.name], category_hint=hint, status=status,
        ))
    return out


def fetch(source: dict, fetcher, today=None) -> list[RawEvent]:
    opts = source.get("options", {})
    key = os.environ.get(opts.get("apiKeyEnv", "TICKETMASTER_API_KEY"), "").strip()
    if not key:
        raise MissingApiKey("TICKETMASTER_API_KEY not set")
    out, page = [], 0
    while page < 5:
        r = fetcher.get(source["url"], params={
            "apikey": key, "latlong": opts["latlong"], "radius": opts.get("radiusKm", 50), "unit": "km",
            "size": 100, "page": page, "sort": "date,asc", "countryCode": "CA",
        }, check_robots=False)  # official API: governed by its terms, not robots.txt
        data = r.json()
        out.extend(parse(data, source))
        info = data.get("page") or {}
        if page + 1 >= int(info.get("totalPages") or 0):
            break
        page += 1
    return out
