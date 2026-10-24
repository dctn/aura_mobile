"""Home in the Figma look: the day strip, today's alert pills with their 14-day cards, Burnout, Tasks, Events,
this week's movement, the user's pins, then "Finish your profile" and the band line.

Each open alert is a black pill (icon, name, two-word reason, one green verb) over a card of the metric it reads.
Tapping a pill opens its ⓘ sheet; the verb does the action. Movement is ``weeklyActiveMinutes`` against 150 (DX-09).
The pill "Finish your profile" shows while body details are missing (BS-01); the band line warns after a day
without a sync (BS-02).
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from datetime import date, datetime, time, timedelta
from typing import Any

from PyQt6.QtCore import QRectF, Qt, QTime, pyqtSignal
from PyQt6.QtGui import QFont, QPainter, QPen
from PyQt6.QtWidgets import QAbstractButton, QDialog, QFrame, QHBoxLayout, QLineEdit, QVBoxLayout, QWidget

from aura_mobile.core import metrics as m
from aura_mobile.core import profile as person
from aura_mobile.core.features import feature_for_alert
from aura_mobile.ui import actions, icons, kit, layers, prefs
from aura_mobile.ui import context as area
from aura_mobile.ui import tokens as t
from aura_mobile.ui.context import AppContext
from aura_mobile.ui.pages.base import Page
from aura_mobile.ui.widgets import clear_layout, label, set_shown

log = logging.getLogger(__name__)

#: DX-09: the WHO's 150 active minutes a week
WEEKLY_ACTIVE_TARGET = 150
#: BS-02: the band line turns into a warning after this long without a sync
SYNC_WARN_AFTER = timedelta(hours=24)
#: alert pills on Home; the rest wait in Recovery
SHOW_ALERTS = 3
SHOW_EVENTS = 3
SHOW_TASKS = 5
MAX_PINS = 6

#: a pinned metric's tile: (icon, accent name)
PIN_LOOK: dict[str, tuple[str, str]] = {
    "totalSleepMin": ("sleep", "sleep"),
    "sleepDebtMin14": ("sleep", "sleep"),
    "sleepLatencyMin": ("sleep", "sleep"),
    "wakeRegularityMin": ("sunrise", "sleep"),
    "recoveryScore": ("recover", "recovery"),
    "restingHrBpm": ("heart", "cardiac"),
    "restingHrBaselineBpm": ("heart", "cardiac"),
    "hrvAboveBaseline": ("signal", "cardiac"),
    "skinTempDeviationC": ("sun", "stress"),
    "stressDurationMin": ("bolt", "stress"),
    "weeklyActiveMinutes": ("walk", "fitness"),
    "longestSittingBoutMin": ("timer", "fitness"),
    "steps": ("walk", "fitness"),
    "vo2max": ("flame", "workout"),
    "circadianAlignmentPct": ("sun", "focus"),
    "spo2DipEvents": ("drop", "cardiac"),
    "burnoutIndexScore": ("flame", "workout"),
}

#: the cards under the Burnout pill (Avg sleep falls back to nightly sleep when the burnout column is missing)
BURNOUT_CARDS = ("avg_sleep", "late_nights", "recovery")


def open_alerts(alerts: list[dict]) -> list[dict]:
    """Not done yet, newest first; high priority ahead within a day."""
    rank = {"high": 0, "normal": 1, "low": 2}
    live = [a for a in alerts if not a.get("done")]
    live.sort(key=lambda a: (str(a.get("date") or ""), -rank.get(str(a.get("priority")), 1)), reverse=True)
    return live


def clock(value: Any, tz) -> str:
    try:
        return datetime.fromisoformat(str(value)).astimezone(tz).strftime("%H:%M")
    except ValueError:
        return ""


def day_word(day: str, today: date) -> str:
    """"Today", "Yesterday", "Tomorrow" or "Fri 24"."""
    try:
        d = date.fromisoformat(day[:10])
    except ValueError:
        return ""
    return {0: "Today", -1: "Yesterday", 1: "Tomorrow"}.get((d - today).days, f"{d:%a} {d.day}")


def duration_text(minutes: int) -> str:
    if minutes <= 0:
        return ""
    if minutes < 60:
        return f"{minutes} min"
    h, rest = divmod(minutes, 60)
    return f"{h} h {rest} min" if rest else f"{h} h"


def burnout_risk(days: list[dict], latest: dict, today: date) -> str:
    """The risk word: the server's ``burnoutIndexRisk`` when it sends one, else the newest score bucketed."""
    word = (latest.get("metrics") or {}).get("burnoutIndexRisk")
    if isinstance(word, str) and word.strip():
        return word.strip().capitalize()
    score = m.newest(m.series(days, "burnoutIndexScore", today))
    if score is None:
        return "No data yet"
    return "High" if score >= 70 else "Moderate" if score >= 40 else "Low"


