"""Finding the right pages for performers from what event listings already say.

Three ways, all of which need more than a name:

1. Links in the listing.  Venues often put the act's links in the event description ("LISTEN:
   https://dumpbabes.bandcamp.com/").  A link is attributed to a performer when its anchor text is the
   performer's name, when its handle/sub-domain spells the name (dumpbabes <-> "Dump Babes"), or when it sits in
   a paragraph that names exactly that one performer.  Ticket, venue, share and Facebook *event* links are ignored.

2. Hometowns in the listing.  Descriptions often say where an act is from ("two-time JUNO winners from
   Winnipeg", "a five-piece band based in Saskatoon, Saskatchewan").  Those places are collected per performer
   and later compared with MusicBrainz: an act is matched only when exactly one MusicBrainz artist with that
   exact name is from the same place.

3. The act's own pages.  When a listing links the act's Bandcamp page, website or Linktree, that page is read
   (robots.txt respected) for the act's own Facebook/Instagram/YouTube/... links.
"""
from __future__ import annotations

import re
from typing import Iterable, Optional
from urllib.parse import urlparse

from bs4 import BeautifulSoup

from .normalize import norm_name

TICKET_HOSTS = re.compile(
    r"show\.ps|showpass|eventbrite|ticketmaster|tickets\.|universe\.com|ticketrocket|ticketweb|eventim|"
    r"broadwaytheatre\.ca|bit\.ly|google\.\w+/maps|goo\.gl|maps\.app", re.I)
NOT_ARTIST = re.compile(
    r"facebook\.com/(events|sharer|share|dialog|plugins|groups|hashtag)\b|twitter\.com/(intent|share)|x\.com/intent|"
    r"instagram\.com/(p|explore|reel)/|youtube\.com/(watch|shorts|playlist)\b|youtu\.be/|mailto:|tel:|"
    r"linkedin\.com|pinterest\.|wa\.me|whatsapp", re.I)
_URL_IN_TEXT = re.compile(r"(?:https?://|www\.)[^\s<>()\"']+|\b[\w-]+\.bandcamp\.com[^\s<>()\"']*|"
                          r"\b(?:facebook|instagram|linktr\.ee|soundcloud|tiktok)\.com?/[^\s<>()\"']+", re.I)
# Words bands add around their name in handles: thedumpbabes, dumpbabesmusic, dumpbabesband, dumpbabes_yxe ...
_HANDLE_AFFIXES = ("official", "music", "band", "theband", "yxe", "sask", "sk", "ca", "the", "tv", "hq", "live")


def _clean_url(u: str) -> str:
    u = u.strip().rstrip(".,;:!)")
    return u if re.match(r"https?://", u, re.I) else "https://" + u


def handle_of(url: str) -> Optional[str]:
    """The account part of a profile URL, normalised like a name (letters and digits only)."""
    p = urlparse(url)
    host = p.netloc.lower().removeprefix("www.").removeprefix("m.")
    segs = [s for s in p.path.split("/") if s]
    h = None
    if host.endswith(".bandcamp.com"):
        h = host.split(".")[0]
    elif re.search(r"(facebook|instagram|tiktok|soundcloud|twitter|x)\.com$|linktr\.ee$|fb\.com$", host) and segs:
        h = segs[0]
        if h in ("pg", "people") and len(segs) > 1:
            h = segs[1]
    elif host.endswith("youtube.com") and segs:
        h = segs[1] if segs[0] in ("c", "user", "channel") and len(segs) > 1 else segs[0]
        if segs[0] == "channel":
            return None  # opaque channel id
    elif host:
        labels = host.split(".")
        h = labels[-2] if len(labels) >= 2 else labels[0]
    if not h:
        return None
    return re.sub(r"[^a-z0-9]", "", h.lower().lstrip("@").replace("&", "and")) or None


