"""The Test lab's engine: pick an unused window of past days, send the scenario, then look for its alert.

Why a window of *unused* days: a computed day is kept, and the alert engine allows one alert per feature per day
(``alert-<date>-<featureId>``). Re-sending the same dates would only re-compute what is there, so every run takes
fresh dates, walking back from yesterday. The dates already used are remembered per account in
``%APPDATA%\\AuraMobileDemo\\lab-<sub>.json``. When the windows reach more than ~35 days back, earlier runs start to
blur into the rules' 30-day history; delete the test account in Profile and sign up again for a clean one.

Synchronous and Qt-free. The UI runs :meth:`LabRunner.run` on a worker thread and passes ``progress``.
"""

from __future__ import annotations

import json
import os
import threading
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime, time, timedelta
from datetime import date as Date
from pathlib import Path
from zoneinfo import ZoneInfo

from aura_mobile.services.api import AuraApi
from aura_mobile.services.http import ApiError
from aura_mobile.services.sim.band import band_day, batches
from aura_mobile.services.sim.scenarios import Scenario

COMPUTES_PER_HOUR = 5
LOOKBACK_DAYS = 150
STALE_AFTER_DAYS = 35
UPLOAD_THREADS = 3  # dev runs at most 10 Lambdas at once for the whole account
NO_CONSENT = ("Health Data refused the run: this account has no health-data consent on dev (Identity has to be "
              "redeployed with the mobile routes first). Nothing was sent and no dates were used.")

Progress = Callable[[str, float], None]


def state_dir() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home())
    return Path(base) / "AuraMobileDemo"


@dataclass
class LabRun:
    feature_id: str
    target: str
    dates: list[str]
    started_at: str
    posted_packets: int = 0
    computed: bool = False
    found_alert: str | None = None


@dataclass
class LabState:
    """What this phone remembers about one account's lab: used dates, runs, and the compute calls it made."""

    path: Path
    touched: set[str] = field(default_factory=set)
    runs: list[LabRun] = field(default_factory=list)
    computes: list[str] = field(default_factory=list)  # ISO UTC instants

    @classmethod
    def load(cls, sub: str, directory: Path | None = None) -> LabState:
        safe = "".join(c for c in sub if c.isalnum() or c in "-_")[:64] or "anon"
        path = (directory or state_dir()) / f"lab-{safe}.json"
        state = cls(path)
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return state
        state.touched = set(raw.get("touched") or [])
        state.runs = [LabRun(**r) for r in raw.get("runs") or [] if isinstance(r, dict)]
        state.computes = list(raw.get("computes") or [])
        return state

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        body = {"touched": sorted(self.touched), "runs": [asdict(r) for r in self.runs[-200:]],
                "computes": self.computes[-50:]}
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(body, indent=1), encoding="utf-8")
        tmp.replace(self.path)

    def computes_this_hour(self, now: datetime | None = None) -> int:
        """The backend's cap is 5 per person per UTC clock hour."""
        now = (now or datetime.now(UTC)).astimezone(UTC)
        hour = now.replace(minute=0, second=0, microsecond=0)
        return sum(1 for c in self.computes if datetime.fromisoformat(c) >= hour)

    def latest_run(self, feature_id: str) -> LabRun | None:
        for run in reversed(self.runs):
            if run.feature_id == feature_id:
                return run
        return None

    def reset(self) -> None:
        self.touched.clear()
        self.runs.clear()
        self.save()


def choose_target(scenario: Scenario, touched: set[str], today: Date) -> Date | None:
    """The latest date ≤ yesterday that fits the scenario's weekday/day-of-month and whose window is unused."""
    for back in range(1, LOOKBACK_DAYS):
        target = today - timedelta(days=back)
        if not scenario.fits(target):
            continue
        if all(d.isoformat() not in touched for d in scenario.dates(target)):
            return target
    return None


