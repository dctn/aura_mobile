"""Recovery: what you need to do (the open alerts, each with its action), then the recovery presets.

A preset starts a Session (``kind`` recovery or walk, ``mode`` timer) in the full-screen timer; finishing it sends
``complete``, ending it early sends ``stop``.
"""

from __future__ import annotations

from datetime import date
from typing import Any

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import QDialog, QFrame, QGridLayout, QHBoxLayout, QVBoxLayout, QWidget

from aura_mobile.core.features import Action, Feature, feature_for_alert
from aura_mobile.ui import actions, kit, layers
from aura_mobile.ui import tokens as t
from aura_mobile.ui.context import AppContext
from aura_mobile.ui.pages.base import Page
from aura_mobile.ui.pages.home import open_alerts
from aura_mobile.ui.widgets import clear_layout, label, set_shown

#: (title, minutes, session kind, accent, note, icon)
PRESETS: tuple[tuple[str, int, str, str, str, str], ...] = (
    ("Nap", 10, "recovery", t.SLEEP, "Lie down, eyes closed. Before 3 pm.", "sleep"),
    ("Slow breathing", 5, "recovery", t.STRESS, "In for 5, out for 5.", "wind"),
    ("Move break", 3, "walk", t.FITNESS, "Stand up, walk, stretch.", "move"),
    ("Wind-down", 10, "recovery", t.SLEEP, "Dim the lights, write tomorrow's list.", "evening"),
    ("Outdoor light", 15, "walk", t.FOCUS, "Daylight before 10 am resets the body clock.", "sun"),
    ("Recovery break", 10, "recovery", t.RECOVERY, "Ten quiet minutes, no screen.", "recover"),
    ("Easy walk", 30, "walk", t.FITNESS, "A relaxed pace you could talk at.", "walk"),
)

#: the icon on an alert's tile, by its action
ACTION_ICONS: dict[Action, str] = {
    Action.BEDTIME: "alarm",
    Action.WAKE_ANCHOR: "sunrise",
    Action.WIND_DOWN: "evening",
    Action.NAP: "sleep",
    Action.RECOVERY: "recover",
    Action.REST: "heart",
    Action.BREATHING: "wind",
    Action.ADD_WALKS: "walk",
    Action.LIGHT: "sun",
    Action.SCORES: "flame",
    Action.MOVE: "move",
    Action.ADD_SESSIONS: "dumbbell",
    Action.DOCTOR: "shield",
    Action.MOOD: "bubble",
    Action.CONNECTION: "people",
    Action.WATER: "drop",
    Action.RECONNECT: "band",
    Action.ACCOUNT: "me",
}


def feature_icon(feature: Feature | None) -> str:
    return ACTION_ICONS.get(feature.action, "bell") if feature else "bell"


def feature_colour(feature: Feature | None) -> str:
    return t.current().tint(feature.accent) if feature else t.current().accent


def day_text(value: object, today: date) -> str:
    """"Today", "Yesterday" or "Mon 19 Oct"."""
    try:
        day = date.fromisoformat(str(value)[:10])
    except ValueError:
        return ""
    gap = (today - day).days
    if gap == 0:
        return "Today"
    if gap == 1:
        return "Yesterday"
    return day.strftime("%a %d %b").replace(" 0", " ")


def section_head(text: str) -> QWidget:
    """The grey uppercase heading of a section that isn't a grouped list."""
    head = label(text.upper(), "head")
    head.setContentsMargins(16, 0, 16, 0)
    return head