def handle_matches(handle: Optional[str], name: str) -> bool:
    n = norm_name(name)
    if not handle or len(n) < 3:
        return False
    if handle == n or handle == "the" + n:
        return True
    core = handle
    for _ in range(2):  # strip up to two affixes: "thedumpbabesmusic"
        for a in _HANDLE_AFFIXES:
            if core.startswith(a) and core[len(a):] and len(core) - len(a) >= 3:
                if core[len(a):] == n:
                    return True
            if core.endswith(a) and len(core) - len(a) >= 3:
                if core[: -len(a)] == n:
                    return True
        for a in _HANDLE_AFFIXES:
            if core.startswith(a) and len(core) - len(a) >= 3:
                core = core[len(a):]
                break
    if core == n:
        return True
    # Long names are often shortened in handles: "lachlanneville" for "Lachlan Neville and the Lakers".
    words = [norm_name(w) for w in re.split(r"\s+(?:and|&|\+)\s+", name, flags=re.I)]
    return len(words) > 1 and len(words[0]) >= 8 and handle in (words[0], "the" + words[0])


def _name_forms(name: str) -> list[str]:
    """The name, plus the front person's name for 'X and the Y' / 'X & the Z' acts (bios name the person)."""
    forms = [re.sub(r"^the\s+", "", name.strip(), flags=re.I)]
    m = re.match(r"^(.+?)\s+(?:and|&)\s+(?:the|her|his|their)\s+\S", name.strip(), re.I)
    if m and (len(m.group(1).split()) >= 2 or len(norm_name(m.group(1))) >= 8):
        forms.append(m.group(1))
    return forms


def _name_in(text: str, name: str) -> bool:
    for n in _name_forms(name):
        if len(norm_name(n)) < 3:
            continue
        words = [r"(?:and|&)" if w.lower() in ("and", "&") else re.escape(w) for w in re.split(r"\s+", n)]
        pat = r"(?<![\w])" + r"[\s\-]*".join(words) + r"(?![\w])"
        if re.search(pat, text, re.I):
            return True
    return False


def _blocks(soup: BeautifulSoup) -> list[tuple[str, list[tuple[str, str]]]]:
    """Paragraph-ish blocks of (text, [(anchor_text, href)]), keeping raw URLs typed as text."""
    for br in soup.find_all("br"):
        br.replace_with("\n")
    out = []
    leaves = [n for n in soup.find_all(["p", "li", "div", "h2", "h3", "h4", "h5", "h6"])
              if not n.find(["p", "li", "div", "h2", "h3", "h4", "h5", "h6"])] or [soup]
    for node in leaves:
        anchors = [(a.get_text(" ", strip=True), a.get("href") or "") for a in node.find_all("a")]
        text = node.get_text()
        for chunk in re.split(r"\n\s*\n", text):
            chunk_anchors = [(t, h) for t, h in anchors if t and t in chunk] if len(anchors) > 0 else []
            raw = [(m.group(0), m.group(0)) for m in _URL_IN_TEXT.finditer(chunk)]
            out.append((chunk.strip(), chunk_anchors + [r for r in raw if not any(r[0] in t or r[0] in h for t, h in chunk_anchors)]))
        # anchors whose text was not found in a chunk (e.g. image links) still belong to the node
        leftovers = [(t, h) for t, h in anchors if not t or not any(t in c for c in re.split(r"\n\s*\n", text))]
        if leftovers:
            out.append(("", leftovers))
    return out


_LINK_LINE = re.compile(r"^\W*(?:listen|links?|follow|stream|hear|watch|socials?|music|check (?:them|him|her|it) out|"
                        r"find (?:them|him|her)|facebook|instagram|bandcamp|spotify|youtube|soundcloud|tiktok|website)\b", re.I)


def _is_link_line(text: str) -> bool:
    """'LISTEN: <url>' or a line that is nothing but links."""
    rest = _URL_IN_TEXT.sub("", text).strip(" \t:-–|•·")
    return bool(_LINK_LINE.match(text)) or rest == ""


