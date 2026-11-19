"""Text normalisation and conservative field extraction. Never invents values: returns None when unsure."""
from __future__ import annotations

import html
import re
import unicodedata
from datetime import time
from typing import Optional

from bs4 import BeautifulSoup


def html_to_text(s: Optional[str]) -> Optional[str]:
    if not s:
        return None
    soup = BeautifulSoup(s, "lxml")
    for br in soup.find_all("br"):
        br.replace_with("\n")
    for blk in soup.find_all(["p", "div", "li", "h1", "h2", "h3", "h4"]):
        blk.insert_after("\n")
    text = soup.get_text()
    text = html.unescape(text).replace("\xa0", " ")
    lines = [re.sub(r"[ \t]+", " ", ln).strip() for ln in text.splitlines()]
    out, blank = [], 0
    for ln in lines:
        if not ln:
            blank += 1
            if blank == 1 and out:
                out.append("")
            continue
        blank = 0
        out.append(ln)
    t = "\n".join(out).strip()
    return t or None


def slugify(s: str) -> str:
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode()
    s = re.sub(r"[^a-zA-Z0-9]+", "-", s.lower()).strip("-")
    return s or "x"


_STOP = {"the", "a", "an", "at", "w", "with", "and", "live", "presents", "present", "saskatoon", "sk", "in", "of", "ft", "feat", "featuring", "guests", "special", "tour"}


def norm_tokens(s: str) -> set[str]:
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode().lower()
    s = s.replace("&", " and ")
    toks = re.findall(r"[a-z0-9]+", s)
    return {t for t in toks if t not in _STOP and len(t) > 1}


def norm_name(s: str) -> str:
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode().lower()
    s = s.replace("&", "and")
    s = re.sub(r"^the\s+", "", s.strip())
    s = re.sub(r"\(the\)", "", s)
    return re.sub(r"[^a-z0-9]+", "", s)


# ---------- time ----------
_TIME_RE = re.compile(r"\b(\d{1,2})(?::(\d{2}))?\s*([ap])\.?m\.?\b", re.I)


def parse_clock(s: str) -> Optional[time]:
    m = _TIME_RE.search(s or "")
    if not m:
        m24 = re.search(r"\b([01]?\d|2[0-3]):([0-5]\d)\b", s or "")
        if m24:
            return time(int(m24.group(1)), int(m24.group(2)))
        return None
    h, mi, ap = int(m.group(1)), int(m.group(2) or 0), m.group(3).lower()
    if h > 12 or mi > 59:
        return None
    if ap == "p" and h != 12:
        h += 12
    if ap == "a" and h == 12:
        h = 0
    return time(h, mi)


def parse_doors(text: Optional[str]) -> Optional[time]:
    if not text:
        return None
    m = re.search(r"doors?\s*(?:open|at|@)?\s*:?\s*(\d{1,2}(?::\d{2})?\s*[ap]\.?m\.?)", text, re.I)
    return parse_clock(m.group(1)) if m else None


# ---------- age ----------
def parse_age(*texts: Optional[str]) -> Optional[str]:
    """Return 'all_ages', '16+', '18+', '19+' etc. only when explicitly stated."""
    blob = " ".join(t for t in texts if t)
    if not blob:
        return None
    m = re.search(r"\b(1[3-9]|2[01])\s*\+", blob)
    if m:
        return f"{m.group(1)}+"
    m = re.search(r"\bages?\s*:?\s*(1[3-9]|2[01])\s*(?:and|&)\s*(?:up|over|older)", blob, re.I)
    if m:
        return f"{m.group(1)}+"
    if re.search(r"\ball[\s-]+ages\b", blob, re.I):
        return "all_ages"
    return None


# ---------- price ----------
_MONEY = re.compile(r"\$\s?(\d{1,4}(?:\.\d{2})?)")


def parse_price(text: Optional[str]) -> tuple[Optional[str], Optional[float], Optional[float], Optional[bool]]:
    """Returns (price_text, min, max, is_free). Free only when the text says so."""
    if not text:
        return None, None, None, None
    t = text.strip()
    if re.search(r"\b(free|no cover|pay what you can|pwyc)\b", t, re.I) and not _MONEY.search(t):
        return t, 0.0, 0.0, True
    amounts = [float(a) for a in _MONEY.findall(t)]
    if amounts:
        return t, min(amounts), max(amounts), False
    if re.search(r"\b(cover|tickets?)\b", t, re.I):
        return t, None, None, False if re.search(r"cover", t, re.I) else None
    return t, None, None, None


