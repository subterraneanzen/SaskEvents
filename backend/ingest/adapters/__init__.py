from . import amigos, broadway, elfsight, ics, jsonld, squarespace_blog, ticketmaster

ADAPTERS = {
    "amigos_html": amigos.fetch,
    "broadway_platform": broadway.fetch,
    "elfsight_calendar": elfsight.fetch,
    "jsonld": jsonld.fetch,
    "ics": ics.fetch,
    "ticketmaster_api": ticketmaster.fetch,
    "squarespace_blog": squarespace_blog.fetch,
}


class NeedsConfiguration(Exception):
    """Raised when a source cannot run without configuration (e.g. an API key)."""
