"""Home: the notification (coach card), then Calendar, Tasks, this week's movement and the user's pinned metrics.

A "Finish your profile" row shows while body details are missing (BS-01). Movement is ``weeklyActiveMinutes``
against 150 (DX-09). The band row says when this phone last synced and warns after a day (BS-02).

The coach card shows the newest open alert (or the newest unread inbox item) with its action. Tapping the card opens
the quick sheet: a mood or connection check-in, a quick breathe or move, and water logging, all in one place.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from datetime import date, datetime, time, timedelta
from typing import Any

from PyQt6.QtCore import QRectF, Qt, QTime, pyqtSignal
from PyQt6.QtGui import QColor, QFont, QPainter, QPen
from PyQt6.QtWidgets import QAbstractButton, QDialog, QFrame, QHBoxLayout, QLineEdit, QVBoxLayout, QWidget

from aura_mobile.core import metrics as m
from aura_mobile.core import profile as person
from aura_mobile.core.features import Feature, feature_for_alert
from aura_mobile.ui import actions, icons, kit, layers, prefs
from aura_mobile.ui import context as area
from aura_mobile.ui import tokens as t
from aura_mobile.ui.context import AppContext
from aura_mobile.ui.pages.base import Page
from aura_mobile.ui.widgets import clear_layout, label, set_shown

log = logging.getLogger(__name__)

#: DX-09: the WHO's 150 active minutes a week
WEEKLY_ACTIVE_TARGET = 150
#: BS-02: the band row turns into a warning after this long without a sync
SYNC_WARN_AFTER = timedelta(hours=24)
#: rows shown before "and N more"
SHOW_EVENTS = 6
SHOW_TASKS = 6
MAX_PINS = 6

#: the icon for a feature's group (the accent comes from ``Feature.accent``)
GROUP_ICONS = {
    "Sleep": "sleep",
    "Recovery & stress": "recover",
    "Movement": "walk",
    "Burnout": "flame",
    "Health check": "heart",
    "Mood": "bubble",
    "Connection": "people",
    "Hydration": "drop",
    "Account": "me",
    "Band": "band",
}

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


def feature_look(feature: Feature | None) -> tuple[str, str]:
    """(icon, accent hex) for a feature's tile."""
    if feature is None:
        return "bell", t.current().accent
    return GROUP_ICONS.get(feature.group, "bell"), t.current().tint(feature.accent)


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


# -- small widgets ----------------------------------------------------------------------------------------------


class CheckMark(QAbstractButton):
    """The round check in front of a task: an outline, or filled accent with a white tick. 29 wide like a tile, so
    the row's hairline lines up with the text."""

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
            p.setBrush(kit.colour("accent"))
            p.drawEllipse(r)
            p.drawPixmap(7, 7, icons.pixmap("check", QColor("white"), 15, stroke=2.6))
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
        font.setPixelSize(17)
        font.setWeight(QFont.Weight.Bold)
        p.setFont(font)
        p.setPen(kit.colour("label"))
        p.drawText(QRectF(self.rect()), Qt.AlignmentFlag.AlignCenter, f"{round(self.fraction * 100)}%")


class Stat(QWidget):
    """A small heading over a value, for the movement card."""

    def __init__(self, heading: str) -> None:
        super().__init__()
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(0)
        lay.addWidget(label(heading.upper(), "head"))
        self.value = label("", "rowtitle-strong")
        lay.addWidget(self.value)


class FieldRow(QFrame):
    """A list row holding a text field, with a leading icon and an Add pill once something is typed."""

    submitted = pyqtSignal()

    def __init__(self, placeholder: str, icon: str = "plus") -> None:
        super().__init__()
        self.setProperty("class", "row")
        self.setMinimumHeight(t.ROW_MIN)
        self.has_tile = True
        lay = QHBoxLayout(self)
        lay.setContentsMargins(16, 6, 12, 6)
        lay.setSpacing(12)
        holder = QWidget()
        holder.setFixedSize(29, 29)
        mark = kit.Glyph(icon, 22, "accent", stroke=2.0)
        mark.setParent(holder)
        mark.move(3, 3)
        lay.addWidget(holder)
        self.edit = QLineEdit()
        self.edit.setProperty("class", "plain")
        self.edit.setPlaceholderText(placeholder)
        self.edit.returnPressed.connect(self.submitted)
        lay.addWidget(self.edit, 1)
        self.add = kit.text_button("Add", "smprimary")
        self.add.clicked.connect(self.submitted)
        self.add.hide()
        lay.addWidget(self.add)
        self.edit.textChanged.connect(lambda text: self.add.setVisible(bool(text.strip())))