def find_price_in_text(text: Optional[str]) -> Optional[str]:
    if not text:
        return None
    for ln in text.splitlines():
        if re.search(r"\$\s?\d|no cover|\bfree\b|pay what you can|admission|cover", ln, re.I) and len(ln) < 160:
            return ln.strip()
    return None


# ---------- status ----------
def detect_status(*texts: Optional[str]) -> str:
    blob = " ".join(t for t in texts if t)
    if re.search(r"\bcancel+ed\b", blob, re.I):
        return "cancelled"
    if re.search(r"\bpostponed\b", blob, re.I):
        return "postponed"
    if re.search(r"\brescheduled\b", blob, re.I):
        return "rescheduled"
    return "scheduled"


# ---------- performers from titles ----------
_SPLIT = re.compile(r"\s+(?:w/|with|ft\.?|feat\.?|featuring|and guests?|\+)\s+|\s*,\s*|\s+/\s+|\s+\|\s+", re.I)
_NON_PERFORMER = re.compile(
    r"\b(trivia|bingo|karaoke|night|party|festival|fest|presents?|tour|show|release|open mic|jam|"
    r"comedy|tickets?|tba|guests?|special|edition|live at|xmas|christmas|new year|art nite|dj night|"
    r"close out|celebration|brunch|market|workshop|sold out|winterruption|cancell?ed|postponed|\d+\s*[ap]m)\b",
    re.I,
)


def performers_from_title(title: str) -> list[str]:
    """Conservative split of 'A w/ B, C & D' style titles. '&' / 'and' are kept (band names use them)."""
    t = re.sub(r"\s*[-–|]\s*(saskatoon|sk|live in saskatoon|live at the capitol)\b.*$", "", title, flags=re.I)
    t = re.sub(r"^[^:]{0,60}:\s*", lambda m: "" if re.search(r"winterruption|presents?|20\d\d", m.group(0), re.I) else m.group(0), t)
    t = re.sub(r"\s+-\s+live at .*$", "", t, flags=re.I)
    parts = [p.strip(" !.-–") for p in _SPLIT.split(t) if p and p.strip(" !.-–")]
    out = []
    for p in parts:
        p = re.sub(r"^(?:and|&)\s+", "", p, flags=re.I).strip()
        if not p or len(p) > 60 or len(p) < 2:
            continue
        if _NON_PERFORMER.search(p):
            continue
        out.append(p)
    # A single remaining "part" equal to the whole title is only a performer if the title looks like a name.
    return out


# ---------- categories ----------
CATEGORIES = [
    "live_music", "other_music", "festival", "market", "comedy", "theatre", "arts_culture",
    "workshop", "community", "family", "sports", "seasonal", "other",
]

_RULES: list[tuple[str, str, list[str]]] = [
    # (category, regex, extra tags)
    ("sports", r"\b(vs\.?|versus|hockey|saskatoon blades|saskatchewan rush|huskies|basketball|football|baseball|game day|home game|curling|wrestling|boxing)\b", []),
    ("family", r"\b(storytime|story time|kids?|children'?s|family|puppet|toddler|baby)\b", ["family"]),
    ("workshop", r"\b(workshop|class|lessons?|masterclass|course|seminar|clinic)\b", []),
    ("comedy", r"\b(comedy|comedian|stand[- ]?up|improv|roast)\b", []),
    ("theatre", r"\b(theatre|theater|the play|musical|ballet|opera|dance theatre|drama|cabaret)\b", []),
    ("market", r"\b(market|craft fair|bazaar|makers|flea|vendor)\b", []),
    ("community", r"\b(trivia|bingo|pop quiz|quiz night|meet ?up|fundraiser|gala|drag)\b", []),
    ("other_music", r"\b(dj|karaoke|rave|club night|dance party|day party|daytime clubbing|emo night)\b", []),
    ("arts_culture", r"\b(exhibit|exhibition|gallery|art nite|art night|film|screening|cinema|museum|lecture|talk|reading|poetry|story slam)\b", []),
    ("live_music", r"(?:^|\s)w/\s|\b(band|concert|live music|tour|songwriter|ep release|album|tribute|jazz|blues|orchestra|symphony|quartet|quintet|trio|choir|acoustic|bluegrass|folk|rock|punk|metal|hip hop|country|piano|guitar|unplugged|in concert)\b", []),
    ("festival", r"\b(festival|fest|winterruption|folkfest|jazzfest)\b", ["festival"]),
]
_SEASONAL = r"\b(christmas|xmas|halloween|new year|easter|canada day|winter festival|solstice|holiday)\b"
_MUSIC_WORDS = r"\b(band|singer|songwriter|album|record|music|musician|guitar|tour|concert|tribute|songs?|vocal|jazz|blues|folk|rock|country|orchestra|trio|quartet|dj|hip hop|rapper|producer)\b"

