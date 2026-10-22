"""Test lab: one list per alert feature. Run sends that feature's synthetic band days to dev, then the page watches
``GET /v1/alerts`` until the alert shows (or gives up).

Only one run at a time: they share the band's upload threads and the 5-computes-an-hour cap. Watching is a single
page timer that asks about every run still waiting.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

from PyQt6.QtCore import QTimer
from PyQt6.QtWidgets import QHBoxLayout, QVBoxLayout, QWidget

from aura_mobile.core.features import LabMode
from aura_mobile.services.sim.runner import COMPUTES_PER_HOUR, LabRun
from aura_mobile.services.sim.scenarios import SCENARIOS, Scenario
from aura_mobile.ui import context as area
from aura_mobile.ui import kit, layers
from aura_mobile.ui import tokens as t
from aura_mobile.ui.context import AppContext, describe_error
from aura_mobile.ui.pages.base import Page
from aura_mobile.ui.pages.profile import feature_tile, set_tone
from aura_mobile.ui.widgets import label, repolish, set_shown

WATCH_MS = 20_000
#: how long after a run the page keeps looking; a timer feature adds its own wait
WATCH_MINUTES = 12

MODE_NOTE = {
    LabMode.BAND: "Band days",
    LabMode.CHECKIN: "Check-ins + band day",
    LabMode.QUIET_DAY: "Quiet day (1 compute)",
    LabMode.TIMER: "Fires ~2 h later",
}


def _day(d: date) -> str:
    return f"{d.day} {d.strftime('%b')}"


def _span(dates: list[date]) -> str:
    """"1 day, 21 Oct" or "14 days, 8–21 Oct" (or "28 Sep – 5 Oct" across months)."""
    first, last = dates[0], dates[-1]
    if len(dates) == 1:
        return f"1 day, {_day(first)}"
    span = f"{first.day}–{_day(last)}" if first.month == last.month else f"{_day(first)} – {_day(last)}"
    return f"{len(dates)} days, {span}"


def _enable_row(row: kit.Row, on: bool) -> None:
    """A link row that greys out while it can't be used."""
    row.setEnabled(on)
    row.title.setProperty("class", "rowtitle-link" if on else "rowtitle-quiet")
    repolish(row.title)


class LabRow(kit.Group):
    """One scenario: the feature, what the next run sends, progress, Run / Check now and the Compute switch."""

    def __init__(self, scenario: Scenario) -> None:
        super().__init__()
        feature = scenario.feature
        self.scenario = scenario
        derived = feature.reliability == "derived"
        chip = label("derived" if derived else "direct", "chip")
        chip.setFixedHeight(20)
        chip.setToolTip("Derived: pushes a composite score, so it may need a second run."
                        if derived else "Direct: crosses a raw threshold.")
        icon, accent = feature_tile(feature)
        self.head = kit.Row(feature.name, f"{feature.id} · {MODE_NOTE.get(feature.lab, '')}", icon=icon,
                            accent=accent, trailing=chip)
        self.add(self.head)

        body = QWidget()
        lay = QVBoxLayout(body)
        lay.setContentsMargins(16, 10, 16, 12)
        lay.setSpacing(6)
        about = label(scenario.note, "rowsub", wrap=True)
        lay.addWidget(about)
        lay.addWidget(label(f"Rule: {feature.rule}", "small", wrap=True))
        self.plan = label("", "rowtitle", wrap=True)
        lay.addWidget(self.plan)
        self.bar = kit.Bar(height=5)
        self.bar.hide()
        lay.addWidget(self.bar)
        self.status = label("", "rowsub", wrap=True)
        lay.addWidget(self.status)
        lay.addSpacing(4)
        buttons = QHBoxLayout()
        buttons.setSpacing(4)
        self.run = kit.text_button("Run", "smprimary")
        self.run.setProperty("feature", scenario.feature_id)
        self.run.setMinimumWidth(64)
        buttons.addWidget(self.run)
        self.check = kit.text_button("Check now", "link")
        self.check.setProperty("feature", scenario.feature_id)
        buttons.addWidget(self.check)
        buttons.addStretch(1)
        buttons.addWidget(label("Compute", "rowsub"))
        self.compute = kit.Switch(tip="Spend one of the 5 hourly computes instead of waiting ~2 min for the final run.")
        if scenario.compute_empty_target:
            self.compute.setChecked(True)
            self.compute.setEnabled(False)
        buttons.addWidget(self.compute)
        lay.addLayout(buttons)
        self.add(body)

    def set_status(self, text: str, tone: str = "") -> None:
        """The run's last word; ``tone`` is ``good``, ``warn`` or ``bad``."""
        self.status.setText(text)
        set_tone(self.status, tone)

    def set_progress(self, fraction: float | None) -> None:
        """Show the thin progress line (``None`` hides it)."""
        set_shown(self.bar, fraction is not None)
        self.bar.set_fraction(fraction or 0.0)