def fill_card(card: kit.MetricCard, c: m.Card, days: list[dict], today: date, *, accent: str | None = None) -> bool:
    """Point a metric card at one column: its label, unit, colour, 14 bars and the newest value. False when none
    of the 14 days has a value (an alert's card is then left out)."""
    raw = m.series(days, c.field, today)
    card.name.setText(c.label)
    card.bars.key = accent or c.accent
    card.setProperty("metric", c.key)
    card.set_series([c.number(v) or 0.0 for v in raw], c.show(m.newest(raw)), c.unit)
    return m.newest(raw) is not None


def fit(pill: kit.ActionPill) -> kit.ActionPill:
    """Let a long name ("Weekly movement target") wrap inside the pill instead of widening the page."""
    pill.title.setWordWrap(True)
    pill.reason.setWordWrap(True)
    return pill


# -- small widgets ----------------------------------------------------------------------------------------------


class CheckMark(QAbstractButton):
    """The round check in front of a task: an outline, or filled green with a dark tick."""

    def __init__(self, tip: str = "Mark done") -> None:
        super().__init__()
        self.setCheckable(True)
        self.setFixedSize(29, 29)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setToolTip(tip)

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        r = QRectF(3.5, 3.5, 22, 22)
        if self.isChecked():
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(kit.colour("go"))
            p.drawEllipse(r)
            p.drawPixmap(7, 7, icons.pixmap("check", kit.colour("go_ink"), 15, stroke=2.6))
        else:
            p.setPen(QPen(kit.colour("label3"), 1.6))
            p.setBrush(Qt.BrushStyle.NoBrush)
            p.drawEllipse(r)


class PercentRing(kit.Ring):
    """The movement ring with the percentage in the middle."""

    def paintEvent(self, event) -> None:
        super().paintEvent(event)
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        font = QFont(t.FONT)
        font.setPixelSize(15)
        font.setWeight(QFont.Weight.Bold)
        p.setFont(font)
        p.setPen(kit.colour("label"))
        p.drawText(QRectF(self.rect()), Qt.AlignmentFlag.AlignCenter, f"{round(self.fraction * 100)}%")


class FieldRow(QFrame):
    """A rounded pill holding a text field, with a leading + and an Add button once something is typed."""

    submitted = pyqtSignal()

    def __init__(self, placeholder: str, icon: str = "plus") -> None:
        super().__init__()
        self.setProperty("class", "softpill")
        self.setMinimumHeight(48)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(14, 4, 8, 4)
        lay.setSpacing(10)
        holder = QWidget()
        holder.setFixedSize(29, 29)
        mark = kit.Glyph(icon, 22, "label3", stroke=2.0)
        mark.setParent(holder)
        mark.move(3, 3)
        lay.addWidget(holder)
        self.edit = QLineEdit()
        self.edit.setProperty("class", "plain")
        self.edit.setPlaceholderText(placeholder)
        self.edit.returnPressed.connect(self.submitted)
        lay.addWidget(self.edit, 1)
        self.add = kit.text_button("Add", "go")
        self.add.clicked.connect(self.submitted)
        self.add.hide()
        lay.addWidget(self.add)
        self.edit.textChanged.connect(lambda text: self.add.setVisible(bool(text.strip())))


class TaskPill(kit.Clickable):
    """One open task: the check and the title in a rounded pill. A tap anywhere ticks it."""

    def __init__(self, title: str, task_id: Any) -> None:
        super().__init__("softpill")
        self.setMinimumHeight(48)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(14, 4, 16, 4)
        lay.setSpacing(10)
        self.check = CheckMark()
        self.check.setProperty("task_id", task_id)
        lay.addWidget(self.check)
        self.title = label(title, "rowtitle-strong")
        lay.addWidget(self.title, 1)
        self.clicked.connect(self.check.toggle)

    def text(self) -> str:
        return self.title.text()