def links_for_performers(description_html: Optional[str], names: list[str],
                         exclude_domains: Iterable[str] = ()) -> dict[str, list[str]]:
    """{performer name: [urls]} for links in a listing that clearly belong to one performer."""
    if not description_html or not names:
        return {}
    excl = {d.lower().removeprefix("www.") for d in exclude_domains if d}
    soup = BeautifulSoup(description_html, "lxml")
    found: dict[str, list[str]] = {}

    def add(name: str, url: str):
        lst = found.setdefault(name, [])
        if url not in lst:
            lst.append(url)

    prev_named: Optional[str] = None
    for text, links in _blocks(soup):
        named = [n for n in names if _name_in(text, n)]
        for anchor_text, href in links:
            if not href or href.startswith("#"):
                continue
            url = _clean_url(href)
            host = urlparse(url).netloc.lower().removeprefix("www.")
            if TICKET_HOSTS.search(url) or NOT_ARTIST.search(url) or host in excl or any(host.endswith("." + d) for d in excl):
                continue
            h = handle_of(url)
            by_text = [n for n in names if anchor_text and norm_name(anchor_text) == norm_name(n)]
            by_handle = [n for n in names if handle_matches(h, n)]
            if len(by_text) == 1:
                add(by_text[0], url)
            elif len(by_handle) == 1:
                add(by_handle[0], url)
            elif len(named) == 1 and not by_handle:
                add(named[0], url)
            elif not named and prev_named and not by_handle and _is_link_line(text):
                # A bare "Listen: <link>" line straight after a paragraph about one act.
                add(prev_named, url)
        prev_named = named[0] if len(named) == 1 else (prev_named if not text else None)
    return found


# ---------------------------------------------------------------- hometowns

PROVINCES = {
    "ab": "alberta", "bc": "british columbia", "mb": "manitoba", "nb": "new brunswick", "nl": "newfoundland and labrador",
    "ns": "nova scotia", "nt": "northwest territories", "nu": "nunavut", "on": "ontario", "pe": "prince edward island",
    "pei": "prince edward island", "qc": "quebec", "sk": "saskatchewan", "yt": "yukon", "sask": "saskatchewan",
}
US_STATES = {
    "al": "alabama", "ak": "alaska", "az": "arizona", "ar": "arkansas", "ca": "california", "co": "colorado",
    "ct": "connecticut", "de": "delaware", "fl": "florida", "ga": "georgia", "hi": "hawaii", "id": "idaho",
    "il": "illinois", "in": "indiana", "ia": "iowa", "ks": "kansas", "ky": "kentucky", "la": "louisiana", "me": "maine",
    "md": "maryland", "ma": "massachusetts", "mi": "michigan", "mn": "minnesota", "ms": "mississippi", "mo": "missouri",
    "mt": "montana", "ne": "nebraska", "nv": "nevada", "nh": "new hampshire", "nj": "new jersey", "nm": "new mexico",
    "ny": "new york", "nc": "north carolina", "nd": "north dakota", "oh": "ohio", "ok": "oklahoma", "or": "oregon",
    "pa": "pennsylvania", "ri": "rhode island", "sc": "south carolina", "sd": "south dakota", "tn": "tennessee",
    "tx": "texas", "ut": "utah", "vt": "vermont", "va": "virginia", "wa": "washington", "wv": "west virginia",
    "wi": "wisconsin", "wy": "wyoming",
}
# Cities that listings name without a province. Used only to widen a match ("Winnipeg" also matches an
# artist whose MusicBrainz area is just "Manitoba"), never to invent a hometown.
CITY_REGION = {
    "saskatoon": "saskatchewan", "regina": "saskatchewan", "prince albert": "saskatchewan", "moose jaw": "saskatchewan",
    "north battleford": "saskatchewan", "yorkton": "saskatchewan", "swift current": "saskatchewan", "estevan": "saskatchewan",
    "la ronge": "saskatchewan", "rocanville": "saskatchewan", "lloydminster": "saskatchewan", "humboldt": "saskatchewan",
    "winnipeg": "manitoba", "brandon": "manitoba", "calgary": "alberta", "edmonton": "alberta", "lethbridge": "alberta",
    "red deer": "alberta", "medicine hat": "alberta", "vancouver": "british columbia", "victoria": "british columbia",
    "kelowna": "british columbia", "nanaimo": "british columbia", "kamloops": "british columbia",
    "toronto": "ontario", "ottawa": "ontario", "hamilton": "ontario", "london": "ontario", "guelph": "ontario",
    "kingston": "ontario", "thunder bay": "ontario", "sudbury": "ontario", "montreal": "quebec", "montréal": "quebec",
    "quebec city": "quebec", "halifax": "nova scotia", "moncton": "new brunswick", "fredericton": "new brunswick",
    "st. john's": "newfoundland and labrador", "charlottetown": "prince edward island", "whitehorse": "yukon",
    "yellowknife": "northwest territories", "minneapolis": "minnesota", "fargo": "north dakota", "seattle": "washington",
    "portland": "oregon", "chicago": "illinois", "nashville": "tennessee", "austin": "texas", "denver": "colorado",
    "los angeles": "california", "san francisco": "california", "brooklyn": "new york", "new york city": "new york",
}
COUNTRY_OF_REGION = {**{v: "canada" for v in PROVINCES.values()}, **{v: "united states" for v in US_STATES.values()}}
_REGIONS = set(COUNTRY_OF_REGION)

