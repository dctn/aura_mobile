"""The prototype's building blocks: grouped lists and rows, switches, segmented controls, steppers, chips, chart
cards, mode tiles and the coach card.

Colours come from the stylesheet (``theme.py``, by ``class``) or, for painted parts, from :func:`tokens.current`
at paint time, so a theme switch is just a restyle and a repaint.
"""

from __future__ import annotations

from collections.abc import Sequence

from PyQt6.QtCore import QEasingCurve, QPointF, QRectF, QSize, Qt, QTime, QVariantAnimation, pyqtSignal
from PyQt6.QtGui import QColor, QPainter, QPen
from PyQt6.QtWidgets import (
    QAbstractButton,
    QButtonGroup,
    QFrame,
    QGridLayout,
    QHBoxLayout,
    QPushButton,
    QSizePolicy,
    QTimeEdit,
    QVBoxLayout,
    QWidget,
)

from aura_mobile.ui import icons
from aura_mobile.ui import tokens as t
from aura_mobile.ui.widgets import clear_layout, label, repolish, set_shown


def colour(key: str) -> QColor:
    """A palette field (``"accent"``, ``"label3"``…), a feature accent name (``"sleep"``) or a literal colour."""
    p = t.current()
    if key in t.ACCENTS:
        return t.qcolor(p.tint(key))
    return t.qcolor(getattr(p, key) if hasattr(p, key) else key)


# -- small painted pieces -------------------------------------------------------------------------------------


class Glyph(QWidget):
    """One icon in a palette colour, repainted with the theme."""

    def __init__(self, name: str, size: int = 20, key: str = "accent", *, stroke: float = 1.8) -> None:
        super().__init__()
        self.name, self.key, self.stroke, self._size = name, key, stroke, size
        self.setFixedSize(size, size)
        self.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)

    def set_icon(self, name: str, key: str | None = None) -> None:
        self.name = name
        if key:
            self.key = key
        self.update()

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.drawPixmap(0, 0, icons.pixmap(self.name, colour(self.key), self._size, stroke=self.stroke))


class Tile(QWidget):
    """The 29×29 rounded icon tile in front of a row: a feature colour with a white icon, or grey with an accent one."""

    def __init__(self, name: str, accent: str | None = None, size: int = 29) -> None:
        super().__init__()
        self.name, self.accent = name, accent
        self.setFixedSize(size, size)
        self.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QColor(self.accent) if self.accent else colour("fill"))
        p.drawRoundedRect(QRectF(self.rect()), t.R_TILE, t.R_TILE)
        side = round(self.width() * 0.66)
        ink = QColor("white") if self.accent else colour("accent")
        off = (self.width() - side) / 2
        p.drawPixmap(QPointF(off, off), icons.pixmap(self.name, ink, side))


class IconButton(QPushButton):
    """A button whose icon is painted in a palette colour: nav-bar round buttons, the back chevron, ⓘ."""

    def __init__(self, name: str, cls: str = "round", *, text: str = "", key: str = "accent", size: int = 20,
                 tip: str = "") -> None:
        super().__init__(text)
        self.name, self.key, self._size = name, key, size
        self.setProperty("class", cls)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        if tip:
            self.setToolTip(tip)

    def paintEvent(self, event) -> None:
        super().paintEvent(event)
        p = QPainter(self)
        px = icons.pixmap(self.name, colour(self.key), self._size, stroke=2.0)
        if self.text():
            y = (self.height() - self._size) / 2
            p.drawPixmap(QPointF(2, y), px)
        else:
            p.drawPixmap(QPointF((self.width() - self._size) / 2, (self.height() - self._size) / 2), px)


def text_button(text: str, cls: str = "primary", *, tip: str = "") -> QPushButton:
    b = QPushButton(text)
    b.setProperty("class", cls)
    b.setCursor(Qt.CursorShape.PointingHandCursor)
    if tip:
        b.setToolTip(tip)
    return b


# -- lists ------------------------------------------------------------------------------------------------------


