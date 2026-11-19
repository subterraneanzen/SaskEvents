from . import amigos, broadway, elfsight, ics, jsonld, ticketmaster

ADAPTERS = {
    "amigos_html": amigos.fetch,
    "broadway_platform": broadway.fetch,
    "elfsight_calendar": elfsight.fetch,
    "jsonld": jsonld.fetch,
    "ics": ics.fetch,
    "ticketmaster_api": ticketmaster.fetch,
}


class NeedsConfiguration(Exception):
    """Raised when a source cannot run without configuration (e.g. an API key)."""