_TAG_RULES = [
    ("dj", r"\bdj\b"), ("open_mic", r"open mic"), ("jam", r"\bjam\b|jam session"), ("karaoke", r"karaoke"),
    ("trivia", r"trivia|pop quiz"), ("bingo", r"bingo"), ("drag", r"\bdrag\b"), ("tribute", r"tribute"),
    ("festival", r"festival|winterruption"), ("seasonal", r"christmas|xmas|halloween|new year"),
    ("jazz", r"\bjazz"), ("blues", r"\bblues"), ("punk", r"\bpunk"), ("metal", r"\bmetal"),
    ("country", r"\bcountry"), ("folk", r"\bfolk"), ("hip_hop", r"hip[- ]?hop|\brap"), ("comedy", r"comedy|improv"),
    ("album_release", r"(ep|album|record) release"), ("film", r"\bfilm|screening"),
]


def infer_category(title: str, description: Optional[str], hint: Optional[str], venue_type: Optional[str],
                   has_performers: bool, music_venue: bool = False) -> tuple[str, list[str]]:
    """Title is the strongest signal; structured hints (MusicEvent) beat weak title words; description last."""
    title_l = title.lower()
    desc_l = (description or "")[:800].lower()
    tags: list[str] = []
    for tag, rx in _TAG_RULES:
        if re.search(rx, title_l) or re.search(rx, desc_l[:300]):
            tags.append(tag)
    if re.search(_SEASONAL, title_l):
        tags.append("seasonal")
    weak_vs_music = {"family", "workshop", "sports", "community", "market", "arts_culture"}
    for cat, rx, extra in _RULES:
        if re.search(rx, title_l, re.I):
            if cat == "festival":
                if hint == "live_music" or has_performers or re.search(_MUSIC_WORDS, desc_l):
                    return "live_music", sorted(set(tags + ["festival"]))
                return "festival", sorted(set(tags + extra))
            if hint == "live_music" and cat in weak_vs_music:
                return "live_music", sorted(set(tags))
            return cat, sorted(set(tags + extra))
    if hint in CATEGORIES:
        return hint, sorted(set(tags))
    # Description: music first at music venues (bios mention hockey, kids, classes...).
    if (music_venue or has_performers) and re.search(_MUSIC_WORDS, desc_l):
        return "live_music", sorted(set(tags))
    for cat, rx, extra in _RULES:
        if cat in ("sports", "family", "workshop", "market") and music_venue:
            continue  # too easily triggered by artist bios
        if re.search(rx, desc_l, re.I):
            if cat == "festival" and has_performers:
                return "live_music", sorted(set(tags + ["festival"]))
            return cat, sorted(set(tags + extra))
    if "seasonal" in tags:
        return "seasonal", sorted(set(tags))
    if venue_type in ("music_venue",) or (has_performers and venue_type in ("bar", "bar_restaurant", "bar_campus", "brewery")):
        return "live_music", sorted(set(tags))
    return "other", sorted(set(tags))


_TITLE_NOISE = re.compile(r"\s*(?:[-–|!]\s*)?\b(sold out!?|cancell?ed!?|postponed!?)\s*!?\s*$", re.I)


def clean_title(title: str) -> tuple[str, list[str]]:
    """Strip trailing status words from titles ("X - Sold Out!") and return them as tags."""
    tags = []
    t = title.strip()
    while True:
        m = _TITLE_NOISE.search(t)
        if not m or m.start() == 0:
            break
        w = m.group(1).lower()
        tags.append("sold_out" if "sold" in w else w.rstrip("!").replace("canceled", "cancelled"))
        t = t[:m.start()].rstrip(" -–|!")
    return t, tags


NOT_EVENTS = re.compile(r"\b(season pass|gift card|gift certificate|membership|subscription package)\b", re.I)
