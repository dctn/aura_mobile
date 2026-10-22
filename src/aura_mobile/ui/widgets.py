"""The few shared helpers every screen uses: labels, buttons, emptying a layout, and a plain card.

The old pop-ups that used to live here moved onto the phone's own layers. They stay importable by their old names:
``Toast`` is :class:`~aura_mobile.ui.layers.Hud`, ``TimerDialog``/``BreathingDialog``/``BreathingCircle`` are in
:mod:`~aura_mobile.ui.runview` and ``RatingDialog`` is in :mod:`~aura_mobile.ui.sheets` (loaded on first use,
because those modules build on this one).
"""

from __future__ import annotations

from typing import Any

from PyQt6.QtCore import QRectF, Qt
from PyQt6.QtGui import QColor, QPainter
from PyQt6.QtWidgets import QFrame, QHBoxLayout, QLabel, QLayout, QPushButton, QVBoxLayout, QWidget


def label(text: str = "", cls: str = "", *, wrap: bool = False) -> QLabel:
    w = QLabel(text)
    if cls:
        w.setProperty("class", cls)
    w.setWordWrap(wrap)
    return w


def button(text: str, cls: str = "", *, tip: str = "") -> QPushButton:
    b = QPushButton(text)
    if cls:
        b.setProperty("class", cls)
    if tip:
        b.setToolTip(tip)
    b.setCursor(Qt.CursorShape.PointingHandCursor)
    return b


def clear_layout(layout: QLayout) -> None:
    """Empty a layout. ``hide()`` before ``deleteLater()``, never ``setParent(None)`` (a top-level flash)."""
    while layout.count():
        item = layout.takeAt(0)
        child = item.widget()
        if child is not None:
            child.hide()
            child.deleteLater()
        elif item.layout() is not None:
            clear_layout(item.layout())


def set_shown(w: QWidget, on: bool) -> None:
    """``setVisible`` that never turns a widget with no parent yet into its own window (a blank frame that blinks
    on screen). Such a widget is left to appear with the parent it is later laid out in, unless it was hidden."""
    if on and w.parentWidget() is None:
        if w.testAttribute(Qt.WidgetAttribute.WA_WState_ExplicitShowHide):
            # undo an earlier hide(): a layout shows a child that was not explicitly hidden
            w.setAttribute(Qt.WidgetAttribute.WA_WState_ExplicitShowHide, False)
        return
    w.setVisible(on)


def repolish(w: QWidget) -> None:
    w.style().unpolish(w)
    w.style().polish(w)


class _Dot(QWidget):
    """The small accent dot in front of a card title (painted: the colour is data, not style)."""

    def __init__(self, colour: str) -> None:
        super().__init__()
        self.colour = colour
        self.setFixedSize(8, 8)

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QColor(self.colour))
        p.drawEllipse(QRectF(self.rect()))


class Card(QFrame):
    """A rounded card with a title row (title, optional trailing widget) and a body layout."""

    def __init__(self, title: str = "", *, trailing: QWidget | None = None, accent: str | None = None) -> None:
        super().__init__()
        self.setProperty("class", "card")
        outer = QVBoxLayout(self)
        outer.setContentsMargins(16, 14, 16, 14)
        outer.setSpacing(8)
        if title or trailing is not None:
            row = QHBoxLayout()
            row.setSpacing(8)
            if accent:
                row.addWidget(_Dot(accent), 0, Qt.AlignmentFlag.AlignVCenter)
            self.title = label(title, "title")
            row.addWidget(self.title, 1)
            if trailing is not None:
                row.addWidget(trailing)
            outer.addLayout(row)
        self.body = QVBoxLayout()
        self.body.setSpacing(6)
        outer.addLayout(self.body)
        # A card given a minimum height keeps its title at the top and the spare room at the bottom.
        outer.addStretch(1)


#: old name -> (module, name) of what replaced it
_MOVED = {
    "Toast": ("aura_mobile.ui.layers", "Hud"),
    "TimerDialog": ("aura_mobile.ui.runview", "TimerDialog"),
    "BreathingDialog": ("aura_mobile.ui.runview", "BreathingDialog"),
    "BreathingCircle": ("aura_mobile.ui.runview", "BreathingCircle"),
    "RatingDialog": ("aura_mobile.ui.sheets", "RatingDialog"),
}


def __getattr__(name: str) -> Any:
    """Old imports (``from aura_mobile.ui.widgets import Toast``) keep working without an import cycle."""
    if name in _MOVED:
        import importlib

        module, attr = _MOVED[name]
        return getattr(importlib.import_module(module), attr)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
