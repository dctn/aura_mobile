"""Search → Logging: what you note by hand, as cards you tap.

Water (NI-04): today's ml against the goal, one drop per glass, the week as bars, +250 / +500. Mood (NI-02) and
Connection (NI-03): five faces, a tap saves the check-in. Strength (PB-04): minutes and Log. The sleep check (DX-03)
opens its seven questions. Then this week's entries.
"""

from __future__ import annotations

from datetime import date, timedelta

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import QFrame, QHBoxLayout, QLabel, QVBoxLayout

from aura_mobile.ui import actions, kit
from aura_mobile.ui import tokens as t
from aura_mobile.ui.context import AppContext
from aura_mobile.ui.pages.base import Page
from aura_mobile.ui.sheets import RatingDialog
from aura_mobile.ui.widgets import label, set_shown

#: the tile on a history row, by the start of its text
HISTORY_TILES: tuple[tuple[str, str, str], ...] = (
    ("Water", "drop", t.BLUE),
    ("Strength", "dumbbell", t.FITNESS),
    ("Mood", "bubble", t.SLEEP),
    ("Connection", "people", t.TEAL),
    ("Sleep check", "sleep", t.SLEEP),
)
#: history rows shown
HISTORY_ROWS = 6


def when_text(value: str, today: date) -> str:
    """``2026-10-22T10:00…`` as "Today 10:00", "Yesterday 10:00" or "Mon 10:00"."""
    try:
        day = date.fromisoformat(value[:10])
    except ValueError:
        return value[:16].replace("T", " ")
    clock = value[11:16]
    gap = (today - day).days
    name = "Today" if gap == 0 else "Yesterday" if gap == 1 else day.strftime("%a")
    return f"{name} {clock}".strip()


def checkin_text(c: dict) -> str:
    kind = str(c.get("kind"))
    if kind == "insomnia":
        score = c.get("insomniaScore")
        if score is None and isinstance(c.get("insomnia"), list):
            score = sum(int(v) for v in c["insomnia"])
        return f"Sleep check {score}/28" if score is not None else "Sleep check"
    value = c.get(kind)
    return f"{kind.capitalize()} {value}/5" if value is not None else kind.capitalize()


def log_text(x: dict) -> str:
    data = x.get("data") or {}
    kind = str(x.get("type") or "water")
    if kind == "water":
        return f"Water {data.get('ml')} ml"
    if kind == "strength":
        return f"Strength {data.get('minutes')} min"
    return kind.capitalize()


