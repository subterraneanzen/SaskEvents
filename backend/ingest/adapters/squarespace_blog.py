"""Event round-up blog posts on Squarespace sites (Sneakers & Lipstick, sneakersandlipstick.com).

The site has no events calendar or feed: events are written up in blog posts, chiefly the weekly
"Things to do in Saskatoon: Your Weekend Event Guide". A post body is a run of blocks:

    <h2>Friday, November 21</h2>                 <- optional day header (sets the date for what follows)
    <h3>Festival of Trees</h3>                   <- an event title (heading, or a paragraph that is all bold)
    <p>Nov 21-23 | 10am-8pm | TCU Place ...</p>  <- details: dates, times, location, cost, links
    <p><a href="...">Tickets</a></p>

The parser is deliberately conservative: an entry becomes an event only when a date can be read from the entry
itself or from the day header above it (weekday names are resolved against the post's own date). Nothing is
guessed. The source is tier 4 (a blog), so it never confirms an event on its own.

Options (sources.json):
  extraPages   other post URLs to read every run
  listingUrl   a category/blog page; the newest `maxPosts` post links on it are read as well
  postMatch    regex a post URL must match to be followed from listingUrl (default: weekend/things-to-do/events)
"""
from __future__ import annotations

import json
import logging
import re
from datetime import date, datetime, time, timedelta
from typing import Optional
from urllib.parse import urljoin, urlparse

from bs4 import BeautifulSoup, NavigableString, Tag

from ..models import RawEvent
from ..normalize import detect_status, parse_age, parse_clock, parse_price, slugify, unescape_all

log = logging.getLogger(__name__)

