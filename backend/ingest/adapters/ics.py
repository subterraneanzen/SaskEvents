"""Minimal iCalendar (RFC 5545) adapter for venue/library feeds. Handles VEVENT with DTSTART/DTEND, SUMMARY,
DESCRIPTION, LOCATION, URL, UID, STATUS. Recurrence rules are not expanded (most public feeds publish instances)."""
from __future__ import annotations

from datetime import date, datetime, time
from typing import Optional
from zoneinfo import ZoneInfo

from ..models import Performer, RawEvent
from ..normalize import html_to_text, parse_age, performers_from_title

TZ = ZoneInfo("America/Regina")


def _unfold(text: str) -> list[str]:
    out: list[str] = []
    for ln in text.splitlines():
        if ln.startswith((" ", "\t")) and out:
            out[-1] += ln[1:]
        else:
            out.append(ln)
    return out


def _unescape(v: str) -> str:
    return v.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")


def _dt(value: str, params: dict) -> tuple[Optional[date], Optional[time], bool]:
    v = value.strip()
    if params.get("VALUE") == "DATE" or len(v) == 8:
        return datetime.strptime(v[:8], "%Y%m%d").date(), None, True
    dt = datetime.strptime(v[:15], "%Y%m%dT%H%M%S")
    if v.endswith("Z"):
        dt = dt.replace(tzinfo=ZoneInfo("UTC")).astimezone(TZ)
    elif params.get("TZID"):
        try:
            dt = dt.replace(tzinfo=ZoneInfo(params["TZID"])).astimezone(TZ)
        except Exception:
            pass
    return dt.date(), dt.time(), False


def parse_ics(text: str, source: dict) -> list[RawEvent]:
    events, cur = [], None
    for ln in _unfold(text):
        if ln == "BEGIN:VEVENT":
            cur = {}
        elif ln == "END:VEVENT" and cur is not None:
            events.append(cur)
            cur = None
        elif cur is not None and ":" in ln:
            head, value = ln.split(":", 1)
            name, *ps = head.split(";")
            params = dict(p.split("=", 1) for p in ps if "=" in p)
            cur[name.upper()] = (value, params)
    out = []
    for e in events:
        if "DTSTART" not in e or "SUMMARY" not in e:
            continue
        sd, st, all_day = _dt(*e["DTSTART"])
        ed = et = None
        if "DTEND" in e:
            ed, et, _ = _dt(*e["DTEND"])
        title = _unescape(e["SUMMARY"][0]).strip()
        desc = html_to_text(_unescape(e.get("DESCRIPTION", ("", {}))[0])) if "DESCRIPTION" in e else None
        status = (e.get("STATUS", ("", {}))[0] or "").upper()
        out.append(RawEvent(
            source_id=source["id"],
            source_key=e.get("UID", (title + str(sd), {}))[0],
            source_url=e.get("URL", (source["url"], {}))[0],
            title=title, start_date=sd, start_time=st, all_day=all_day,
            end_date=ed if ed and ed != sd else None, end_time=et if not all_day else None,
            description=desc,
            venue_id=source.get("venueId"),
            venue_name=_unescape(e["LOCATION"][0]) if "LOCATION" in e else None,
            age_text=parse_age(desc, title),
            performers=[Performer(name=n) for n in performers_from_title(title)],
            status="cancelled" if status == "CANCELLED" else "scheduled",
        ))
    return out


def fetch(source: dict, fetcher) -> list[RawEvent]:
    return parse_ics(fetcher.get(source["url"]).text, source)
