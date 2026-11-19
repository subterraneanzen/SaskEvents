"""Polite HTTP fetching: identifies itself, honours robots.txt, rate-limits per host, retries transient errors."""
from __future__ import annotations

import logging
import time
import urllib.robotparser
from urllib.parse import urlparse

import requests

log = logging.getLogger(__name__)

USER_AGENT = (
    "SaskEventsBot/1.0 (+https://github.com/subterraneanzen/SaskEvents; "
    "personal non-commercial local events guide; checks a few times per day)"
)
# Matching token for robots.txt rules.
ROBOTS_TOKEN = "SaskEventsBot"


class RobotsDisallowed(Exception):
    pass


class FetchError(Exception):
    pass


class Fetcher:
    def __init__(self, min_delay: float = 1.5, timeout: float = 25.0, retries: int = 2):
        self.session = requests.Session()
        self.session.headers.update({
            "User-Agent": USER_AGENT,
            "Accept-Language": "en-CA,en;q=0.8",
        })
        self.min_delay = min_delay
        self.timeout = timeout
        self.retries = retries
        self._last: dict[str, float] = {}
        self._robots: dict[str, urllib.robotparser.RobotFileParser | None] = {}
        self.request_count = 0

    def _robots_for(self, url: str):
        p = urlparse(url)
        base = f"{p.scheme}://{p.netloc}"
        if base not in self._robots:
            rp = urllib.robotparser.RobotFileParser()
            try:
                r = self.session.get(base + "/robots.txt", timeout=self.timeout)
                self.request_count += 1
                if r.status_code >= 400:
                    rp = None  # no robots.txt => allowed
                else:
                    rp.parse(r.text.splitlines())
            except requests.RequestException:
                rp = None
            self._robots[base] = rp
        return self._robots[base]

    def allowed(self, url: str) -> bool:
        rp = self._robots_for(url)
        if rp is None:
            return True
        return rp.can_fetch(ROBOTS_TOKEN, url)

    def _throttle(self, url: str):
        host = urlparse(url).netloc
        wait = self.min_delay - (time.monotonic() - self._last.get(host, 0))
        if wait > 0:
            time.sleep(wait)
        self._last[host] = time.monotonic()

    def get(self, url: str, *, params=None, headers=None, check_robots=True) -> requests.Response:
        if check_robots and not self.allowed(url):
            raise RobotsDisallowed(f"robots.txt disallows {url}")
        last_exc: Exception | None = None
        for attempt in range(self.retries + 1):
            self._throttle(url)
            try:
                r = self.session.get(url, params=params, headers=headers, timeout=self.timeout)
                self.request_count += 1
                if r.status_code in (429, 500, 502, 503, 504) and attempt < self.retries:
                    time.sleep(3 * (attempt + 1))
                    continue
                if r.status_code >= 400:
                    raise FetchError(f"HTTP {r.status_code} for {url}")
                return r
            except requests.RequestException as e:
                last_exc = e
                time.sleep(2 * (attempt + 1))
        raise FetchError(f"{type(last_exc).__name__}: {last_exc} for {url}")
