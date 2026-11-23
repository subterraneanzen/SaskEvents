import json
from datetime import date, datetime, time, timezone
from pathlib import Path

import pytest

from ingest.adapters import amigos, broadway, elfsight, ics, jsonld, jumpcomedy
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
            jumpcomedy.WIDGET_URL.format(id=8): "jumpcomedy_artbar.json",
            jumpcomedy.WIDGET_URL.format(id=9): "jumpcomedy_artbar.json",
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
    # Art Bar: official Jump Comedy widget, two widgets listing the same shows -> no duplicates
    assert states["artbar"]["status"] == "ok"
    ab = [e for e in ev if e["venueId"] == "art-bar"]
    assert len([e for e in ab if e["title"] == "Edges"]) == 2
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


def test_regrouped_event_is_superseded_not_unlisted(tmp_path):
    now = datetime(2026, 11, 18, 18, 0, tzinfo=timezone.utc)
    b = run(CONFIG, tmp_path / "p", tmp_path / "o1", force=True, fetcher=FixtureFetcher(), geocode=False, now=now)
    # Simulate an old id that no longer matches any cluster but whose source listing still exists.
    bundle_path = tmp_path / "o1" / "saskatoon" / "bundle.json"
    data = json.loads(bundle_path.read_text())
    ghost = dict(data["events"][0]); ghost["id"] = "e-ghost"
    data["events"].append(ghost)
    bundle_path.write_text(json.dumps(data))
    b2 = run(CONFIG, tmp_path / "o1", tmp_path / "o2", force=True, fetcher=FixtureFetcher(), geocode=False, now=now)
    assert "e-ghost" not in {e["id"] for e in b2["events"]}


def test_unescape_and_title_split():
    from ingest.normalize import unescape_all
    assert unescape_all("80s &amp;amp; 90s") == "80s & 90s"
    got = performers_from_title("Acoustic Sessions Christmas : Munro &amp; Patrick, Jay Semko, &amp; Chris Henderson")
    assert "Chris Henderson" in got and "Jay Semko" in got and not any("&amp;" in g for g in got)
    assert performers_from_title("80s &amp;amp; 90s Over 30s Daytime Clubbing - SASKATOON") == []


def test_musicbrainz_enrichment(tmp_path):
    from datetime import datetime, timezone
    from ingest.enrich import enrich_artists

    class MBFetcher:
        calls = 0

        def get(self, url, params=None, headers=None, check_robots=True):
            MBFetcher.calls += 1

            class R:
                pass
            r = R()
            if url.endswith("/url"):
                if params["resource"] == "https://www.songkick.com/artists/111":
                    r.json = lambda: {"relations": [{"artist": {"id": "mb-1", "name": "Band One"}}]}
                    return r
                raise RuntimeError("HTTP 404 for x")
            r.json = lambda: {"relations": [
                {"type": "bandcamp", "url": {"resource": "https://bandone.bandcamp.com/"}},
                {"type": "youtube", "url": {"resource": "https://www.youtube.com/channel/UC123"}},
                {"type": "social network", "url": {"resource": "https://www.facebook.com/bandone"}},
                {"type": "discogs", "url": {"resource": "https://www.discogs.com/artist/1"}},
                {"type": "purchase for download", "url": {"resource": "https://itunes.apple.com/x"}},
                {"type": "free streaming", "url": {"resource": "https://www.deezer.com/artist/1"}},
                {"type": "youtube", "url": {"resource": "https://www.youtube.com/@second"}},
                {"type": "official homepage", "url": {"resource": "https://bandone.ca/"}},
                {"type": "social network", "ended": True, "url": {"resource": "https://www.instagram.com/old"}},
            ]}
            return r

    arts = [
        {"id": "a1", "name": "Band One", "links": [{"type": "songkick", "url": "https://www.songkick.com/artists/111-band-one"}], "evidence": []},
        {"id": "a2", "name": "Band Two", "links": [{"type": "songkick", "url": "https://www.songkick.com/artists/222-band-two"}], "evidence": []},
        {"id": "a3", "name": "Band One", "links": [], "evidence": []},  # same name, no profile: never enriched
    ]
    now = datetime(2026, 11, 19, tzinfo=timezone.utc)
    st = enrich_artists(arts, MBFetcher(), tmp_path / "c" / "mb.json", tmp_path / "prev.json", now=now)
    types = [l["type"] for l in arts[0]["links"]]
    assert types == ["songkick", "bandcamp", "youtube", "facebook", "website"]
    assert all("MusicBrainz" in l["provenance"] for l in arts[0]["links"][1:])
    assert len(arts[1]["links"]) == 1 and arts[2]["links"] == []
    assert st["enriched"] == 1 and st["errors"] == 0
    # Second run uses the cache (including the negative result) and adds no duplicates.
    calls = MBFetcher.calls
    enrich_artists(arts, MBFetcher(), tmp_path / "c" / "mb.json", tmp_path / "c" / "mb.json", now=now)
    assert MBFetcher.calls == calls and len(arts[0]["links"]) == 5


