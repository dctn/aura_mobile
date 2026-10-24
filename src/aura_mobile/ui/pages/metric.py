"""One metric, opened from a Search category or a Home/Recovery card (the Figma "Walk Distance" page).

The big newest value and its unit, the average over the range shown, a 2 Weeks / 1 Month / 2 Months switch over
tall rounded bars, a "Trending up +8%" card (the last 7 days against the 7 before) and "Pin to Home". Two weeks come
from the store's ``metrics`` part; the longer ranges are fetched on a worker the first time they are picked.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import timedelta

from PyQt6.QtCore import QPointF, Qt
from PyQt6.QtGui import QPainter, QTransform
from PyQt6.QtWidgets import QFrame, QHBoxLayout, QVBoxLayout, QWidget

from aura_mobile.core import metrics as m
from aura_mobile.ui import actions, icons, kit, prefs
from aura_mobile.ui.context import AppContext
from aura_mobile.ui.pages.base import Page
from aura_mobile.ui.widgets import label, repolish, set_shown

#: (switch label, days)
RANGES: tuple[tuple[str, int], ...] = (("2 Weeks", 14), ("1 Month", 30), ("2 Months", 60))
#: a change smaller than this reads as "Steady"
STEADY_PCT = 2.0


def trend(values: list[float | None]) -> float | None:
    """The last 7 days' average against the 7 before, in percent; None without both weeks."""
    now, before = m.average(values[-7:]), m.average(values[-14:-7])
    if now is None or before is None or before == 0:
        return None
    return (now - before) / abs(before) * 100


class TrendArrow(QWidget):
    """The ``trend`` glyph, flipped for a fall and flat-ish for steady, in the metric's colour."""

    def __init__(self, accent: str, size: int = 22) -> None:
        super().__init__()
        self.accent, self.direction = accent, 1
        self.setFixedSize(size, size)

    def set_trend(self, accent: str, direction: int) -> None:
        self.accent, self.direction = accent, direction
        self.update()

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform)
        px = icons.pixmap("trend" if self.direction else "minus", kit.colour(self.accent), self.width(), stroke=2.4)
        if self.direction < 0:
            px = px.transformed(QTransform().scale(1, -1))
        p.drawPixmap(QPointF(0, 0), px)