class BandLine(QFrame):
    """One short line about the band: "Synced 10:42" or "Not synced", and Reconnect."""

    def __init__(self) -> None:
        super().__init__()
        self.setProperty("class", "softpill")
        self.setMinimumHeight(48)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(16, 4, 8, 4)
        lay.setSpacing(10)
        self.glyph = kit.Glyph("band", 20, "label2", stroke=2.0)
        lay.addWidget(self.glyph)
        self.say = label("", "rowtitle")
        lay.addWidget(self.say, 1)
        self.reconnect = kit.text_button("Reconnect", "link", tip="Send what the band kept offline")
        lay.addWidget(self.reconnect)

    def set_line(self, text: str, warn: bool = False) -> None:
        self.say.setText(text)
        self.say.setProperty("class", "warn" if warn else "rowtitle")
        self.say.style().unpolish(self.say)
        self.say.style().polish(self.say)
        self.glyph.key = "warn" if warn else "label2"
        self.glyph.update()

    def text(self) -> str:
        return self.say.text()


class MovementCard(kit.Clickable):
    """This week's active minutes: the ring, then "62 of 150 min" over "This week"."""

    def __init__(self) -> None:
        super().__init__("bigcard")
        lay = QHBoxLayout(self)
        lay.setContentsMargins(18, 16, 18, 16)
        lay.setSpacing(16)
        self.ring = PercentRing(72, 10, "fitness")
        lay.addWidget(self.ring)
        text = QVBoxLayout()
        text.setSpacing(0)
        text.addStretch(1)
        text.addWidget(label("Movement", "mlabel"))
        big = QHBoxLayout()
        big.setSpacing(4)
        self.big = label("–", "mbig")
        big.addWidget(self.big, 0, Qt.AlignmentFlag.AlignBottom)
        big.addWidget(label("min", "munit"), 0, Qt.AlignmentFlag.AlignBottom)
        big.addStretch(1)
        text.addLayout(big)
        self.note = label("This week", "tilesub")
        text.addWidget(self.note)
        text.addStretch(1)
        lay.addLayout(text, 1)


def stack(*widgets: QWidget, gap: int = 10) -> QWidget:
    """A tight column (a pill over its card, a section title over its rows)."""
    w = QWidget()
    lay = QVBoxLayout(w)
    lay.setContentsMargins(0, 0, 0, 0)
    lay.setSpacing(gap)
    for x in widgets:
        lay.addWidget(x)
    return w


# -- sheets -----------------------------------------------------------------------------------------------------


class QuickSheet(layers.Sheet):
    """Check in, breathe or move, log water. The chosen step runs after the sheet closes (``then``), so timers and
    check-ins open over the phone, not over the sheet."""

    def __init__(self, ctx: AppContext, parent: QWidget, alert: dict | None = None) -> None:
        super().__init__(parent, "Quick", done="Done", cancel=None)
        self.ctx = ctx
        self.alert = alert
        #: what to do once the sheet has closed
        self.then: Callable[[], None] | None = None
        page = parent

        feature = feature_for_alert(alert) if alert is not None else None
        if alert is not None and feature is not None:
            act = kit.text_button(feature.verb, "primary")
            act.setProperty("tint", feature.accent)
            act.clicked.connect(lambda: self._run(lambda: actions.perform(ctx, page, alert)))
            self.body.addWidget(act)

        checkin = kit.Group("Check in", sheet=True)
        mood = checkin.add(kit.Row("Mood", icon="bubble", accent=t.SLEEP, chevron=True))
        mood.clicked.connect(lambda: self._run(lambda: actions.ask_checkin(ctx, page, "mood")))
        conn = checkin.add(kit.Row("Connection", icon="people", accent=t.TEAL, chevron=True))
        conn.clicked.connect(lambda: self._run(lambda: actions.ask_checkin(ctx, page, "connection")))
        self.body.addWidget(checkin)

        quick = kit.Group("Quick", sheet=True)
        breathe = quick.add(kit.Row("Breathe", value="5 min", icon="wind", accent=t.STRESS, chevron=True))
        breathe.clicked.connect(lambda: self._run(self._breathe))
        move = quick.add(kit.Row("Move", value="3 min", icon="walk", accent=t.FITNESS, chevron=True))
        move.clicked.connect(lambda: self._run(self._move))
        self.body.addWidget(quick)

        water = kit.Group("Water", sheet=True)
        chips = QWidget()
        row = QHBoxLayout(chips)
        row.setContentsMargins(0, 0, 0, 0)
        row.setSpacing(6)
        for ml in (250, 500):
            b = kit.text_button(f"+{ml} ml", "chip", tip=f"Log {ml} ml of water")
            b.setProperty("ml", ml)
            b.clicked.connect(self._water)
            row.addWidget(b)
        water.add(kit.Row(self._water_today(), icon="drop", accent=t.BLUE, trailing=chips))
        self.body.addWidget(water)

    def _water_today(self) -> str:
        logs = self.ctx.store.data.get("logs")
        if not isinstance(logs, list):
            return "Water"
        today = actions.now_local(self.ctx).date().isoformat()
        ml = sum(int((x.get("data") or {}).get("ml") or 0) for x in logs
                 if str(x.get("date") or str(x.get("takenAt") or "")[:10]) == today)
        return f"{ml} ml today"

    def _run(self, step: Callable[[], None]) -> None:
        self.then = step
        self.accept()

    def _breathe(self) -> None:
        actions.run_timed_session(self.ctx, self.parentWidget(), "Slow breathing", 5, accent=t.STRESS,
                                  breathing=True)

    def _move(self) -> None:
        actions.run_timed_session(self.ctx, self.parentWidget(), "Move break", 3, kind="walk", accent=t.FITNESS)

    def _water(self) -> None:
        actions.log_water(self.ctx, int(self.sender().property("ml")))