class Row(QFrame):
    """A list row: optional tile, title over subtitle, value, trailing widget, chevron. ``kind`` colours the title:
    ``link`` (accent), ``danger`` (red), ``quiet`` (grey)."""

    clicked = pyqtSignal()

    def __init__(self, title: str = "", sub: str = "", value: str = "", *, icon: str | None = None,
                 accent: str | None = None, chevron: bool = False, kind: str = "", trailing: QWidget | None = None,
                 wrap: bool = False, clickable: bool | None = None) -> None:
        super().__init__()
        self.setProperty("class", "row")
        self.setMinimumHeight(t.ROW_MIN)
        self.has_tile = icon is not None
        lay = QHBoxLayout(self)
        lay.setContentsMargins(16, 9, 16, 9)
        lay.setSpacing(12)
        if icon is not None:
            self.tile = Tile(icon, accent)
            lay.addWidget(self.tile)
        text = QVBoxLayout()
        text.setSpacing(1)
        self.title = label(title, f"rowtitle-{kind}" if kind else "rowtitle", wrap=wrap)
        self.sub = label(sub, "rowsub", wrap=True)
        set_shown(self.sub, bool(sub))
        text.addWidget(self.title)
        text.addWidget(self.sub)
        lay.addLayout(text, 1)
        self.value = label(value, "rowvalue")
        set_shown(self.value, bool(value))
        lay.addWidget(self.value)
        self.trailing = trailing
        if trailing is not None:
            lay.addWidget(trailing)
        if chevron:
            lay.addWidget(Glyph("chevron", 14, "label3", stroke=2.4))
        self.clickable = (chevron or kind in ("link", "danger")) if clickable is None else clickable
        if self.clickable:
            self.setCursor(Qt.CursorShape.PointingHandCursor)
            self.setAttribute(Qt.WidgetAttribute.WA_Hover)
            self.setProperty("tap", True)

    def set_title(self, text: str) -> None:
        self.title.setText(text)

    def set_sub(self, text: str) -> None:
        self.sub.setText(text)
        set_shown(self.sub, bool(text))

    def set_value(self, text: str) -> None:
        self.value.setText(text)
        set_shown(self.value, bool(text))

    def text(self) -> str:
        return " · ".join(x for x in (self.title.text(), self.sub.text(), self.value.text()) if x)

    def mouseReleaseEvent(self, event) -> None:
        if self.clickable and event.button() == Qt.MouseButton.LeftButton and self.rect().contains(
                event.position().toPoint()):
            self.clicked.emit()
        super().mouseReleaseEvent(event)


class ListFrame(QFrame):
    """The rounded white list. Draws the hairlines between rows (inset 16, or 57 after a row with a tile)."""

    def __init__(self, cls: str = "list") -> None:
        super().__init__()
        self.setProperty("class", cls)
        self.rows = QVBoxLayout(self)
        self.rows.setContentsMargins(0, 0, 0, 0)
        self.rows.setSpacing(0)

    def paintEvent(self, event) -> None:
        super().paintEvent(event)
        p = QPainter(self)
        p.setPen(QPen(colour("sep"), 1))
        visible = [w for w in (self.rows.itemAt(i).widget() for i in range(self.rows.count()))
                   if w is not None and w.isVisible()]
        for w in visible[1:]:
            inset = 57 if getattr(w, "has_tile", False) else 16
            y = w.geometry().top()
            p.drawLine(inset, y, self.width(), y)


class Group(QWidget):
    """A heading, the list, a footer: the prototype's inset grouped list."""

    def __init__(self, heading: str = "", footer: str = "", *, trailing: QWidget | None = None,
                 sheet: bool = False) -> None:
        super().__init__()
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(7)
        head = QHBoxLayout()
        head.setContentsMargins(16, 0, 4, 0)
        self.heading = label(heading.upper() if heading else "", "head")
        head.addWidget(self.heading, 1)
        if trailing is not None:
            head.addWidget(trailing)
        self._head = QWidget()
        self._head.setLayout(head)
        set_shown(self._head, bool(heading) or trailing is not None)
        lay.addWidget(self._head)
        self.list = ListFrame("list2" if sheet else "list")
        lay.addWidget(self.list)
        self.foot = label(footer, "foot", wrap=True)
        self.foot.setContentsMargins(16, 0, 16, 0)
        set_shown(self.foot, bool(footer))
        lay.addWidget(self.foot)

    @property
    def rows(self) -> QVBoxLayout:
        return self.list.rows

    def add(self, w: QWidget) -> QWidget:
        self.list.rows.addWidget(w)
        self.list.update()
        return w

    def clear(self) -> None:
        clear_layout(self.list.rows)

    def count(self) -> int:
        return self.list.rows.count()

    def set_footer(self, text: str) -> None:
        self.foot.setText(text)
        set_shown(self.foot, bool(text))


