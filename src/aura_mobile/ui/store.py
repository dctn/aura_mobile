"""One cache of what the screens show. Every page draws from it, so switching tabs draws at once.

Why: each call to us-west-1 takes 0.25–0.9 s from here. Pages used to fetch their parts one after another and
reload everything after each tap, so a change took 5–10 s to appear. Now:

- :meth:`Store.load` fetches the parts it is given **in parallel**, on a worker.
- :meth:`Store.patch` changes the cache **locally** and redraws now. The page sends the write after, then loads
  that part again to confirm. A load that started before a patch is dropped when it lands, so a slow answer can't
  undo what the user just did.
- Pages redraw only the cards whose parts changed.
"""

from __future__ import annotations

import logging
import math
import time
from collections.abc import Callable, Iterable
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

from PyQt6.QtCore import QObject, pyqtSignal

from aura_mobile.ui.tasks import run_async

log = logging.getLogger(__name__)

ALERT_LOOKBACK_DAYS = 40
#: a page shown again re-asks for parts older than this
STALE_AFTER_S = 30.0

_pool = ThreadPoolExecutor(max_workers=12, thread_name_prefix="aura-fetch")


def _today(api: Any) -> date:
    return datetime.now(ZoneInfo(api.time_zone)).date()


def _metrics(api: Any, today: date) -> dict[str, Any]:
    days = api.metrics_range((today - timedelta(days=10)).isoformat(), today.isoformat())
    ok = [d for d in days if d.get("status") == "ok"]
    newest = max(ok, key=lambda d: str(d.get("date"))) if ok else None
    latest = api.metrics_day(str(newest["date"])) if newest is not None else None
    return {"days": days, "latest": latest}


def _week(today: date) -> tuple[str, str]:
    return (today - timedelta(days=7)).isoformat(), today.isoformat()


#: part name -> fetch(api, today)
FETCHERS: dict[str, Callable[[Any, date], Any]] = {
    "alerts": lambda api, d: api.alerts((d - timedelta(days=ALERT_LOOKBACK_DAYS)).isoformat(), d.isoformat()),
    "inbox": lambda api, d: api.notifications(limit=30),
    "events": lambda api, d: api.events(d.isoformat(), (d + timedelta(days=7)).isoformat()),
    "tasks": lambda api, d: api.tasks(),
    "metrics": _metrics,
    "checkins": lambda api, d: api.checkins(*_week(d)),
    "logs": lambda api, d: api.logs(*_week(d)),  # water (NI-04) and strength (PB-04)
    "active": lambda api, d: api.active_session(),
    "sessions": lambda api, d: api.sessions(*_week(d)),
    "me": lambda api, d: api.me(),
    "alert_off": lambda api, d: api.alert_settings(),
}
ALL_PARTS = tuple(FETCHERS)


def _message(exc: BaseException) -> str:
    return str(getattr(exc, "message", "") or f"{exc.__class__.__name__}: {exc}")


def fetch_all(api: Any, parts: Iterable[str]) -> dict[str, tuple[bool, Any]]:
    """Fetch every part at once. Each answer is ``(True, value)`` or ``(False, message)``; one failure stays local."""
    today = _today(api)

    def one(part: str) -> tuple[bool, Any]:
        try:
            return True, FETCHERS[part](api, today)
        except Exception as exc:  # shown on the page that needs it
            log.info("Loading %s failed: %r", part, exc)
            return False, _message(exc)

    parts = list(parts)
    return dict(zip(parts, _pool.map(one, parts), strict=True))


class Store(QObject):
    #: a frozenset of the part names that changed
    updated = pyqtSignal(object)

    def __init__(self, api: Any) -> None:
        super().__init__()
        self.api = api
        self.data: dict[str, Any] = {}
        self.errors: dict[str, str] = {}
        self.enabled: Callable[[], bool] = lambda: True
        self._gen: dict[str, int] = {}
        self._loaded_at: dict[str, float] = {}
        self._loading: set[str] = set()
        self._epoch = 0

    def get(self, part: str, default: Any = None) -> Any:
        value = self.data.get(part)
        return default if value is None else value

    def status(self, part: str, what: str) -> str:
        """What a card says while it has nothing to draw: the error, or that it is still loading."""
        return self.errors.get(part) or (f"{what} unavailable" if part in self._loaded_at else "Loading…")

    def load(self, parts: Iterable[str]) -> None:
        """Ask the server again for these parts, all at once."""
        wanted = tuple(dict.fromkeys(p for p in parts if p in FETCHERS))
        if not wanted or not self.enabled():
            return
        started = {p: self._gen.get(p, 0) for p in wanted}
        epoch = self._epoch
        self._loading.update(wanted)
        api = self.api

        def work() -> dict[str, tuple[bool, Any]]:
            return fetch_all(api, wanted)

        def done(results: dict[str, tuple[bool, Any]]) -> None:
            self._apply(results, started, epoch)

        run_async(work, done)

    def ensure(self, parts: Iterable[str], max_age: float = STALE_AFTER_S) -> None:
        """Load the parts that were never loaded or are older than ``max_age``, unless a load is under way."""
        now = time.monotonic()
        self.load([p for p in parts
                   if p not in self._loading and now - self._loaded_at.get(p, -math.inf) > max_age])

    def patch(self, part: str, change: Callable[[Any], Any]) -> None:
        """Change the cached part now and redraw; loads already under way for it are dropped when they land."""
        self.data[part] = change(self.data.get(part))
        self._gen[part] = self._gen.get(part, 0) + 1
        self.updated.emit(frozenset({part}))

    def clear(self) -> None:
        self._epoch += 1
        self.data.clear()
        self.errors.clear()
        self._loaded_at.clear()
        self._loading.clear()
        self.updated.emit(frozenset(FETCHERS))

    def _apply(self, results: dict[str, tuple[bool, Any]], started: dict[str, int], epoch: int) -> None:
        if epoch != self._epoch:
            return
        changed: set[str] = set()
        now = time.monotonic()
        for part, (ok, value) in results.items():
            self._loading.discard(part)
            if self._gen.get(part, 0) != started[part]:
                continue  # patched while this was loading; the patch's own reload brings the truth
            if ok:
                self.data[part] = value
                self.errors.pop(part, None)
            else:
                self.errors[part] = value  # keep showing the last good answer
            self._loaded_at[part] = now
            changed.add(part)
        if changed:
            self.updated.emit(frozenset(changed))
