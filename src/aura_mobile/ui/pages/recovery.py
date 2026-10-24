"""Recovery: what to do (each open alert as a pill with its metric card under it), then the recovery presets.

A preset starts a Session (``kind`` recovery or walk, ``mode`` timer) in the full-screen timer; finishing it sends
``complete``, ending it early sends ``stop``.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import date
from typing import Any

from PyQt6.QtWidgets import QDialog, QGridLayout, QVBoxLayout, QWidget

from aura_mobile.core import metrics as m
from aura_mobile.core.features import feature_for_alert
from aura_mobile.ui import actions, kit
from aura_mobile.ui import tokens as t
from aura_mobile.ui.context import AppContext
from aura_mobile.ui.pages.base import Page
from aura_mobile.ui.pages.home import AlertSheet, fill_card, fit, open_alerts
from aura_mobile.ui.widgets import clear_layout, label

#: (session title, tile word, minutes, session kind, accent, note, icon)
PRESETS: tuple[tuple[str, str, int, str, str, str, str], ...] = (
    ("Nap", "Nap", 10, "recovery", "sleep", "Lie down, eyes closed. Before 3 pm.", "sleep"),
    ("Slow breathing", "Breathe", 5, "recovery", "stress", "In for 5, out for 5.", "wind"),
    ("Move break", "Move", 3, "walk", "fitness", "Stand up, walk, stretch.", "move"),
    ("Wind-down", "Wind down", 10, "recovery", "sleep", "Dim the lights, write tomorrow's list.", "evening"),
    ("Outdoor light", "Light", 15, "walk", "focus", "Daylight before 10 am resets the body clock.", "sun"),
    ("Recovery break", "Rest", 10, "recovery", "recovery", "Ten quiet minutes, no screen.", "recover"),
    ("Easy walk", "Walk", 30, "walk", "fitness", "A relaxed pace you could talk at.", "walk"),
)


def preset_grid(tiles: list[kit.IconTile]) -> QWidget:
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


class AlertCard(QWidget):
    """One thing to do: the alert pill (icon, name, reason, the green verb, a check for Done) over the 14-day card
    of the metric it reads. ``act`` is the verb button, ``done`` the check, ``info`` the pill (its ``clicked``)."""

    def __init__(self, alert: dict[str, Any], days: list[dict], today: date) -> None:
        super().__init__()
        self.setProperty("alert", alert)
        self.feature = f = feature_for_alert(alert)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(8)
        if f is None:
            self.pill = kit.ActionPill("bell", str(alert.get("title") or "Alert"), "", "Open", with_done=True)
        else:
            self.pill = kit.ActionPill(f.icon, f.name, f.reason, f.verb, accent=f.accent, with_done=True)
        fit(self.pill)
        self.pill.setToolTip(str(alert.get("title") or ""))
        self.act = self.pill.verb
        self.done = self.pill.check
        self.info = self.pill
        lay.addWidget(self.pill)
        self.card: kit.MetricCard | None = None
        if f is not None and f.metric:
            card = kit.MetricCard("", chart_height=52)
            if fill_card(card, m.card_for(f.metric), days, today, accent=f.accent):
                self.card = card
                lay.addWidget(card)
            else:
                card.deleteLater()

    def text(self) -> str:
        return self.pill.text()


class RecoveryPage(Page):
    parts = ("alerts", "metrics")
    heading = "Recovery"

    def __init__(self, ctx: AppContext) -> None:
        super().__init__(ctx)
        #: opens a metric's detail by its ``metrics.CARDS`` key; the shell sets it
        self.on_metric: Callable[[str], None] | None = None
        self.todo = QWidget()
        self.todo_list = QVBoxLayout(self.todo)
        self.todo_list.setContentsMargins(0, 0, 0, 0)
        self.todo_list.setSpacing(14)
        todo = QWidget()
        lay = QVBoxLayout(todo)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(10)
        lay.addWidget(kit.section("To do"))
        lay.addWidget(self.todo)
        self.column.addWidget(todo)

        presets = QWidget()
        lay = QVBoxLayout(presets)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(10)
        lay.addWidget(kit.section("Presets"))
        self.preset_tiles: list[kit.IconTile] = []
        for i, (title, word, minutes, _kind, accent, note, icon) in enumerate(PRESETS):
            tile = kit.IconTile(word, f"{minutes} min", icon=icon, accent=accent)
            tile.setToolTip(f"{title}: {note}")
            tile.setProperty("preset", i)
            tile.clicked.connect(self._start_preset)
            self.preset_tiles.append(tile)
        lay.addWidget(preset_grid(self.preset_tiles))
        self.column.addWidget(presets)
        self.column.addStretch(1)

    def render(self, names: set[str]) -> None:
        clear_layout(self.todo_list)
        alerts = self.ctx.store.data.get("alerts")
        if alerts is None:
            note = label(self.ctx.store.status("alerts", "Alerts"), "foot")
            note.setContentsMargins(16, 0, 16, 0)
            self.todo_list.addWidget(note)
            self.set_heading(self.heading)
            return
        live = open_alerts(alerts)
        count = len(live)
        self.set_heading(self.heading, f"{count} thing{'s' if count != 1 else ''} to do" if count else "All clear")
        if not live:
            self.todo_list.addWidget(kit.ActionPill("check", "All clear", "Nothing to do", accent="go"))
            return
        today = actions.now_local(self.ctx).date()
        days = list(self.ctx.store.get("metrics", {}).get("days") or [])
        for alert in live[:12]:
            card = AlertCard(alert, days, today)
            alert_id = str(alert.get("id"))
            card.pill.act.connect(lambda a=alert_id: self._act(a))
            card.pill.done.connect(lambda a=alert_id: self._done(a))
            card.info.clicked.connect(lambda a=alert_id: self._info(a))
            if card.card is not None:
                card.card.clicked.connect(lambda c=card.card: self._metric(str(c.property("metric") or "")))
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

    def _metric(self, key: str) -> None:
        if key and self.on_metric is not None:
            self.on_metric(key)

    def _start_preset(self) -> None:
        title, _word, minutes, kind, accent, note, _icon = PRESETS[int(self.sender().property("preset"))]
        colour = t.ACCENTS.get(accent, t.RECOVERY)
        if title == "Wind-down":
            actions.wind_down(self.ctx, self, colour)  # the full DX-03 view: the list for tomorrow is saved
            return
        actions.run_timed_session(self.ctx, self, title, minutes, kind=kind, accent=colour, note=note,
                                  breathing=title == "Slow breathing")


class FeatureSheet(AlertSheet):
    """ⓘ on an alert: the feature's icon and name, what it means, what to do, and its verb as the footer button.
    Home's sheet without "Not now" (Recovery has Done)."""