# -- sheets -----------------------------------------------------------------------------------------------------


class QuickSheet(layers.Sheet):
    """Check-in, quick action and logging, opened from the notification card. The chosen step runs after the
    sheet closes (``then``), so timers and check-ins open over the phone, not over the sheet."""

    def __init__(self, ctx: AppContext, parent: QWidget, alert: dict | None) -> None:
        super().__init__(parent, "Right now", done="Done", cancel=None)
        self.ctx = ctx
        self.alert = alert
        #: what to do once the sheet has closed
        self.then: Callable[[], None] | None = None
        page = parent

        if alert is not None:
            feature = feature_for_alert(alert)
            icon, accent = feature_look(feature)
            now = kit.Group("Now", sheet=True)
            now.add(kit.Row(str(alert.get("title") or "Aura"), str(alert.get("body") or ""), icon=icon,
                            accent=accent, wrap=True))
            self.body.addWidget(now)
            act = kit.text_button(feature.action_label if feature else "Do it", "primary")
            if feature is not None:
                act.setProperty("tint", feature.accent)
            act.clicked.connect(lambda: self._run(lambda: actions.perform(ctx, page, alert)))
            self.body.addWidget(act)

        checkin = kit.Group("Check in", sheet=True)
        mood = checkin.add(kit.Row("Mood", "How are you feeling?", icon="bubble", accent=t.SLEEP, chevron=True))
        mood.clicked.connect(lambda: self._run(lambda: actions.ask_checkin(ctx, page, "mood")))
        conn = checkin.add(kit.Row("Connection", "Time with people lately", icon="people", accent=t.TEAL,
                                   chevron=True))
        conn.clicked.connect(lambda: self._run(lambda: actions.ask_checkin(ctx, page, "connection")))
        self.body.addWidget(checkin)

        quick = kit.Group("Quick action", sheet=True)
        breathe = quick.add(kit.Row("Breathe", "Slow breathing", "5 min", icon="wind", accent=t.STRESS,
                                    chevron=True))
        breathe.clicked.connect(lambda: self._run(self._breathe))
        move = quick.add(kit.Row("Move", "Stand up and walk around", "3 min", icon="walk", accent=t.FITNESS,
                                 chevron=True))
        move.clicked.connect(lambda: self._run(self._move))
        self.body.addWidget(quick)

        water = kit.Group("Log", sheet=True)
        chips = QWidget()
        row = QHBoxLayout(chips)
        row.setContentsMargins(0, 0, 0, 0)
        row.setSpacing(6)
        for ml in (250, 500):
            b = kit.text_button(f"+{ml} ml", "chip", tip=f"Log {ml} ml of water")
            b.setProperty("ml", ml)
            b.clicked.connect(self._water)
            row.addWidget(b)
        water.add(kit.Row("Water", self._water_today(), icon="drop", accent=t.BLUE, trailing=chips))
        self.body.addWidget(water)

    def _water_today(self) -> str:
        logs = self.ctx.store.data.get("logs")
        if not isinstance(logs, list):
            return ""
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


class InfoSheet(layers.Sheet):
    """ⓘ on the coach card: what the feature is and what it asks."""

    def __init__(self, parent: QWidget, feature: Feature) -> None:
        super().__init__(parent, feature.name, done="Done", cancel=None)
        icon, accent = feature_look(feature)
        top = kit.Group(sheet=True)
        top.add(kit.Row(feature.name, f"{feature.id} · {feature.group}", icon=icon, accent=accent))
        self.body.addWidget(top)
        sees = kit.Group("What you see", sheet=True)
        sees.add(kit.Row(feature.shows, wrap=True))
        self.body.addWidget(sees)
        does = kit.Group("What you can do", sheet=True)
        does.add(kit.Row(feature.does, wrap=True))
        does.set_footer(f"The button: {feature.action_label}.")
        self.body.addWidget(does)


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
        super().__init__(parent, "Pinned metrics", done="Done", cancel="Cancel", tall=True)
        self._picked = [f for f in chosen if f in fields or not fields][:MAX_PINS]
        self.group = kit.Group("Pin up to 6", sheet=True)
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
        self.group.set_footer(f"{len(self._picked)} of {MAX_PINS} pinned. Tap a metric to pin or unpin it.")

    def chosen(self) -> list[str]:
        return self._picked[:MAX_PINS]