class HistoryRow(QLabel):
    """A history line drawn as a list row: tile, what, and when on the right. Still a ``QLabel`` (``text()`` is
    what was logged)."""

    has_tile = True

    def __init__(self, what: str, when: str) -> None:
        super().__init__(what)
        self.setProperty("class", "rowtitle")
        self.setMinimumHeight(t.ROW_MIN)
        icon, accent = next(((i, a) for start, i, a in HISTORY_TILES if what.startswith(start)), ("note", None))
        self.tile = kit.Tile(icon, accent)
        self.tile.setParent(self)
        self.when = label(when, "rowvalue")
        self.when.setParent(self)
        self.setContentsMargins(57, 0, 120, 0)

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self.tile.move(16, (self.height() - self.tile.height()) // 2)
        self.when.adjustSize()
        right = 16 + self.when.width() + 8
        if self.contentsMargins().right() != right:
            self.setContentsMargins(57, 0, right, 0)
        self.when.move(self.width() - 16 - self.when.width(), (self.height() - self.when.height()) // 2)


def _card(cls: str = "bigcard") -> tuple[QFrame, QVBoxLayout]:
    frame = QFrame()
    frame.setProperty("class", cls)
    lay = QVBoxLayout(frame)
    lay.setContentsMargins(18, 14, 18, 16)
    lay.setSpacing(10)
    return frame, lay


def _head(icon: str, accent: str, name: str) -> tuple[QHBoxLayout, QLabel]:
    """A card's top line: a soft disc, the name, and a grey note on the right (returned to fill in)."""
    row = QHBoxLayout()
    row.setSpacing(10)
    row.addWidget(kit.Disc(icon, accent, 32, soft=True))
    row.addWidget(label(name, "mlabel"), 1)
    note = label("", "mwhen")
    row.addWidget(note)
    return row, note


class WaterCard(QFrame):
    """Today's water: the big ml, a drop per glass, the week's bars and two buttons."""

    def __init__(self) -> None:
        super().__init__()
        self.setProperty("class", "mcard")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(18, 14, 18, 16)
        lay.setSpacing(8)
        head, self.when = _head("drop", "blue", "Water")
        self.when.setText("Today")
        lay.addLayout(head)
        big = QHBoxLayout()
        big.setSpacing(4)
        self.big = label("–", "mbig")
        self.unit = label("", "munit")
        big.addWidget(self.big, 0, Qt.AlignmentFlag.AlignBottom)
        big.addWidget(self.unit, 0, Qt.AlignmentFlag.AlignBottom)
        big.addStretch(1)
        lay.addLayout(big)
        self.glasses = kit.DropRow("blue", 24)
        lay.addWidget(self.glasses)
        self.bars = kit.Bars(56, "blue", track=True)
        lay.addWidget(self.bars)
        buttons = QHBoxLayout()
        buttons.setSpacing(8)
        self.add_glass = kit.text_button(f"+{actions.WATER_GLASS_ML} ml", "logbtn")
        self.add_bottle = kit.text_button(f"+{2 * actions.WATER_GLASS_ML} ml", "logbtn")
        buttons.addWidget(self.add_glass, 1)
        buttons.addWidget(self.add_bottle, 1)
        lay.addLayout(buttons)

    def text(self) -> str:
        return f"{self.big.text()} {self.unit.text()}".strip()


class FaceCard(QFrame):
    """Mood or connection: the name, the last answer on the right, five faces."""

    def __init__(self, kind: str, icon: str, accent: str) -> None:
        super().__init__()
        self.kind = kind
        self.setProperty("class", "bigcard")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(18, 14, 18, 14)
        lay.setSpacing(12)
        head, self.last = _head(icon, accent, kind.capitalize())
        lay.addLayout(head)
        self.faces = kit.FaceScale(44)
        lay.addWidget(self.faces)

    def show_last(self, value: int | None) -> None:
        words = RatingDialog.WORDS.get(self.kind, RatingDialog.WORDS["mood"])
        self.last.setText(words[value - 1] if value and 1 <= value <= 5 else "")
        self.faces.set_value(value)


class LoggingPage(Page):
    parts = ("checkins", "logs", "me")
    heading = "Logging"

    def __init__(self, ctx: AppContext) -> None:
        super().__init__(ctx)
        self.water_card = WaterCard()
        self.water_card.add_glass.clicked.connect(lambda: self._water())
        self.water_card.add_bottle.clicked.connect(lambda: self._water(2 * actions.WATER_GLASS_ML))
        self.column.addWidget(self.water_card)

        self.mood = FaceCard("mood", "bubble", "sleep")
        self.mood.faces.picked.connect(lambda v: actions.save_checkin(self.ctx, self, "mood", v))
        self.column.addWidget(self.mood)
        self.connection = FaceCard("connection", "people", "teal")
        self.connection.faces.picked.connect(lambda v: actions.save_checkin(self.ctx, self, "connection", v))
        self.column.addWidget(self.connection)

        self.strength_card, lay = _card()
        head, self.strength_week = _head("dumbbell", "fitness", "Strength")
        lay.addLayout(head)
        row = QHBoxLayout()
        row.setSpacing(10)
        self.strength_minutes = kit.Stepper(30, 5, 240, 5, "{} min")
        row.addWidget(self.strength_minutes)
        row.addStretch(1)
        self.strength_log = kit.text_button("Log", "go")
        self.strength_log.clicked.connect(self._strength)
        row.addWidget(self.strength_log)
        lay.addLayout(row)
        self.column.addWidget(self.strength_card)

        checks = kit.Group()
        self.sleep_row = kit.Row("Sleep check", icon="sleep", accent=t.SLEEP, chevron=True)
        self.sleep_row.clicked.connect(self._sleep_check)
        checks.add(self.sleep_row)
        self.column.addWidget(checks)

        self.history_group = kit.Group("This week")
        self.history = self.history_group.rows
        self.column.addWidget(self.history_group)
        self.column.addStretch(1)

    # -- drawing -----------------------------------------------------------------

    def render(self, names: set[str]) -> None:
        store = self.ctx.store
        today = actions.now_local(self.ctx).date()
        logs = store.get("logs", []) or []
        water = [x for x in logs if x.get("type", "water") == "water"]
        ml = sum(int((x.get("data") or {}).get("ml") or 0) for x in water if str(x.get("date")) == today.isoformat())
        me = store.data.get("me") if isinstance(store.data.get("me"), dict) else self.ctx.me
        goal = int(((me or {}).get("healthGoals") or {}).get("waterGoalMl") or 2000)
        glass = actions.WATER_GLASS_ML
        card = self.water_card
        card.big.setText(str(ml))
        card.unit.setText(f"of {goal} ml")
        card.glasses.set_counts(ml // glass, -(-goal // glass))
        days = [today - timedelta(days=n) for n in range(6, -1, -1)]
        per_day = {d.isoformat(): 0 for d in days}
        for x in water:
            key = str(x.get("date"))
            if key in per_day:
                per_day[key] += int((x.get("data") or {}).get("ml") or 0)
        card.bars.set_data(list(per_day.values()))
        set_shown(card.bars, "logs" in store.data)

        checkins = store.get("checkins", []) or []
        for face in (self.mood, self.connection):
            last = [c for c in checkins if c.get("kind") == face.kind and c.get(face.kind) is not None]
            last.sort(key=lambda c: str(c.get("takenAt")))
            face.show_last(int(last[-1][face.kind]) if last else None)
        strength = sum(int((x.get("data") or {}).get("minutes") or 0) for x in logs if x.get("type") == "strength")
        self.strength_week.setText(f"{strength} min this week" if strength else "")
        sleep = [c for c in checkins if c.get("kind") == "insomnia"]
        self.sleep_row.set_value(checkin_text(sleep[-1]).removeprefix("Sleep check ") if sleep else "")
        self._show_history(checkins, logs, today)

    def _show_history(self, checkins: list, logs: list, today: date) -> None:
        store = self.ctx.store
        self.history_group.clear()
        rows = sorted([(str(c.get("takenAt")), checkin_text(c)) for c in checkins]
                      + [(str(x.get("takenAt")), log_text(x)) for x in logs], reverse=True)
        if not rows:
            loaded = "checkins" in store.data or "logs" in store.data
            empty = label(store.errors.get("checkins") or ("Nothing yet" if loaded else "Loading…"), "rowsub")
            empty.setContentsMargins(16, 12, 16, 12)
            self.history_group.add(empty)
        for when, what in rows[:HISTORY_ROWS]:
            self.history_group.add(HistoryRow(what, when_text(when, today)))

    # -- taps --------------------------------------------------------------------

    def _water(self, ml: int = actions.WATER_GLASS_ML) -> None:
        actions.log_water(self.ctx, ml)

    def _strength(self) -> None:
        actions.log_strength(self.ctx, self, minutes=self.strength_minutes.value())

    def _sleep_check(self) -> None:
        actions.sleep_check(self.ctx, self)