_PLACE = r"((?:[A-Z][\w'’.]+|St\.)(?:[ -](?:[A-Z][\w'’.]+|de|du|la))*(?:,\s*(?:[A-Z]{2,4}\b|[A-Z][a-z]+(?: [A-Z][a-z]+){0,2}))?)"
_CUES = [
    re.compile(r"\b(?:hail(?:s|ing)? from|originally from|straight (?:out )?of|come[s]? from|from|out of|based (?:out )?(?:of|in)|"
               r"calls? (?:it )?home|lives? in(?: [a-z ]{0,30}? in)?|grew up in|raised in)\s+(?:the\s+)?(?:city of\s+)?" + _PLACE),
    re.compile(_PLACE + r"[- ](?:based|born|raised|bred)\b"),
    re.compile(_PLACE + r"['’]s (?:own |very own |finest )"),
    re.compile(r"\(\s*" + _PLACE + r"\s*\)"),
]
_NOT_PLACE = {"the", "a", "an", "their", "his", "her", "my", "our", "all", "across", "around", "juno", "polaris",
              "canada's", "rock", "country", "jazz", "blues", "folk", "metal", "pop", "punk", "doors", "tickets", "january",
              "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
              "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"}


def place_keys(place: str) -> set[str]:
    """Lower-case names a place can match: the city, its province/state, and the province's full name."""
    parts = [p.strip().lower().rstrip(".") for p in place.split(",") if p.strip()]
    keys = set()
    for p in parts:
        full = PROVINCES.get(p) or US_STATES.get(p) or p
        keys.add(full)
    if parts and parts[0] in CITY_REGION:
        keys.add(CITY_REGION[parts[0]])
    return keys


def is_clear_place(place: str) -> bool:
    """True for 'City, Province'/'City, ST' style places or well-known cities/regions: safe to show as a hometown."""
    parts = [p.strip().lower().rstrip(".") for p in place.split(",")]
    if len(parts) == 2 and (parts[1] in PROVINCES or parts[1] in US_STATES or parts[1] in _REGIONS or parts[1] == "canada"):
        return True
    return parts[0] in CITY_REGION or parts[0] in _REGIONS


def _sentences(text: str) -> list[str]:
    return [s for s in re.split(r"(?<=[.!?])\s+(?=[A-Z\"“])|\n+", text) if s.strip()]


