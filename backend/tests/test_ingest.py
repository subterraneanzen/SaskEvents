import json
from datetime import date, datetime, time, timezone
from pathlib import Path

import pytest

from ingest.adapters import amigos, broadway, elfsight, ics, jsonld
from ingest.main import run
from ingest.normalize import infer_category, parse_age, parse_price, performers_from_title

FX = Path(__file__).parent / "fixtures"
CONFIG = Path(__file__).parent.parent / "config"
SRC = {s["id"]: s for s in json.loads((CONFIG / "sources.json").read_text())["sources"]}


def test_amigos_listing_and_detail():
    evs = amigos.parse_listing((FX / "amigos_list.html").read_text(), SRC["amigos"])
    assert [e.title for e in evs][:2] == ["Amigos Trivia Night", "JJ Taylor w/ Cole Patton"]
    jj = evs[1]
    assert jj.start_date == date(2026, 11, 21) and jj.start_time == time(22, 0)
    assert jj.venue_id == "amigos-cantina"
    assert jj.links[0]["url"].startswith("https://www.facebook.com/events/")
    assert [p.name for p in jj.performers] == ["JJ Taylor", "Cole Patton"]
    assert evs[0].performers == []  # trivia is not a performer
    w = evs[3]
    assert w.start_time == time(22, 15)
    assert [p.name for p in w.performers] == ["Cola", "Gizmo", "Lucy's Seduction"]
    lach = evs[2]
    amigos.apply_detail(lach, (FX / "amigos_detail.html").read_text())
    assert lach.age_text == "19+"
    assert "Rocanville" in lach.description
    assert lach.ticket_url.endswith("event=308")
    assert lach.price_text is None  # not stated -> not guessed


def test_elfsight_blackcat():
    data = json.loads((FX / "elfsight_boot.json").read_text())
    evs = elfsight.parse_boot(data, SRC["blackcat"], today=date(2026, 11, 18))
    by = {e.title: e for e in evs}
    assert "Customer Service w/ The New Daydream" not in by  # past events dropped
    bg = by["Booze & Glory w/ The No Goes & Guests"]
    names = {p.name: p for p in bg.performers}
    assert names["Booze & Glory"].listed_links == ["http://boozeandglory.com"]
    assert names["The No Goes"].listed_links == ["https://thenogoes.bandcamp.com"]
    assert all("show.ps" not in l for p in bg.performers for l in p.listed_links)  # ticket links never attributed
    assert bg.age_text == "19+" and bg.doors_time == time(21, 0)
    assert bg.ticket_url == "https://show.ps/l/8e6cc1b0/"
    nf = by["Nameless Friends w/ Checkout Queens & Slutty Waist"]
    nfp = {p.name: p.listed_links for p in nf.performers}
    assert nfp["Nameless Friends"] == ["https://linktr.ee/namelessfriends"]
    assert nfp["Checkout Queens"] == ["https://linktr.ee/checkoutqueens"]
    assert nfp["Slutty Waist"] == []
    assert by["Karaoke"].is_free is True
    assert by["Drag Queen Trivia: Theme TBA!"].is_free is False and by["Drag Queen Trivia: Theme TBA!"].price_min is None
    assert by["Protest the Hero"].venue_id == "louis-pub"  # widget location, not assumed Black Cat
    assert by["Truck Violence w/ Special Guests"].venue_id == "black-cat-tavern"


def test_broadway_platform():
    evs = broadway.parse_listing((FX / "broadway.html").read_text(), SRC["broadway-theatre"])
    assert evs[0].title == "The Bros. Landreth w/ Madeleine Roger" and "sold_out" in evs[0].tags
    assert evs[0].venue_id is None and evs[0].venue_name == "Broadway Theatre"
    assert evs[1].price_min == 16 and evs[1].doors_time == time(19, 0)
    assert evs[2].venue_name == "Black Cat Tavern" and evs[2].price_max == 45
    assert evs[0].source_url == "https://broadwaytheatre.ca/events?p=event&event=4300"


