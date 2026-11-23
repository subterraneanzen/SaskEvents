"""Elfsight Event Calendar widget (used by Black Cat Tavern).

The venue's page loads https://core.service.elfsight.com/p/boot/?page=<page>&w=<widgetId>, a public JSON document
containing settings.events[] and settings.locations[]. We read that same JSON instead of rendering the page.
"""
from __future__ import annotations

import re
from datetime import date, datetime, timedelta
from typing import Optional

from bs4 import BeautifulSoup

from ..models import Performer, RawEvent
from ..normalize import (detect_status, html_to_text, norm_name, parse_age, parse_clock, parse_doors, parse_price,
                         performers_from_title)

BOOT_URL = "https://core.service.elfsight.com/p/boot/"
TICKET_HOSTS = re.compile(r"show\.ps|showpass|bit\.ly|eventbrite|ticketmaster|broadwaytheatre\.ca|tickets\.|universe\.com|ticketrocket", re.I)
_URLISH = re.compile(r"^(?:https?://)?(?:www\.)?[\w-]+(?:\.[\w-]+)+(?:/\S*)?$", re.I)
_NAME_DASH_URL = re.compile(r"^(.{2,60}?)\s+[-–]\s+((?:https?://)?(?:www\.)?[\w-]+(?:\.[\w-]+)+/?\S*)$")


def _norm_url(u: str) -> str:
    u = u.strip()
    return u if u.startswith("http") else "https://" + u