class LabPage(Page):
    areas = (area.ALERTS,)
    heading = "Test lab"

    def __init__(self, ctx: AppContext) -> None:
        super().__init__(ctx)
        self.rows: dict[str, LabRow] = {}
        self._running: str | None = None
        #: feature id → (run, give up at)
        self._watching: dict[str, tuple[LabRun, datetime]] = {}

        self.summary = kit.Group(
            "This hour",
            "Each Run sends synthetic band days for unused past dates to dev, the same way the band would. The "
            "backend computes the day itself about 2 minutes later, then the alert appears in Recovery.")
        self.usage = kit.Row("Computes", "", "", icon="flask", accent=t.TEAL)
        self.summary.add(self.usage)
        self.stale = kit.Row("Older history", "", icon="info", accent=t.STRESS, wrap=True)
        set_tone(self.stale.sub, "warn")
        self.stale.hide()
        self.summary.add(self.stale)
        self.stop = kit.Row("Stop run", kind="link")
        self.stop.clicked.connect(self._stop)
        _enable_row(self.stop, False)
        self.summary.add(self.stop)
        self.reset_row = kit.Row("Reset lab", kind="danger")
        self.reset_row.clicked.connect(self._reset)
        self.summary.add(self.reset_row)
        self.column.addWidget(self.summary)

        for scenario in SCENARIOS:
            row = LabRow(scenario)
            row.run.clicked.connect(self._run)
            row.check.clicked.connect(self._check)
            self.rows[scenario.feature_id] = row
            self.column.addWidget(row)
        self.column.addStretch(1)

        self._timer = QTimer(self)
        self._timer.setInterval(WATCH_MS)
        self._timer.timeout.connect(self._watch)

    # -- drawing ---------------------------------------------------------------

    def refresh(self) -> None:
        lab = self.ctx.lab
        if lab is None:
            return
        used = lab.state.computes_this_hour()
        self.usage.set_value(f"{used} of {COMPUTES_PER_HOUR}")
        self.usage.set_sub(f"{len(lab.state.touched)} dates used · {lab.time_zone}")
        self.usage.setToolTip(f"Computes this hour: {used} of {COMPUTES_PER_HOUR}")
        stale = False
        for fid, row in self.rows.items():
            planned = lab.plan(row.scenario)
            if planned is None:
                row.plan.setText("No unused dates left. Reset the lab or use a fresh account.")
                row.run.setEnabled(False)
                continue
            target, dates = planned
            row.plan.setText(f"Next: {_span(dates)} → alert {_day(target)}")
            row.plan.setToolTip(f"{dates[0].isoformat()} … {dates[-1].isoformat()} → {target.isoformat()}")
            stale = stale or lab.is_stale(target)
            row.run.setEnabled(self._running is None)
            if fid not in self._watching and fid != self._running:
                self._show_last(row)
        self.stale.set_sub("Next windows reach more than 35 days back, where older runs blur into the rules' "
                           "30-day history. Delete the account in Profile and sign up again for a clean slate.")
        set_shown(self.stale, stale)
        self.summary.list.update()

    def _show_last(self, row: LabRow) -> None:
        last = self.ctx.lab.state.latest_run(row.scenario.feature_id) if self.ctx.lab else None
        if last is None:
            row.set_status("Not run yet.")
        elif last.found_alert:
            row.set_status(f"Passed · alert {last.found_alert}", "good")
        else:
            row.set_status(f"Last run {last.target}: no alert seen yet. Check now to look again.")

    # -- running ---------------------------------------------------------------

    def _row(self) -> LabRow:
        return self.rows[str(self.sender().property("feature"))]

    def _run(self) -> None:
        lab = self.ctx.lab
        if lab is None or self._running is not None:
            return
        row = self._row()
        compute_now = row.compute.isChecked()
        if compute_now and lab.state.computes_this_hour() >= COMPUTES_PER_HOUR:
            layers.Alert.tell(self, "Compute cap", "5 computes are used this hour. Turn Compute off; the day's own "
                                                   "final run comes about 2 min after sending.")
            return
        self._running = row.scenario.feature_id
        for r in self.rows.values():
            r.run.setEnabled(False)
        _enable_row(self.stop, True)
        row.set_progress(0.0)
        row.set_status("Starting…")
        scenario = row.scenario

        def work(report) -> LabRun:
            return lab.run(scenario, compute_now=compute_now, progress=report)

        self.ctx.call(work, self._sent, self._failed, on_progress=self._progress)

    def _progress(self, message: str, fraction: float) -> None:
        row = self.rows.get(self._running or "")
        if row is not None:
            row.set_progress(fraction)
            row.set_status(message)

    def _sent(self, run: LabRun) -> None:
        row = self.rows[run.feature_id]
        self._finish_run()
        row.set_progress(None)
        wait = WATCH_MINUTES + row.scenario.timer_wait_minutes
        self._watching[run.feature_id] = (run, datetime.now(UTC) + timedelta(minutes=wait))
        when = "about 2 h (a timer), so leave the app open or Check now later" if row.scenario.timer_wait_minutes \
            else "a few minutes"
        row.set_status(f"Sent {run.posted_packets} minutes for {run.target}. Watching for the alert; "
                       f"it takes {when}.")
        self._timer.start()
        self.refresh()

    def _failed(self, exc: Exception) -> None:
        row = self.rows.get(self._running or "")
        self._finish_run()
        if row is not None:
            row.set_progress(None)
            row.set_status(f"Failed: {describe_error(exc)}", "bad")
        self.refresh()

    def _finish_run(self) -> None:
        self._running = None
        _enable_row(self.stop, False)

    def _stop(self) -> None:
        if self.ctx.lab is not None:
            self.ctx.lab.cancel()

    # -- watching --------------------------------------------------------------

    def _check(self) -> None:
        lab = self.ctx.lab
        row = self._row()
        if lab is None:
            return
        fid = row.scenario.feature_id
        run = self._watching.get(fid, (None, None))[0] or lab.state.latest_run(fid)
        if run is None:
            row.set_status("Not run yet.")
            return
        row.set_status("Looking…")
        self.ctx.call(lambda: lab.find_alert(run), self._make_found(fid), self._make_lookup_failed(fid))

    def _watch(self) -> None:
        lab = self.ctx.lab
        if lab is None or not self._watching:
            self._timer.stop()
            return
        now = datetime.now(UTC)
        for fid, (run, give_up) in list(self._watching.items()):
            if now > give_up:
                del self._watching[fid]
                self.rows[fid].set_status(
                    f"No alert for {run.target} yet. Derived rules sometimes need a second run; "
                    "Check now looks again.", "warn")
                continue
            self.ctx.call(lambda run=run: lab.find_alert(run), self._make_found(fid), self._make_lookup_failed(fid))

    def _make_found(self, fid: str):
        def found(alert: dict | None) -> None:
            row = self.rows[fid]
            if alert is None:
                if fid not in self._watching:
                    row.set_status("Not there yet. Try again in a minute.")
                return
            self._watching.pop(fid, None)
            row.set_status(f"Passed · {alert.get('title') or alert.get('id')} on {alert.get('date')}", "good")
            self.ctx.toast.emit(f"{fid} fired: {alert.get('title') or 'alert'}")
            self.ctx.notify(area.ALERTS)
        return found

    def _make_lookup_failed(self, fid: str):
        def failed(exc: Exception) -> None:
            self.rows[fid].set_status(f"Couldn't check: {describe_error(exc)}", "bad")
        return failed

    # -- reset -----------------------------------------------------------------

    def _reset(self) -> None:
        if self.ctx.lab is None or self._running is not None:
            return
        if not layers.Alert.ask(
                self, "Reset the lab?",
                "Forget which dates this phone used? Re-sending a used date only recomputes it, and an alert already "
                "raised for that date will not fire again.", "Reset", danger=True):
            return
        self.ctx.lab.state.reset()
        self._watching.clear()
        self.refresh()

    def stop_watching(self) -> None:
        self._timer.stop()
        self._watching.clear()