def test_jsonld_songkick_and_allevents():
    sk = jsonld.parse_html((FX / "songkick.html").read_text(), SRC["songkick"], SRC["songkick"]["url"])
    landreth = sk[0]
    assert landreth.title == "The Bros. Landreth" and landreth.venue_name == "Broadway Theatre"
    assert landreth.start_time == time(19, 30) and landreth.description is None
    assert "utm_" not in landreth.source_url
    assert landreth.performers[0].profile_platform == "songkick"
    assert sk[3].status == "cancelled"
    ae = jsonld.parse_html((FX / "allevents.html").read_text(), SRC["allevents-capitol"], SRC["allevents-capitol"]["url"])
    assert ae[0].venue_name == "Capitol Music Club" and ae[0].start_time is None
    assert ae[1].image_url is None  # generated placeholder art dropped
    assert (ae[1].price_min, ae[1].price_max) == (26, 39)


def test_normalizers():
    assert parse_age("Ages: 19+ I.D. Required") == "19+"
    assert parse_age("Great show tonight") is None
    assert parse_age("ALL AGES show") == "all_ages"
    assert parse_price("No cover")[3] is True
    assert parse_price("$15 advance / $20 at the door")[1:3] == (15, 20)
    assert performers_from_title("Patti and the Stack") == ["Patti and the Stack"]
    assert performers_from_title("Punk Rock Bingo") == []
    assert infer_category("Saskatoon Blades vs. Lethbridge Hurricanes", None, None, "arena", False)[0] == "sports"
    assert infer_category("Amigos Trivia Night", None, None, "bar_restaurant", False)[0] == "community"
    assert infer_category("The Saskatoon Soaps Present: Lake America", "Live improv comedy", None, "theatre", False)[0] == "comedy"


def test_ics():
    text = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:1\r\nSUMMARY:Family Storytime\r\nDTSTART;TZID=America/Regina:20261120T103000\r\nDTEND;TZID=America/Regina:20261120T110000\r\nLOCATION:Frances Morrison Central Library\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
    evs = ics.parse_ics(text, {"id": "x", "url": "https://x"})
    assert evs[0].start_time == time(10, 30) and evs[0].end_time == time(11, 0)


class FixtureFetcher:
    """Serves fixtures instead of the network."""
    def __init__(self):
        self.map = {
            "https://www.amigoscantina.com/events-and-concerts/": "amigos_list.html",
            "https://www.amigoscantina.com/events/lachlan-neville-and-the-lakers-w-sharptail/": "amigos_detail.html",
            "https://broadwaytheatre.ca/events": "broadway.html",
            "https://www.songkick.com/metro-areas/27391-canada-saskatoon": "songkick.html",
            "https://www.bandsintown.com/c/saskatoon-sk": "bandsintown.html",
            "https://allevents.in/saskatoon/capitol": "allevents.html",
            elfsight.BOOT_URL: "elfsight_boot.json",
        }

    def get(self, url, params=None, headers=None, check_robots=True):
        class R:
            pass
        if url not in self.map:
            raise RuntimeError(f"HTTP 404 for {url}")
        r = R()
        r.text = (FX / self.map[url]).read_text()
        r.json = lambda: json.loads(r.text)
        return r


