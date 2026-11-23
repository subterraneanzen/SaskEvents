"""Jump Comedy / Jump Boulevard ticketing widget (used by Art Bar).

artbartheatre.com (GoDaddy Website Builder) embeds <event-list widget-id="N"> web components from
webcomponents.jumpblvd.com. Each one loads https://amplify.jumpcomedy.com/store/widgets/<N>, a public JSON document
with data.events[]: one product per show, with variants[] = individual showtimes (local start time, prices in cents,
stock) and the venue record. We read that same JSON instead of rendering the page. robots.txt on
amplify.jumpcomedy.com only disallows /e/mau1/.
"""
from __future__ import annotations

import re
from datetime import date, datetime
from typing import Optional

from ..models import RawEvent
from ..normalize import detect_status, unescape_all

WIDGET_URL = "https://amplify.jumpcomedy.com/store/widgets/{id}"
EVENT_PAGE = "https://{domain}/e/{handle}"

# Art Bar titles end in an upper-case label: "Hassan Phills - COMEDY", "Edges - MUSICAL SONG CYCLE".
_LABEL = re.compile(r"\s*[-–]\s*([A-Z][A-Z '&]+[A-Z])\s*$")
_LABEL_HINT = [
    (re.compile(r"COMEDY"), "comedy"),
    (re.compile(r"DRAG"), "theatre"),
    (re.compile(r"MUSICAL|THEATRE|THEATER|PLAY"), "theatre"),
    (re.compile(r"POETRY|STORY|SPOKEN"), "arts_culture"),
    (re.compile(r"MUSIC|CONCERT"), "live_music"),
]
# Generic labels that add nothing once removed; descriptive ones ("INTERGALACTIC SPACE DRAG") stay in the title.
_STRIP_LABELS = {"COMEDY", "MUSICAL COMEDY", "DRAG SHOW", "MUSIC", "THEATRE", "MUSICAL SONG CYCLE"}


def _split_label(title: str) -> tuple[str, Optional[str]]:
    m = _LABEL.search(title)
    if not m:
        return title, None
    label = m.group(1).strip()
    hint = next((h for rx, h in _LABEL_HINT if rx.search(label)), None)
    if label in _STRIP_LABELS:
        title = title[:m.start()].strip()
    return title, hint


def _ends_on(price: dict) -> Optional[date]:
    ends = (price.get("price_list") or {}).get("ends_at")
    if not ends:
        return None
    try:
        return datetime.fromisoformat(ends.replace("Z", "+00:00")).date()
    except ValueError:
        return None


def _prices(variant: dict, today: date) -> list[float]:
    out = []
    for p in variant.get("prices") or []:
        if (p.get("currency_code") or "cad").lower() != "cad" or p.get("amount") is None:
            continue
        ends = _ends_on(p)
        if ends is not None and ends < today:
            continue  # expired early-bird / override price
        out.append(p["amount"] / 100)
    return out


def _fmt(x: float) -> str:
    return f"${x:g}" if x != int(x) else f"${int(x)}"


def parse_widget(data: dict, source: dict, today: Optional[date] = None) -> list[RawEvent]:
    today = today or date.today()
    domain = (data.get("data", {}).get("config", {}).get("domain") or "www.jumpcomedy.com").replace("https://", "")
    opts = source.get("options", {})
    age_policy = opts.get("venueAgePolicy")
    out: list[RawEvent] = []
    for ev in data.get("data", {}).get("events") or []:
        if ev.get("status") not in (None, "published"):
            continue
        raw_title = unescape_all(ev.get("title") or "").strip()
        if not raw_title:
            continue
        title, hint = _split_label(raw_title)
        venue = (ev.get("venueArrangement") or {}).get("venue") or {}
        if venue.get("is_virtual"):
            continue
        page = EVENT_PAGE.format(domain=domain, handle=ev.get("handle")) if ev.get("handle") else source["url"]
        city = (venue.get("city") or {}).get("name")
        variants = [v for v in ev.get("variants") or [] if v.get("display_showtime", True)]
        for v in variants:
            start = v.get("starts_at") or v.get("startsAt")
            if not start:
                continue
            try:
                dt = datetime.fromisoformat(start[:16])
            except ValueError:
                continue
            if dt.date() < today:
                continue
            prices = _prices(v, today)
            pt = None
            if prices:
                lo, hi = min(prices), max(prices)
                pt = _fmt(lo) + (f"–{_fmt(hi)}" if hi != lo else "")
            tags = ["sold_out"] if v.get("is_out_of_stock") else []
            out.append(RawEvent(
                source_id=source["id"],
                source_key=str(v.get("id") or f"{ev.get('id')}:{start}"),
                source_url=page,
                title=title,
                start_date=dt.date(),
                start_time=dt.time(),
                venue_id=source.get("venueId"),
                venue_name=venue.get("name"),
                venue_address=", ".join(x for x in [venue.get("address"), city, venue.get("province")] if x) or None,
                venue_locality=city,
                image_url=v.get("thumbnail") or ev.get("thumbnail"),
                ticket_url=ev.get("external_order_url") or page,  # Jump page sells the tickets unless an external seller is named
                price_text=pt, price_min=min(prices) if prices else None, price_max=max(prices) if prices else None,
                is_free=(max(prices) == 0) if prices else None,
                age_text=age_policy,
                category_hint=hint,
                tags=tags,
                status=detect_status(raw_title),
            ))
    return out


def fetch(source: dict, fetcher, today=None) -> list[RawEvent]:
    seen: set[str] = set()
    out: list[RawEvent] = []
    for wid in source.get("options", {}).get("widgetIds", []):
        r = fetcher.get(WIDGET_URL.format(id=wid), params={"draft_mode": "false"})
        for e in parse_widget(r.json(), source, today=today):
            if e.source_key not in seen:  # the venue's two widgets list the same shows
                seen.add(e.source_key)
                out.append(e)
    return out