MONTHS = {m: i for i, m in enumerate(
    ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"], start=1)}
WEEKDAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
_MON = r"(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?"
_WD = r"(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)(?:day|nesday|sday|urday|rsday)?\.?"
# "November 21", "Nov. 21st", optional range "– 23" / "- Nov 23" / "& 22" / "to December 2"
_DATE_RE = re.compile(
    rf"\b{_MON}\s+(\d{{1,2}})(?:st|nd|rd|th)?(?:,?\s*(20\d\d))?"
    rf"(?:\s*(?:-|–|—|to|through|thru|&|and)\s*(?:{_MON}\s+)?(\d{{1,2}})(?:st|nd|rd|th)?(?:,?\s*(20\d\d))?)?",
    re.I)
_WD_RE = re.compile(rf"\b{_WD}(?=\W|$)", re.I)
_TIME_RANGE_RE = re.compile(
    r"\b(\d{1,2}(?::\d{2})?\s*(?:[ap]\.?m\.?)?)\s*(?:-|–|—|to)\s*(\d{1,2}(?::\d{2})?\s*[ap]\.?m\.?)", re.I)
_TIME_RE = re.compile(r"\b(\d{1,2}(?::\d{2})?\s*[ap]\.?m\.?)", re.I)
_LABEL_RE = re.compile(r"^\s*(where|location|venue|place|address|cost|price|admission|tickets?|when|date|time|hours)\s*:\s*(.+)$", re.I)
_DEFAULT_POST_MATCH = r"weekend|things-to-do|events?"


def _month(s: str) -> int:
    return MONTHS[s.lower()[:3]]


def _closest_year(month: int, day: int, ref: date, year: Optional[str]) -> Optional[date]:
    try:
        if year:
            return date(int(year), month, day)
        cands = [date(ref.year + k, month, day) for k in (-1, 0, 1)]
    except ValueError:
        return None
    # Round-ups describe what's coming: prefer the nearest date not long before the post.
    return min(cands, key=lambda d: abs((d - ref).days) if d >= ref - timedelta(days=45) else 10_000)


def _dates_in(text: str, ref: date) -> tuple[Optional[date], Optional[date]]:
    m = _DATE_RE.search(text)
    if not m:
        return None, None
    mon1, d1, y1, mon2, d2, y2 = m.groups()
    start = _closest_year(_month(mon1), int(d1), ref, y1 or y2)
    if not start:
        return None, None
    end = None
    if d2:
        try:
            end = date(start.year, _month(mon2) if mon2 else start.month, int(d2))
            if end < start:
                end = date(start.year + 1, end.month, end.day)
        except ValueError:
            end = None
    return start, end if end and end != start else None


def _weekday_date(text: str, ref: date) -> Optional[date]:
    m = _WD_RE.search(text)
    if not m:
        return None
    key = m.group(1).lower()[:3]
    idx = [w[:3] for w in WEEKDAYS].index(key)
    return ref + timedelta(days=(idx - ref.weekday()) % 7)


def _times(text: str) -> tuple[Optional[time], Optional[time]]:
    m = _TIME_RANGE_RE.search(text)
    if m:
        a, b = m.group(1), m.group(2)
        if not re.search(r"[ap]\.?m", a, re.I):  # "7-10pm" -> both pm
            a += re.search(r"[ap]\.?m\.?", b, re.I).group(0)
        return parse_clock(a), parse_clock(b)
    m = _TIME_RE.search(text)
    return (parse_clock(m.group(1)), None) if m else (None, None)


def _post_date(soup: BeautifulSoup) -> Optional[date]:
    """Latest of published/modified: the weekend guide is one post that is rewritten every week."""
    found: list[date] = []

    def add(v):
        if not v:
            return
        try:
            found.append(datetime.fromisoformat(str(v).strip().replace("Z", "+00:00")[:25]).date())
        except ValueError:
            try:
                found.append(date.fromisoformat(str(v).strip()[:10]))
            except ValueError:
                pass
    for tag in soup.find_all("script", type="application/ld+json"):
        try:
            data = json.loads(tag.string or tag.get_text() or "")
        except json.JSONDecodeError:
            continue
        for d in data if isinstance(data, list) else [data]:
            if isinstance(d, dict):
                add(d.get("dateModified")); add(d.get("datePublished"))
    for sel, attr in (('meta[itemprop="dateModified"]', "content"), ('meta[itemprop="datePublished"]', "content"),
                      ('meta[property="article:modified_time"]', "content"), ('meta[property="article:published_time"]', "content"),
                      ("time.dt-published", "datetime"), ("time[datetime]", "datetime")):
        for t in soup.select(sel):
            add(t.get(attr))
    return max(found) if found else None


def _content_root(soup: BeautifulSoup) -> Tag:
    for sel in (".blog-item-content", "article .entry-content", "article .sqs-layout", "article", "main"):
        el = soup.select_one(sel)
        if el:
            return el
    return soup.body or soup


def _is_bold_line(el: Tag, text: str) -> bool:
    if len(text) > 120:
        return False
    bold = "".join(b.get_text(" ", strip=True) for b in el.find_all(["strong", "b"]))
    return len(bold) >= 0.8 * len(text.replace(" ", "")) if bold else False


def _is_day_header(text: str) -> bool:
    t = text.strip(" :.-–—!").lower()
    if len(t) > 40:
        return False
    rest = _DATE_RE.sub("", _WD_RE.sub("", t)).strip(" ,.-–—:|")
    return rest == "" and bool(_WD_RE.search(t) or _DATE_RE.search(t))


def _blocks(root: Tag):
    """Yield (kind, element, text) for headings, paragraphs and list items in reading order."""
    for el in root.find_all(["h1", "h2", "h3", "h4", "h5", "p", "li"]):
        if el.find_parent(["nav", "footer", "header", "form"]) or (el.name == "p" and el.find_parent("li")):
            continue
        text = re.sub(r"\s+", " ", el.get_text(" ", strip=True)).strip()
        if text:
            yield el.name, el, unescape_all(text)


def parse_post(html: str, source: dict, page_url: str, today: Optional[date] = None) -> list[RawEvent]:
    soup = BeautifulSoup(html, "lxml")
    ref = _post_date(soup) or today or date.today()
    root = _content_root(soup)
    host = urlparse(page_url).netloc

    entries: list[dict] = []
    day_ctx: Optional[date] = None
    cur: Optional[dict] = None
    for kind, el, text in _blocks(root):
        if _is_day_header(text) and (kind.startswith("h") or _is_bold_line(el, text)):
            s, _ = _dates_in(text, ref)
            day_ctx = s or _weekday_date(text, ref)
            cur = None
            continue
        if kind in ("h2", "h3", "h4", "h5") or (kind in ("p", "li") and _is_bold_line(el, text)):
            cur = {"title": text, "lines": [], "links": [], "day": day_ctx, "image": None}
            entries.append(cur)
            for a in el.find_all("a", href=True):
                cur["links"].append(a["href"])
            continue
        if cur is None:
            continue
        cur["lines"].append(text)
        for a in el.find_all("a", href=True):
            cur["links"].append(a["href"])

    out: list[RawEvent] = []
    for e in entries:
        title = re.sub(r"\s*[|–—-]\s*$", "", e["title"]).strip()
        # A title can carry the details too: "Festival of Trees | Nov 21-23 | TCU Place"
        parts = [p.strip() for p in re.split(r"\s+[|•]\s+", title) if p.strip()]
        title = parts[0]
        details = parts[1:] + e["lines"]
        body = "\n".join(details)
        if not title or len(title) < 3 or len(title) > 140:
            continue
        start, end = _dates_in(body, ref)
        if not start:
            start, end = _dates_in(title, ref)
            if start:
                title = _DATE_RE.sub("", title).strip(" ,:-–—|")
        if not start:
            wd_line = next((l for l in details if _WD_RE.search(l) and len(l) < 80), None)
            start = _weekday_date(wd_line, ref) if wd_line else None
        from_header_only = not start
        start = start or e["day"]
        if not start or not title:
            continue
        venue = price_line = None
        for ln in details:
            m = _LABEL_RE.match(ln)
            if m:
                label, val = m.group(1).lower(), m.group(2).strip()
                if label in ("where", "location", "venue", "place", "address") and not venue:
                    venue = re.split(r"\s*[|(]\s*", val)[0].strip(" .")
                elif label in ("cost", "price", "admission", "tickets", "ticket") and not price_line:
                    price_line = val
        if not venue:
            # "Title | Nov 21 | 7pm | TCU Place": the segment that is not a date, time or price is the place.
            segs = parts[1:] + [s.strip() for ln in e["lines"] if re.search(r"\s[|•]\s", ln)
                                for s in re.split(r"\s+[|•]\s+", ln)]
            for p in segs:
                if len(p) < 60 and re.match(r"^[A-Z0-9]", p) and not (_DATE_RE.search(p) or _TIME_RE.search(p) or _WD_RE.search(p)) \
                        and not re.search(r"\$|\bfree\b|tickets?|https?://", p, re.I):
                    venue = p
                    break
        if not venue:
            m = re.search(r"\b(?:at|@)\s+(?:the\s+)?([A-Z][\w'’&-]*(?:\s+[A-Z][\w'’&-]*){0,5})", body)
            venue = m.group(1).strip(" .") if m else None
        address = None
        if venue and "," in venue and re.search(r"\d", venue.split(",", 1)[1]):
            venue, address = (x.strip() for x in venue.split(",", 1))
        if not price_line:
            price_line = next((ln for ln in details if re.search(r"\$\s?\d|\bfree\b|admission|by donation|pwyc", ln, re.I) and len(ln) < 160), None)
        pt, pmin, pmax, free = parse_price(price_line)
        st, et = _times(body)
        links = [urljoin(page_url, u) for u in e["links"] if u and not u.startswith(("mailto:", "tel:", "#"))]
        ext = [u for u in links if urlparse(u).netloc and urlparse(u).netloc != host]
        if from_header_only and not (st or venue or pt or ext):
            continue  # a heading under a day header with nothing event-like (e.g. "Newsletter signup")
        anchor = slugify(title)[:60]
        desc = body.strip() or None
        out.append(RawEvent(
            source_id=source["id"],
            source_key=f"{page_url}#{anchor}@{start.isoformat()}",
            source_url=f"{page_url}#{anchor}",
            title=title,
            start_date=start, start_time=st,
            end_date=end, end_time=et if et and not end else None,
            description=desc[:800] if desc else None,
            venue_name=venue,
            venue_address=address,
            ticket_url=ext[0] if ext else None,
            price_text=pt, price_min=pmin, price_max=pmax, is_free=free,
            age_text=parse_age(desc, title),
            performers=[],  # round-ups describe events, not line-ups; acts come from venue/ticket sources
            status=detect_status(title, (desc or "")[:200]),
            links=[{"label": "More info", "url": u} for u in ext[1:3]],
        ))
    return out


def post_links(html: str, page_url: str, pattern: str, limit: int) -> list[str]:
    soup = BeautifulSoup(html, "lxml")
    host = urlparse(page_url).netloc
    rx = re.compile(pattern, re.I)
    seen: list[str] = []
    for a in soup.select("article a[href], .blog-item a[href], a.blog-title-link, h1 a[href], h2 a[href], h3 a[href]"):
        u = urljoin(page_url, a["href"]).split("?")[0].split("#")[0]
        p = urlparse(u)
        if p.netloc != host or "/category/" in p.path or "/tag/" in p.path or p.path.rstrip("/").count("/") < 2:
            continue
        if rx.search(p.path) and u not in seen:
            seen.append(u)
        if len(seen) >= limit:
            break
    return seen


def fetch(source: dict, fetcher, today=None) -> list[RawEvent]:
    opts = source.get("options", {})
    urls = [source["url"]] + list(opts.get("extraPages", []))
    if opts.get("listingUrl"):
        try:
            lst = fetcher.get(opts["listingUrl"]).text
            for u in post_links(lst, opts["listingUrl"], opts.get("postMatch", _DEFAULT_POST_MATCH), int(opts.get("maxPosts", 4))):
                if u.rstrip("/") not in {x.rstrip("/") for x in urls}:
                    urls.append(u)
        except Exception as e:
            log.warning("listing page failed: %s: %s", opts["listingUrl"], e)
    out: list[RawEvent] = []
    for u in urls:
        try:
            out.extend(parse_post(fetcher.get(u).text, source, u, today=today))
        except Exception as e:
            if u == source["url"]:
                raise
            log.warning("post failed: %s: %s", u, e)
    # The same event can appear in several posts; keep the first (newest guide is fetched first).
    seen, uniq = set(), []
    for ev in out:
        k = (slugify(ev.title), ev.start_date)
        if k not in seen:
            seen.add(k)
            uniq.append(ev)
    return uniq