def _places_in(text: str) -> list[str]:
    out = []
    for rx in _CUES:
        for m in rx.finditer(text):
            pl = re.split(r"(?<!\bSt)\.\s", m.group(1))[0].strip(" ,.")
            first = pl.split(",")[0].split()[0].lower() if pl else ""
            if not pl or first in _NOT_PLACE or len(pl) > 50:
                continue
            if pl not in out:
                out.append(pl)
    return out


def hometowns_for_performers(text: Optional[str], names: list[str]) -> dict[str, list[str]]:
    """{performer: [places]} for places the listing says a performer is from."""
    if not text or not names:
        return {}
    found: dict[str, list[str]] = {}
    paragraphs = [p for p in re.split(r"\n\s*\n", text) if p.strip()]
    for para in paragraphs:
        named = [n for n in names if _name_in(para, n)]
        if len(named) == 1 or (not named and len(names) == 1):
            who = named[0] if named else names[0]
            _add_places(found, who, _places_in(para))
            continue
        for s in _sentences(para):
            sn = [n for n in names if _name_in(s, n)]
            if len(sn) == 1:
                _add_places(found, sn[0], _places_in(s))
    return found


def _add_places(found: dict[str, list[str]], who: str, places: list[str]) -> None:
    lst = found.setdefault(who, [])
    lst.extend(p for p in places if p not in lst)
    if not lst:
        del found[who]


# ---------------------------------------------------------------- the act's own pages

SOCIAL = re.compile(r"facebook\.com|fb\.com|instagram\.com|bandcamp\.com|youtube\.com|open\.spotify\.com|soundcloud\.com|"
                    r"tiktok\.com|music\.apple\.com|linktr\.ee", re.I)


def social_links_on_own_page(html: str, page_url: str, name: str) -> list[str]:
    """Profile links on the act's own Bandcamp page / website / Linktree.

    Bandcamp: only the artist's own '#band-links' list.  Elsewhere: profile links on the page, at most one per
    platform; when a platform has several, only the one whose handle spells the act's name is kept.
    """
    soup = BeautifulSoup(html, "lxml")
    host = urlparse(page_url).netloc.lower()
    if host.endswith("bandcamp.com"):
        box = soup.select_one("#band-links") or soup.select_one(".band-links")
        anchors = box.find_all("a") if box else []
    else:
        for junk in soup.select("script, style, noscript"):
            junk.decompose()
        anchors = soup.find_all("a")
    by_platform: dict[str, list[str]] = {}
    for a in anchors:
        href = (a.get("href") or "").strip()
        if not href.startswith("http") or not SOCIAL.search(href) or NOT_ARTIST.search(href):
            continue
        if urlparse(href).netloc.lower() == host:
            continue
        plat = re.search(r"facebook|fb\.com|instagram|bandcamp|youtube|spotify|soundcloud|tiktok|apple|linktr", href, re.I).group(0).lower()
        plat = "facebook" if plat == "fb.com" else plat
        lst = by_platform.setdefault(plat, [])
        u = href.split("?")[0] if plat not in ("youtube",) else href
        if u not in lst:
            lst.append(u)
    out = []
    for plat, urls in by_platform.items():
        if len(urls) == 1:
            out.append(urls[0])
        else:
            m = [u for u in urls if handle_matches(handle_of(u), name)]
            if len(m) == 1:
                out.append(m[0])
    return out


def attach_listing_links(performers: list, description_html: Optional[str], exclude_domains: Iterable[str] = ()) -> int:
    """Adds links found in a listing's description to the matching Performer objects. Returns how many were added."""
    found = links_for_performers(description_html, [p.name for p in performers], exclude_domains)
    n = 0
    for p in performers:
        for u in found.get(p.name, []):
            if u not in p.listed_links:
                p.listed_links.append(u)
                n += 1
    return n
