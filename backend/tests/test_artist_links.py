import json
from datetime import datetime, timezone

from ingest.artist_links import (attach_listing_links, handle_matches, handle_of, hometowns_for_performers,
                                 is_clear_place, links_for_performers, social_links_on_own_page)
from ingest.artists import ArtistBook
from ingest.enrich import enrich_from_listings
from ingest.models import Performer

AMIGOS_DESC = """<div class="event-description"><p>19+ w/ Valid ID</p>
<p>A new project from Piper Burns, longtime music and worrying enthusiast.</p>
<p>Dump Babes are a garage band.</p>
<p>LISTEN: <a href="https://dumpbabes.bandcamp.com/">https://dumpbabes.bandcamp.com/</a></p>
<p><a href="https://tickets.amigoscantina.com/events?p=event&event=318">Tickets</a>
<a href="https://www.facebook.com/events/1157106813449575/">RSVP</a>
<a href="https://www.amigoscantina.com/">Amigos</a></p></div>"""


def test_listen_line_goes_to_the_act_its_handle_spells():
    names = ["P. Burns & the Unrelenting Fear", "Dump Babes"]
    assert links_for_performers(AMIGOS_DESC, names, ["amigoscantina.com"]) == {"Dump Babes": ["https://dumpbabes.bandcamp.com/"]}


def test_anchor_text_paragraph_and_raw_urls():
    html = """<p><a href="https://www.facebook.com/sharptailband">Sharptail</a> opens the night.</p>
    <p>Lachlan Neville writes songs on the prairie. Follow him: instagram.com/lachlannevillemusic</p>
    <p>Presented by <a href="https://www.facebook.com/nesscreek">Ness Creek</a> and <a href="https://www.facebook.com/x">friends</a></p>"""
    got = links_for_performers(html, ["Lachlan Neville and the Lakers", "Sharptail"])
    assert got["Sharptail"] == ["https://www.facebook.com/sharptailband"]
    assert got["Lachlan Neville and the Lakers"] == ["https://instagram.com/lachlannevillemusic"]
    assert all("nesscreek" not in u for v in got.values() for u in v)  # a promoter's page is never attributed


def test_paragraph_naming_two_acts_is_not_guessed():
    html = "<p>Band A and Band B tour together. <a href='https://www.facebook.com/tourpage'>Tour</a></p>"
    assert links_for_performers(html, ["Band A", "Band B"]) == {}


def test_handles():
    assert handle_of("https://www.facebook.com/BlackCatyxe/") == "blackcatyxe"
    assert handle_of("https://dumpbabes.bandcamp.com/album/x") == "dumpbabes"
    assert handle_of("https://www.youtube.com/@TheBrosLandreth") == "thebroslandreth"
    assert handle_matches("thebroslandreth", "The Bros. Landreth")
    assert handle_matches("sharptailmusic", "Sharptail")
    assert not handle_matches("amigoscantina", "Dump Babes")
    assert not handle_matches("sharp", "Sharptail")


def test_hometowns_per_act():
    text = ("19+ w/ Valid ID\n\nLachlan Neville is a singer/songwriter from Rocanville, Saskatchewan- a small town.\n\n"
            "Sharptail is a Regina-based alt-country band.")
    assert hometowns_for_performers(text, ["Lachlan Neville and the Lakers", "Sharptail"]) == {
        "Lachlan Neville and the Lakers": ["Rocanville, Saskatchewan"], "Sharptail": ["Regina"]}
    assert hometowns_for_performers("The Bros. Landreth are two-time JUNO Award winners from Winnipeg.",
                                    ["The Bros. Landreth", "Madeleine Roger"]) == {"The Bros. Landreth": ["Winnipeg"]}
    # Two acts in one sentence: nobody gets the place.
    assert hometowns_for_performers("Band A and Band B are both from Calgary.", ["Band A", "Band B"]) == {}
    assert is_clear_place("Rocanville, Saskatchewan") and is_clear_place("Winnipeg") and not is_clear_place("Fats Waller")


def test_bandcamp_page_only_uses_band_links():
    html = """<html><body><a href="https://www.facebook.com/bandcamp">Bandcamp on FB</a>
    <ol id="band-links"><li><a href="https://www.facebook.com/dumpbabes">Facebook</a></li>
    <li><a href="https://www.instagram.com/dumpbabes/">Instagram</a></li></ol></body></html>"""
    assert social_links_on_own_page(html, "https://dumpbabes.bandcamp.com/", "Dump Babes") == [
        "https://www.facebook.com/dumpbabes", "https://www.instagram.com/dumpbabes/"]


def test_own_site_with_two_facebook_links_keeps_the_matching_one():
    html = """<a href="https://facebook.com/somevenue">Venue</a><a href="https://facebook.com/dumpbabes">Us</a>
    <a href="https://www.facebook.com/sharer/sharer.php?u=x">Share</a>"""
    assert social_links_on_own_page(html, "https://dumpbabes.ca/", "Dump Babes") == ["https://facebook.com/dumpbabes"]