class LabRunner:
    def __init__(self, api: AuraApi, state: LabState) -> None:
        self.api = api
        self.state = state
        self._cancel = threading.Event()

    @property
    def time_zone(self) -> str:
        return self.api.time_zone

    def today(self) -> Date:
        return datetime.now(ZoneInfo(self.time_zone)).date()

    def cancel(self) -> None:
        self._cancel.set()

    def plan(self, scenario: Scenario) -> tuple[Date, list[Date]] | None:
        target = choose_target(scenario, self.state.touched, self.today())
        if target is None:
            return None
        return target, scenario.dates(target)

    def is_stale(self, target: Date) -> bool:
        return (self.today() - target).days > STALE_AFTER_DAYS

    def run(self, scenario: Scenario, *, compute_now: bool = False, progress: Progress | None = None) -> LabRun:
        """Send the scenario. Returns the run; the alert is looked for afterwards with :meth:`find_alert`."""
        self._cancel.clear()
        report = progress or (lambda _m, _f: None)
        planned = self.plan(scenario)
        if planned is None:
            raise RuntimeError("No unused dates left in the last 150 days. Reset the lab or use a fresh account.")
        target, dates = planned
        run = LabRun(scenario.feature_id, target.isoformat(), [d.isoformat() for d in dates],
                     datetime.now(UTC).isoformat())
        # Mark the dates first: a half-sent window is still used.
        self.state.touched.update(run.dates)
        self.state.runs.append(run)
        self.state.save()

        # The first write goes alone: when the server refuses it (no consent on dev), nothing else is sent and the
        # run is forgotten, so its dates stay free.
        accepted = False
        try:
            tz = ZoneInfo(self.time_zone)
            for ci in scenario.checkins:
                day = target - timedelta(days=ci.days_before)
                taken = datetime.combine(day, time(ci.hour), tzinfo=tz)
                report(f"Check-in: {ci.kind} {ci.value}/5 on {day.isoformat()}", 0.02)
                self.api.post_checkin(ci.kind, taken_at=taken, **{ci.kind: ci.value})
                accepted = True

            shapes = scenario.shapes(target, self.time_zone)
            total = sum(len(band_day(s, with_rr=False)) for s in shapes) or 1
            sent = 0
            for shape in shapes:
                if self._cancel.is_set():
                    report("Stopped.", sent / total)
                    break
                chunks = list(batches(band_day(shape)))
                if chunks and not accepted:
                    sent += self._post_batch(chunks.pop(0))
                    run.posted_packets = sent
                    accepted = True
                with ThreadPoolExecutor(max_workers=UPLOAD_THREADS) as pool:
                    for done in pool.map(self._post_batch, chunks):
                        sent += done
                        run.posted_packets = sent
                        report(f"Sending {shape.date}: {sent}/{total} minutes", min(sent / total, 0.99))
            compute = scenario.compute_empty_target or compute_now
            if compute and not self._cancel.is_set():
                if self.state.computes_this_hour() >= COMPUTES_PER_HOUR:
                    raise RuntimeError("5 computes used this hour. The day's own final run comes in ~2 min anyway.")
                report(f"Computing {target.isoformat()}…", 0.99)
                self.api.compute_day(target.isoformat())
                self.state.computes.append(datetime.now(UTC).isoformat())
                run.computed = True
        except ApiError as exc:
            if not accepted:
                self._forget(run)
            if exc.status == 404 and "consent" in exc.message.lower():
                raise RuntimeError(NO_CONSENT) from exc
            raise
        self.state.save()
        report("Sent. Waiting for the day to compute (about 2 min)…", 1.0)
        return run

    def _post_batch(self, chunk: list[dict]) -> int:
        if self._cancel.is_set():
            return 0
        try:
            self.api.post_samples(chunk)
        except Exception:
            self._cancel.set()  # one refused batch stops the ones still queued
            raise
        return len(chunk)

    def _forget(self, run: LabRun) -> None:
        """A run the server refused outright: its dates go back to the free pool."""
        others = {d for r in self.state.runs if r is not run for d in r.dates}
        self.state.touched.difference_update(set(run.dates) - others)
        self.state.runs = [r for r in self.state.runs if r is not run]
        self.state.save()

    def find_alert(self, run: LabRun) -> dict | None:
        """The run's alert, if the backend raised it on any date of the window (timers may land on today)."""
        start = min(run.dates) if run.dates else run.target
        end = self.today().isoformat()
        started = run.started_at[:10]
        for alert in self.api.alerts(start, end):
            day = str(alert.get("date") or "")
            # Its own window, or a timer that fired after the run (an older run's alert is on its own dates).
            if alert.get("featureId") == run.feature_id and (day in run.dates or day >= started):
                run.found_alert = str(alert.get("id") or "")
                self.state.save()
                return alert
        return None

    def day_status(self, run: LabRun) -> dict | None:
        return self.api.metrics_day(run.target)