# -- the page ---------------------------------------------------------------------------------------------------


class HomePage(Page):
    parts = ("alerts", "inbox", "events", "tasks", "metrics", "me")
    heading = "Home"

    def __init__(self, ctx: AppContext) -> None:
        super().__init__(ctx)
        self._top_alert: dict | None = None
        #: open alert ids hidden with "Not now"; a new id brings the card back
        self._snoozed: set[str] = set()
        #: opens the Profile screen; the shell sets it
        self.on_profile: Callable[[], None] | None = None

        # 1. notification
        self.notice = kit.CoachCard()
        self.notice.setToolTip("Tap for a check-in, a quick action or logging")
        self.notice.clicked.connect(self._open_sheet)
        self.notice.acted.connect(self._act)
        self.notice.dismissed.connect(self._not_now)
        self.notice.info.connect(self._info)
        self.notice.set_content("Now", "Loading…", quiet=True)
        self.column.addWidget(self.notice)

        # 2. profile gaps
        self.finish_group = kit.Group()
        self.finish = kit.Row("Finish your profile", value="", icon="me", accent=t.BLUE, chevron=True)
        self.finish.setToolTip("BS-01: Health Data uses your body details for its calculations")
        self.finish.clicked.connect(self._finish_profile)
        self.finish_group.add(self.finish)
        self.finish.hide()
        self.finish_group.hide()
        self.column.addWidget(self.finish_group)

        # 3. calendar
        add_event = kit.IconButton("plus", "iconbtn", size=20, tip="New event")
        add_event.clicked.connect(self._add_event)
        self.calendar = kit.Group("Calendar", trailing=add_event)
        self.column.addWidget(self.calendar)

        # 4. tasks
        self.tasks = kit.Group("Tasks")
        self.add_row = FieldRow("Add a task")
        self.add_row.submitted.connect(self._add_task)
        self.task_input = self.add_row.edit
        self.tasks.add(self.add_row)
        self.column.addWidget(self.tasks)

        # 5. movement (DX-09)
        self.movement = kit.ChartCard("Movement", "walk", key="fitness", when="This week")
        self.movement.setCursor(Qt.CursorShape.ArrowCursor)
        self.movement_label = self.movement.big
        self.ring = PercentRing(84, 11, "fitness")
        stats = QVBoxLayout()
        stats.setSpacing(8)
        self.to_go = Stat("To go")
        self.days_left = Stat("Days left")
        stats.addWidget(self.to_go)
        stats.addWidget(self.days_left)
        stats.addStretch(1)
        self.movement.body.addLayout(stats, 1)
        self.movement.body.addWidget(self.ring, 0, Qt.AlignmentFlag.AlignBottom)
        self.column.addWidget(self.movement)

        # 6. user pins + band
        edit = kit.text_button("Edit", "link", tip="Choose the metrics on these tiles")
        edit.clicked.connect(self._edit_pins)
        self.pins = kit.Group("Your pins", trailing=edit)
        self.pin_box = QVBoxLayout()
        self.pin_box.setContentsMargins(0, 0, 0, 3)
        holder = QWidget()
        holder.setLayout(self.pin_box)
        self.pins.layout().insertWidget(1, holder)
        reconnect = kit.text_button("Reconnect", "sm", tip="BS-02: send what the band kept offline")
        reconnect.clicked.connect(self._reconnect)
        self.band_row = kit.Row("Band", "", icon="band", accent=t.RECOVERY, trailing=reconnect)
        self.band_label = self.band_row
        self.pins.add(self.band_row)
        self.column.addWidget(self.pins)
        self.column.addStretch(1)

    # -- loading ---------------------------------------------------------------

    def render(self, names: set[str]) -> None:
        if names & {"alerts", "inbox"}:
            self._show_notice()
        if "events" in names:
            self._show_calendar()
        if "tasks" in names:
            self._show_tasks()
        if "metrics" in names:
            self._show_pins()
            self._show_movement()
        if "me" in names:
            self._show_finish()

    def _today(self) -> date:
        return actions.now_local(self.ctx).date()

    def _show_notice(self) -> None:
        store = self.ctx.store
        alerts = open_alerts(store.get("alerts", []))
        ids = {str(a.get("id")) for a in alerts}
        if ids - self._snoozed:
            self._snoozed = set()  # something new arrived: show the card again
        self._top_alert = alerts[0] if alerts and not ids <= self._snoozed else None
        card = self.notice
        if self._top_alert is not None:
            alert = self._top_alert
            feature = feature_for_alert(alert)
            icon, accent = feature_look(feature)
            when = " · ".join(x for x in (feature.group if feature else "Now",
                                          day_word(str(alert.get("date") or ""), self._today())) if x)
            if len(alerts) > 1:
                when += f" · {len(alerts) - 1} more"
            card.set_content(when, str(alert.get("title") or "Aura"), str(alert.get("body") or ""),
                             feature.action_label if feature else "Open", icon=icon, accent=accent)
            card.action.setProperty("tint", feature.accent if feature else None)
            card.action.style().unpolish(card.action)
            card.action.style().polish(card.action)
            set_shown(card.info_button, feature is not None)
            return
        card.info_button.hide()
        unread = [n for n in store.get("inbox", []) if not n.get("readAt") and n.get("status") != "dismissed"]
        if unread and not self._snoozed:
            item = unread[0]
            card.set_content("Inbox", str(item.get("title") or "New notification"),
                             str(item.get("body") or "Tap for a check-in, a quick action or logging."),
                             icon="inbox", accent=t.current().accent)
            return
        waiting = f"{len(alerts)} waiting in Recovery. " if alerts else ""
        card.set_content("Now", "Nothing needed right now.",
                         f"{waiting}Tap for a check-in, a quick action or logging.", icon="check",
                         accent=t.current().good, quiet=True)

    def _show_calendar(self) -> None:
        group = self.calendar
        group.clear()
        events = self.ctx.store.data.get("events")
        if events is None:
            group.add(kit.Row(self.ctx.store.status("events", "Calendar"), kind="quiet", wrap=True))
            return
        tz = actions.now_local(self.ctx).tzinfo
        today = self._today()
        events = sorted(events, key=lambda e: str(e.get("start")))
        if not events:
            group.add(kit.Row("Nothing planned this week", kind="quiet"))
        for event in events[:SHOW_EVENTS]:
            day = str(event.get("date") or str(event.get("start"))[:10])
            sub = day_word(day, today)
            try:
                length = int((datetime.fromisoformat(str(event.get("end")))
                              - datetime.fromisoformat(str(event.get("start")))).total_seconds() // 60)
            except (TypeError, ValueError):
                length = 0
            sub = " · ".join(x for x in (sub, duration_text(length)) if x)
            row = kit.Row(str(event.get("title") or "Event"), sub, clock(event.get("start"), tz))
            row.setProperty("event_id", event.get("id"))
            group.add(row)
        if len(events) > SHOW_EVENTS:
            group.set_footer(f"And {len(events) - SHOW_EVENTS} more this week.")
        else:
            group.set_footer("")

    def _clear_tasks(self) -> None:
        rows = self.tasks.rows
        for i in reversed(range(rows.count())):
            w = rows.itemAt(i).widget()
            if w is not None and w is not self.add_row:
                rows.takeAt(i)
                w.hide()
                w.deleteLater()

    def _add_task_row(self, row: QWidget) -> None:
        self.tasks.rows.insertWidget(self.tasks.count() - 1, row)
        self.tasks.list.update()

    def _show_tasks(self) -> None:
        self._clear_tasks()
        tasks = self.ctx.store.data.get("tasks")
        if tasks is None:
            self._add_task_row(kit.Row(self.ctx.store.status("tasks", "Tasks"), kind="quiet", wrap=True))
            return
        open_tasks = [x for x in tasks if x.get("status") != "completed"]
        order = {"today": 0, "week": 1, "month": 2}
        open_tasks.sort(key=lambda x: order.get(str(x.get("horizon")), 3))
        for task in open_tasks[:SHOW_TASKS]:
            horizon = str(task.get("horizon") or "")
            check = CheckMark()
            check.setProperty("task_id", task.get("id"))
            check.toggled.connect(self._complete_task)
            row = kit.Row(str(task.get("title") or "Task"), "", horizon.capitalize() if horizon else "",
                          clickable=True)
            row.layout().insertWidget(0, check)
            row.has_tile = True
            row.clicked.connect(check.toggle)
            self._add_task_row(row)
        more = len(open_tasks) - SHOW_TASKS
        self.tasks.set_footer(f"And {more} more open." if more > 0 else ("No open tasks." if not open_tasks else ""))

    def _show_pins(self) -> None:
        clear_layout(self.pin_box)
        data = self.ctx.store.get("metrics", {})
        latest = data.get("latest") or {}
        values = latest.get("metrics") or {}
        tiles = []
        for field in prefs.pins()[:MAX_PINS]:
            icon, accent = PIN_LOOK.get(field, ("chart", "blue"))
            tile = kit.ModeTile(m.format_value(field, values.get(field)), m.label_for(field), icon=icon,
                                accent=t.current().tint(accent))
            tile.setMinimumHeight(88)
            tile.setToolTip("Tap to choose your pins")
            tile.clicked.connect(self._edit_pins)
            tiles.append(tile)
        if tiles:
            self.pin_box.addWidget(kit.tile_grid(tiles, 2))
        days = data.get("days") or []
        stale = False
        if latest:
            text, stale = self._sync_text()
            day = day_word(str(latest.get("date") or ""), self._today())
            self.band_row.set_sub(f"Readings from {day.lower() if day in ('Today', 'Yesterday') else day} · {text}")
        elif days:
            newest = max(days, key=lambda d: str(d.get("date")))
            self.band_row.set_sub(f"No computed day yet ({newest.get('date')}: {newest.get('status')})")
        elif "metrics" not in self.ctx.store.data:
            self.band_row.set_sub(self.ctx.store.status("metrics", "Band data"))
        else:
            self.band_row.set_sub("No band data in the last 10 days. Use the Test lab or Reconnect.")
        self.band_row.tile.accent = t.STRESS if stale else t.RECOVERY
        self.band_row.tile.update()
        self.pins.set_footer("Over a day since the last sync: tap Reconnect to send what the band kept." if stale
                             else "")

    def _sync_text(self) -> tuple[str, bool]:
        """The last-sync words and whether that's over a day ago."""
        last = actions.last_sync(self.ctx)
        if last is None:
            return "not synced from this phone yet", False
        text = f"last sync {last.day} {last:%b %H:%M}"
        return text, actions.now_local(self.ctx) - last > SYNC_WARN_AFTER

    def _show_movement(self) -> None:
        latest = (self.ctx.store.get("metrics", {}).get("latest") or {})
        minutes = (latest.get("metrics") or {}).get("weeklyActiveMinutes")
        card = self.movement
        today = self._today()
        days_left = 6 - today.weekday()  # Monday-to-Sunday week, today counted as gone
        if not isinstance(minutes, int | float):
            card.set_values(f"0 of {WEEKLY_ACTIVE_TARGET}", "min", "No active minutes yet this week.")
            self.ring.set_fraction(0)
            self.to_go.value.setText(f"{WEEKLY_ACTIVE_TARGET} min")
            self.days_left.value.setText(str(days_left))
            return
        minutes = int(minutes)
        left = WEEKLY_ACTIVE_TARGET - minutes
        upto = day_word(str(latest.get("date") or ""), today)
        upto = upto.lower() if upto in ("Today", "Yesterday") else upto
        note = "Target met. Nice work." if left <= 0 else "Two 30-min walks would close most of the gap."
        card.set_values(f"{minutes} of {WEEKLY_ACTIVE_TARGET}", "min",
                        f"Counted up to {upto}. {note}" if upto else note)
        self.ring.set_fraction(minutes / WEEKLY_ACTIVE_TARGET)
        self.to_go.value.setText(f"{max(0, left)} min")
        self.days_left.value.setText(str(days_left))

    def _show_finish(self) -> None:
        gaps = person.missing(self.ctx.store.data.get("me"))
        self.finish.set_value(f"{len(gaps)} missing" if gaps else "")
        self.finish.set_sub("For accurate scores" if gaps else "")
        self.finish.setToolTip(f"Add your {', '.join(gaps)}. Health Data uses these for its calculations." if gaps
                               else "")
        set_shown(self.finish, bool(gaps))
        set_shown(self.finish_group, bool(gaps))

    # -- actions ---------------------------------------------------------------

    def _finish_profile(self) -> None:
        if self.on_profile is not None:
            self.on_profile()

    def _open_sheet(self) -> None:
        sheet = QuickSheet(self.ctx, self, self._top_alert)
        sheet.exec()
        if sheet.then is not None:
            sheet.then()

    def _act(self) -> None:
        if self._top_alert is not None:
            actions.perform(self.ctx, self, self._top_alert)
        else:
            self._open_sheet()

    def _not_now(self) -> None:
        self._snoozed = {str(a.get("id")) for a in open_alerts(self.ctx.store.get("alerts", []))}
        self._show_notice()

    def _info(self) -> None:
        feature = feature_for_alert(self._top_alert) if self._top_alert is not None else None
        if feature is not None:
            InfoSheet(self, feature).exec()

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