class AlertSheet(layers.Sheet):
    """ⓘ on an alert pill: the feature's icon and name, what the alert means, what to do, and its one-verb button.

    ``act`` is set when the button closed it; ``later`` when "Not now" did (``snooze=True`` shows that link)."""

    def __init__(self, parent: QWidget, alert: dict[str, Any], *, snooze: bool = False) -> None:
        f = feature_for_alert(alert)
        super().__init__(parent, "", done="Done", cancel=None, footer=f.verb if f else None)
        self.act = False
        self.later = False
        if self.footer_button is not None and f is not None:
            self.footer_button.setProperty("tint", f.accent)
            self.footer_button.pressed.connect(self._acting)
        hero = QVBoxLayout()
        hero.setSpacing(8)
        hero.setContentsMargins(0, 0, 0, 4)
        hero.addWidget(kit.Disc(f.icon if f else "bell", f.accent if f else "accent", 56), 0,
                       Qt.AlignmentFlag.AlignHCenter)
        name = label(f.name if f else str(alert.get("title") or "Alert"), "h2", wrap=True)
        name.setAlignment(Qt.AlignmentFlag.AlignCenter)
        hero.addWidget(name)
        self.body.addLayout(hero)
        says = str(alert.get("body") or "").strip() or (f.shows if f else "")
        text = kit.Group(sheet=True)
        if says:
            text.add(kit.Row(says, icon="info", wrap=True))
        if f is not None:
            text.add(kit.Row(f.does, icon="check", accent=t.current().good, wrap=True))
        set_shown(text, text.count() > 0)
        self.body.addWidget(text)
        if snooze:
            later = kit.text_button("Not now", "link", tip="Hide today's alerts until a new one comes")
            later.clicked.connect(self._later)
            self.body.addWidget(later, 0, Qt.AlignmentFlag.AlignHCenter)

    def _acting(self) -> None:
        self.act = True

    def _later(self) -> None:
        self.later = True
        self.reject()


class EventDialog(layers.Sheet):
    """A new event today: title, start, length."""

    def __init__(self, parent: QWidget) -> None:
        super().__init__(parent, "New event", done="Add", cancel="Cancel")
        form = kit.Group(sheet=True)
        self.event_title = QLineEdit()
        self.event_title.setProperty("class", "plain")
        self.event_title.setPlaceholderText("Title")
        self.event_title.textChanged.connect(lambda text: self.done_button.setEnabled(bool(text.strip())))
        self.event_title.returnPressed.connect(self.accept)
        title_row = QFrame()
        title_row.setProperty("class", "row")
        title_row.setMinimumHeight(t.ROW_MIN)
        lay = QHBoxLayout(title_row)
        lay.setContentsMargins(16, 4, 16, 4)
        lay.addWidget(self.event_title)
        form.add(title_row)
        self.body.addWidget(form)

        when = kit.Group(sheet=True)
        now = datetime.now()
        self.start = kit.time_pill(QTime((now.hour + 1) % 24, 0))
        when.add(kit.Row("Starts today", trailing=self.start))
        self.minutes = kit.Stepper(30, 5, 480, 5, "{} min")
        when.add(kit.Row("Length", trailing=self.minutes))
        self.body.addWidget(when)
        self.done_button.setEnabled(False)

    def accept(self) -> None:
        if self.event_title.text().strip():
            super().accept()


