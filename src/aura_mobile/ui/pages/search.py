"""Search: a search field over two lists, in the Figma look.

"Health" lists the metric categories (``core/metrics.CATEGORIES``): a coloured glyph, the word, the newest value and
a chevron that opens the metric page. The second list opens Logging, Modes, Sessions and Settings. The field
filters both lists. Everything you do lives one tap down, on those pages (``pages/logbook.py``, ``pages/modes.py``,
``pages/sessions.py``, ``pages/metric.py``); the shell pushes them (``on_metric``, ``on_open``).
"""

from __future__ import annotations

from collections.abc import Callable

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import QFrame, QHBoxLayout, QLineEdit, QWidget

from aura_mobile.core import metrics as m
from aura_mobile.ui import actions, kit
from aura_mobile.ui.context import AppContext
from aura_mobile.ui.pages.base import Page
from aura_mobile.ui.widgets import label, set_shown

#: (name, icon, accent, extra words the field matches) for the second list; the name is what ``on_open`` gets
PLACES: tuple[tuple[str, str, str, str], ...] = (
    ("Logging", "pencil", "fitness", "water hydration mood connection strength sleep check log"),
    ("Modes", "bolt", "workout", "timer flowtime walk meeting focus start"),
    ("Sessions", "clock", "sleep", "history running stress change"),
    ("Settings", "settings", "label3", "profile goals alerts appearance dark"),
)

#: extra words per category key, so "hrv" or "steps" still finds a row
WORDS: dict[str, str] = {
    "sleep": "night bed rest",
    "heart": "hr bpm resting pulse",
    "stress": "calm tension",
    "recovery": "readiness hrv",
    "movement": "active steps walk exercise",
    "breathing": "breath respiration",
    "burnout": "tired fatigue",
    "body": "temperature skin temp",
}


def value_text(card: m.Card, raw: float | None) -> str:
    """"7.5 h", "62 bpm", "41/100"; empty when there is no value."""
    if raw is None:
        return ""
    shown = card.show(raw)
    return f"{shown}{card.unit}" if card.unit.startswith("/") else f"{shown} {card.unit}".strip()


class SearchPage(Page):
    parts = ("metrics", "active")
    heading = "Search"

    #: set by the shell: open the metric page for a ``metrics.CARDS`` key, or one of :data:`PLACES` by name
    on_metric: Callable[[str], None] | None = None
    on_open: Callable[[str], None] | None = None

    def __init__(self, ctx: AppContext) -> None:
        super().__init__(ctx)
        # the search field sits right under the large title
        field = QFrame()
        field.setProperty("class", "search")
        field.setFixedHeight(36)
        row = QHBoxLayout(field)
        row.setContentsMargins(8, 0, 6, 0)
        row.setSpacing(6)
        row.addWidget(kit.Glyph("search", 17, "label3", stroke=2.0))
        self.query = QLineEdit()
        self.query.setProperty("class", "plain")
        self.query.setPlaceholderText("Search")
        self.query.setClearButtonEnabled(True)
        self.query.textChanged.connect(self._filter)
        row.addWidget(self.query, 1)
        top = self.column.itemAt(0).layout()
        top.addSpacing(10)
        top.addWidget(field)
        self.nothing = label("", "muted", wrap=True)
        self.nothing.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.nothing.hide()
        self.column.addWidget(self.nothing)

        self.health = kit.Group("Health")
        self.categories: dict[str, kit.CategoryRow] = {}
        for key in m.CATEGORIES:
            card = m.CARDS[key]
            r = kit.CategoryRow(card.label, card.icon, card.accent)
            r.setProperty("search", f"{card.label} {WORDS.get(key, '')} health".lower())
            r.clicked.connect(lambda key=key: self._metric(key))
            self.health.add(r)
            self.categories[key] = r
        self.column.addWidget(self.health)

        self.more = kit.Group()
        self.places: dict[str, kit.CategoryRow] = {}
        for name, icon, accent, words in PLACES:
            r = kit.CategoryRow(name, icon, accent)
            r.setProperty("search", f"{name} {words}".lower())
            r.clicked.connect(lambda name=name: self._open(name))
            self.more.add(r)
            self.places[name] = r
        self.column.addWidget(self.more)
        self.column.addStretch(1)

    # -- loading ---------------------------------------------------------------

    def render(self, names: set[str]) -> None:
        if "metrics" in names:
            self._show_values()
        if "active" in names:
            active = self.ctx.store.data.get("active")
            self.places["Sessions"].set_value("Running" if isinstance(active, dict) else "")
        self._filter(self.query.text())

    def _show_values(self) -> None:
        days = (self.ctx.store.get("metrics", {}) or {}).get("days") or []
        today = actions.now_local(self.ctx).date()
        for key, r in self.categories.items():
            card = m.CARDS[key]
            r.set_value(value_text(card, m.newest(m.series(days, card.field, today))))

    def _filter(self, text: str) -> None:
        needle = text.strip().lower()
        found = False
        for group in (self.health, self.more):
            hits = 0
            for i in range(group.count()):
                w = group.rows.itemAt(i).widget()
                if w is None:
                    continue
                hit = not needle or needle in str(w.property("search"))
                set_shown(w, hit)
                hits += hit
            set_shown(group, hits > 0)
            group.list.update()
            found = found or hits > 0
        self.nothing.setText(f"No results for “{text.strip()}”")
        set_shown(self.nothing, bool(needle) and not found)

    # -- taps ------------------------------------------------------------------

    def _metric(self, key: str) -> None:
        if self.on_metric is not None:
            self.on_metric(key)

    def _open(self, name: str) -> None:
        if self.on_open is not None:
            self.on_open(name)


def rows_shown(group: kit.Group) -> list[QWidget]:
    """The rows of a group that the filter left on (tests and screenshots)."""
    out = []
    for i in range(group.count()):
        w = group.rows.itemAt(i).widget()
        if w is not None and not w.isHidden():
            out.append(w)
    return out