# -- controls ---------------------------------------------------------------------------------------------------


class Switch(QAbstractButton):
    """The iOS switch: 51×31, the knob slides 20 px."""

    def __init__(self, checked: bool = False, *, tip: str = "") -> None:
        super().__init__()
        self.setCheckable(True)
        self.setChecked(checked)
        self.setFixedSize(51, 31)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        if tip:
            self.setToolTip(tip)
        self._pos = 1.0 if checked else 0.0
        self._anim = QVariantAnimation(self)
        self._anim.setDuration(180)
        self._anim.setEasingCurve(QEasingCurve.Type.OutCubic)
        self._anim.valueChanged.connect(self._moved)
        self.toggled.connect(self._slide)

    def sizeHint(self) -> QSize:
        return QSize(51, 31)

    def _slide(self, on: bool) -> None:
        self._anim.stop()
        if not self.isVisible():
            self._moved(1.0 if on else 0.0)
            return
        self._anim.setStartValue(self._pos)
        self._anim.setEndValue(1.0 if on else 0.0)
        self._anim.start()

    def _moved(self, value: float) -> None:
        self._pos = float(value)
        self.update()

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        off, on = colour("switch_off"), colour("switch_on")
        k = self._pos
        track = QColor(
            round(off.red() + (on.red() - off.red()) * k), round(off.green() + (on.green() - off.green()) * k),
            round(off.blue() + (on.blue() - off.blue()) * k), round(off.alpha() + (on.alpha() - off.alpha()) * k))
        if not self.isEnabled():
            track.setAlphaF(track.alphaF() * 0.5)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(track)
        p.drawRoundedRect(QRectF(0, 0, 51, 31), 15.5, 15.5)
        x = 2 + 20 * k
        p.setBrush(QColor(0, 0, 0, 40))
        p.drawEllipse(QRectF(x, 3.5, 27, 27))
        p.setBrush(QColor("white"))
        p.drawEllipse(QRectF(x, 2, 27, 27))


class Segmented(QFrame):
    """The segmented control: one choice of a few."""

    changed = pyqtSignal(int)

    def __init__(self, options: Sequence[str], index: int = 0) -> None:
        super().__init__()
        self.setProperty("class", "segbar")
        lay = QHBoxLayout(self)
        lay.setContentsMargins(2, 2, 2, 2)
        lay.setSpacing(2)
        self.group = QButtonGroup(self)
        self.buttons: list[QPushButton] = []
        for i, text in enumerate(options):
            b = text_button(text, "seg")
            b.setCheckable(True)
            self.group.addButton(b, i)
            lay.addWidget(b, 1)
            self.buttons.append(b)
        if self.buttons:
            self.buttons[max(0, min(index, len(self.buttons) - 1))].setChecked(True)
        self.group.idClicked.connect(self.changed)

    def index(self) -> int:
        return self.group.checkedId()

    def text(self) -> str:
        b = self.group.checkedButton()
        return b.text() if b else ""

    def set_index(self, i: int) -> None:
        if 0 <= i < len(self.buttons):
            self.buttons[i].setChecked(True)


class Stepper(QFrame):
    """− value +, for minutes and amounts."""

    changed = pyqtSignal(int)

    def __init__(self, value: int, lo: int, hi: int, step: int = 1, fmt: str = "{}") -> None:
        super().__init__()
        self.setProperty("class", "stepper")
        self.lo, self.hi, self.step, self.fmt = lo, hi, step, fmt
        self._value = max(lo, min(hi, value))
        lay = QHBoxLayout(self)
        lay.setContentsMargins(2, 2, 2, 2)
        lay.setSpacing(2)
        self.minus = IconButton("minus", "stepbtn", key="label", size=16, tip="Less")
        self.minus.clicked.connect(lambda: self.setValue(self._value - self.step, emit=True))
        self.out = label("", "stepval")
        self.out.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.out.setMinimumWidth(64)
        self.plus = IconButton("plus", "stepbtn", key="label", size=16, tip="More")
        self.plus.clicked.connect(lambda: self.setValue(self._value + self.step, emit=True))
        lay.addWidget(self.minus)
        lay.addWidget(self.out)
        lay.addWidget(self.plus)
        self._render()

    def value(self) -> int:
        return self._value

    def setValue(self, v: int, *, emit: bool = False) -> None:
        v = max(self.lo, min(self.hi, int(v)))
        if v == self._value:
            return
        self._value = v
        self._render()
        if emit:
            self.changed.emit(v)

    def _render(self) -> None:
        self.out.setText(self.fmt.format(self._value))
        self.minus.setEnabled(self._value > self.lo)
        self.plus.setEnabled(self._value < self.hi)