class MetricPage(Page):
    parts = ("metrics",)
    heading = "Metric"

    #: set by the shell: the pins changed, so Home redraws them
    on_pins_changed: Callable[[], None] | None = None

    def __init__(self, ctx: AppContext) -> None:
        super().__init__(ctx)
        self.card = m.CARDS[m.CATEGORIES[0]]
        #: days fetched for the longer ranges, by range length; cleared when the store's metrics change
        self._ranges: dict[int, list[dict]] = {}
        self._fetching: set[int] = set()
        self._quiet = False

        hero = QHBoxLayout()
        hero.setSpacing(6)
        self.big = label("–", "hero")
        self.unit = label("", "munit")
        hero.addWidget(self.big, 0, Qt.AlignmentFlag.AlignBottom)
        hero.addWidget(self.unit, 0, Qt.AlignmentFlag.AlignBottom)
        hero.addStretch(1)
        avg = QHBoxLayout()
        avg.setSpacing(6)
        self.avg_word = label("Avg", "mwhen")
        self.avg = label("", "heroavg")
        avg.addWidget(self.avg_word)
        avg.addWidget(self.avg)
        avg.addStretch(1)
        top = self.column.itemAt(0).layout()
        top.addLayout(hero)
        top.addLayout(avg)

        self.chart = QFrame()
        self.chart.setProperty("class", "mcard")
        lay = QVBoxLayout(self.chart)
        lay.setContentsMargins(16, 14, 16, 18)
        lay.setSpacing(16)
        self.range = kit.Segmented([r[0] for r in RANGES])
        self.range.changed.connect(lambda _i: self._draw())
        lay.addWidget(self.range)
        self.bars = kit.Bars(210, self.card.accent, track=True)
        lay.addWidget(self.bars)
        self.column.addWidget(self.chart)

        self.trend_card = QFrame()
        self.trend_card.setProperty("class", "bigcard")
        tl = QVBoxLayout(self.trend_card)
        tl.setContentsMargins(18, 16, 18, 16)
        tl.setSpacing(4)
        head = QHBoxLayout()
        head.setSpacing(8)
        self.arrow = TrendArrow(self.card.accent)
        self.trend_title = label("", "trendtitle")
        head.addWidget(self.arrow)
        head.addWidget(self.trend_title, 1)
        tl.addLayout(head)
        line = QHBoxLayout()
        line.setSpacing(6)
        self.trend_pct = label("", "trendbig")
        line.addWidget(self.trend_pct, 0, Qt.AlignmentFlag.AlignBottom)
        self.trend_note = label("vs last week", "mwhen")
        line.addWidget(self.trend_note, 0, Qt.AlignmentFlag.AlignBottom)
        line.addStretch(1)
        tl.addLayout(line)
        self.column.addWidget(self.trend_card)

        pin_group = kit.Group()
        self.pin = kit.Switch(tip="Show it in Your pins on Home")
        self.pin.toggled.connect(self._pin)
        self.pin_row = kit.Row("Pin to Home", trailing=self.pin)
        pin_group.add(self.pin_row)
        self.column.addWidget(pin_group)
        self.column.addStretch(1)

    # -- which metric ------------------------------------------------------------

    def show_card(self, key: str) -> None:
        """Point the page at one ``metrics.CARDS`` key (or a raw column name) and draw it from the store."""
        self.card = m.CARDS.get(key) or m.card_for(key)
        self.set_heading(self.card.label)
        self.bars.key = self.card.accent
        self.avg.setProperty("tint", self.card.accent)
        repolish(self.avg)
        self.range.set_index(0)
        self._quiet = True  # showing the pin as it is, not toggling it
        self.pin.setChecked(self.card.field in prefs.pins())
        self._quiet = False
        self.verticalScrollBar().setValue(0)
        self._draw()

    # -- drawing -----------------------------------------------------------------

    def render(self, names: set[str]) -> None:
        if "metrics" in names:
            self._ranges.clear()
            self._draw()

    def days_for(self, count: int) -> list[dict]:
        """The summary days for a range: the store's two weeks, or a longer fetch (asked for here when missing)."""
        stored = (self.ctx.store.get("metrics", {}) or {}).get("days") or []
        if count <= 14:
            return stored
        if count not in self._ranges:
            self._fetch(count)
            return stored
        return self._ranges[count]

    def _draw(self) -> None:
        card = self.card
        today = actions.now_local(self.ctx).date()
        count = RANGES[max(0, self.range.index())][1]
        values = m.series(self.days_for(count), card.field, today, count)
        recent = m.series(self.days_for(14), card.field, today, 14)
        newest = m.newest(recent)
        self.big.setText(card.show(newest) if newest is not None else m.NO_VALUE)
        self.unit.setText(card.unit)
        set_shown(self.unit, bool(card.unit) and newest is not None)
        mean = m.average(values)
        self.avg.setText(f"{card.show(mean)} {card.unit}".strip() if mean is not None else m.NO_VALUE)
        self.bars.set_data([card.number(v) or 0.0 for v in values])
        pct = trend(recent)
        set_shown(self.trend_card, pct is not None)
        if pct is not None:
            direction = 0 if abs(pct) < STEADY_PCT else (1 if pct > 0 else -1)
            self.arrow.set_trend(card.accent, direction)
            self.trend_title.setText(("Steady", "Trending up", "Trending down")[direction])
            self.trend_pct.setText(f"{pct:+.0f}%")

    def _fetch(self, count: int) -> None:
        if count in self._fetching or not self.ctx.session.is_signed_in:
            return
        self._fetching.add(count)
        today = actions.now_local(self.ctx).date()
        start, end = (today - timedelta(days=count - 1)).isoformat(), today.isoformat()
        api = self.ctx.api

        def done(days: object) -> None:
            self._fetching.discard(count)
            self._ranges[count] = days if isinstance(days, list) else []
            self._draw()

        def failed(exc: Exception) -> None:
            self._fetching.discard(count)
            self.ctx.report_error(exc)

        self.ctx.call(lambda: api.metrics_range(start, end), done, failed)

    # -- pin ---------------------------------------------------------------------

    def _pin(self, on: bool) -> None:
        if self._quiet:
            return
        pins = [f for f in prefs.pins() if f != self.card.field]
        if on:
            pins.append(self.card.field)
        prefs.save({"pins": pins})
        self.ctx.toast.emit(f"{self.card.label} {'pinned to' if on else 'off'} Home")
        if self.on_pins_changed is not None:
            self.on_pins_changed()