def test_aggregator_links_need_matching_handle():
    book = ArtistBook([])
    p = Performer(name="Dump Babes", listed_links=["https://dumpbabes.bandcamp.com/", "https://facebook.com/promoter"])
    aid = book.resolve(p, event_id="e1", venue_id="v", municipality="Saskatoon", source_name="AllEvents", source_tier=3)
    assert [l["url"] for l in book.records[aid]["links"]] == ["https://dumpbabes.bandcamp.com/"]


class _R:
    def __init__(self, data=None, text=""):
        self._d, self.text = data, text

    def json(self):
        return self._d


class FakeMB:
    def __init__(self, search):
        self.search, self.calls = search, []

    def get(self, url, params=None, headers=None, check_robots=True):
        self.calls.append(url)
        if url.endswith("/ws/2/artist"):
            return _R({"artists": self.search})
        if "/ws/2/artist/" in url:
            return _R({"relations": [
                {"type": "social network", "url": {"resource": "https://www.facebook.com/thebroslandreth"}},
                {"type": "bandcamp", "url": {"resource": "https://broslandreth.bandcamp.com/"}}]})
        raise RuntimeError(f"HTTP 404 for {url}")


def _artist(name, places):
    return {"id": "a-x", "name": name, "identity": "unverified", "links": [], "evidence": [], "genres": [],
            "hometown": None, "_places": places}


def test_hometown_match_adds_facebook(tmp_path):
    fx = FakeMB([{"id": "mb-1", "name": "The Bros. Landreth", "area": {"name": "Winnipeg"}},
                 {"id": "mb-2", "name": "The Bros. Landreth", "area": {"name": "Germany"}}])
    a = _artist("The Bros. Landreth", ["Winnipeg"])
    st = enrich_from_listings([a], fx, tmp_path / "c.json", tmp_path / "p.json", now=datetime.now(timezone.utc))
    assert st["hometown_matches"] == 1
    assert a["identity"] == "matched" and a["hometown"] == "Winnipeg"
    assert {l["type"] for l in a["links"]} == {"facebook", "bandcamp"}
    assert "_places" not in a
    # cached: a second run makes no requests
    fx.calls.clear()
    enrich_from_listings([_artist("The Bros. Landreth", ["Winnipeg"])], fx, tmp_path / "c.json", tmp_path / "p.json",
                         now=datetime.now(timezone.utc))
    assert fx.calls == []


def test_hometown_mismatch_or_ambiguity_adds_nothing(tmp_path):
    # Same name, but from elsewhere.
    fx = FakeMB([{"id": "mb-9", "name": "Sharptail", "area": {"name": "Leeds"}}])
    a = _artist("Sharptail", ["Regina"])
    enrich_from_listings([a], fx, tmp_path / "c.json", tmp_path / "p.json", now=datetime.now(timezone.utc))
    assert a["links"] == [] and a["identity"] == "unverified"
    # Two artists from the same province: ambiguous.
    fx = FakeMB([{"id": "m1", "name": "Echo", "area": {"name": "Saskatchewan"}},
                 {"id": "m2", "name": "Echo", "begin-area": {"name": "Regina"}}])
    b = _artist("Echo", ["Regina, SK"])
    enrich_from_listings([b], fx, tmp_path / "c2.json", tmp_path / "p.json", now=datetime.now(timezone.utc))
    assert b["links"] == [] and b["hometown"] == "Regina, SK"  # stated hometown still shown, labelled as such


def test_province_level_match(tmp_path):
    fx = FakeMB([{"id": "m1", "name": "Sharptail", "area": {"name": "Saskatchewan"}},
                 {"id": "m2", "name": "Sharptail", "area": {"name": "Leeds"}}])
    a = _artist("Sharptail", ["Regina"])
    enrich_from_listings([a], fx, tmp_path / "c.json", tmp_path / "p.json", now=datetime.now(timezone.utc))
    assert a["identity"] == "matched"


def test_reads_linked_bandcamp_for_socials(tmp_path):
    class F:
        def get(self, url, params=None, headers=None, check_robots=True):
            if "musicbrainz" in url:
                raise RuntimeError(f"HTTP 404 for {url}")
            return _R(text='<ol id="band-links"><li><a href="https://www.facebook.com/dumpbabes">fb</a></li></ol>')
    a = {"id": "a", "name": "Dump Babes", "identity": "venue_linked", "evidence": [], "hometown": None,
         "links": [{"type": "bandcamp", "url": "https://dumpbabes.bandcamp.com/", "provenance": "listed next to this name by Amigos"}]}
    enrich_from_listings([a], F(), tmp_path / "c.json", tmp_path / "p.json", now=datetime.now(timezone.utc))
    fb = [l for l in a["links"] if l["type"] == "facebook"]
    assert fb and fb[0]["url"] == "https://www.facebook.com/dumpbabes" and "Bandcamp" in fb[0]["provenance"]


def test_attach_to_performers():
    perfs = [Performer(name="P. Burns & the Unrelenting Fear"), Performer(name="Dump Babes")]
    assert attach_listing_links(perfs, AMIGOS_DESC, ["amigoscantina.com"]) == 1
    assert perfs[1].listed_links == ["https://dumpbabes.bandcamp.com/"]
