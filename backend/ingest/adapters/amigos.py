"""Amigos Cantina official events page (WordPress, custom markup).

Listing: ul.event-list > li.event-individual with .event-name a, .event-date ("Saturday, November 21, 2026 at 10pm"),
.event-photo img, optional a.event-rsvp (Facebook event) / a.event-tickets.
Detail page (/events/<slug>/): .event-description, a.event-tickets.
"""
from __future__ import annotations

import logging
import re
from datetime import date, datetime, timedelta
from typing import Optional

from bs4 import BeautifulSoup

from ..artist_links import attach_listing_links
from ..models import Performer, RawEvent
from ..normalize import detect_status, html_to_text, parse_age, parse_clock, parse_doors, performers_from_title, find_price_in_text, parse_price

log = logging.getLogger(__name__)

_DATE_RE = re.compile(r"([A-Za-z]+),?\s+([A-Za-z]+)\s+(\d{1,2}),\s+(\d{4})(?:\s+at\s+(.+))?")


def parse_date_line(s: str) -> tuple[Optional[date], Optional[object]]:
    m = _DATE_RE.search(s.strip())
    if not m:
        return None, None
    try:
        d = datetime.strptime(f"{m.group(2)} {m.group(3)} {m.group(4)}", "%B %d %Y").date()
    except ValueError:
        try:
            d = datetime.strptime(f"{m.group(2)} {m.group(3)} {m.group(4)}", "%b %d %Y").date()
        except ValueError:
            return None, None
    t = parse_clock(m.group(5)) if m.group(5) else None
    return d, t


def parse_listing(html: str, source: dict) -> list[RawEvent]:
    soup = BeautifulSoup(html, "lxml")
    out: list[RawEvent] = []
    for li in soup.select("li.event-individual"):
        a = li.select_one(".event-name a")
        dl = li.select_one(".event-date")
        if not a or not dl:
            continue
        title = a.get_text(" ", strip=True)
        d, t = parse_date_line(dl.get_text(" ", strip=True))
        if not d:
            continue
        url = a.get("href")
        img = li.select_one(".event-photo img")
        links = []
        rsvp = li.select_one("a.event-rsvp")
        if rsvp and rsvp.get("href"):
            links.append({"label": "RSVP (Facebook event)", "url": rsvp["href"]})
        tickets = li.select_one("a.event-tickets")
        ev = RawEvent(
            source_id=source["id"],
            source_key=(url or title).rstrip("/").rsplit("/", 1)[-1],
            source_url=url or source["url"],
            title=title,
            start_date=d,
            start_time=t,
            venue_id=source.get("venueId"),
            image_url=img.get("src") if img else None,
            ticket_url=tickets.get("href") if tickets else None,
            links=links,
            status=detect_status(title),
        )
        ev.performers = [Performer(name=n) for n in performers_from_title(title)]
        out.append(ev)
    return out


def apply_detail(ev: RawEvent, html: str) -> None:
    soup = BeautifulSoup(html, "lxml")
    desc_el = soup.select_one(".single-event .event-description") or soup.select_one(".event-description")
    if desc_el:
        ev.description = html_to_text(str(desc_el))
        attach_listing_links(ev.performers, str(desc_el), exclude_domains=["amigoscantina.com"])
    t = soup.select_one(".single-event a.event-tickets") or soup.select_one("a.event-tickets")
    if t and t.get("href"):
        ev.ticket_url = t["href"]
    r = soup.select_one(".single-event a.event-rsvp")
    if r and r.get("href") and not any(l["url"] == r["href"] for l in ev.links):
        ev.links.append({"label": "RSVP (Facebook event)", "url": r["href"]})
    img = soup.select_one(".single-event .event-photo img")
    if img and img.get("src"):
        ev.image_url = img["src"]
    ev.age_text = parse_age(ev.description)
    ev.doors_time = parse_doors(ev.description)
    pt = find_price_in_text(ev.description)
    if pt:
        ev.price_text, ev.price_min, ev.price_max, ev.is_free = parse_price(pt)
    ev.status = detect_status(ev.title, (ev.description or "")[:200])


def fetch(source: dict, fetcher, today=None) -> list[RawEvent]:
    r = fetcher.get(source["url"])
    events = parse_listing(r.text, source)
    opts = source.get("options", {})
    if opts.get("fetchDetails"):
        horizon = (today or date.today()) + timedelta(days=int(opts.get("detailDaysAhead", 60)))
        for ev in events:
            if ev.start_date > horizon or not ev.source_url.startswith("http"):
                continue
            try:
                apply_detail(ev, fetcher.get(ev.source_url).text)
            except Exception as e:  # detail page failures must not lose the listing
                log.warning("amigos detail failed %s: %s", ev.source_url, e)
    return events