def test_squarespace_blog_weekend_guide():
    from ingest.adapters import squarespace_blog
    html = (FX / "sneakers_weekend.html").read_text()
    src = SRC["sneakers-lipstick"]
    evs = {e.title: e for e in squarespace_blog.parse_post(html, src, src["url"])}
    assert set(evs) == {"Test Festival of Lights", "Test Holiday Craft Market", "Test Comedy Night",
                        "Test Family Skate", "Test Brunch Pop-up"}  # no "Ongoing"/"Newsletter signup"/footer
    lights = evs["Test Festival of Lights"]
    assert (lights.start_date, lights.end_date) == (date(2026, 11, 20), date(2026, 12, 31))
    assert lights.start_time == time(17, 0) and lights.venue_name == "Example Gardens"
    assert (lights.price_min, lights.price_max) == (6, 12) and lights.ticket_url == "https://example.org/lights"
    mkt = evs["Test Holiday Craft Market"]
    assert mkt.start_date == date(2026, 11, 20) and mkt.start_time == time(10, 0) and mkt.end_time == time(16, 0)
    assert (mkt.venue_name, mkt.venue_address) == ("Example Hall", "123 Main St")
    assert mkt.is_free is True
    com = evs["Test Comedy Night"]
    assert com.start_time == time(20, 0) and com.venue_name == "The Example Room" and com.age_text == "19+" and com.price_min == 20
    # Weekday headers resolve against the post's last-modified date (Thu Nov 19), never the machine clock.
    skate = evs["Test Family Skate"]
    assert skate.start_date == date(2026, 11, 21) and skate.start_time == time(13, 0) and skate.end_time == time(15, 0)
    brunch = evs["Test Brunch Pop-up"]
    assert brunch.start_date == date(2026, 11, 22) and brunch.status == "cancelled" and brunch.venue_name == "Example Cafe"
    assert all(e.performers == [] for e in evs.values())
    assert len({e.source_key for e in evs.values()}) == 5


def test_art_bar_listings_resolve_to_art_bar_venue():
    from ingest.models import RawEvent
    from ingest.venues import VenueRegistry
    reg = VenueRegistry(json.loads((CONFIG / "venues.json").read_text()))
    d = date(2026, 12, 3)
    names = ["Art Bar", "ART BAR", "Art Bar – Saskatoon", "Art Bar Theatre"]
    for n in names:
        assert reg.resolve(RawEvent("x", n, "u", "Show", d, venue_name=n)) == "art-bar", n
    # Listings that only give the street address (Ticket Tailor, AllEvents)
    ev = RawEvent("x", "k", "u", "Show", d, venue_name="Back Room", venue_address="229 20th St W, Saskatoon, SK")
    assert reg.resolve(ev) == "art-bar"
    assert SRC["artbar"]["venueId"] == "art-bar" and SRC["artbar"]["enabled"]


def test_jumpcomedy_artbar_widget():
    data = json.loads((FX / "jumpcomedy_artbar.json").read_text())
    evs = jumpcomedy.parse_widget(data, SRC["artbar"], today=date(2026, 11, 25))
    by = {}
    for e in evs:
        by.setdefault(e.title, []).append(e)
    # Comedy Pit on Nov 24 is past; Dec 8 remains. External seller (Showpass) is the ticket link.
    pit = by["The Comedy Pit- a showcase of CLEAN standup comedy"]
    assert [e.start_date for e in pit] == [date(2026, 12, 8)] and "showpass.com" in pit[0].ticket_url
    assert pit[0].start_time == time(19, 0) and pit[0].price_text == "$10" and pit[0].venue_id == "art-bar"
    # Generic " - COMEDY" label stripped and used as a hint; descriptive labels stay in the title.
    hp = by["Hassan Phills"]
    assert hp[0].category_hint == "comedy" and hp[0].start_time == time(21, 30)
    assert "sold_out" in hp[1].tags and "sold_out" not in hp[0].tags
    assert "Out Of This World!- INTERGALACTIC SPACE DRAG" in by
    assert by["Edges"][0].category_hint == "theatre" and len(by["Edges"]) == 2
    # Live early-bird price gives a range; the expired Nov 7 override price is ignored.
    sb = by["Stitch Bitch Drag Show"][0]
    assert sb.price_text == "$20–$30" and sb.price_min == 20
    # Jump-sold show: ticket link is its Jump Comedy event page.
    assert by["Henry Sir Comedy"][0].ticket_url == "https://www.jumpcomedy.com/e/henry-sir-comedy"
    assert sb.age_text == "19+ (venue policy)" and sb.venue_address.startswith("229 20th St W")
