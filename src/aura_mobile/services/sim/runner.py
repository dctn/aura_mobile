"""The Test lab's engine: pick an unused window of past days, send the scenario, then look for its alert.

Why a window of *unused* days: a computed day is kept, and the alert engine allows one alert per feature per day
(``alert-<date>-<featureId>``). Re-sending the same dates would only re-compute what is there, so every run takes
fresh dates, walking back from yesterday. The dates already used are remembered per account in
``%APPDATA%\\AuraMobileDemo\\lab-<sub>.json``. Every date a run writes lies in the last :data:`WINDOW_DAYS`
days (Health Data drops older band minutes); when that room is used up, wait for new days or delete the test account
in Profile and sign up again.

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
#: Health Data keeps a stored band minute for 35 days from the minute's own time (aws_docs health-data
#: data-access/keys.ts SAMPLE_DAYS), and DynamoDB may delete an expired one before its day is assembled (dev, 22 Oct:
#: 16 and 17 Sep lost their first hours, so DX-03's first two nights read 0). So every date a run writes stays in the
#: last 33 days: two days' margin for the upload and the assemble.
SAMPLE_DAYS = 35
WINDOW_DAYS = SAMPLE_DAYS - 2
NO_ROOM = (f"No room left in the last {WINDOW_DAYS} days (AWS keeps band data {SAMPLE_DAYS} days). Each new day "
           "frees one; or delete the account in Profile and sign up again.")
UPLOAD_THREADS = 3  # dev runs at most 10 Lambdas at once for the whole account
NO_CONSENT = ("Health Data refused the run: health data is off for this account. Turn it on (Settings, or sign in "
              "again) and run again. Nothing was sent and no dates were used.")
STOPPED_EARLY = "Stopped before anything was sent. No dates were used."
#: Health Data computes VO2max only when HRmax rests on the profile's age (pipeline/workout.py has_profile_hr_anchor)
NO_BIRTH_DATE = ("PB-04 reads VO2max, which AWS computes only with your birth date. Set it in Profile, then run "
                 "again. Nothing was sent.")

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
    #: the server took at least one of the run's writes (check-in, samples or compute)
    accepted: bool = False
    #: a run for several features: the ones whose alert was seen
    found: list[str] = field(default_factory=list)

    @property
    def is_ghost(self) -> bool:
        """Nothing of it reached the server: a refused or stopped run, or one saved by a lab from before 22 Oct
        that kept refused runs (no ``accepted`` flag, 0 packets)."""
        return not (self.accepted or self.posted_packets or self.computed or self.found_alert or self.found)


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
        if state.drop_ghosts():
            state.save()
        return state

    def drop_ghosts(self) -> int:
        """Forget runs that sent nothing and give their dates back. Returns how many were dropped."""
        ghosts = [r for r in self.runs if r.is_ghost]
        if ghosts:
            self.runs = [r for r in self.runs if not r.is_ghost]
            kept = {d for r in self.runs for d in r.dates}
            self.touched.difference_update({d for r in ghosts for d in r.dates} - kept)
        return len(ghosts)

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
    """The latest date ≤ yesterday that fits the scenario's weekday/day-of-month and whose footprint is unused and
    inside the last :data:`WINDOW_DAYS` days."""
    oldest = today - timedelta(days=WINDOW_DAYS)
    for back in range(1, WINDOW_DAYS + 1):
        target = today - timedelta(days=back)
        footprint = scenario.footprint(target)
        if min(footprint) < oldest:
            return None  # further back only moves the window further out
        if scenario.fits(target) and all(d.isoformat() not in touched for d in footprint):
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

    def run(self, scenario: Scenario, *, compute_now: bool = False, progress: Progress | None = None) -> LabRun:
        """Send the scenario. Returns the run; the alert is looked for afterwards with :meth:`find_alert`."""
        self._cancel.clear()
        report = progress or (lambda _m, _f: None)
        planned = self.plan(scenario)
        if planned is None:
            raise RuntimeError(NO_ROOM)
        if "PB-04" in scenario.feature_ids and not (self.api.me() or {}).get("birthDate"):
            raise RuntimeError(NO_BIRTH_DATE)
        target, _dates = planned
        run = LabRun(scenario.feature_id, target.isoformat(), [d.isoformat() for d in scenario.footprint(target)],
                     datetime.now(UTC).isoformat())
        # Mark the dates first: a half-sent window is still used.
        self.state.touched.update(run.dates)
        self.state.runs.append(run)
        self.state.save()

        # The first write goes alone: when the server refuses it (health data off), nothing else is sent and the
        # run is forgotten, so its dates stay free. So is a run stopped before anything was sent.
        try:
            tz = ZoneInfo(self.time_zone)
            for ci in scenario.checkins:
                day = target - timedelta(days=ci.days_before)
                taken = datetime.combine(day, time(ci.hour), tzinfo=tz)
                report(f"Check-in: {ci.kind} {ci.value}/5 on {day.isoformat()}", 0.02)
                self.api.post_checkin(ci.kind, taken_at=taken, **{ci.kind: ci.value})
                run.accepted = True

            shapes = scenario.shapes(target, self.time_zone)
            total = sum(len(band_day(s, with_rr=False)) for s in shapes) or 1
            sent = 0
            for shape in shapes:
                if self._cancel.is_set():
                    report("Stopped.", sent / total)
                    break
                chunks = list(batches(band_day(shape)))
                if chunks and not run.accepted:
                    sent += self._post_batch(chunks.pop(0))
                    run.posted_packets = sent
                    run.accepted = True
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
                run.computed = run.accepted = True
        except Exception as exc:
            if not run.accepted:
                self._forget(run)
            if isinstance(exc, ApiError) and (exc.code == "consent_required"
                                              or (exc.status == 404 and "consent" in exc.message.lower())):
                raise RuntimeError(NO_CONSENT) from exc
            raise
        if not run.accepted:
            self._forget(run)
            raise RuntimeError(STOPPED_EARLY)
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

    def find_alerts(self, run: LabRun, feature_ids: tuple[str, ...]) -> dict[str, dict]:
        """Each of ``feature_ids``'s alerts the run raised (the first per feature), by the same test as
        :meth:`find_alert`."""
        start = min(run.dates) if run.dates else run.target
        started = run.started_at[:10]
        fired: dict[str, dict] = {}
        for alert in self.api.alerts(start, self.today().isoformat()):
            fid, day = alert.get("featureId"), str(alert.get("date") or "")
            if fid in feature_ids and fid not in fired and (day in run.dates or day >= started):
                fired[fid] = alert
        if sorted(fired) != run.found:
            run.found = sorted(fired)
            self.state.save()
        return fired

    def check_month(self, run: LabRun, feature_ids: tuple[str, ...]) -> LabCheck:
        """A run for several features: which alerts are in, and whether its last day is computed yet."""
        fired = self.find_alerts(run, feature_ids)
        day = {} if len(fired) == len(feature_ids) else self.day_status(run) or {}
        return LabCheck(run.target, computed=bool(day), final=bool(day.get("final")),
                        computed_at=day.get("computedAt"), fired=fired)

    def day_status(self, run: LabRun) -> dict | None:
        return self.api.metrics_day(run.target)

    def check(self, run: LabRun, rule: str = "", days: int = 1) -> LabCheck:
        """The run's alert or, while there is none, what AWS computed for its target day: whether the day is
        computed yet, and the metric the rule reads (the first field name in ``rule``). A rule that counts nights
        ("on 4+ nights") also gets that metric for each of the run's ``days`` nights."""
        alert = self.find_alert(run)
        if alert is not None:
            return LabCheck(run.target, alert=alert)
        day = self.day_status(run) or {}
        name = rule_field(rule)
        value = (day.get("metrics") or {}).get(name) if name else None
        nights: tuple[tuple[str, object], ...] = ()
        if name and days > 1 and night_count(rule):
            first = (Date.fromisoformat(run.target) - timedelta(days=days - 1)).isoformat()
            got = {d.get("date"): (d.get("metrics") or {}).get(name)
                   for d in self.api.metrics_range(first, run.target, fields=name)}
            nights = tuple((d, got.get(d)) for d in _days_between(first, run.target))
        return LabCheck(run.target, computed=bool(day), final=bool(day.get("final")),
                        computed_at=day.get("computedAt"), field=name, value=value, nights=nights)


