"""Generic schema.org Event / MusicEvent JSON-LD adapter (AllEvents, Songkick, Bandsintown, many venue sites)."""
from __future__ import annotations

import json
import logging
import re
from datetime import date, datetime
from typing import Any, Iterable, Optional
from urllib.parse import urlparse, urlunparse, parse_qsl, urlencode

from bs4 import BeautifulSoup

from ..artist_links import attach_listing_links
from ..models import Performer, RawEvent
from ..normalize import detect_status, html_to_text, parse_age, parse_price, performers_from_title, unescape_all

log = logging.getLogger(__name__)

EVENT_TYPES = {"Event", "MusicEvent", "ComedyEvent", "TheaterEvent", "Festival", "DanceEvent", "ExhibitionEvent",
               "SportsEvent", "ChildrensEvent", "SocialEvent", "EducationEvent", "FoodEvent", "LiteraryEvent",
               "ScreeningEvent", "VisualArtsEvent"}
TYPE_HINT = {"MusicEvent": "live_music", "ComedyEvent": "comedy", "TheaterEvent": "theatre", "Festival": "festival",
             "DanceEvent": "theatre", "ExhibitionEvent": "arts_culture", "SportsEvent": "sports",
             "ChildrensEvent": "family", "EducationEvent": "workshop", "FoodEvent": "community",
             "ScreeningEvent": "arts_culture", "VisualArtsEvent": "arts_culture"}


def _walk(obj: Any) -> Iterable[dict]:
    if isinstance(obj, list):
        for o in obj:
            yield from _walk(o)
    elif isinstance(obj, dict):
        if "@graph" in obj:
            yield from _walk(obj["@graph"])
        t = obj.get("@type")
        types = set(t if isinstance(t, list) else [t])
        if types & EVENT_TYPES:
            yield obj
        for k in ("itemListElement", "item", "subEvent", "events"):
            if k in obj:
                yield from _walk(obj[k])


def _strip_tracking(u: Optional[str]) -> Optional[str]:
    if not u:
        return u
    p = urlparse(u)
    q = [(k, v) for k, v in parse_qsl(p.query) if not k.startswith("utm_") and k not in ("came_from", "ref")]
    return urlunparse(p._replace(query=urlencode(q)))


def _parse_dt(s: Optional[str]):
    if not s:
        return None, None
    s = s.strip()
    try:
        if len(s) == 10:
            return date.fromisoformat(s), None
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
        if dt.tzinfo is not None:
            from zoneinfo import ZoneInfo
            dt = dt.astimezone(ZoneInfo("America/Regina"))
        return dt.date(), dt.time().replace(second=0, microsecond=0)
    except ValueError:
        m = re.match(r"(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})", s)
        if m:
            from datetime import time
            return date.fromisoformat(m.group(1)), time(int(m.group(2)), int(m.group(3)))
    return None, None


def _first(x):
    return x[0] if isinstance(x, list) and x else x


def _platform(url: Optional[str]) -> Optional[str]:
    if not url:
        return None
    h = urlparse(url).netloc.lower()
    for p in ("songkick", "bandsintown", "allevents", "eventbrite", "ticketmaster"):
        if p in h:
            return p
    return None


def parse_html(html: str, source: dict, page_url: str) -> list[RawEvent]:
    soup = BeautifulSoup(html, "lxml")
    out: list[RawEvent] = []
    for tag in soup.find_all("script", type="application/ld+json"):
        raw = tag.string or tag.get_text() or ""
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            try:
                data = json.loads(re.sub(r"[\x00-\x1f]", " ", raw))
            except json.JSONDecodeError:
                continue
        for ev in _walk(data):
            rev = _to_raw(ev, source, page_url)
            if rev:
                out.append(rev)
    return out


