"""Intermediate records produced by source adapters (before venue matching and de-duplication)."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, time
from typing import Optional


@dataclass
class Performer:
    name: str
    # Profile page on the source itself (e.g. Songkick artist page). Identifies the act *on that platform*.
    profile_url: Optional[str] = None
    profile_platform: Optional[str] = None  # "songkick" | "bandsintown" | ...
    # Links the source explicitly placed next to this performer's name (e.g. venue listing text).
    listed_links: list[str] = field(default_factory=list)
    genres: list[str] = field(default_factory=list)


@dataclass
class RawEvent:
    source_id: str
    source_key: str            # stable id of this event within the source
    source_url: str            # page for this specific event, or the listing page
    title: str
    start_date: date
    start_time: Optional[time] = None
    end_date: Optional[date] = None
    end_time: Optional[time] = None
    all_day: bool = False
    description: Optional[str] = None
    venue_id: Optional[str] = None          # set when the adapter knows the venue for sure
    venue_name: Optional[str] = None
    venue_address: Optional[str] = None
    venue_locality: Optional[str] = None
    venue_lat: Optional[float] = None
    venue_lon: Optional[float] = None
    image_url: Optional[str] = None
    ticket_url: Optional[str] = None
    price_text: Optional[str] = None
    price_min: Optional[float] = None
    price_max: Optional[float] = None
    is_free: Optional[bool] = None
    age_text: Optional[str] = None
    performers: list[Performer] = field(default_factory=list)
    category_hint: Optional[str] = None
    tags: list[str] = field(default_factory=list)
    status: str = "scheduled"               # scheduled | cancelled | postponed | rescheduled
    links: list[dict] = field(default_factory=list)  # [{"label":..., "url":...}]
    doors_time: Optional[time] = None
