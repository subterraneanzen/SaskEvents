"""Ingest entry point.

  python -m ingest.main --config config --prev ../feed --out ../feed

Reads the source registry, runs due sources (others reuse their cached results), consolidates events and writes:
  <out>/index.json                 list of cities + schema version
  <out>/<city>/bundle.json         events, venues, artists, sources (what the app downloads)
  <out>/<city>/report.md           human-readable run report (source health, counts, warnings)
  <out>/cache/<source>.json        last successful raw events per source
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

from .adapters import ADAPTERS
from .adapters.ticketmaster import MissingApiKey
from .enrich import enrich_artists, enrich_from_listings
from .http import Fetcher, RobotsDisallowed
from .pipeline import Builder, raw_from_json, raw_to_json
from .venues import VenueRegistry

log = logging.getLogger("ingest")
SCHEMA_VERSION = 1


def _load(p: Path, default=None):
    try:
        return json.loads(p.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return default


def _iso(dt: datetime) -> str:
    return dt.replace(microsecond=0).isoformat()


def run(config_dir: Path, prev_dir: Path, out_dir: Path, *, force: bool = False, fetcher=None,
        only: set[str] | None = None, geocode: bool = True, enrich: bool = True,
        now: datetime | None = None) -> dict:
    now = now or datetime.now(timezone.utc)
    # Adapters must use the run's clock, not the machine's, so runs (and tests) are reproducible.
    local_today = now.astimezone(ZoneInfo("America/Regina")).date()
    venues_cfg = _load(config_dir / "venues.json")
    sources_cfg = _load(config_dir / "sources.json")["sources"]
    curated = (_load(config_dir / "artists.json", {"artists": []}) or {}).get("artists", [])
    city = venues_cfg["city"]["id"]
    prev_bundle = _load(prev_dir / city / "bundle.json", {}) or {}
    prev_state = {s["id"]: s for s in prev_bundle.get("sources", [])}
    fetcher = fetcher or Fetcher()
    sources = {s["id"]: s for s in sources_cfg}
    (out_dir / "cache").mkdir(parents=True, exist_ok=True)
    (out_dir / city).mkdir(parents=True, exist_ok=True)

    raws, healthy, states = [], set(), []
    for s in sources_cfg:
        st = dict(prev_state.get(s["id"], {}))
        st.update({"id": s["id"], "name": s["name"], "url": s["url"], "adapter": s["adapter"], "tier": s["tier"],
                   "enabled": s.get("enabled", False), "frequencyHours": s.get("frequencyHours"), "notes": s.get("notes")})
        st.setdefault("consecutiveFailures", 0)
        st.setdefault("totalFailures", 0)
        cache_path = out_dir / "cache" / f"{s['id']}.json"
        prev_cache = prev_dir / "cache" / f"{s['id']}.json"
        cached = _load(cache_path, None) or _load(prev_cache, None)
        if not s.get("enabled") or s["adapter"] not in ADAPTERS or (only and s["id"] not in only):
            st["status"] = "disabled" if not s.get("enabled") else ("manual" if s["adapter"] not in ADAPTERS else st.get("status", "skipped"))
            if only and s["id"] not in only and s.get("enabled") and cached:
                raws.extend(raw_from_json(x) for x in cached["events"])
            states.append(st)
            continue
        due = force or not st.get("lastAttempt") or \
            datetime.fromisoformat(st["lastAttempt"]) + timedelta(hours=float(s.get("frequencyHours", 6))) - timedelta(minutes=20) <= now
        if not due and cached:
            raws.extend(raw_from_json(x) for x in cached["events"])
            healthy.add(s["id"]) if st.get("status") == "ok" else None
            st["note"] = "not due; reused last results"
            states.append(st)
            continue
        st["lastAttempt"] = _iso(now)
        try:
            evs = ADAPTERS[s["adapter"]](s, fetcher, today=local_today)
            st.update(status="ok", lastSuccess=_iso(now), eventsDiscovered=len(evs), consecutiveFailures=0, lastError=None)
            cache_path.write_text(json.dumps({"fetchedAt": _iso(now), "events": [raw_to_json(e) for e in evs]}, indent=1))
            raws.extend(evs)
            healthy.add(s["id"])
            if not evs:
                st["warning"] = "Fetched successfully but found 0 events: page structure may have changed."
            else:
                st.pop("warning", None)
        except MissingApiKey as e:
            st.update(status="needs_api_key", lastError=str(e))
        except RobotsDisallowed as e:
            st.update(status="robots_disallowed", lastError=str(e))
        except Exception as e:  # keep going; record the failure
            log.exception("source %s failed", s["id"])
            st.update(status="failed", lastError=f"{type(e).__name__}: {e}"[:400])
            st["consecutiveFailures"] = int(st.get("consecutiveFailures", 0)) + 1
            st["totalFailures"] = int(st.get("totalFailures", 0)) + 1
            if cached:
                raws.extend(raw_from_json(x) for x in cached["events"])
                st["note"] = f"using cached results from {cached.get('fetchedAt')}"
        states.append(st)

    registry = VenueRegistry(venues_cfg, prev_bundle.get("venues"))
    builder = Builder(sources, registry, curated, prev_bundle, now=now)
    events = builder.build(raws, healthy)
    # Carried-forward events may reference venues/artists not seen in this run.
    prev_venues = {v["id"]: v for v in prev_bundle.get("venues", [])}
    for e in events:
        vid = e.get("venueId")
        if vid and vid not in registry.venues and vid in prev_venues:
            registry.venues[vid] = {**prev_venues[vid]}
            registry.used.add(vid)
    if geocode:
        registry.geocode_missing(fetcher)
    artists = builder.artists.export()
    have = {a["id"] for a in artists}
    prev_artists = {a["id"]: a for a in prev_bundle.get("artists", [])}
    for e in events:
        for aid in e.get("performerIds", []):
            if aid not in have and aid in prev_artists:
                artists.append(prev_artists[aid])
                have.add(aid)
    for a in artists:
        a["eventCount"] = sum(1 for e in events if a["id"] in e.get("performerIds", []))
    artists = [a for a in artists if a["eventCount"] > 0]
    if enrich:
        st_enrich = enrich_artists(artists, fetcher, out_dir / "cache" / "musicbrainz.json",
                                   prev_dir / "cache" / "musicbrainz.json", now=now)
        log.info("artist links from MusicBrainz: %s", st_enrich)
        st_listing = enrich_from_listings(artists, fetcher, out_dir / "cache" / "artist-links.json",
                                          prev_dir / "cache" / "artist-links.json", now=now, budget=80)
        log.info("artist links from listings: %s", st_listing)
    for a in artists:
        a.pop("_places", None)
    for s in states:
        s["eventsInFeed"] = sum(1 for e in events if any(x["sourceId"] == s["id"] for x in e["sources"]))

    bundle = {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": _iso(now),
        "city": venues_cfg["city"],
        "events": events,
        "venues": registry.export(),
        "artists": sorted(artists, key=lambda a: a["name"].lower()),
        "sources": states,
    }
    (out_dir / city / "bundle.json").write_text(json.dumps(bundle, ensure_ascii=False, separators=(",", ":")))
    index = {"schemaVersion": SCHEMA_VERSION, "generatedAt": _iso(now),
             "cities": [{**venues_cfg["city"], "bundle": f"{city}/bundle.json", "eventCount": len(events)}]}
    (out_dir / "index.json").write_text(json.dumps(index, indent=1))
    (out_dir / city / "report.md").write_text(report(bundle))
    return bundle


def report(b: dict) -> str:
    ev = b["events"]
    lines = [f"# Ingest report – {b['city']['name']}", "", f"Generated {b['generatedAt']} (UTC)", "",
             f"- Events in feed: **{len(ev)}**",
             f"- Verified: {sum(e['verification']=='verified' for e in ev)}, cross-checked: "
             f"{sum(e['verification']=='cross_checked' for e in ev)}, unconfirmed: {sum(e['verification']=='unconfirmed' for e in ev)}",
             f"- Multi-source (consolidated duplicates): {sum(len(e['sources'])>1 for e in ev)}",
             f"- Cancelled/postponed/unlisted: {sum(e['status']!='scheduled' for e in ev)}",
             f"- Venues: {len(b['venues'])} ({sum(v['provisional'] for v in b['venues'])} provisional, auto-discovered)",
             f"- Artists: {len(b['artists'])} (" + ", ".join(f"{k}: {sum(a['identity']==k for a in b['artists'])}"
                                                              for k in ['verified', 'venue_linked', 'aggregator_linked', 'unverified']) + ")",
             "", "## Sources", "", "| Source | Status | Found last run | In feed | Last success | Note |", "|---|---|---|---|---|---|"]
    for s in b["sources"]:
        note = s.get("warning") or s.get("lastError") or s.get("note") or ""
        lines.append(f"| {s['name']} | {s.get('status')} | {s.get('eventsDiscovered', '')} | {s.get('eventsInFeed', 0)} | "
                     f"{s.get('lastSuccess', '')} | {str(note).replace('|', '/')[:160]} |")
    from collections import Counter
    lines += ["", "## Categories", ""] + [f"- {k}: {v}" for k, v in Counter(e["category"] for e in ev).most_common()]
    prov = [v for v in b["venues"] if v["provisional"]]
    if prov:
        lines += ["", "## Auto-discovered venues to review", ""] + \
                 [f"- {v['name']} ({v.get('municipality') or '?'}) – {v.get('address') or 'no address'}" for v in prov]
    return "\n".join(lines) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default=str(Path(__file__).resolve().parent.parent / "config"))
    ap.add_argument("--prev", default="../feed")
    ap.add_argument("--out", default="../feed")
    ap.add_argument("--force", action="store_true", help="ignore per-source frequency")
    ap.add_argument("--only", default="", help="comma-separated source ids")
    ap.add_argument("--no-geocode", action="store_true")
    ap.add_argument("--no-enrich", action="store_true", help="skip MusicBrainz artist links")
    a = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    b = run(Path(a.config), Path(a.prev), Path(a.out), force=a.force,
            only=set(filter(None, a.only.split(","))) or None, geocode=not a.no_geocode, enrich=not a.no_enrich)
    print(report(b))
    ok = sum(1 for s in b["sources"] if s.get("status") == "ok")
    # Fail the job only when every enabled source failed (feed would be stale).
    enabled = [s for s in b["sources"] if s.get("enabled") and s.get("status") not in ("needs_api_key", "manual")]
    if enabled and ok == 0:
        print("All sources failed", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