def test_full_run(tmp_path):
    now = datetime(2026, 11, 18, 18, 0, tzinfo=timezone.utc)
    b = run(CONFIG, tmp_path / "prev", tmp_path / "out", force=True, fetcher=FixtureFetcher(), geocode=False, now=now)
    ev = b["events"]
    states = {s["id"]: s for s in b["sources"]}
    assert states["amigos"]["status"] == "ok" and states["ticketmaster"]["status"] == "needs_api_key"
    assert states["amigos-tickets"]["status"] == "failed"  # no fixture -> recorded, not hidden
    # Bros. Landreth: Broadway Theatre (official) + Songkick + Bandsintown (Madeleine Roger support slot) -> one event
    landreth = [e for e in ev if "Landreth" in e["title"]]
    assert len(landreth) == 1, [e["title"] for e in ev if e["startDate"] == "2026-11-18"]
    l = landreth[0]
    assert {s["sourceId"] for s in l["sources"]} >= {"broadway-theatre", "songkick"}
    assert l["verification"] == "verified" and l["venueId"] == "broadway-theatre"
    assert l["startTime"] == "19:30" and l["fieldSources"]["title"] == "broadway-theatre"
    # Truck Violence: Black Cat (official) + Songkick consolidated
    tv = [e for e in ev if "truck violence" in e["title"].lower()]
    assert len(tv) == 1 and len(tv[0]["sources"]) == 2
    # Winterruption at Black Cat listed by both Black Cat and Broadway Theatre ticketing -> one event at Black Cat
    w = [e for e in ev if e["startDate"] == "2027-01-21"]
    assert len(w) == 1 and w[0]["venueId"] == "black-cat-tavern"
    # Protest the Hero: venue says scheduled, Songkick says cancelled -> keep venue status, add note
    pth = [e for e in ev if e["title"] == "Protest the Hero"][0]
    assert pth["venueId"] == "louis-pub" and pth["status"] == "scheduled" and "cancel" in pth["statusNote"]
    # Out-of-town venue becomes a provisional venue with its municipality
    bk = [e for e in ev if "Brett Kissel" in e["title"]][0]
    v = {x["id"]: x for x in b["venues"]}[bk["venueId"]]
    assert v["provisional"] and v["municipality"] == "North Battleford"
    # Artists: venue-supplied links attached with provenance; name-only acts carry no links
    arts = {a["name"]: a for a in b["artists"]}
    assert arts["Booze & Glory"]["identity"] == "venue_linked"
    assert arts["Booze & Glory"]["links"][0]["url"] == "http://boozeandglory.com"
    assert arts["Cole Patton"]["identity"] == "unverified" and arts["Cole Patton"]["links"] == []
    assert arts["The Bros. Landreth"]["identity"] == "aggregator_linked"
    # Missing info stays missing
    trivia = [e for e in ev if e["title"] == "Amigos Trivia Night"][0]
    assert trivia["ageRestriction"] is None and trivia["priceText"] is None and trivia["category"] == "community"
    assert (tmp_path / "out" / "saskatoon" / "report.md").exists()

    # Second run a day later with Black Cat no longer listing Karaoke: event kept, then flagged after 2 misses.
    prev = tmp_path / "out"
    f = FixtureFetcher()
    data = json.loads((FX / "elfsight_boot.json").read_text())
    W = SRC["blackcat"]["options"]["widgetId"]
    data["data"]["widgets"][W]["data"]["settings"]["events"] = [
        e for e in data["data"]["widgets"][W]["data"]["settings"]["events"] if e["name"] != "Karaoke"]
    (tmp_path / "boot2.json").write_text(json.dumps(data))
    f.map[elfsight.BOOT_URL] = str(tmp_path / "boot2.json")
    ids = {e["title"]: e["id"] for e in ev}
    b2 = run(CONFIG, prev, tmp_path / "out2", force=True, fetcher=f, geocode=False, now=now.replace(day=19))
    k = [e for e in b2["events"] if e["title"] == "Karaoke"][0]
    assert k["status"] == "scheduled" and k["missCount"] == 1
    assert {e["title"]: e["id"] for e in b2["events"]}["JJ Taylor w/ Cole Patton"] == ids["JJ Taylor w/ Cole Patton"]  # stable ids
    b3 = run(CONFIG, tmp_path / "out2", tmp_path / "out3", force=True, fetcher=f, geocode=False, now=now.replace(day=20))
    k = [e for e in b3["events"] if e["title"] == "Karaoke"][0]
    assert k["status"] == "unlisted" and "No longer listed" in k["statusNote"]
