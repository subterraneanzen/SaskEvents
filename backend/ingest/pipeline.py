"""Venue resolution, duplicate consolidation, verification, continuity with the previous feed, and export."""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import asdict
from datetime import date, datetime, time, timedelta, timezone
from typing import Optional
from zoneinfo import ZoneInfo

from .artists import ArtistBook
from .models import Performer, RawEvent
from .normalize import NOT_EVENTS, clean_title, infer_category, norm_name, norm_tokens, parse_age
from .venues import VenueRegistry

TZ = ZoneInfo("America/Regina")
FIELDS = ["title", "description", "start_time", "end_date", "end_time", "image_url", "ticket_url", "price_text",
          "price_min", "price_max", "is_free", "age_text", "doors_time"]


# ---------------- serialisation of raw events (per-source cache) ----------------
def raw_to_json(ev: RawEvent) -> dict:
    d = asdict(ev)
    for k, v in list(d.items()):
        if isinstance(v, (date, time)):
            d[k] = v.isoformat()
    return d


def raw_from_json(d: dict) -> RawEvent:
    d = dict(d)
    for k in ("start_date", "end_date"):
        if d.get(k):
            d[k] = date.fromisoformat(d[k])
    for k in ("start_time", "end_time", "doors_time"):
        if d.get(k):
            d[k] = time.fromisoformat(d[k])
    d["performers"] = [Performer(**p) for p in d.get("performers", [])]
    return RawEvent(**d)


# ---------------- duplicate consolidation ----------------
def _same_event(a: RawEvent, b: RawEvent) -> bool:
    if a.start_time and b.start_time:
        da = datetime.combine(a.start_date, a.start_time)
        db = datetime.combine(b.start_date, b.start_time)
        if abs((da - db).total_seconds()) > 3.5 * 3600:
            return False
    ta, tb = norm_tokens(a.title), norm_tokens(b.title)
    if ta and tb:
        j = len(ta & tb) / len(ta | tb)
        if j >= 0.5:
            return True
        small, big = (ta, tb) if len(ta) <= len(tb) else (tb, ta)
        if len(small) >= 1 and small <= big and sum(len(t) for t in small) >= 5:
            return True
    pa = {norm_name(p.name) for p in a.performers if len(norm_name(p.name)) >= 4}
    pb = {norm_name(p.name) for p in b.performers if len(norm_name(p.name)) >= 4}
    if pa & pb:
        return True
    na, nb = norm_name(a.title), norm_name(b.title)
    return len(na) >= 6 and len(nb) >= 6 and (na in nb or nb in na)


def cluster(raws: list[RawEvent], venue_of: dict[int, Optional[str]]) -> list[list[RawEvent]]:
    groups: dict[tuple, list[RawEvent]] = {}
    for r in raws:
        vk = venue_of[id(r)] or ("name:" + norm_name(r.venue_name or "") if r.venue_name else "none:" + r.source_id)
        groups.setdefault((vk, r.start_date), []).append(r)
    clusters: list[list[RawEvent]] = []
    for _, items in sorted(groups.items(), key=lambda kv: str(kv[0])):
        cl: list[list[RawEvent]] = []
        for r in items:
            placed = False
            for c in cl:
                # never merge two different events from the same source
                if any(x.source_id == r.source_id and x.source_key != r.source_key for x in c):
                    continue
                if any(_same_event(x, r) for x in c):
                    c.append(r)
                    placed = True
                    break
            if not placed:
                cl.append([r])
        clusters.extend(cl)
    return clusters