class PinsDialog(layers.Sheet):
    """Choose up to six metrics for the Home tiles."""

    def __init__(self, parent: QWidget, fields: list[str], chosen: list[str]) -> None:
        super().__init__(parent, "Pins", done="Done", cancel="Cancel", tall=True)
        self._picked = [f for f in chosen if f in fields or not fields][:MAX_PINS]
        self.group = kit.Group("Pins", sheet=True)
        self.rows: dict[str, tuple[kit.Row, kit.Glyph]] = {}
        for f in fields or chosen:
            icon, accent = PIN_LOOK.get(f, ("chart", "blue"))
            tick = kit.Glyph("check", 20, "accent", stroke=2.4)
            row = kit.Row(m.label_for(f), icon=icon, accent=t.current().tint(accent), trailing=tick,
                          clickable=True)
            row.setProperty("field", f)
            row.clicked.connect(lambda f=f: self._toggle(f))
            self.group.add(row)
            self.rows[f] = (row, tick)
        self.body.addWidget(self.group)
        self._render()

    def _toggle(self, field: str) -> None:
        if field in self._picked:
            self._picked.remove(field)
        elif len(self._picked) < MAX_PINS:
            self._picked.append(field)
        self._render()

    def _render(self) -> None:
        for f, (_row, tick) in self.rows.items():
            set_shown(tick, f in self._picked)
        self.group.heading.setText(f"{len(self._picked)} of {MAX_PINS} pinned".upper())

    def chosen(self) -> list[str]:
        return self._picked[:MAX_PINS]


# -- the page ---------------------------------------------------------------------------------------------------