@dataclass
class LabCheck:
    """One look at a run on the server."""

    target: str
    alert: dict | None = None
    computed: bool = False
    final: bool = False
    computed_at: str | None = None
    field: str | None = None
    value: object = None
    #: (date, value) for each night of the run, for a rule that counts nights
    nights: tuple[tuple[str, object], ...] = ()
    #: a run for several features: feature id → its alert, for the ones that fired
    fired: dict[str, dict] | None = None


def rule_field(rule: str) -> str | None:
    """``sleepLatencyMin`` from "sleepLatencyMin > 30 on 4+ nights" (the first camelCase metric name)."""
    import re

    found = re.search(r"\b[a-z]+[A-Z][A-Za-z0-9]*\b", rule)
    return found.group(0) if found else None


def night_count(rule: str) -> tuple[float, int] | None:
    """(30, 4) from "sleepLatencyMin > 30 on 4+ nights": the bar each night must pass, and how many must."""
    import re

    found = re.search(r"> *(\d+(?:\.\d+)?) on (\d+)\+ nights", rule)
    return (float(found.group(1)), int(found.group(2))) if found else None


def _days_between(first: str, last: str) -> list[str]:
    start, end = Date.fromisoformat(first), Date.fromisoformat(last)
    return [(start + timedelta(days=i)).isoformat() for i in range((end - start).days + 1)]
