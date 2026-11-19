"""Ticketing platform used by Broadway Theatre (broadwaytheatre.ca/events) and Amigos (tickets.amigoscantina.com/events).

Each upcoming event is a div.event card: poster img, h3 title, h5 "Friday, November 20, 2026<br>8:00 PM / Venue",
description, link 'events?p=event&event=<id>', and price labels in the ticket form.
"""
from __future__ import annotations

import re
from datetime import datetime
from urllib.parse import parse_qs, urljoin, urlparse

from bs4 import BeautifulSoup

from ..models import Performer, RawEvent
from ..normalize import detect_status, html_to_text, parse_age, parse_clock, parse_doors, performers_from_title


def parse_listing(html: str, source: dict) -> list[RawEvent]:
    soup = BeautifulSoup(html, "lxml")
    out: list[RawEvent] = []
    seen = set()
    for card in soup.select("div.event"):
        h3 = card.select_one("h3")
        h5 = card.select_one("h5")
        if not h3 or not h5:
            continue
        title = h3.get_text(" ", strip=True)
        sold_out = bool(re.search(r"\bSOLD OUT\b", title))
        title = re.sub(r"\s*\bSOLD OUT\b\s*", " ", title).strip()
        lines = [s.strip() for s in h5.get_text("\n", strip=True).split("\n") if s.strip()]
        try:
            d = datetime.strptime(re.sub(r"^\w+,\s*", "", lines[0]), "%B %d, %Y").date()
        except (ValueError, IndexError):
            continue
        t, venue_name = None, None
        if len(lines) > 1:
            parts = lines[1].split("/", 1)
            t = parse_clock(parts[0])
            venue_name = parts[1].strip() if len(parts) > 1 else None
        a = card.select_one("a[href*='p=event']")
        href = urljoin(source["url"], a["href"]) if a else source["url"]
        ev_id = parse_qs(urlparse(href).query).get("event", [href])[0]
        if ev_id in seen:
            continue
        seen.add(ev_id)
        desc_el = h5.find_next_sibling("div")
        if desc_el:
            for b in desc_el.select("a.button"):
                b.decompose()
        desc = html_to_text(str(desc_el)) if desc_el else None
        prices = []
        for lab in card.select("span.label"):
            m = re.search(r"\$\s?(\d+(?:\.\d{2})?)", lab.get_text())
            if m:
                prices.append(float(m.group(1)))
        img = card.select_one("img")
        default_venue = source.get("options", {}).get("defaultVenueId")
        # The card names the venue ("8:00 PM / Black Cat Tavern"); only fall back to the site's venue when absent.
        venue_id = default_venue if not venue_name else None
        ev = RawEvent(
            source_id=source["id"],
            source_key=str(ev_id),
            source_url=href,
            title=title,
            start_date=d,
            start_time=t,
            description=desc,
            venue_id=venue_id,
            venue_name=venue_name,
            image_url=img.get("src") if img else None,
            ticket_url=href,
            price_text=(f"From ${min(prices):g}" if prices else None),
            price_min=min(prices) if prices else None,
            price_max=max(prices) if prices else None,
            is_free=False if prices else None,
            age_text=parse_age(desc, title),
            doors_time=parse_doors(desc),
            status=detect_status(title),
            tags=["sold_out"] if sold_out else [],
        )
        ev.performers = [Performer(name=n) for n in performers_from_title(title)]
        out.append(ev)
    return out


def fetch(source: dict, fetcher) -> list[RawEvent]:
    return parse_listing(fetcher.get(source["url"]).text, source)