class HomePage(Page):
    parts = ("alerts", "events", "tasks", "metrics", "me")
    heading = "Home"

    def __init__(self, ctx: AppContext) -> None:
        super().__init__(ctx)
        self._top_alert: dict | None = None
        #: the alert behind each pill slot (None for an empty or "All clear" slot)
        self._shown: list[dict | None] = [None] * SHOW_ALERTS
        #: open alert ids hidden with "Not now"; a new id brings the pills back
        self._snoozed: set[str] = set()
        #: hooks the shell sets: Profile, the Recovery tab, a metric's detail (by ``metrics.CARDS`` key)
        self.on_profile: Callable[[], None] | None = None
        self.on_open_recovery: Callable[[], None] | None = None
        self.on_metric: Callable[[str], None] | None = None

        # 1. the day strip
        self.days = kit.DayStrip()
        self.column.addWidget(self.days)

        # 2. today: up to three alert pills, each over its metric card
        quick = kit.IconButton("plus", "iconbtn", size=20, tip="Check in, breathe or log water")
        quick.clicked.connect(self._open_sheet)
        self.see_all = kit.text_button("See all", "link")
        self.see_all.clicked.connect(self._open_recovery)
        trailing = QWidget()
        row = QHBoxLayout(trailing)
        row.setContentsMargins(0, 0, 0, 0)
        row.setSpacing(2)
        row.addWidget(self.see_all)
        row.addWidget(quick)
        self.pills: list[kit.ActionPill] = []
        self._slots: list[QWidget] = []
        self.alert_cards: list[kit.MetricCard] = []
        today = QWidget()
        lay = QVBoxLayout(today)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(14)
        lay.addWidget(kit.section("Today", trailing))
        for i in range(SHOW_ALERTS):
            pill = fit(kit.ActionPill())
            pill.act.connect(lambda i=i: self._act(i))
            pill.clicked.connect(lambda i=i: self._info(i))
            card = kit.MetricCard("", chart_height=52)
            card.clicked.connect(lambda c=card: self._metric(str(c.property("metric") or "")))
            card.hide()
            slot = stack(pill, card, gap=8)
            if i:
                slot.hide()
            lay.addWidget(slot)
            self._slots.append(slot)
            self.pills.append(pill)
            self.alert_cards.append(card)
        self.notice = self.pills[0]
        self.notice.set_content("check", "Loading…", accent="go")
        self.column.addWidget(today)

        # 3. burnout: the risk pill, then the three cards behind it
        self.burnout = fit(kit.ActionPill("flame", "Burnout risk", "", "See more", accent="workout"))
        self.burnout.act.connect(self._see_burnout)
        self.burnout.clicked.connect(self._see_burnout)
        self.burnout_cards: dict[str, kit.MetricCard] = {}
        burn = [kit.section("Burnout"), self.burnout]
        for key in BURNOUT_CARDS:
            c = m.CARDS[key]
            card = kit.MetricCard(c.label, c.unit, key=c.accent, chart_height=52)
            card.setProperty("metric", key)
            card.clicked.connect(lambda c=card: self._metric(str(c.property("metric") or "")))
            self.burnout_cards[key] = card
            burn.append(card)
        self.column.addWidget(stack(*burn))

        # 4. tasks
        self.tasks = QWidget()
        self.task_rows = QVBoxLayout(self.tasks)
        self.task_rows.setContentsMargins(0, 0, 0, 0)
        self.task_rows.setSpacing(8)
        self.add_row = FieldRow("Add a task")
        self.add_row.submitted.connect(self._add_task)
        self.task_input = self.add_row.edit
        self.task_rows.addWidget(self.add_row)
        self.column.addWidget(stack(kit.section("Tasks"), self.tasks))

        # 5. events
        add_event = kit.IconButton("plus", "iconbtn", size=20, tip="New event")
        add_event.clicked.connect(self._add_event)
        self.calendar = kit.Group()
        self.column.addWidget(stack(kit.section("Events", add_event), self.calendar))

        # 6. movement (DX-09)
        self.movement = MovementCard()
        self.movement.clicked.connect(lambda: self._metric("movement"))
        self.movement_label = self.movement.big
        self.ring = self.movement.ring
        self.column.addWidget(self.movement)

        # 7. the user's pins
        edit = kit.text_button("Edit", "link", tip="Choose the metrics on these tiles")
        edit.clicked.connect(self._edit_pins)
        self.pins = QWidget()
        self.pin_box = QVBoxLayout(self.pins)
        self.pin_box.setContentsMargins(0, 0, 0, 0)
        self.column.addWidget(stack(kit.section("Pins", edit), self.pins))

        # 8. profile gaps (BS-01) and 9. the band (BS-02)
        self.finish = fit(kit.ActionPill("me", "Finish your profile", "", "Fix", accent="blue"))
        self.finish.act.connect(self._finish_profile)
        self.finish.clicked.connect(self._finish_profile)
        self.finish.hide()
        self.band_row = BandLine()
        self.band_row.reconnect.clicked.connect(self._reconnect)
        self.column.addWidget(stack(self.finish, self.band_row))
        self.column.addStretch(1)

    # -- loading ---------------------------------------------------------------

    def render(self, names: set[str]) -> None:
        self.days.set_today(self._today())
        if names & {"alerts", "metrics"}:
            self._show_alerts()
        if "events" in names:
            self._show_calendar()
        if "tasks" in names:
            self._show_tasks()
        if "metrics" in names:
            self._show_burnout()
            self._show_pins()
            self._show_band()
            self._show_movement()
        if "me" in names:
            self._show_finish()

    def _today(self) -> date:
        return actions.now_local(self.ctx).date()

    def _days(self) -> list[dict]:
        return list(self.ctx.store.get("metrics", {}).get("days") or [])

    def _show_alerts(self) -> None:
        alerts = open_alerts(self.ctx.store.get("alerts", []))
        ids = {str(a.get("id")) for a in alerts}
        if ids - self._snoozed:
            self._snoozed = set()  # something new arrived: show the pills again
        live = [] if alerts and ids <= self._snoozed else alerts
        self._top_alert = live[0] if live else None
        self._shown = [*live[:SHOW_ALERTS], *[None] * SHOW_ALERTS][:SHOW_ALERTS]
        set_shown(self.see_all, len(alerts) > SHOW_ALERTS or (bool(alerts) and not live))
        days, today = self._days(), self._today()
        for i, (pill, card) in enumerate(zip(self.pills, self.alert_cards, strict=True)):
            alert = self._shown[i]
            if alert is None:
                set_shown(card, False)
                if i == 0:
                    waiting = f"{len(alerts)} in Recovery" if alerts else "Nothing to do"
                    pill.set_content("check", "All clear", waiting, accent="go")
                    pill.setToolTip("Tap to check in, breathe or log water")
                set_shown(self._slots[i], i == 0)
                continue
            f = feature_for_alert(alert)
            if f is None:
                pill.set_content("bell", str(alert.get("title") or "Alert"), "", "Open")
            else:
                pill.set_content(f.icon, f.name, f.reason, f.verb, accent=f.accent)
            pill.setToolTip(str(alert.get("title") or ""))
            set_shown(self._slots[i], True)
            has_data = f is not None and bool(f.metric) and fill_card(card, m.card_for(f.metric), days, today,
                                                                     accent=f.accent)
            set_shown(card, has_data)

    def _show_burnout(self) -> None:
        data = self.ctx.store.get("metrics", {})
        days, today = self._days(), self._today()
        self.burnout.reason.setText(burnout_risk(days, data.get("latest") or {}, today))
        set_shown(self.burnout.reason, True)
        for key, card in self.burnout_cards.items():
            c = m.CARDS[key]
            if key == "avg_sleep" and m.newest(m.series(days, c.field, today)) is None:
                c = m.CARDS["sleep"]
            fill_card(card, c, days, today)
            card.name.setText(m.CARDS[key].label)
            card.setProperty("metric", c.key)

    def _show_calendar(self) -> None:
        group = self.calendar
        group.clear()
        events = self.ctx.store.data.get("events")
        if events is None:
            group.add(kit.Row(self.ctx.store.status("events", "Events"), kind="quiet"))
            return
        tz = actions.now_local(self.ctx).tzinfo
        today = self._today()
        events = sorted(events, key=lambda e: str(e.get("start")))
        if not events:
            group.add(kit.Row("Nothing planned", kind="quiet"))
        for event in events[:SHOW_EVENTS]:
            day = str(event.get("date") or str(event.get("start"))[:10])
            try:
                length = int((datetime.fromisoformat(str(event.get("end")))
                              - datetime.fromisoformat(str(event.get("start")))).total_seconds() // 60)
            except (TypeError, ValueError):
                length = 0
            sub = " · ".join(x for x in (day_word(day, today), duration_text(length)) if x)
            row = kit.Row(str(event.get("title") or "Event"), sub, clock(event.get("start"), tz))
            row.setProperty("event_id", event.get("id"))
            group.add(row)

    def _clear_tasks(self) -> None:
        rows = self.task_rows
        for i in reversed(range(rows.count())):
            w = rows.itemAt(i).widget()
            if w is not None and w is not self.add_row:
                rows.takeAt(i)
                w.hide()
                w.deleteLater()

    def _add_task_row(self, row: QWidget) -> None:
        self.task_rows.insertWidget(self.task_rows.count() - 1, row)

    def _show_tasks(self) -> None:
        self._clear_tasks()
        tasks = self.ctx.store.data.get("tasks")
        if tasks is None:
            note = label(self.ctx.store.status("tasks", "Tasks"), "foot")
            note.setContentsMargins(16, 0, 16, 0)
            self._add_task_row(note)
            return
        open_tasks = [x for x in tasks if x.get("status") != "completed"]
        order = {"today": 0, "week": 1, "month": 2}
        open_tasks.sort(key=lambda x: order.get(str(x.get("horizon")), 3))
        for task in open_tasks[:SHOW_TASKS]:
            row = TaskPill(str(task.get("title") or "Task"), task.get("id"))
            row.check.toggled.connect(self._complete_task)
            self._add_task_row(row)

    def _show_pins(self) -> None:
        clear_layout(self.pin_box)
        latest = self.ctx.store.get("metrics", {}).get("latest") or {}
        values = latest.get("metrics") or {}
        tiles = []
        for field in prefs.pins()[:MAX_PINS]:
            icon, accent = PIN_LOOK.get(field, ("chart", "blue"))
            tile = kit.IconTile(m.format_value(field, values.get(field)), m.label_for(field), icon=icon,
                                accent=accent)
            tile.setToolTip("Tap to choose your pins")
            tile.clicked.connect(self._edit_pins)
            tiles.append(tile)
        if tiles:
            self.pin_box.addWidget(kit.tile_grid(tiles, 2))
        else:
            add = kit.Row("Pin a metric", kind="link", icon="pin")
            add.clicked.connect(self._edit_pins)
            group = kit.Group()
            group.add(add)
            self.pin_box.addWidget(group)

    def _show_band(self) -> None:
        data = self.ctx.store.data.get("metrics")
        if data is None:
            self.band_row.set_line(self.ctx.store.status("metrics", "Band data"))
            return
        if not (data.get("latest") or data.get("days")):
            self.band_row.set_line("No band data", warn=True)
            return
        text, stale = self._sync_text()
        self.band_row.set_line(text, warn=stale)

    def _sync_text(self) -> tuple[str, bool]:
        """"Synced 10:42" (or "Synced Fri 10:42") and whether that's over a day ago."""
        last = actions.last_sync(self.ctx)
        if last is None:
            return "Not synced", False
        now = actions.now_local(self.ctx)
        stale = now - last > SYNC_WARN_AFTER
        if stale:
            return f"Not synced since {last:%a}", True
        return (f"Synced {last:%H:%M}" if last.date() == now.date() else f"Synced {last:%a %H:%M}"), False

    def _show_movement(self) -> None:
        latest = (self.ctx.store.get("metrics", {}).get("latest") or {})
        minutes = (latest.get("metrics") or {}).get("weeklyActiveMinutes")
        minutes = int(minutes) if isinstance(minutes, int | float) else 0
        self.movement.big.setText(f"{minutes} of {WEEKLY_ACTIVE_TARGET}")
        self.movement.note.setText("Target met" if minutes >= WEEKLY_ACTIVE_TARGET else "This week")
        self.ring.set_fraction(minutes / WEEKLY_ACTIVE_TARGET)

    def _show_finish(self) -> None:
        gaps = person.missing(self.ctx.store.data.get("me"))
        self.finish.reason.setText(f"{len(gaps)} missing" if gaps else "")
        set_shown(self.finish.reason, bool(gaps))
        self.finish.setToolTip(f"Add your {', '.join(gaps)}" if gaps else "")
        set_shown(self.finish, bool(gaps))

    # -- actions ---------------------------------------------------------------

    def _finish_profile(self) -> None:
        if self.on_profile is not None:
            self.on_profile()

    def _open_recovery(self) -> None:
        if self.on_open_recovery is not None:
            self.on_open_recovery()

    def _metric(self, key: str) -> None:
        if key and self.on_metric is not None:
            self.on_metric(key)

    def _see_burnout(self) -> None:
        if self.on_metric is not None:
            self.on_metric("burnout")
        else:
            self._open_recovery()

    def _open_sheet(self) -> None:
        sheet = QuickSheet(self.ctx, self, self._top_alert)
        sheet.exec()
        if sheet.then is not None:
            sheet.then()

    def _act(self, slot: int = 0) -> None:
        alert = self._shown[slot]
        if alert is not None:
            actions.perform(self.ctx, self, alert)
        else:
            self._open_sheet()

    def _not_now(self) -> None:
        self._snoozed = {str(a.get("id")) for a in open_alerts(self.ctx.store.get("alerts", []))}
        self._show_alerts()

    def _info(self, slot: int = 0) -> None:
        alert = self._shown[slot]
        if alert is None:
            self._open_sheet()
            return
        sheet = AlertSheet(self, alert, snooze=True)
        accepted = sheet.exec() == QDialog.DialogCode.Accepted
        if sheet.later:
            self._not_now()
        elif accepted and sheet.act:
            actions.perform(self.ctx, self, alert)

    def _add_event(self) -> None:
        dialog = EventDialog(self)
        if dialog.exec() != QDialog.DialogCode.Accepted or not dialog.event_title.text().strip():
            return
        now = actions.now_local(self.ctx)
        qt = dialog.start.time()
        start = datetime.combine(now.date(), time(qt.hour(), qt.minute()), tzinfo=now.tzinfo)
        actions.add_events(self.ctx, [(dialog.event_title.text().strip(), start, dialog.minutes.value())])

    def _add_task(self) -> None:
        title = self.task_input.text().strip()
        if not title:
            return
        self.task_input.clear()
        draft = {"id": f"pending-{datetime.now().timestamp()}", "title": title, "status": "open", "horizon": "today"}
        self.ctx.store.patch("tasks", lambda tasks: [*(tasks or []), draft])
        self.ctx.write(lambda: self.ctx.api.create_task(title, horizon="today"), area.TASKS)

    def _complete_task(self, checked: bool) -> None:
        if not checked:
            return
        task_id = str(self.sender().property("task_id") or "")
        if task_id.startswith("pending-"):
            return  # not saved yet; it gets its real id on the confirming reload
        self.ctx.store.patch("tasks", lambda tasks: [
            {**x, "status": "completed"} if x.get("id") == task_id else x for x in (tasks or [])])
        self.ctx.write(lambda: self.ctx.api.complete_task(task_id), area.TASKS)

    def _edit_pins(self) -> None:
        values = ((self.ctx.store.get("metrics", {})).get("latest") or {}).get("metrics") or {}
        fields = m.numeric_fields(values) or list(m.KNOWN)
        dialog = PinsDialog(self, fields, prefs.pins())
        if dialog.exec() == QDialog.DialogCode.Accepted:
            prefs.save({"pins": dialog.chosen()})
            self._show_pins()

    def _reconnect(self) -> None:
        actions.reconnect_band(self.ctx)