def preset_grid(tiles: list[kit.ModeTile]) -> QWidget:
    """Two columns; an odd last tile takes the whole row."""
    w = QWidget()
    grid = QGridLayout(w)
    grid.setContentsMargins(0, 0, 0, 0)
    grid.setSpacing(10)
    for i, tile in enumerate(tiles):
        wide = i == len(tiles) - 1 and i % 2 == 0
        grid.addWidget(tile, i // 2, i % 2, 1, 2 if wide else 1)
    grid.setColumnStretch(0, 1)
    grid.setColumnStretch(1, 1)
    return w


class AlertCard(QFrame):
    """One thing to do: the feature's tile, the title and when, the why, the action pill, Done and ⓘ."""

    def __init__(self, alert: dict[str, Any], today: date) -> None:
        super().__init__()
        self.setProperty("class", "card")
        self.setProperty("alert", alert)
        self.feature = feature_for_alert(alert)
        f = self.feature
        lay = QVBoxLayout(self)
        lay.setContentsMargins(16, 14, 12, 12)
        lay.setSpacing(8)
        top = QHBoxLayout()
        top.setSpacing(12)
        top.addWidget(kit.Tile(feature_icon(f), feature_colour(f), 34), 0, Qt.AlignmentFlag.AlignTop)
        text = QVBoxLayout()
        text.setSpacing(1)
        head = QHBoxLayout()
        head.setSpacing(6)
        self.title = label(str(alert.get("title") or (f.name if f else "Alert")), "rowtitle-strong", wrap=True)
        head.addWidget(self.title, 1)
        if str(alert.get("priority")) == "high":
            high = label("High", "chip")
            high.setProperty("tint", "stress")
            head.addWidget(high, 0, Qt.AlignmentFlag.AlignVCenter)
        text.addLayout(head)
        meta = [day_text(alert.get("date"), today), f.name if f else str(alert.get("featureId") or "")]
        text.addWidget(label(" · ".join(x for x in meta if x), "rowsub", wrap=True))
        top.addLayout(text, 1)
        self.info = kit.IconButton("info", "iconbtn", size=22, tip="About this alert")
        top.addWidget(self.info, 0, Qt.AlignmentFlag.AlignTop)
        lay.addLayout(top)
        self.why = label(str(alert.get("body") or (f.does if f else "")), "coachwhy", wrap=True)
        self.why.setContentsMargins(46, 0, 4, 0)
        set_shown(self.why, bool(self.why.text()))
        lay.addWidget(self.why)
        lay.addSpacing(4)
        row = QHBoxLayout()
        row.setContentsMargins(0, 0, 4, 0)
        row.setSpacing(8)
        self.act = kit.text_button(f.action_label if f else "Do it", "primary")
        self.act.setProperty("tint", f.accent if f else "blue")
        row.addWidget(self.act, 1)
        self.done = kit.text_button("Done", "linkbold", tip="Hide this alert; it comes back if it's still true")
        self.done.setMinimumWidth(64)
        row.addWidget(self.done)
        lay.addLayout(row)


class RecoveryPage(Page):
    parts = ("alerts",)
    heading = "Recovery"

    def __init__(self, ctx: AppContext) -> None:
        super().__init__(ctx)
        todo = QWidget()
        lay = QVBoxLayout(todo)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(7)
        lay.addWidget(section_head("What to do"))
        self.todo = QWidget()
        self.todo_list = QVBoxLayout(self.todo)
        self.todo_list.setContentsMargins(0, 0, 0, 0)
        self.todo_list.setSpacing(10)
        lay.addWidget(self.todo)
        self.column.addWidget(todo)

        presets = QWidget()
        lay = QVBoxLayout(presets)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(7)
        lay.addWidget(section_head("Recovery presets"))
        self.preset_tiles: list[kit.ModeTile] = []
        for i, (title, minutes, _kind, accent, note, icon) in enumerate(PRESETS):
            tile = kit.ModeTile(title, f"{minutes} min", icon=icon, accent=accent)
            tile.setToolTip(note)
            tile.setProperty("preset", i)
            tile.clicked.connect(self._start_preset)
            self.preset_tiles.append(tile)
        lay.addWidget(preset_grid(self.preset_tiles))
        foot = label("Each preset runs a timed session. Let it finish to count it as done; ending early stops it.",
                     "foot", wrap=True)
        foot.setContentsMargins(16, 0, 16, 0)
        lay.addWidget(foot)
        self.column.addWidget(presets)
        self.column.addStretch(1)

    def render(self, names: set[str]) -> None:
        clear_layout(self.todo_list)
        alerts = self.ctx.store.data.get("alerts")
        if alerts is None:
            note = label(self.ctx.store.status("alerts", "Alerts"), "foot", wrap=True)
            note.setContentsMargins(16, 0, 16, 0)
            self.todo_list.addWidget(note)
            self.set_heading(self.heading)
            return
        live = open_alerts(alerts)
        count = len(live)
        self.set_heading(self.heading, f"{count} thing{'s' if count != 1 else ''} to do" if count else "All clear")
        if not live:
            calm = kit.Group()
            calm.add(kit.Row("Nothing to do right now", "Alerts appear here when your day calls for one.",
                             icon="check", accent=t.RECOVERY, wrap=True))
            self.todo_list.addWidget(calm)
            return
        today = actions.now_local(self.ctx).date()
        for alert in live[:12]:
            card = AlertCard(alert, today)
            card.act.clicked.connect(lambda _=False, a=alert: self._act(str(a.get("id"))))
            card.done.clicked.connect(lambda _=False, a=alert: self._done(str(a.get("id"))))
            card.info.clicked.connect(lambda _=False, a=alert: self._info(str(a.get("id"))))
            self.todo_list.addWidget(card)

    def _find(self, alert_id: str) -> dict | None:
        for i in range(self.todo_list.count()):
            w = self.todo_list.itemAt(i).widget()
            alert = w.property("alert") if w is not None else None
            if isinstance(alert, dict) and alert.get("id") == alert_id:
                return alert
        return None

    def _act(self, alert_id: str) -> None:
        alert = self._find(alert_id)
        if alert is not None:
            actions.perform(self.ctx, self, alert)

    def _done(self, alert_id: str) -> None:
        alert = self._find(alert_id)
        if alert is not None:
            actions.mark_done(self.ctx, alert)

    def _info(self, alert_id: str) -> None:
        alert = self._find(alert_id)
        if alert is None:
            return
        sheet = FeatureSheet(self, alert)
        if sheet.exec() == QDialog.DialogCode.Accepted and sheet.act:
            self._act(alert_id)

    def _start_preset(self) -> None:
        title, minutes, kind, accent, note, _icon = PRESETS[int(self.sender().property("preset"))]
        if title == "Wind-down":
            actions.wind_down(self.ctx, self, accent)  # the full DX-03 view: the list for tomorrow is saved
            return
        actions.run_timed_session(self.ctx, self, title, minutes, kind=kind, accent=accent, note=note,
                                  breathing=title == "Slow breathing")


class FeatureSheet(layers.Sheet):
    """ⓘ on an alert: what the feature is, from ``core/features.py``, with its action as the footer button."""

    def __init__(self, parent: QWidget, alert: dict[str, Any]) -> None:
        f = feature_for_alert(alert)
        super().__init__(parent, "About this alert", done="Done", cancel=None,
                         footer=f.action_label if f else None)
        self.act = False
        if self.footer_button is not None and f is not None:
            self.footer_button.setProperty("tint", f.accent)
            self.footer_button.pressed.connect(self._acting)
        hero = QVBoxLayout()
        hero.setSpacing(6)
        hero.setContentsMargins(0, 6, 0, 2)
        hero.addWidget(kit.Tile(feature_icon(f), feature_colour(f), 56), 0, Qt.AlignmentFlag.AlignHCenter)
        name = label(f.name if f else str(alert.get("title") or "Alert"), "h2", wrap=True)
        name.setAlignment(Qt.AlignmentFlag.AlignCenter)
        hero.addWidget(name)
        if f is not None:
            meta = label(f"{f.id} · {f.group}", "muted")
            meta.setAlignment(Qt.AlignmentFlag.AlignCenter)
            hero.addWidget(meta)
        self.body.addLayout(hero)
        now = kit.Group("This alert", sheet=True)
        now.add(kit.Row(str(alert.get("title") or ""), str(alert.get("body") or ""), wrap=True))
        self.body.addWidget(now)
        if f is None:
            return
        if f.shows.strip() != str(alert.get("body") or "").strip():
            about = kit.Group("What it says", sheet=True, footer="Example: the alert's own words change each day.")
            about.add(kit.Row(f.shows, wrap=True))
            self.body.addWidget(about)
        does = kit.Group("What you can do", sheet=True, footer=f"Shows when {f.rule}.")
        does.add(kit.Row(f.does, wrap=True))
        self.body.addWidget(does)

    def _acting(self) -> None:
        self.act = True
