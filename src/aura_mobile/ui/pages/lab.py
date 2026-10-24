"""Test lab: one month that raises the alerts together, and one line per alert feature. Run sends the synthetic band
days to dev, then the page watches ``GET /v1/alerts`` until the alerts show (or gives up).

The month sits on top as a ring ("3 / 16") with a chip per alert; each feature is one line (glyph, name, a status
dot, Run). Everything else a row says (the scenario, the rule, the next dates, what AWS computed) is in the sheet a
tap on it opens; the how-it-works is behind the ⓘ.

Only one run at a time: they share the band's upload threads and the 5-computes-an-hour cap. Watching is a single
page timer that asks about every run still waiting.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

from PyQt6.QtCore import QTimer
from PyQt6.QtWidgets import QGridLayout, QHBoxLayout, QVBoxLayout, QWidget

from aura_mobile.core.features import BY_ID, LabMode
from aura_mobile.services.sim.runner import (
    COMPUTES_PER_HOUR,
    NO_ROOM,
    WINDOW_DAYS,
    LabCheck,
    LabRun,
    night_count,
)
from aura_mobile.services.sim.scenarios import ALL_AT_ONCE, SCENARIOS, Scenario
from aura_mobile.ui import context as area
from aura_mobile.ui import kit, layers
from aura_mobile.ui import tokens as t
from aura_mobile.ui.context import AppContext, describe_error
from aura_mobile.ui.pages.base import Page
from aura_mobile.ui.pages.profile import FitSheet, info_button, set_tone
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


#: the ⓘ sheet
ABOUT = (
    ("flask", "Run sends synthetic band days for unused past dates to dev, the way the band would."),
    ("clock", "Dev computes each day about 2 min after its last minute; then the alert shows in Recovery."),
    ("bolt", f"Compute skips that wait, at most {COMPUTES_PER_HOUR} an hour. One run at a time."),
    ("chart", "Derived features push a composite score, so they may need a second run."),
    ("cal", "All at once sends one month that should raise every alert, the way a real month would. It needs a "
            "fresh account."),
)


def _day(d: date) -> str:
    return f"{d.day} {d.strftime('%b')}"


def _clock(at: datetime | str) -> str:
    """Local HH:MM of an instant (a datetime, or the server's ISO string)."""
    if isinstance(at, str):
        at = datetime.fromisoformat(at.replace("Z", "+00:00"))
    return at.astimezone().strftime("%H:%M")


def describe_check(seen: LabCheck, rule: str, watching_until: str | None) -> str:
    """What AWS has done with a run that has no alert yet, so a row never just says "Looking…"."""
    day = _day(date.fromisoformat(seen.target))
    now = datetime.now().strftime("%H:%M")
    if not seen.computed:
        text = f"AWS hasn't computed {day} yet (it does about 2 min after the last minute arrives)."
    else:
        when = f" at {_clock(seen.computed_at)}" if seen.computed_at else ""
        text = f"AWS computed {day}{when}"
        bar = night_count(rule)
        if seen.field and seen.nights and bar:
            values = ", ".join("none" if v is None else f"{v:g}" if isinstance(v, float) else str(v)
                               for _d, v in seen.nights)
            over = sum(1 for _d, v in seen.nights if isinstance(v, int | float) and v > bar[0])
            text += (f": {seen.field} by night {values}. {over} of {len(seen.nights)} nights over {bar[0]:g}; "
                     f"the rule needs {bar[1]}")
        elif seen.field:
            shown = "no value" if seen.value is None else seen.value
            text += f": {seen.field} = {shown} (rule: {rule})"
        text += ". No alert yet."
    if watching_until:
        return f"{text} Checked {now}; watching until {watching_until}."
    if seen.computed:
        return f"{text} Checked {now}; stopped watching. If the value misses the rule, the data didn't trigger it."
    return f"{text} Checked {now}. Check now looks again."


def describe_month(seen: LabCheck, feature_ids: tuple[str, ...], watching_until: str | None) -> str:
    """Which of the month's alerts are in and which are still to come."""
    fired = seen.fired or {}
    waiting = [f for f in feature_ids if f not in fired]
    if not waiting:
        return f"Passed · all {len(feature_ids)} alerts fired."
    text = f"{len(fired)} of {len(feature_ids)} fired"
    text += f": {', '.join(sorted(fired))}. Waiting for {', '.join(waiting)}." if fired else "."
    if not seen.computed:
        text += f" AWS hasn't computed the last day ({_day(date.fromisoformat(seen.target))}) yet."
    now = datetime.now().strftime("%H:%M")
    if watching_until:
        return f"{text} Checked {now}; watching until {watching_until} (NI-04's timer takes ~2 h)."
    return f"{text} Checked {now}. Check now looks again."


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


class Detail(QWidget):
    """What a row's sheet shows: the scenario, the rule, the next run's dates, AWS's last word and Check now. It
    lives on the row (hidden) so every status update lands there; the sheet borrows it while open."""

    def __init__(self, owner: QWidget, meta: str, note: str, rule: str, feature_id: str) -> None:
        super().__init__(owner)
        self.hide()
        lay = QVBoxLayout(self)
        lay.setContentsMargins(4, 0, 4, 0)
        lay.setSpacing(8)
        lay.addWidget(label(meta, "codetag"))
        lay.addWidget(label(note, "rowsub", wrap=True))
        lay.addWidget(label(rule, "small", wrap=True))
        self.plan = label("", "rowtitle", wrap=True)
        lay.addWidget(self.plan)
        self.status = label("", "rowsub", wrap=True)
        lay.addWidget(self.status)
        self.buttons = QHBoxLayout()
        self.buttons.setSpacing(4)
        self.check = kit.text_button("Check now", "link")
        self.check.setProperty("feature", feature_id)
        self.buttons.addWidget(self.check)
        self.buttons.addStretch(1)
        lay.addLayout(self.buttons)


class LabRow(kit.Clickable):
    """One scenario on one line: the feature's glyph and name, a status dot and Run. A tap opens its sheet
    (:class:`Detail`: the scenario, the rule, the next run's dates, what AWS said, Check now, the Compute switch)."""

    def __init__(self, scenario: Scenario) -> None:
        super().__init__("row")
        feature = scenario.feature
        self.scenario = scenario
        self.setProperty("tap", True)
        self.setMinimumHeight(t.ROW_MIN + 4)
        self.has_tile = True
        lay = QHBoxLayout(self)
        lay.setContentsMargins(16, 8, 12, 8)
        lay.setSpacing(12)
        lay.addWidget(kit.Glyph(feature.icon, 24, feature.accent, stroke=2.0))
        lay.addSpacing(5)  # the name starts where the hairline does (57)
        words = QVBoxLayout()
        words.setSpacing(3)
        line = QHBoxLayout()
        line.setSpacing(6)
        self.name = label(feature.name, "rowtitle")
        line.addWidget(self.name)
        line.addWidget(label(feature.id, "codetag"))
        line.addStretch(1)
        words.addLayout(line)
        self.bar = kit.Bar(height=4)
        self.bar.hide()
        words.addWidget(self.bar)
        lay.addLayout(words, 1)
        self.dot = kit.Dot()
        lay.addWidget(self.dot)
        self.run = kit.text_button("Run", "run")
        self.run.setProperty("feature", scenario.feature_id)
        lay.addWidget(self.run)

        derived = "derived" if feature.reliability == "derived" else "direct"
        self.detail = Detail(self, f"{feature.id} · {MODE_NOTE.get(feature.lab, '')} · {derived}", scenario.note,
                             f"Rule: {feature.rule}", scenario.feature_id)
        self.plan, self.status, self.check = self.detail.plan, self.detail.status, self.detail.check
        self.detail.buttons.addWidget(label("Compute", "rowsub"))
        self.compute = kit.Switch(tip="Spend one of the 5 hourly computes instead of waiting ~2 min for the final run.")
        if scenario.compute_empty_target:
            self.compute.setChecked(True)
            self.compute.setEnabled(False)
        self.detail.buttons.addWidget(self.compute)

    @property
    def title(self) -> str:
        return self.scenario.feature.name

    def set_status(self, text: str, tone: str = "") -> None:
        """The run's last word; ``tone`` is ``good``, ``warn``, ``bad`` or ``busy`` (running or watching)."""
        self.status.setText(text)
        set_tone(self.status, tone)
        self.dot.set_tone(tone)

    def set_progress(self, fraction: float | None) -> None:
        """Show the thin progress line (``None`` hides it)."""
        set_shown(self.bar, fraction is not None)
        self.bar.set_fraction(fraction or 0.0)


class MonthRow(kit.Clickable):
    """The all-at-once month: a ring with how many of its alerts fired, Run, and a chip per alert (grey waiting,
    green fired, red failed or stopped). A tap opens the same sheet as a feature row."""

    compute = None  # every day of it is past, so each computes itself
    title = "All at once"

    def __init__(self, scenario: Scenario) -> None:
        super().__init__("bigcard")
        self.scenario = scenario
        lay = QVBoxLayout(self)
        lay.setContentsMargins(18, 18, 18, 18)
        lay.setSpacing(16)
        top = QHBoxLayout()
        top.setSpacing(18)
        self.ring = kit.CountRing(104)
        self.ring.set_count(0, len(scenario.feature_ids))
        top.addWidget(self.ring)
        words = QVBoxLayout()
        words.setSpacing(2)
        words.addStretch(1)
        words.addWidget(label("All at once", "mlabel"))
        sub = QHBoxLayout()
        sub.setSpacing(6)
        self.dot = kit.Dot(8)
        sub.addWidget(self.dot)
        sub.addWidget(label(f"{scenario.days} days · {len(scenario.feature_ids)} alerts", "tilesub"), 1)
        words.addLayout(sub)
        self.bar = kit.Bar(height=4)
        self.bar.hide()
        words.addSpacing(4)
        words.addWidget(self.bar)
        words.addSpacing(6)
        go = QHBoxLayout()
        self.run = kit.text_button("Run", "go")
        self.run.setProperty("feature", scenario.feature_id)
        self.run.setMinimumWidth(84)
        go.addWidget(self.run)
        go.addStretch(1)
        words.addLayout(go)
        words.addStretch(1)
        top.addLayout(words, 1)
        lay.addLayout(top)
        grid = QGridLayout()
        grid.setSpacing(8)
        self.chips: dict[str, kit.StatusChip] = {}
        for i, fid in enumerate(scenario.feature_ids):
            chip = kit.StatusChip(fid, BY_ID[fid].icon)
            grid.addWidget(chip, i // 4, i % 4)
            self.chips[fid] = chip
        lay.addLayout(grid)

        self.detail = Detail(self, f"ALL · {scenario.days} days · {len(scenario.feature_ids)} alerts", scenario.note,
                             f"Covers {', '.join(scenario.feature_ids)}. BS-02 needs empty days, so it runs alone.",
                             scenario.feature_id)
        self.plan, self.status, self.check = self.detail.plan, self.detail.status, self.detail.check

    set_status = LabRow.set_status
    set_progress = LabRow.set_progress

    def fired(self) -> set[str]:
        return {fid for fid, chip in self.chips.items() if chip.state == "good"}

    def set_fired(self, fired: set[str] | list[str], *, stopped: bool = False) -> None:
        """Green for the alerts in; the rest red once the run failed or watching stopped, else grey."""
        fired = set(fired)
        for fid, chip in self.chips.items():
            chip.set_state("good" if fid in fired else "bad" if stopped else "wait")
        self.ring.set_count(len(fired & set(self.chips)), len(self.chips))


class LabPage(Page):
    areas = (area.ALERTS,)
    heading = "Test lab"

    def __init__(self, ctx: AppContext) -> None:
        super().__init__(ctx)
        self.rows: dict[str, LabRow | MonthRow] = {}
        self._running: str | None = None
        #: feature id → (run, give up at)
        self._watching: dict[str, tuple[LabRun, datetime]] = {}

        self.nav.add_right(info_button(self, "Test lab", ABOUT))

        month = MonthRow(ALL_AT_ONCE)
        month.run.clicked.connect(self._run)
        month.check.clicked.connect(self._check)
        month.clicked.connect(lambda: self.open_row(month))
        self.rows[ALL_AT_ONCE.feature_id] = month
        self.column.addWidget(month)

        self.summary = kit.TitledGroup("This hour")
        self.usage = kit.Row("Computes", "", "")
        self.summary.add(self.usage)
        self.stale = kit.Row("Out of dates", "", wrap=True)
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

        self.features = kit.TitledGroup("One at a time")
        for scenario in SCENARIOS:
            row = LabRow(scenario)
            row.run.clicked.connect(self._run)
            row.check.clicked.connect(self._check)
            row.clicked.connect(lambda row=row: self.open_row(row))
            self.rows[scenario.feature_id] = row
            self.features.add(row)
        self.column.addWidget(self.features)
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
        self.usage.set_sub(f"{len(lab.state.touched)} dates used")
        self.usage.setToolTip(f"Computes this hour: {used} of {COMPUTES_PER_HOUR} · {lab.time_zone}")
        full = False
        for fid, row in self.rows.items():
            planned = lab.plan(row.scenario)
            if planned is None:
                row.plan.setText(f"Needs {row.scenario.days + 1} unused days in a row in the last {WINDOW_DAYS}: a "
                                 "fresh account." if row.scenario.covers else
                                 f"No room left in the last {WINDOW_DAYS} days.")
                row.run.setEnabled(False)
                full = True
                continue
            target, dates = planned
            row.plan.setText(f"Next: {_span(dates)} → alert {_day(target)}")
            row.plan.setToolTip(f"{dates[0].isoformat()} … {dates[-1].isoformat()} → {target.isoformat()}")
            row.run.setEnabled(self._running is None)
            if fid not in self._watching and fid != self._running:
                self._show_last(row)
        self.stale.set_sub(NO_ROOM)
        set_shown(self.stale, full)
        self.summary.list.update()

    def _show_last(self, row: LabRow) -> None:
        last = self.ctx.lab.state.latest_run(row.scenario.feature_id) if self.ctx.lab else None
        if last is None:
            row.set_status("Not run yet.")
            if isinstance(row, MonthRow):
                row.set_fired(())
        elif row.scenario.covers:
            ids = row.scenario.feature_ids
            seen = f" ({', '.join(last.found)})" if last.found else ""
            row.set_status(f"Last run: {len(last.found)} of {len(ids)} alerts seen{seen}. Check now to look again.",
                           "good" if len(last.found) == len(ids) else "")
            row.set_fired(set(last.found))
        elif last.found_alert:
            row.set_status(f"Passed · alert {last.found_alert}", "good")
        else:
            ran = datetime.fromisoformat(last.started_at).astimezone().strftime("%d %b %H:%M")
            row.set_status(f"Last run {ran}, data for {_day(date.fromisoformat(last.target))}: no alert seen yet. "
                           "Check now to look again.")

    def open_row(self, row: LabRow | MonthRow) -> FitSheet:
        """The row's sheet: its :class:`Detail` (borrowed while open) and a Run that starts it."""
        sheet = self.row_sheet(row)
        sheet.exec()
        self.give_back(row, sheet)
        return sheet

    def row_sheet(self, row: LabRow | MonthRow) -> FitSheet:
        sheet = FitSheet(self, row.title)
        sheet.body.addWidget(row.detail)
        row.detail.show()
        go = kit.text_button("Run", "gobig")
        go.setEnabled(row.run.isEnabled())
        go.clicked.connect(lambda _=False: (sheet.accept(), row.run.click()))
        sheet.body.addWidget(go)
        sheet.fit()
        return sheet

    def give_back(self, row: LabRow | MonthRow, sheet: FitSheet) -> None:
        row.detail.hide()
        row.detail.setParent(row)
        sheet.deleteLater()

    # -- running ---------------------------------------------------------------

    def _row(self) -> LabRow:
        return self.rows[str(self.sender().property("feature"))]

    def _run(self) -> None:
        lab = self.ctx.lab
        if lab is None or self._running is not None:
            return
        row = self._row()
        compute_now = row.compute is not None and row.compute.isChecked()
        if compute_now and lab.state.computes_this_hour() >= COMPUTES_PER_HOUR:
            layers.Alert.tell(self, "Compute cap", "5 computes are used this hour. Turn Compute off; the day's own "
                                                   "final run comes about 2 min after sending.")
            return
        self._running = row.scenario.feature_id
        for r in self.rows.values():
            r.run.setEnabled(False)
        _enable_row(self.stop, True)
        row.set_progress(0.0)
        row.set_status("Starting…", "busy")
        scenario = row.scenario

        def work(report) -> LabRun:
            return lab.run(scenario, compute_now=compute_now, progress=report)

        self.ctx.call(work, self._sent, self._failed, on_progress=self._progress)

    def _progress(self, message: str, fraction: float) -> None:
        row = self.rows.get(self._running or "")
        if row is not None:
            row.set_progress(fraction)
            row.set_status(message, "busy")

    def _sent(self, run: LabRun) -> None:
        row = self.rows[run.feature_id]
        self._finish_run()
        row.set_progress(None)
        wait = WATCH_MINUTES + row.scenario.timer_wait_minutes
        self._watching[run.feature_id] = (run, datetime.now(UTC) + timedelta(minutes=wait))
        when = "about 2 h (a timer), so leave the app open or Check now later" if row.scenario.timer_wait_minutes \
            else "a few minutes"
        row.set_status(f"Sent {run.posted_packets} minutes for {run.target}. Watching for the alert; "
                       f"it takes {when}.", "busy")
        if isinstance(row, MonthRow):
            row.set_fired(())
        self._timer.start()
        self.refresh()

    def _failed(self, exc: Exception) -> None:
        row = self.rows.get(self._running or "")
        self._finish_run()
        if row is not None:
            row.set_progress(None)
            row.set_status(f"Failed: {describe_error(exc)}", "bad")
            if isinstance(row, MonthRow):
                row.set_fired(row.fired(), stopped=True)
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
        row.set_status("Asking AWS…", "busy")
        self.ctx.call(self._look(row.scenario, run), self._make_found(fid), self._make_lookup_failed(fid))

    def _look(self, scenario: Scenario, run: LabRun):
        """The lookup for one run, to call on a worker thread."""
        lab = self.ctx.lab
        if scenario.covers:
            return lambda: lab.check_month(run, scenario.feature_ids)
        return lambda: lab.check(run, scenario.feature.rule, scenario.days)

    def _watch(self) -> None:
        lab = self.ctx.lab
        if lab is None or not self._watching:
            self._timer.stop()
            return
        now = datetime.now(UTC)
        for fid, (run, give_up) in list(self._watching.items()):
            if now > give_up:
                del self._watching[fid]  # one last look; its answer says watching has stopped
            self.ctx.call(self._look(self.rows[fid].scenario, run), self._make_found(fid),
                          self._make_lookup_failed(fid))

    def _make_found(self, fid: str):
        def found(seen: LabCheck) -> None:
            row = self.rows[fid]
            if seen.fired is not None:
                self._month_found(row, seen)
                return
            if seen.alert is None:
                watching = self._watching.get(fid)
                until = _clock(watching[1]) if watching else None
                tone = "busy" if watching else "" if seen.computed else "warn"
                row.set_status(describe_check(seen, row.scenario.feature.rule, until), tone)
                return
            alert = seen.alert
            self._watching.pop(fid, None)
            row.set_status(f"Passed · {alert.get('title') or alert.get('id')} on {alert.get('date')}", "good")
            self.ctx.toast.emit(f"{fid} fired: {alert.get('title') or 'alert'}")
            self.ctx.notify(area.ALERTS)
        return found

    def _month_found(self, row: MonthRow, seen: LabCheck) -> None:
        fid = row.scenario.feature_id
        ids = row.scenario.feature_ids
        fired = seen.fired or {}
        for each, alert in fired.items():
            single = self.rows.get(each)
            if single is not None:
                single.set_status(f"Passed in the all-at-once run · {alert.get('title') or alert.get('id')} on "
                                  f"{alert.get('date')}", "good")
        done = len(fired) == len(ids)
        if done:
            self._watching.pop(fid, None)
            self.ctx.notify(area.ALERTS)
        watching = self._watching.get(fid)
        tone = "good" if done else "busy" if watching else "" if seen.computed else "warn"
        row.set_status(describe_month(seen, ids, _clock(watching[1]) if watching else None), tone)
        row.set_fired(set(fired), stopped=not done and watching is None)

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