def listed_performer_links(description_html: str) -> list[Performer]:
    """Find 'Band Name' immediately followed by a link line, or 'Band Name - link' lines.

    Only links the venue itself placed next to a name are attributed, and ticket links are ignored.
    """
    if not description_html:
        return []
    soup = BeautifulSoup(description_html.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n"), "lxml")
    lines: list[tuple[str, Optional[str]]] = []
    for node in soup.find_all(["div", "p"]) or [soup]:
        if node.find(["div", "p"]):
            continue
        # Build lines, remembering anchors.
        buf, href = "", None
        for child in node.children:
            text = child.get_text() if hasattr(child, "get_text") else str(child)
            if getattr(child, "name", None) == "a":
                href = child.get("href")
            parts = text.split("\n")
            for i, p in enumerate(parts):
                buf += p
                if i < len(parts) - 1:
                    lines.append((buf.strip(), href))
                    buf, href = "", None
        lines.append((buf.strip(), href))
    lines = [(t.replace("\xa0", " ").strip(), h) for t, h in lines]
    out: list[Performer] = []
    prev_text: Optional[str] = None
    for text, href in lines:
        if not text:
            continue
        m = _NAME_DASH_URL.match(text)
        if m and not TICKET_HOSTS.search(m.group(2)):
            out.append(Performer(name=m.group(1).strip(), listed_links=[_norm_url(m.group(2))]))
            prev_text = None
            continue
        is_link_line = bool(href) and (_URLISH.match(text) or text == href)
        if is_link_line:
            if prev_text and not TICKET_HOSTS.search(href) and 1 < len(prev_text) <= 60 and ":" not in prev_text:
                out.append(Performer(name=prev_text, listed_links=[_norm_url(href)]))
            prev_text = None
            continue
        prev_text = text
    return out


def _occurrences(ev: dict, today: date, horizon_days: int = 120) -> list[date]:
    start = date.fromisoformat(ev["start"]["date"])
    period = ev.get("repeatPeriod") or "noRepeat"
    if period == "noRepeat":
        return [start] if start >= today - timedelta(days=1) else []
    interval = max(1, int(ev.get("repeatInterval") or 1))
    step = {"daily": timedelta(days=interval), "weekly": timedelta(weeks=interval)}.get(ev.get("repeatFrequency") or "", None)
    if period in ("weekly",):
        step = timedelta(weeks=interval)
    if step is None:
        return [start]
    exceptions = {e if isinstance(e, str) else e.get("date") for e in ev.get("exceptions") or []}
    end = today + timedelta(days=horizon_days)
    if ev.get("repeatEnds") == "onDate" and ev.get("repeatEndsDate"):
        end = min(end, date.fromisoformat(ev["repeatEndsDate"][:10]))
    out, d, n = [], start, 0
    max_n = int(ev.get("repeatEndsOccurrences") or 10**6) if ev.get("repeatEnds") == "after" else 10**6
    while d <= end and n < max_n:
        if d >= today - timedelta(days=1) and d.isoformat() not in exceptions:
            out.append(d)
        d += step
        n += 1
    return out


def parse_boot(data: dict, source: dict, today: Optional[date] = None) -> list[RawEvent]:
    today = today or date.today()
    opts = source.get("options", {})
    widget = data["data"]["widgets"][opts["widgetId"]]["data"]["settings"]
    loc_by_id = {l["id"]: l for l in widget.get("locations", [])}
    loc_map = opts.get("locationVenueMap", {})
    out: list[RawEvent] = []
    for ev in widget.get("events", []):
        try:
            occ = _occurrences(ev, today)
        except (KeyError, ValueError):
            continue
        desc_html = ev.get("description") or ""
        desc = html_to_text(desc_html)
        title = (ev.get("name") or "").strip()
        title = re.sub(r"\s+-\s+Saskatoon,?\s*SK$", "", title, flags=re.I)
        venue_id = source.get("venueId")
        venue_name = None
        for lid in ev.get("location") or []:
            loc = loc_by_id.get(lid)
            if loc:
                venue_id = loc_map.get(loc.get("filterID"), None)
                venue_name = loc.get("name")
                break
        ticket_url, price_text = None, None
        for act in ev.get("actions") or []:
            link = ((act.get("link") or {}).get("value") or "").strip()
            label = (act.get("text") or "").strip()
            if link:
                ticket_url = ticket_url or link
            elif label:
                price_text = label.title()
        start_t = parse_clock(ev["start"].get("time") or "") if ev["start"].get("type") == "datetime" else None
        listed = listed_performer_links(desc_html)
        from_title = []
        listed_keys = {norm_name(p.name) for p in listed}
        for n in performers_from_title(title):
            # "Checkout Queens & Slutty Waist": split only when one side is a name the venue listed separately.
            parts = re.split(r"\s+(?:&|and)\s+", n)
            if len(parts) == 2 and any(norm_name(x) in listed_keys for x in parts):
                from_title.extend(parts)
            else:
                from_title.append(n)
        performers: list[Performer] = []
        seen = set()
        for p in listed + [Performer(name=n) for n in from_title]:
            k = norm_name(p.name)
            if k in seen or not k:
                continue
            seen.add(k)
            performers.append(p)
        image = (ev.get("coverImage") or {}).get("url")
        video = ev.get("video") or {}
        links = []
        if video.get("type") == "youtube" and video.get("id"):
            links.append({"label": "Video (from venue listing)", "url": f"https://www.youtube.com/watch?v={video['id']}"})
        pt, pmin, pmax, free = parse_price(price_text)
        for d in occ:
            out.append(RawEvent(
                source_id=source["id"],
                source_key=f"{ev.get('id')}:{d.isoformat()}" if len(occ) > 1 else str(ev.get("id")),
                source_url=source["url"],
                title=title,
                start_date=d,
                start_time=start_t,
                all_day=bool(ev.get("isAllDay")),
                description=desc,
                venue_id=venue_id,
                venue_name=venue_name,
                image_url=image,
                ticket_url=ticket_url,
                price_text=pt, price_min=pmin, price_max=pmax, is_free=free,
                age_text=parse_age(desc, title),
                doors_time=parse_doors(desc),
                performers=performers,
                links=links,
                status=detect_status(title),
            ))
    return out


def fetch(source: dict, fetcher, today=None) -> list[RawEvent]:
    opts = source["options"]
    # The boot endpoint is the widget's public data API; robots.txt of the venue site governs the page, and
    # core.service.elfsight.com is checked by the fetcher like any other host.
    r = fetcher.get(BOOT_URL, params={"page": opts["pageUrl"], "w": opts["widgetId"]})
    return parse_boot(r.json(), source, today=today)