# ---------------- build ----------------
class Builder:
    def __init__(self, sources: dict[str, dict], venues: VenueRegistry, curated_artists: list[dict],
                 previous: Optional[dict], now: Optional[datetime] = None):
        self.sources = sources
        self.venues = venues
        self.artists = ArtistBook(curated_artists)
        self.prev_events = {e["id"]: e for e in (previous or {}).get("events", [])}
        self.prev_key_to_id = {}
        for e in self.prev_events.values():
            for k in e.get("sourceKeys", []):
                self.prev_key_to_id[k] = e["id"]
        self.now = now or datetime.now(timezone.utc)
        self.today = self.now.astimezone(TZ).date()

    def tier(self, sid: str) -> int:
        return int(self.sources.get(sid, {}).get("tier", 5))

    def build(self, raws: list[RawEvent], healthy_sources: set[str]) -> list[dict]:
        raws = self._prepare(raws)
        venue_of: dict[int, Optional[str]] = {}
        for r in raws:
            venue_of[id(r)] = self.venues.resolve(r)
        events: list[dict] = []
        used_ids = set()
        for c in cluster(raws, venue_of):
            c.sort(key=lambda r: (self.tier(r.source_id), r.source_id))
            ev = self._merge(c, venue_of[id(c[0])])
            if ev["id"] in used_ids:  # extremely unlikely hash collision / double match: keep both
                ev["id"] += "-" + hashlib.sha1(ev["sourceKeys"][0].encode()).hexdigest()[:4]
            used_ids.add(ev["id"])
            events.append(ev)
        events = self._continuity(events, healthy_sources)
        # Window: keep today (local) onward, up to 400 days ahead.
        horizon = self.today + timedelta(days=400)
        events = [e for e in events if date.fromisoformat(e["endDate"] or e["startDate"]) >= self.today
                  and date.fromisoformat(e["startDate"]) <= horizon]
        for e in events:
            if e["venueId"]:
                self.venues.used.add(e["venueId"])
        events.sort(key=lambda e: (e["startDate"], e["startTime"] or "99:99", e["title"].lower()))
        return events

    def _prepare(self, raws: list[RawEvent]) -> list[RawEvent]:
        out = []
        # Coordinates shared by several differently named places are a city-level placeholder, not a venue location.
        by_coord: dict[tuple, set] = {}
        for r in raws:
            if r.venue_lat is not None and r.venue_name:
                by_coord.setdefault((round(r.venue_lat, 5), round(r.venue_lon, 5)), set()).add(norm_name(r.venue_name))
        placeholders = {k for k, names in by_coord.items() if len(names) >= 3}
        for r in raws:
            if NOT_EVENTS.search(r.title):
                continue
            r.title, extra = clean_title(r.title)
            for t in extra:
                if t in ("cancelled", "postponed"):
                    r.status = t
                elif t not in r.tags:
                    r.tags.append(t)
            if r.venue_lat is not None and (round(r.venue_lat, 5), round(r.venue_lon, 5)) in placeholders:
                r.venue_lat = r.venue_lon = None
            # Festival listings without a venue put the festival name in the venue field.
            if r.venue_name and not r.venue_id and norm_tokens(r.venue_name) and \
                    norm_tokens(r.venue_name) <= norm_tokens(r.title) | {"2025", "2026", "2027", "2028", "festival"}:
                r.venue_name = None
            out.append(r)
        return out

    def _merge(self, c: list[RawEvent], venue_id: Optional[str]) -> dict:
        primary = c[0]
        field_src: dict[str, str] = {}
        vals: dict = {}
        for f in FIELDS:
            for r in c:
                v = getattr(r, f)
                if v not in (None, "", []):
                    # A longer description from a lower tier is only used if the higher tier has none.
                    vals[f] = v
                    field_src[f] = r.source_id
                    break
        # Prefer an official-tier title; aggregator titles are often just the headliner.
        vals["title"] = primary.title
        field_src["title"] = primary.source_id
        if vals.get("ticket_url") is None:
            for r in c:
                for l in r.links:
                    if "ticket" in l["label"].lower():
                        vals["ticket_url"] = l["url"]
        venue = self.venues.venues.get(venue_id) if venue_id else None
        statuses = [(r.source_id, r.status) for r in c]
        status = primary.status
        status_note = None
        others = [(s, st) for s, st in statuses if st != status]
        if others:
            status_note = "; ".join(f"{self.sources.get(s, {}).get('name', s)} lists it as {st}" for s, st in others)
        if venue and venue.get("status") == "closed":
            status_note = (status_note + "; " if status_note else "") + \
                f"Listed at {venue['name']}, which appears to be closed. Check with the organiser."
        tiers = {self.tier(r.source_id) for r in c}
        platforms = {r.source_id.split("-")[0] for r in c}
        if min(tiers) <= 3:
            verification = "verified"
        elif len(platforms) >= 2:
            verification = "cross_checked"
        else:
            verification = "unconfirmed"
        # performers merged across sources by normalised name, keeping all evidence
        perf_by: dict[str, Performer] = {}
        perf_src: dict[str, RawEvent] = {}
        for r in c:
            for p in r.performers:
                k = norm_name(p.name)
                if not k:
                    continue
                if k not in perf_by:
                    perf_by[k] = Performer(name=p.name, profile_url=p.profile_url, profile_platform=p.profile_platform,
                                           listed_links=list(p.listed_links), genres=list(p.genres))
                    perf_src[k] = r
                else:
                    q = perf_by[k]
                    q.profile_url = q.profile_url or p.profile_url
                    q.profile_platform = q.profile_platform or p.profile_platform
                    q.listed_links += [l for l in p.listed_links if l not in q.listed_links]
                    q.genres += [g for g in p.genres if g not in q.genres]
                    if p.listed_links and self.tier(r.source_id) < self.tier(perf_src[k].source_id):
                        perf_src[k] = r
        source_keys = [f"{r.source_id}|{r.source_key}" for r in c]
        ev_id = next((self.prev_key_to_id[k] for k in source_keys if k in self.prev_key_to_id), None)
        if not ev_id:
            basis = f"{venue_id or primary.venue_name or ''}|{primary.start_date}|{norm_name(primary.title)}"
            ev_id = "e-" + hashlib.sha1(basis.encode()).hexdigest()[:12]
        municipality = (venue or {}).get("municipality")
        perf_ids = []
        for k, p in perf_by.items():
            src = perf_src[k]
            perf_ids.append(self.artists.resolve(
                p, event_id=ev_id, venue_id=venue_id, municipality=municipality,
                source_name=self.sources.get(src.source_id, {}).get("name", src.source_id), source_tier=self.tier(src.source_id)))
        desc = vals.get("description")
        hint = next((r.category_hint for r in c if r.category_hint), None)
        category, tags = infer_category(vals["title"], desc, hint, (venue or {}).get("type"), bool(perf_by),
                                        music_venue=bool((venue or {}).get("hostsLiveMusic")))
        for r in c:
            tags = sorted(set(tags) | set(r.tags))
        age = vals.get("age_text") or parse_age(desc)
        family = True if (category == "family" or re.search(r"\bfamily[- ]friendly\b|\bfor (the whole )?family\b", (desc or "") + " " + vals["title"], re.I)) else None
        indoor = None
        blob = (vals["title"] + " " + (desc or "")).lower()
        if re.search(r"\b(outdoor|patio|in the park|open air|outside)\b", blob):
            indoor = "outdoor"
        elif venue and venue.get("indoor") is True:
            indoor = "indoor"
        sources = []
        for r in c:
            s = self.sources.get(r.source_id, {})
            if not any(x["sourceId"] == r.source_id for x in sources):
                sources.append({"sourceId": r.source_id, "name": s.get("name", r.source_id), "url": r.source_url,
                                "tier": self.tier(r.source_id)})
        links = []
        for r in c:
            for l in r.links:
                if not any(x["url"] == l["url"] for x in links):
                    links.append({**l, "sourceId": r.source_id})
        score = 0
        score += 2 if verification == "verified" else 1 if verification == "cross_checked" else 0
        score += min(2, len(sources) - 1)
        score += 1 if vals.get("image_url") else 0
        score += 1 if any(p.profile_platform in ("songkick", "bandsintown", "ticketmaster") for p in perf_by.values()) else 0
        score += 1 if category == "live_music" else 0
        st = vals.get("start_time")
        return {
            "id": ev_id,
            "title": vals["title"],
            "description": desc,
            "category": category,
            "tags": tags,
            "performerIds": perf_ids,
            "venueId": venue_id,
            "venueNameRaw": None if venue_id else primary.venue_name,
            "municipality": municipality,
            "startDate": primary.start_date.isoformat(),
            "startTime": st.strftime("%H:%M") if st else None,
            "endDate": vals["end_date"].isoformat() if vals.get("end_date") else None,
            "endTime": vals["end_time"].strftime("%H:%M") if vals.get("end_time") else None,
            "doorsTime": vals["doors_time"].strftime("%H:%M") if vals.get("doors_time") else None,
            "allDay": primary.all_day,
            "timezone": "America/Regina",
            "priceText": vals.get("price_text"),
            "priceMin": vals.get("price_min"),
            "priceMax": vals.get("price_max"),
            "isFree": vals.get("is_free"),
            "ticketUrl": vals.get("ticket_url"),
            "ageRestriction": age,
            "familyFriendly": family,
            "indoorOutdoor": indoor,
            "imageUrl": vals.get("image_url"),
            "sourceUrl": primary.source_url,
            "sourceName": self.sources.get(primary.source_id, {}).get("name", primary.source_id),
            "sources": sources,
            "fieldSources": field_src,
            "links": links,
            "verification": verification,
            "status": status,
            "statusNote": status_note,
            "highlightScore": score,
            "sourceKeys": source_keys,
        }

    def _continuity(self, events: list[dict], healthy: set[str]) -> list[dict]:
        now_iso = self.now.replace(microsecond=0).isoformat()
        seen = set()
        for e in events:
            seen.add(e["id"])
            prev = self.prev_events.get(e["id"])
            e["firstSeen"] = prev.get("firstSeen", now_iso) if prev else now_iso
            e["lastVerified"] = now_iso
            e["missCount"] = 0
            content = {k: e[k] for k in e if k not in ("firstSeen", "lastVerified", "updatedAt", "missCount", "contentHash")}
            h = hashlib.sha1(json.dumps(content, sort_keys=True, default=str).encode()).hexdigest()[:16]
            e["contentHash"] = h
            e["updatedAt"] = prev["updatedAt"] if prev and prev.get("contentHash") == h else now_iso
        # Previously listed events that disappeared.
        for pid, p in self.prev_events.items():
            if pid in seen:
                continue
            end = date.fromisoformat(p.get("endDate") or p["startDate"])
            if end < self.today:
                continue  # expired: archived by omission (app keeps bookmarked copies)
            srcs = {s["sourceId"] for s in p.get("sources", [])}
            if srcs and srcs <= healthy:
                p = dict(p)
                p["missCount"] = int(p.get("missCount", 0)) + 1
                if p["missCount"] >= 2 and p.get("status") in ("scheduled", "rescheduled", None):
                    names = ", ".join(s["name"] for s in p.get("sources", []))
                    p["status"] = "unlisted"
                    p["statusNote"] = f"No longer listed by {names}. It may have been cancelled or moved; check with the venue."
                    p["updatedAt"] = now_iso
                events.append(p)
            elif srcs & healthy and not srcs <= healthy:
                events.append(p)  # partially re-checked; keep as-is
            else:
                events.append(p)  # sources failed or not run this time: carry forward unchanged
        return events