class Chips(QWidget):
    """Pill choices in rows of ``columns``. ``picked`` sends the index; ``multi`` lets several stay on."""

    picked = pyqtSignal(int)

    def __init__(self, options: Sequence[str], *, columns: int = 0, multi: bool = False) -> None:
        super().__init__()
        grid = QGridLayout(self)
        grid.setContentsMargins(0, 0, 0, 0)
        grid.setSpacing(8)
        self.buttons: list[QPushButton] = []
        per_row = columns or len(options) or 1
        self.group = QButtonGroup(self)
        self.group.setExclusive(not multi)
        for i, text in enumerate(options):
            b = text_button(text, "chip")
            b.setCheckable(True)
            self.group.addButton(b, i)
            grid.addWidget(b, i // per_row, i % per_row)
            self.buttons.append(b)
        self.group.idClicked.connect(self.picked)

    def selected(self) -> list[int]:
        return [i for i, b in enumerate(self.buttons) if b.isChecked()]


def time_pill(value: QTime) -> QTimeEdit:
    """A grey time pill (24-hour, five-minute steps)."""
    w = QTimeEdit(value)
    w.setDisplayFormat("HH:mm")
    w.setProperty("class", "pill")
    w.setButtonSymbols(QTimeEdit.ButtonSymbols.NoButtons)
    w.setAlignment(Qt.AlignmentFlag.AlignCenter)
    w.setFixedWidth(76)
    return w


# -- charts -----------------------------------------------------------------------------------------------------


class Ring(QWidget):
    """A progress ring: a grey track and an accent arc from the top."""

    def __init__(self, size: int = 92, stroke: int = 12, key: str = "accent") -> None:
        super().__init__()
        self.setFixedSize(size, size)
        self.stroke, self.key = stroke, key
        self.fraction = 0.0

    def set_fraction(self, f: float) -> None:
        self.fraction = max(0.0, min(1.0, f))
        self.update()

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        half = self.stroke / 2
        r = QRectF(half, half, self.width() - self.stroke, self.height() - self.stroke)
        pen = QPen(colour("fill2"), self.stroke)
        p.setPen(pen)
        p.drawEllipse(r)
        if self.fraction > 0:
            pen = QPen(colour(self.key), self.stroke, cap=Qt.PenCapStyle.RoundCap)
            p.setPen(pen)
            p.drawArc(r, 90 * 16, -round(self.fraction * 360 * 16))


class Bars(QWidget):
    """A small bar chart: dim bars, the highlighted one in full colour, an optional dashed goal line."""

    def __init__(self, height: int = 110, key: str = "accent") -> None:
        super().__init__()
        self.setFixedHeight(height)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.key = key
        self.values: list[float] = []
        self.labels: list[str] = []
        self.goal: float | None = None
        self.highlight = -1

    def set_data(self, values: Sequence[float], labels: Sequence[str] = (), *, goal: float | None = None,
                 highlight: int = -1) -> None:
        self.values, self.labels, self.goal = [max(0.0, float(v)) for v in values], list(labels), goal
        self.highlight = highlight if highlight >= 0 else len(self.values) - 1
        self.update()

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        label_h = 16 if self.labels else 0
        h = self.height() - label_h - 2
        w = self.width()
        top = max([*self.values, self.goal or 0, 1.0]) * 1.1
        p.setPen(QPen(colour("sep"), 1))
        for i in range(3):
            y = round(h * i / 2)
            p.drawLine(0, y, w, y)
        n = len(self.values)
        if n:
            slot = w / n
            bar_w = min(26.0, slot * 0.56)
            accent = colour(self.key)
            p.setPen(Qt.PenStyle.NoPen)
            for i, v in enumerate(self.values):
                c = QColor(accent)
                if i != self.highlight:
                    c.setAlphaF(0.48 if t.current().dark else 0.30)
                p.setBrush(c)
                bh = h * v / top
                x = i * slot + (slot - bar_w) / 2
                if bh > 0:
                    p.drawRoundedRect(QRectF(x, h - bh, bar_w, bh), 4, 4)
            p.setPen(colour("label3"))
            font = p.font()
            font.setPixelSize(t.SMALL)
            p.setFont(font)
            for i, text in enumerate(self.labels[:n]):
                p.drawText(QRectF(i * slot, h + 2, slot, label_h), Qt.AlignmentFlag.AlignCenter, text)
        if self.goal:
            y = h - h * self.goal / top
            pen = QPen(colour("label3"), 1.5, Qt.PenStyle.DashLine)
            pen.setDashPattern([3, 3])
            p.setPen(pen)
            p.drawLine(QPointF(0, y), QPointF(w, y))


class Bar(QWidget):
    """A thin rounded progress line (the lab's run, the water day)."""

    def __init__(self, key: str = "accent", height: int = 6) -> None:
        super().__init__()
        self.setFixedHeight(height)
        self.key = key
        self.fraction = 0.0

    def set_fraction(self, f: float) -> None:
        self.fraction = max(0.0, min(1.0, f))
        self.update()

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setPen(Qt.PenStyle.NoPen)
        r = self.height() / 2
        p.setBrush(colour("fill"))
        p.drawRoundedRect(QRectF(self.rect()), r, r)
        if self.fraction > 0:
            p.setBrush(colour(self.key))
            p.drawRoundedRect(QRectF(0, 0, max(self.height(), self.width() * self.fraction), self.height()), r, r)


# -- cards ------------------------------------------------------------------------------------------------------


class Clickable(QFrame):
    clicked = pyqtSignal()

    def __init__(self, cls: str) -> None:
        super().__init__()
        self.setProperty("class", cls)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setAttribute(Qt.WidgetAttribute.WA_Hover)

    def mouseReleaseEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton and self.rect().contains(event.position().toPoint()):
            self.clicked.emit()
        super().mouseReleaseEvent(event)


class ChartCard(Clickable):
    """Health-style card: accent icon + name, when ›, a big number with its unit, a note, then a chart."""

    def __init__(self, name: str, icon: str, *, key: str = "accent", when: str = "") -> None:
        super().__init__("card")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(16, 14, 16, 12)
        lay.setSpacing(4)
        head = QHBoxLayout()
        head.setSpacing(6)
        head.addWidget(Glyph(icon, 18, key))
        self.name = label(name, "ccname")
        self.name.setProperty("tint", key)
        head.addWidget(self.name, 1)
        self.when = label(when, "ccwhen")
        head.addWidget(self.when)
        head.addWidget(Glyph("chevron", 13, "label3", stroke=2.4))
        lay.addLayout(head)
        big = QHBoxLayout()
        big.setSpacing(4)
        self.big = label("", "big")
        self.unit = label("", "bigunit")
        big.addWidget(self.big, 0, Qt.AlignmentFlag.AlignBottom)
        big.addWidget(self.unit, 0, Qt.AlignmentFlag.AlignBottom)
        big.addStretch(1)
        lay.addLayout(big)
        self.note = label("", "ccnote", wrap=True)
        lay.addWidget(self.note)
        self.body = QHBoxLayout()
        self.body.setSpacing(16)
        lay.addLayout(self.body)

    def set_values(self, big: str, unit: str = "", note: str = "") -> None:
        self.big.setText(big)
        self.unit.setText(unit)
        set_shown(self.unit, bool(unit))
        self.note.setText(note)
        set_shown(self.note, bool(note))


class ModeTile(Clickable):
    """The 2-column tiles: an icon tile, a name, a status line."""

    def __init__(self, name: str, status: str = "", *, icon: str = "circle", accent: str | None = None) -> None:
        super().__init__("modetile")
        self.setMinimumHeight(96)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(14, 12, 14, 12)
        lay.setSpacing(8)
        self.tile = Tile(icon, accent)
        lay.addWidget(self.tile)
        lay.addStretch(1)
        self.name = label(name, "modename")
        self.status = label(status, "modestatus")
        lay.addWidget(self.name)
        lay.addWidget(self.status)

    def text(self) -> str:
        return f"{self.name.text()} · {self.status.text()}"


def tile_grid(tiles: Sequence[QWidget], columns: int = 2) -> QWidget:
    w = QWidget()
    grid = QGridLayout(w)
    grid.setContentsMargins(0, 0, 0, 0)
    grid.setSpacing(10)
    for i, tile in enumerate(tiles):
        grid.addWidget(tile, i // columns, i % columns)
    for c in range(columns):
        grid.setColumnStretch(c, 1)
    return w


class CoachCard(Clickable):
    """"Now · 07:05": one thing worth doing, why, a big button and "Not now". Tapping the card opens more."""

    acted = pyqtSignal()
    dismissed = pyqtSignal()
    info = pyqtSignal()

    def __init__(self, when: str = "Now") -> None:
        super().__init__("card")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(16, 12, 16, 10)
        lay.setSpacing(4)
        top = QHBoxLayout()
        top.setSpacing(6)
        self.tile = Tile("bell", None, 22)
        top.addWidget(self.tile)
        self.when = label(when, "coachwhen")
        top.addWidget(self.when, 1)
        self.info_button = IconButton("info", "iconbtn", size=22, tip="About this")
        self.info_button.clicked.connect(self.info)
        top.addWidget(self.info_button)
        lay.addLayout(top)
        self.say = label("", "coachsay", wrap=True)
        self.why = label("", "coachwhy", wrap=True)
        lay.addWidget(self.say)
        lay.addWidget(self.why)
        lay.addSpacing(10)
        self.action = text_button("", "primary")
        self.action.clicked.connect(self.acted)
        lay.addWidget(self.action)
        self.later = text_button("Not now", "link")
        self.later.clicked.connect(self.dismissed)
        lay.addWidget(self.later, 0, Qt.AlignmentFlag.AlignHCenter)

    def set_content(self, when: str, say: str, why: str = "", action: str = "", *, icon: str = "bell",
                    accent: str | None = None, quiet: bool = False) -> None:
        self.when.setText(when)
        self.say.setText(say)
        self.say.setProperty("class", "coachquiet" if quiet else "coachsay")
        repolish(self.say)
        self.why.setText(why)
        set_shown(self.why, bool(why))
        self.action.setText(action)
        set_shown(self.action, bool(action))
        set_shown(self.later, bool(action))
        self.tile.name, self.tile.accent = icon, accent
        self.tile.update()

    def text(self) -> str:
        return "\n".join(x for x in (self.say.text(), self.why.text()) if x)


# -- nav bar ----------------------------------------------------------------------------------------------------


class NavBar(QFrame):
    """44 high: back on the left, the title in the middle (only once the large title has scrolled away), buttons
    on the right."""

    def __init__(self, parent: QWidget) -> None:
        super().__init__(parent)
        self.setProperty("class", "navbar")
        grid = QGridLayout(self)
        grid.setContentsMargins(8, 0, 10, 0)
        grid.setSpacing(4)
        self.left = QHBoxLayout()
        self.left.setSpacing(0)
        self.right = QHBoxLayout()
        self.right.setSpacing(6)
        self.title = label("", "navtitle")
        self.title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.title.hide()
        left, right = QWidget(), QWidget()
        left.setLayout(self.left)
        right.setLayout(self.right)
        self.left.setContentsMargins(0, 0, 0, 0)
        self.right.setContentsMargins(0, 0, 0, 0)
        grid.addWidget(left, 0, 0, Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
        grid.addWidget(self.title, 0, 1)
        grid.addWidget(right, 0, 2, Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        grid.setColumnStretch(0, 1)
        grid.setColumnStretch(2, 1)
        self.back: IconButton | None = None

    def set_back(self, text: str, on_back) -> IconButton:
        if self.back is None:
            self.back = IconButton("back", "navback", text=text, size=24)
            self.back.clicked.connect(lambda: on_back())
            self.left.addWidget(self.back)
        self.back.setText(text)
        return self.back

    def add_right(self, w: QWidget) -> QWidget:
        self.right.addWidget(w)
        return w

    def set_compact(self, on: bool) -> None:
        if bool(self.property("compact")) == on:
            return
        self.setProperty("compact", on)
        set_shown(self.title, on)
        repolish(self)