def _to_raw(ev: dict, source: dict, page_url: str) -> Optional[RawEvent]:
    name = (unescape_all(ev.get("name")) or "").strip()
    sd, st = _parse_dt(ev.get("startDate"))
    if not name or not sd:
        return None
    ed, et = _parse_dt(ev.get("endDate"))
    loc = _first(ev.get("location")) or {}
    if isinstance(loc, str):
        loc = {"name": loc}
    addr = loc.get("address") or {}
    if isinstance(addr, str):
        addr = {"streetAddress": addr}
    geo = loc.get("geo") or {}
    lat = lon = None
    try:
        lat = float(geo.get("latitude")) if geo.get("latitude") not in (None, "") else None
        lon = float(geo.get("longitude")) if geo.get("longitude") not in (None, "") else None
    except (TypeError, ValueError):
        pass
    venue_name = (unescape_all(loc.get("name")) or "").strip() or None
    # AllEvents puts the address into the place name: "Capitol Music Club, 244 1st Ave N, Saskatoon, Saskatchewan"
    if venue_name and "," in venue_name and re.search(r"\d", venue_name.split(",", 1)[1]):
        venue_name = venue_name.split(",", 1)[0].strip()
    address = ", ".join(x for x in [addr.get("streetAddress"), addr.get("addressLocality"), addr.get("addressRegion")] if x) or None
    url = _strip_tracking(ev.get("url")) or page_url
    # Title cleanup: "Artist @ Venue" (Songkick/Bandsintown)
    title = re.sub(r"\s+@\s+.+$", "", name) if venue_name and name.endswith(venue_name) else name
    perfs: list[Performer] = []
    raw_perf = ev.get("performer") or []
    if isinstance(raw_perf, dict):
        raw_perf = [raw_perf]
    plat = _platform(url)
    organizer = _first(ev.get("organizer")) or {}
    for p in raw_perf:
        if not isinstance(p, dict) or not p.get("name"):
            continue
        prof = _strip_tracking(p.get("sameAs") or p.get("url"))
        if not prof and plat == "bandsintown" and isinstance(organizer, dict) and organizer.get("name") == p.get("name"):
            prof = _strip_tracking(organizer.get("url"))
        g = p.get("genre") or []
        perfs.append(Performer(name=unescape_all(p["name"]).strip(), profile_url=prof, profile_platform=_platform(prof),
                               genres=g if isinstance(g, list) else [g]))
    if not perfs:
        perfs = [Performer(name=n) for n in performers_from_title(title)]
    offers = ev.get("offers") or []
    if isinstance(offers, dict):
        offers = [offers]
    prices, ticket = [], None
    for o in offers:
        if not isinstance(o, dict):
            continue
        for k in ("price", "lowPrice", "highPrice"):
            try:
                if o.get(k) not in (None, ""):
                    prices.append(float(o[k]))
            except (TypeError, ValueError):
                pass
        ticket = ticket or _strip_tracking(o.get("url"))
    if ev.get("description"):
        attach_listing_links(perfs, ev["description"], exclude_domains=[urlparse(url).netloc])
    desc = html_to_text(ev.get("description")) if ev.get("description") else None
    # Songkick/Bandsintown descriptions are boilerplate ("X at Y at <date>"); drop them.
    if desc and (re.match(rf"^{re.escape(title[:20])}.* at .*\d{{4}}-\d{{2}}-\d{{2}}", desc) or desc.strip() == title):
        desc = None
    status_url = str(ev.get("eventStatus") or "")
    status = "cancelled" if "Cancelled" in status_url else "postponed" if "Postponed" in status_url else \
        "rescheduled" if "Rescheduled" in status_url else detect_status(name)
    t = ev.get("@type")
    t = t[0] if isinstance(t, list) else t
    image = _first(ev.get("image"))
    if isinstance(image, dict):
        image = image.get("url")
    # AllEvents auto-generated text cards are not real artwork.
    if image and "dyn-image.allevents.in/generate-image" in image:
        image = None
    is_free = None
    pt = None
    if prices:
        pt = f"${min(prices):g}" + (f"–${max(prices):g}" if max(prices) != min(prices) else "")
        is_free = max(prices) == 0
    if ev.get("isAccessibleForFree") is True:
        is_free, pt = True, "Free"
    return RawEvent(
        source_id=source["id"],
        source_key=url,
        source_url=url,
        title=title,
        start_date=sd, start_time=st,
        end_date=ed if ed and ed != sd else None,
        end_time=et if ed and et else None,
        description=desc,
        venue_name=venue_name,
        venue_address=address,
        venue_locality=addr.get("addressLocality") or None,
        venue_lat=lat, venue_lon=lon,
        image_url=image,
        ticket_url=ticket if ticket and ticket != url else None,
        price_text=pt, price_min=min(prices) if prices else None, price_max=max(prices) if prices else None,
        is_free=is_free,
        age_text=parse_age(desc, name),
        performers=perfs,
        category_hint=TYPE_HINT.get(t),
        status=status,
    )


def fetch(source: dict, fetcher, today=None) -> list[RawEvent]:
    urls = [source["url"]] + list(source.get("options", {}).get("extraPages", []))
    out: list[RawEvent] = []
    errors = []
    for u in urls:
        try:
            out.extend(parse_html(fetcher.get(u).text, source, u))
        except Exception as e:
            errors.append(f"{u}: {e}")
            if u == source["url"]:
                raise
    for e in errors:
        log.warning("extra page failed: %s", e)
    return out
