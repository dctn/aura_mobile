"""Profile (BS-01): who you are, your body details, health goals, your band, which alerts you get, your inbox, and
your data.

Saving sends the name and body details, then the goals, as two writes: one refused part (dev's Identity refuses
``healthGoals`` until it is redeployed) doesn't lose the other. The band is paired with ``PUT /v1/me/settings``
(``pairedBandId``); its last sync is this phone's own record (BS-02).

This is the Settings page, pushed from the gear on every tab, in the Figma look: short grouped rows with the value
on the right and no footers; the few things worth explaining sit behind the ⓘ in the nav bar. Every change saves at
once (a body detail or goal set in its sheet, a time pill when it loses focus). Every alert switch writes
``PUT /v1/alerts/settings``; switching one off is how to check a feature stays quiet (the Test lab's run for it
should then find nothing).
"""

from __future__ import annotations

import secrets
from collections.abc import Callable, Sequence
from typing import Any

from PyQt6.QtCore import QDate, QRectF, Qt, QTime, QTimer, QUrl
from PyQt6.QtGui import QColor, QDesktopServices, QFont, QPainter
from PyQt6.QtWidgets import (
    QComboBox,
    QDateEdit,
    QDialog,
    QDoubleSpinBox,
    QHBoxLayout,
    QLineEdit,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from aura_mobile.core import profile as person
from aura_mobile.core.features import ALERT_FEATURES, Feature
from aura_mobile.ui import actions, kit, layers
from aura_mobile.ui import context as area
from aura_mobile.ui import tokens as t
from aura_mobile.ui.context import AppContext
from aura_mobile.ui.pages.base import Page
from aura_mobile.ui.widgets import label, repolish, set_shown

EXPORT_POLL_MS = 5000
NO_DATE = QDate(1900, 1, 1)  # the date edit's "Not set"

#: the line icon on each feature's tile (kept for older callers; the Figma rows use ``Feature.icon``)
FEATURE_ICON = {
    "DX-01": "sleep", "DX-02": "alarm", "DX-03": "evening", "DX-04": "timer", "DX-05": "recover",
    "DX-06": "shield", "DX-07": "wind", "DX-09": "walk", "PB-01": "sun", "PB-02": "flame", "PB-03": "move",
    "PB-04": "dumbbell", "PB-05": "heart", "NI-02": "bubble", "NI-03": "people", "NI-04": "drop",
    "BS-01": "me", "BS-02": "band",
}

#: the feature's group → the Alerts list it sits in (the one-feature groups join a bigger one; the band's own
#: alert sits with the band)
ALERT_AREA = {
    "Sleep": "Sleep", "Recovery & stress": "Recovery & stress", "Burnout": "Recovery & stress",
    "Health check": "Recovery & stress", "Movement": "Movement", "Mood": "Mood & habits",
    "Connection": "Mood & habits", "Hydration": "Mood & habits", "Band": "Band",
}

#: the ⓘ sheet: (glyph, line)
ABOUT = (
    ("me", "Body details and goals save as soon as you set them. Health Data uses them for calories, VO2max and "
           "heart-rate zones."),
    ("alarm", "Bedtime rings the wind-down reminder; wake time rings the alarm."),
    ("bell", "Switch an alert off and it stops sending. Alerts land in the inbox: push is not set up on dev."),
    ("band", "Pairing saves the band on your account. Sync sends what this phone holds."),
    ("trash", "Delete removes the account and everything in it on dev."),
)


def feature_tile(feature: Feature) -> tuple[str, str]:
    """The icon name and tile colour for a feature."""
    return FEATURE_ICON.get(feature.id, "circle"), t.ACCENTS.get(feature.accent, t.BLUE)


def set_tone(w: QWidget, tone: str) -> None:
    """Colour a ``rowsub`` line: ``good``, ``warn``, ``bad`` or plain."""
    w.setProperty("tone", tone)
    repolish(w)


class FitSheet(layers.Sheet):
    """A Done-only sheet for a few short lines. Put widgets in ``self.body``; :meth:`fit` closes the column."""

    def __init__(self, parent: QWidget, title: str) -> None:
        super().__init__(parent, title, done="Done", cancel=None)

    def fit(self) -> None:
        self.body.addStretch(1)


class InfoSheet(FitSheet):
    """The one ⓘ a screen may have: a few short lines, each with a glyph."""

    def __init__(self, parent: QWidget, title: str, lines: Sequence[tuple[str, str]]) -> None:
        super().__init__(parent, title)
        group = kit.Group(sheet=True)
        for icon, text in lines:
            group.add(kit.GlyphRow(text, icon, wrap=True))
        self.body.addWidget(group)
        self.fit()


def info_button(parent: QWidget, title: str, lines: Sequence[tuple[str, str]]) -> kit.IconButton:
    """The round ⓘ that opens :class:`InfoSheet`."""
    b = kit.IconButton("info", "round", size=20, tip="About this screen")

    def show() -> None:
        sheet = InfoSheet(parent, title, lines)
        sheet.exec()
        sheet.deleteLater()

    b.clicked.connect(show)
    return b


def _hours(minutes: int) -> str:
    """480 → "8 h", 465 → "7 h 45 m"."""
    h, m = divmod(int(minutes), 60)
    return f"{h} h" if not m else f"{h} h {m} m"


class Avatar(QWidget):
    """A round accent disc with the first letter of the name."""

    def __init__(self, size: int = 60) -> None:
        super().__init__()
        self.setFixedSize(size, size)
        self.initial = "?"

    def set_name(self, name: str) -> None:
        self.initial = (name.strip()[:1] or "?").upper()
        self.update()

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(kit.colour("accent"))
        p.drawEllipse(QRectF(self.rect()))
        font = QFont(t.FONT)
        font.setPixelSize(round(self.height() * 0.42))
        font.setWeight(QFont.Weight.DemiBold)
        p.setFont(font)
        p.setPen(QColor("white"))
        p.drawText(self.rect(), Qt.AlignmentFlag.AlignCenter, self.initial)


class ChoicePill(QPushButton):
    """A grey pill showing one choice; a tap opens an action sheet with the rest. Reads like a ``QComboBox``."""

    def __init__(self, title: str) -> None:
        super().__init__()
        self.title = title
        self._items: list[tuple[str, Any]] = []
        self._index = -1
        #: called after a pick from the sheet changed the choice
        self.chosen: Callable[[], None] | None = None
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.clicked.connect(self._pick)

    def addItem(self, text: str, data: Any = None) -> None:
        self._items.append((text, data))
        if self._index < 0:
            self.setCurrentIndex(0)

    def findData(self, data: Any) -> int:
        return next((i for i, (_text, d) in enumerate(self._items) if d == data), -1)

    def currentIndex(self) -> int:
        return self._index

    def currentData(self) -> Any:
        return self._items[self._index][1] if 0 <= self._index < len(self._items) else None

    def setCurrentIndex(self, i: int) -> None:
        if 0 <= i < len(self._items):
            self._index = i
            self.setText(self._items[i][0])

    def _pick(self) -> None:
        picked = layers.ActionSheet.pick(self, self.title, [text for text, _data in self._items])
        if picked is not None and picked != self._index:
            self.setCurrentIndex(picked)
            if self.chosen is not None:
                self.chosen()


class BirthDate(QDateEdit):
    """The birth date pill: "Not set" until touched, then it starts at 1 Jan 1990 rather than 1900."""

    def focusInEvent(self, event) -> None:
        if self.date() == NO_DATE:
            self.setDate(QDate(1990, 1, 1))
        super().focusInEvent(event)


class NumberSheet(layers.Sheet):
    """One number with − and + (height, weight, the goals), big and easy to hit. ``quick`` is the two jump sizes
    of the chips under it."""

    def __init__(self, parent: QWidget, title: str, value: int, lo: int, hi: int, step: int, fmt: str,
                 note: str = "", quick: tuple[int, int] = (5, 10)) -> None:
        super().__init__(parent, title, done="Save")
        self.stepper = kit.Stepper(value, lo, hi, step, fmt)
        row = QHBoxLayout()
        row.addStretch(1)
        row.addWidget(self.stepper)
        row.addStretch(1)
        self.body.addLayout(row)
        small, big = quick
        jumps = (-big, -small, small, big)
        quick_chips = kit.Chips([f"{'+' if j > 0 else '−'}{abs(j):,}" for j in jumps], columns=4)
        quick_chips.picked.connect(lambda i: self.stepper.setValue(self.stepper.value() + jumps[i]))
        for b in quick_chips.buttons:
            b.setCheckable(False)
        self.body.addWidget(quick_chips)
        if note:
            self.body.addWidget(label(note, "foot", wrap=True))

    def value(self) -> int:
        return self.stepper.value()


class BirthSheet(layers.Sheet):
    """Day, month and year as three lists: quicker than a calendar for a birth date."""

    MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    def __init__(self, parent: QWidget, start: QDate, latest: QDate) -> None:
        super().__init__(parent, "Birth date", done="Save")
        row = QHBoxLayout()
        row.setSpacing(8)
        self.day, self.month, self.year = QComboBox(), QComboBox(), QComboBox()
        self.day.addItems([str(d) for d in range(1, 32)])
        self.month.addItems(self.MONTHS)
        years = list(range(latest.year(), 1899, -1))
        self.year.addItems([str(y) for y in years])
        self.day.setCurrentIndex(start.day() - 1)
        self.month.setCurrentIndex(start.month() - 1)
        self.year.setCurrentIndex(max(0, years.index(start.year()) if start.year() in years else 0))
        for box, stretch in ((self.day, 2), (self.month, 3), (self.year, 3)):
            box.setProperty("class", "pill")
            box.setMinimumHeight(38)
            row.addWidget(box, stretch)
        self.body.addLayout(row)
        self.age = label("", "foot")
        self.body.addWidget(self.age)
        for box in (self.day, self.month, self.year):
            box.currentIndexChanged.connect(self._changed)
        self._latest = latest
        self._changed()

    def date(self) -> QDate:
        y, m = int(self.year.currentText()), self.month.currentIndex() + 1
        d = min(self.day.currentIndex() + 1, QDate(y, m, 1).daysInMonth())
        return min(QDate(y, m, d), self._latest)

    def _changed(self) -> None:
        born, today = self.date(), QDate.currentDate()
        age = today.year() - born.year() - ((today.month(), today.day()) < (born.month(), born.day()))
        self.age.setText(f"{born.toString('d MMMM yyyy')} · age {age}")


class InboxSheet(FitSheet):
    """The inbox's notifications; a tap on one offers read, snooze and dismiss."""

    def __init__(self, page: ProfilePage) -> None:
        super().__init__(page, "Inbox")
        self.page = page
        self.list = kit.Group(sheet=True)
        self.body.addWidget(self.list)
        self.fit()
        self.fill()

    def fill(self) -> None:
        self.list.clear()
        for row in self.page.inbox_rows():
            self.list.add(row)


def _held(w: QWidget, parent: QWidget) -> QWidget:
    """A value holder the page keeps but never shows (the rows show its value and open its editor)."""
    w.setParent(parent)
    w.hide()
    return w


class ProfilePage(Page):
    parts = ("me", "alert_off", "inbox")
    heading = "Settings"

    def __init__(self, ctx: AppContext, *, on_appearance: Callable[[bool], None] | None = None) -> None:
        super().__init__(ctx)
        self._on_appearance = on_appearance
        self._export_id: str | None = None
        self._loading_switches = False
        self._inbox_sheet: InboxSheet | None = None

        self.info = self.nav.add_right(info_button(self, "Settings", ABOUT))

        # who you are, and the inbox
        self.account = kit.TitledGroup()
        top = QWidget()
        lay = QHBoxLayout(top)
        lay.setContentsMargins(16, 14, 16, 14)
        lay.setSpacing(14)
        self.avatar = Avatar(56)
        lay.addWidget(self.avatar)
        words = QVBoxLayout()
        words.setSpacing(1)
        self.who = label("", "h2")
        self.email = label("", "rowsub")
        self.complete = label("", "rowsub", wrap=True)
        set_tone(self.complete, "warn")
        words.addStretch(1)
        words.addWidget(self.who)
        words.addWidget(self.email)
        words.addWidget(self.complete)
        words.addStretch(1)
        lay.addLayout(words, 1)
        self.account.add(top)
        self.inbox_row = kit.Row("Inbox", chevron=True)
        self.inbox_row.clicked.connect(self._open_inbox)
        self.account.add(self.inbox_row)
        self.column.addWidget(self.account)

        # body details: tap a row, set it in a sheet, and it saves at once
        self.details = kit.TitledGroup("Body")
        self.name = QLineEdit()
        self.name.setPlaceholderText("Your name")
        self.name.setProperty("class", "plain")
        self.name.setAlignment(Qt.AlignmentFlag.AlignRight)
        self.name.textChanged.connect(self._name_typed)
        self.name.editingFinished.connect(self._name_done)
        self.details.add(kit.Row("Name", trailing=self.name))
        # The values live in these (tests and _save read them); the rows show them and open the editors.
        self.birth = BirthDate(self)
        self.birth.setDisplayFormat("d MMM yyyy")
        self.birth.setMinimumDate(NO_DATE)
        self.birth.setMaximumDate(QDate.currentDate().addYears(-5))
        self.birth.setSpecialValueText("Not set")
        self.birth.hide()
        self.sex = ChoicePill("Sex")
        for value in person.SEX:
            self.sex.addItem("Not set" if value == "notSet" else value.capitalize(), value)
        self.sex.setProperty("class", "valuepick")
        self.sex.chosen = self._body_edited
        self.height = QSpinBox(self)
        self.height.setRange(49, 272)  # 49 reads "Not set"; the server takes 50-272
        self.height.hide()
        self.weight = QDoubleSpinBox(self)
        self.weight.setRange(19, 400)  # 19 reads "Not set"; the server takes 20-400
        self.weight.setDecimals(1)
        self.weight.hide()
        self.birth_row = kit.Row("Birth date", chevron=True)
        self.birth_row.clicked.connect(self._edit_birth)
        self.details.add(self.birth_row)
        sex_row = kit.Row("Sex", trailing=self.sex, chevron=True)
        sex_row.clicked.connect(self.sex.click)
        self.details.add(sex_row)
        self.height_row = kit.Row("Height", chevron=True)
        self.height_row.clicked.connect(self._edit_height)
        self.details.add(self.height_row)
        self.weight_row = kit.Row("Weight", chevron=True)
        self.weight_row.clicked.connect(self._edit_weight)
        self.details.add(self.weight_row)
        self.column.addWidget(self.details)
        self._show_body()

        # health goals: the times are pills, the amounts open a sheet
        self.goals = kit.TitledGroup("Goals")
        self.bedtime = kit.time_pill(QTime(23, 0))
        self.wake = kit.time_pill(QTime(7, 0))
        self.bedtime.editingFinished.connect(self._time_done)
        self.wake.editingFinished.connect(self._time_done)
        self.water = _held(kit.Stepper(2000, 0, 10000, 250, "{:,} ml"), self)
        self.sleep_need = _held(kit.Stepper(480, 420, 540, 15, "{} min"), self)
        self.steps = _held(kit.Stepper(8000, 0, 100000, 500, "{:,}"), self)
        self.goals.add(kit.Row("Bedtime", trailing=self.bedtime))
        self.goals.add(kit.Row("Wake time", trailing=self.wake))
        self.sleep_row = kit.Row("Sleep need", chevron=True)
        self.sleep_row.clicked.connect(lambda: self._edit_goal(self.sleep_need, "Sleep need", (15, 30)))
        self.goals.add(self.sleep_row)
        self.water_row = kit.Row("Water", chevron=True)
        self.water_row.clicked.connect(lambda: self._edit_goal(self.water, "Water", (250, 500)))
        self.goals.add(self.water_row)
        self.steps_row = kit.Row("Steps a day", chevron=True)
        self.steps_row.clicked.connect(lambda: self._edit_goal(self.steps, "Steps a day", (500, 1000)))
        self.goals.add(self.steps_row)
        self.column.addWidget(self.goals)
        self._show_goals()

        # band: what's paired (tap for pair / sync) and the last sync
        self.band = kit.TitledGroup("Band")
        self.band_row = kit.Row("Band", value="None", chevron=True)
        self.band_row.clicked.connect(self._band_menu)
        self.band.add(self.band_row)
        self.sync_row = kit.Row("Last sync", value="Never")
        self.band.add(self.sync_row)
        self.column.addWidget(self.band)

        # alerts, one list per area: the feature's name and glyph, a switch
        self.switches: dict[str, kit.Switch] = {}
        lists: dict[str, kit.Group] = {"Band": self.band}
        for feature in ALERT_FEATURES:
            where = ALERT_AREA.get(feature.group, feature.group)
            if where not in lists:
                lists[where] = kit.TitledGroup(f"{where} alerts")
                self.column.addWidget(lists[where])
            switch = kit.Switch(True, tip=feature.name)
            switch.setProperty("feature", feature.id)
            switch.toggled.connect(self._switch)
            lists[where].add(kit.GlyphRow(feature.name, feature.icon, feature.accent, trailing=switch))
            self.switches[feature.id] = switch

        # appearance
        appearance = kit.TitledGroup("Appearance")
        self.appearance = kit.Segmented(("Light", "Dark"))
        self.appearance.setFixedWidth(150)
        self.appearance.changed.connect(self._appearance_picked)
        appearance.add(kit.Row("Theme", trailing=self.appearance))
        self.column.addWidget(appearance)

        # your data
        self.data = kit.TitledGroup("Your data")
        self.export_row = kit.Row("Export my data", kind="link")
        self.export_row.clicked.connect(self._export)
        self.data.add(self.export_row)
        delete = kit.Row("Delete account", kind="danger")
        delete.clicked.connect(self._delete)
        self.data.add(delete)
        self.column.addWidget(self.data)

        leave = kit.TitledGroup()
        sign_out = kit.Row("Sign out", kind="link")
        sign_out.title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        sign_out.clicked.connect(self._sign_out)
        leave.add(sign_out)
        self.column.addWidget(leave)
        self.column.addStretch(1)

        self._export_timer = QTimer(self)
        self._export_timer.setInterval(EXPORT_POLL_MS)
        self._export_timer.timeout.connect(self._poll_export)

    def set_dark(self, dark: bool) -> None:
        self.appearance.set_index(1 if dark else 0)

    # -- loading ---------------------------------------------------------------

    def render(self, names: set[str]) -> None:
        if "me" in names:
            self._show_me()
        if "alert_off" in names:
            self._show_switches()
        if "inbox" in names:
            self._show_inbox()

    def _show_me(self) -> None:
        me = self.ctx.store.get("me", {})
        email = self.ctx.email or str(me.get("email") or "")
        self.email.setText(email)
        self.name.setText(str(me.get("displayName") or ""))
        self._name_typed(self.name.text())
        goals = me.get("healthGoals") or {}
        self.water.setValue(int(goals.get("waterGoalMl") or 2000))
        self.bedtime.setTime(QTime.fromString(goals.get("bedtime") or "23:00", "HH:mm"))
        self.wake.setTime(QTime.fromString(goals.get("wakeTime") or "07:00", "HH:mm"))
        self.sleep_need.setValue(int(goals.get("sleepNeedMinutes") or 480))
        self.steps.setValue(int(goals.get("stepsPerDay") or 8000))
        self._show_goals()
        born = QDate.fromString(str(me.get("birthDate") or ""), "yyyy-MM-dd")
        self.birth.setDate(born if born.isValid() else NO_DATE)
        self.sex.setCurrentIndex(max(0, self.sex.findData(me.get("sex") or "notSet")))
        self.height.setValue(int(me.get("heightCm") or self.height.minimum()))
        self.weight.setValue(float(me.get("weightKg") or self.weight.minimum()))
        self._show_body()
        gaps = person.missing(me) if me else []
        self.complete.setText(f"{len(gaps)} missing: {', '.join(gaps)}" if gaps else "")
        set_shown(self.complete, bool(gaps))
        self._show_band(me)

    def _show_body(self) -> None:
        """The rows' values from the held fields."""
        born = self.birth.date()
        if born == NO_DATE:
            self.birth_row.set_value("Not set")
        else:
            today = QDate.currentDate()
            age = today.year() - born.year() - ((today.month(), today.day()) < (born.month(), born.day()))
            self.birth_row.set_value(f"{born.toString('d MMM yyyy')} · age {age}")
        h, w = self.height.value(), self.weight.value()
        self.height_row.set_value("Not set" if h == self.height.minimum() else f"{h} cm")
        self.weight_row.set_value("Not set" if w == self.weight.minimum() else f"{w:g} kg")

    def _show_goals(self) -> None:
        self.sleep_row.set_value(_hours(self.sleep_need.value()))
        self.water_row.set_value(f"{self.water.value():,} ml")
        self.steps_row.set_value(f"{self.steps.value():,}")

    # -- body detail and goal editors -------------------------------------------

    def _body_edited(self) -> None:
        self._show_body()
        self._save()

    def _name_done(self) -> None:
        name = self.name.text().strip()
        if name and name != str(self.ctx.store.get("me", {}).get("displayName") or ""):
            self._save()

    def _time_done(self) -> None:
        """A time pill lost focus: save when it differs from what the account holds."""
        goals = self.ctx.store.get("me", {}).get("healthGoals") or {}
        held = (goals.get("bedtime") or "23:00", goals.get("wakeTime") or "07:00")
        if (self.bedtime.time().toString("HH:mm"), self.wake.time().toString("HH:mm")) != held:
            self._save()

    def _edit_goal(self, held: kit.Stepper, title: str, quick: tuple[int, int]) -> None:
        sheet = NumberSheet(self, title, held.value(), held.lo, held.hi, held.step, held.fmt, quick=quick)
        if sheet.exec() == QDialog.DialogCode.Accepted:
            held.setValue(sheet.value())
            self._show_goals()
            self._save()

    def _edit_birth(self) -> None:
        start = self.birth.date() if self.birth.date() != NO_DATE else QDate(1990, 1, 1)
        sheet = BirthSheet(self, start, self.birth.maximumDate())
        if sheet.exec() == QDialog.DialogCode.Accepted:
            self.birth.setDate(sheet.date())
            self._body_edited()

    def _edit_height(self) -> None:
        now = self.height.value() if self.height.value() != self.height.minimum() else 170
        sheet = NumberSheet(self, "Height", now, 50, 272, 1, "{} cm", "Without shoes.")
        if sheet.exec() == QDialog.DialogCode.Accepted:
            self.height.setValue(sheet.value())
            self._body_edited()

    def _edit_weight(self) -> None:
        now = round(self.weight.value()) if self.weight.value() != self.weight.minimum() else 70
        sheet = NumberSheet(self, "Weight", now, 20, 400, 1, "{} kg", "Morning weight is the steadiest.")
        if sheet.exec() == QDialog.DialogCode.Accepted:
            self.weight.setValue(float(sheet.value()))
            self._body_edited()

    def _name_typed(self, text: str) -> None:
        shown = text.strip() or self.email.text() or "Your profile"
        self.who.setText(text.strip() or "Your profile")
        self.avatar.set_name(shown)

    def _show_band(self, me: dict) -> None:
        band = person.paired_band(me)
        last = actions.last_sync(self.ctx)
        self.band_row.set_value(band or "None")
        self.sync_row.set_value(last.strftime("%d %b %H:%M") if last else "Never")

    def _show_switches(self) -> None:
        off = set(self.ctx.store.get("alert_off", []))
        self._loading_switches = True
        for fid, switch in self.switches.items():
            switch.setChecked(fid not in off)
        self._loading_switches = False

    # -- inbox -----------------------------------------------------------------

    def _show_inbox(self) -> None:
        items = self.ctx.store.data.get("inbox")
        if items is None:
            self.inbox_row.set_value("…")
        else:
            self.inbox_row.set_value(str(sum(1 for n in items if n.get("status") != "dismissed")))
        if self._inbox_sheet is not None:
            self._inbox_sheet.fill()

    def inbox_rows(self) -> list[kit.Row]:
        """One row per notification still in the inbox (the newest 10), for the inbox sheet."""
        items = self.ctx.store.data.get("inbox")
        if items is None:
            return [kit.Row(self.ctx.store.status("inbox", "Inbox"), kind="quiet", wrap=True)]
        live = [n for n in items if n.get("status") != "dismissed"]
        if not live:
            return [kit.Row("No notifications", kind="quiet")]
        rows = []
        for n in live[:10]:
            unread = not n.get("readAt")
            row = kit.Row(str(n.get("title") or ""), str(n.get("body") or ""), icon="bell",
                          accent=t.BLUE if unread else None, kind="strong" if unread else "", chevron=True, wrap=True)
            row.setProperty("nid", n.get("id"))
            row.setProperty("unread", unread)
            row.clicked.connect(self._inbox_pick)
            rows.append(row)
        return rows

    def inbox_sheet(self) -> InboxSheet:
        """The inbox sheet, kept up to date while it is open."""
        self._inbox_sheet = InboxSheet(self)
        return self._inbox_sheet

    def _open_inbox(self) -> None:
        sheet = self.inbox_sheet()
        sheet.exec()
        self._inbox_sheet = None
        sheet.deleteLater()

    # -- actions ---------------------------------------------------------------

    def body_changes(self) -> dict[str, Any]:
        """Name and body details as set on screen; a field left at "Not set" is not sent."""
        out: dict[str, Any] = {}
        name = self.name.text().strip()
        if name:
            out["displayName"] = name
        if self.birth.date() != NO_DATE:
            out["birthDate"] = self.birth.date().toString("yyyy-MM-dd")
        out["sex"] = str(self.sex.currentData())
        if self.height.value() != self.height.minimum():
            out["heightCm"] = self.height.value()
        if self.weight.value() != self.weight.minimum():
            out["weightKg"] = round(self.weight.value(), 1)
        return out

    def _save(self) -> None:
        body = self.body_changes()
        goals = {
            "waterGoalMl": self.water.value(),
            "bedtime": self.bedtime.time().toString("HH:mm"),
            "wakeTime": self.wake.time().toString("HH:mm"),
            "sleepNeedMinutes": self.sleep_need.value(),
            "stepsPerDay": self.steps.value(),
        }
        me = self.ctx.store.get("me", {})
        version = int(me["version"]) if me.get("version") is not None else None

        def merged(old: dict | None) -> dict:
            old = old if isinstance(old, dict) else {}
            return {**old, **body, "healthGoals": {**(old.get("healthGoals") or {}), **goals}}

        self.ctx.store.patch("me", merged)
        self.ctx.toast.emit("Saved")
        api = self.ctx.api

        def send() -> dict:
            # Two writes: the body details still save where the goals are refused.
            saved = api.update_profile_fresh(body, version) if body else {}
            next_version = int(saved["version"]) if saved.get("version") is not None else version
            return api.update_profile_fresh({"healthGoals": goals}, next_version)

        self.ctx.write(send, area.PROFILE, self._saved)

    def _band_menu(self) -> None:
        paired = person.paired_band(self.ctx.store.get("me", {}))
        picked = layers.ActionSheet.pick(self, paired or "Band",
                                         ["Sync now", ("Unpair", "danger") if paired else "Pair a band"])
        if picked == 0:
            self._sync()
        elif picked == 1:
            self._pair()

    def _pair(self) -> None:
        me = self.ctx.store.get("me", {})
        band = None if person.paired_band(me) else f"aura-band-{secrets.token_hex(3)}"
        self.ctx.store.patch("me", lambda old: {**(old or {}), "settings": {
            **((old or {}).get("settings") or {}), person.PAIRED_BAND: band}})
        self.ctx.toast.emit(f"Paired {band}" if band else "Band unpaired")
        self.ctx.write(lambda: self.ctx.api.update_settings({person.PAIRED_BAND: band}), area.PROFILE, self._saved)

    def _sync(self) -> None:
        actions.reconnect_band(self.ctx, lambda: self._show_band(self.ctx.store.get("me", {})))

    def _saved(self, body: Any) -> None:
        if isinstance(body, dict) and body:
            self.ctx.store.patch("me", lambda _old: body)

    def _switch(self, checked: bool) -> None:
        if self._loading_switches:
            return
        off = [fid for fid, switch in self.switches.items() if not switch.isChecked()]
        self.ctx.store.patch("alert_off", lambda _old: off)
        changed = self.sender()
        name = changed.toolTip() if isinstance(changed, kit.Switch) else "Alerts"
        self.ctx.toast.emit(f"{name} {'on' if checked else 'off'}")
        self.ctx.write(lambda: self.ctx.api.set_alert_settings(off), area.PROFILE)

    def _inbox_pick(self) -> None:
        row = self.sender()
        nid = str(row.property("nid"))
        verbs = (["read"] if row.property("unread") else []) + ["snooze", "dismiss"]
        names = {"read": "Mark as read", "snooze": "Snooze 15 min", "dismiss": ("Dismiss", "danger")}
        picked = layers.ActionSheet.pick(self, row.title.text(), [names[v] for v in verbs])
        if picked is not None:
            self.inbox_action(nid, verbs[picked])

    def inbox_action(self, nid: str, verb: str) -> None:
        """Read, dismiss or snooze one notification: drawn at once, then sent."""
        api = self.ctx.api
        fn = {"read": lambda: api.notification_read(nid), "dismiss": lambda: api.notification_dismiss(nid),
              "snooze": lambda: api.notification_snooze(nid, 15)}[verb]

        def change(items: list[dict] | None) -> list[dict]:
            out = []
            for n in items or []:
                if n.get("id") == nid:
                    if verb == "snooze":
                        continue  # back in 15 minutes
                    n = {**n, "readAt": n.get("readAt") or "now"} if verb == "read" else {**n, "status": "dismissed"}
                out.append(n)
            return out

        self.ctx.store.patch("inbox", change)
        self.ctx.write(fn, area.INBOX)

    def _sign_out(self) -> None:
        self.ctx.sign_out()

    def _appearance_picked(self, index: int) -> None:
        if self._on_appearance is not None:
            self._on_appearance(index == 1)

    def _export(self) -> None:
        self.export_row.set_value("Preparing…")
        self.ctx.call(self.ctx.api.request_export, self._export_started)

    def _export_started(self, body: dict) -> None:
        record = (body or {}).get("export") or {}
        self._export_id = str(record.get("exportId") or "")
        self._show_export(record)
        if record.get("status") == "preparing":
            self._export_timer.start()

    def _poll_export(self) -> None:
        if not self._export_id:
            self._export_timer.stop()
            return
        export_id = self._export_id
        self.ctx.call(lambda: self.ctx.api.export_status(export_id), self._export_polled)

    def _export_polled(self, body: dict) -> None:
        record = (body or {}).get("export") or {}
        self._show_export(record)
        if record.get("status") != "preparing":
            self._export_timer.stop()

    def _show_export(self, record: dict) -> None:
        status = record.get("status")
        if status == "ready" and record.get("url"):
            self.export_row.set_value("Ready")
            self.export_row.setToolTip(f"{record.get('bytes') or 0} bytes")
            QDesktopServices.openUrl(QUrl(str(record["url"])))
        elif status == "failed":
            self.export_row.set_value("Failed")
            self.export_row.setToolTip(f"Export failed: {record.get('error') or 'unknown'}")
        else:
            parts = record.get("parts") or {}
            done = sum(1 for v in parts.values() if v == "done")
            self.export_row.set_value(f"{done}/{len(parts) or '?'} parts")

    def delete_alert(self) -> layers.Alert:
        """The red confirm: Delete only works once DELETE is typed."""
        alert = layers.Alert(self, "Delete account?",
                             "This deletes the account and all its data on dev. Type DELETE to confirm.",
                             (("Cancel", "cancel"), ("Delete", "danger")))
        field = QLineEdit()
        field.setPlaceholderText("DELETE")
        field.setAlignment(Qt.AlignmentFlag.AlignCenter)
        alert.extra.addWidget(field)
        alert.field = field
        go = alert.buttons[1]
        go.setEnabled(False)
        field.textChanged.connect(lambda text: go.setEnabled(text.strip() == "DELETE"))
        return alert

    def _delete(self) -> None:
        alert = self.delete_alert()
        if alert.exec() != QDialog.DialogCode.Accepted or alert.field.text().strip() != "DELETE":
            return
        self.ctx.call(self.ctx.api.delete_account, self._deleted)

    def _deleted(self, _body: Any) -> None:
        if self.ctx.lab_state is not None:
            self.ctx.lab_state.reset()
        layers.Alert.tell(self, "Account deleted", "The account is being deleted. Sign up again for a fresh one.")
        self.ctx.sign_out()
