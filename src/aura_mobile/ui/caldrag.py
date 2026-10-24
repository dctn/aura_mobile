"""Dragging calendar items with the mouse, as on the website (``calendar/useDrag.ts``). A press on a block or a to-do
row drags once the mouse moves a few pixels. While dragging:

- over the hours, the block follows in 15-minute steps (its bottom edge stretches the length instead);
- over a day in the grid, the item goes to that day at the same time;
- holding on ‹ › flips the week or month, holding at the hours' left or right edge flips the day;
- near the top or bottom of the hours, they scroll.

Esc or letting go anywhere else puts it back. A chip follows the pointer with where the item would land; it holds
the mouse and the keyboard while the drag lasts, so the row it started from can be rebuilt under it. The drop goes
out as :attr:`CalDrag.dropped`; Home writes it.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import TYPE_CHECKING

from PyQt6.QtCore import QEvent, QObject, QPoint, QPointF, Qt, QTimer, pyqtSignal
from PyQt6.QtGui import QCursor
from PyQt6.QtWidgets import QLabel, QWidget

from aura_mobile.core import drag as d
from aura_mobile.core.agenda import Item

if TYPE_CHECKING:
    from aura_mobile.ui.calview import CalendarCard

MOUSE_SLOP = 5
#: how long a hold on ‹ › or a side edge waits before flipping, and between flips
FLIP_MS = 650
SIDE_PX = 22
SCROLL_PX = 24
SCROLL_STEP = 6
#: the bottom of a block (not a short one) that stretches it
HANDLE_PX = 8


@dataclass
class Live:
    item: Item
    mode: str  # "move" | "stretch"
    #: the day the item shows on
    day: date
    #: where in the block it was picked up (minutes), so it doesn't jump under the pointer
    grab: float
    pos: QPointF
    target: d.Target | None = None
    over_day: date | None = None
    side: int = 0


class DragChip(QLabel):
    """What follows the pointer: the item and where it would land."""

    def __init__(self, owner: CalDrag, parent: QWidget) -> None:
        super().__init__(parent)
        self.owner = owner
        self.setProperty("class", "dragchip")
        self.setTextFormat(Qt.TextFormat.RichText)
        self.hide()

    def mouseMoveEvent(self, event) -> None:
        self.owner.aim(event.globalPosition())

    def mouseReleaseEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton:
            self.owner.end(True)

    def keyPressEvent(self, event) -> None:
        if event.key() == Qt.Key.Key_Escape:
            self.owner.end(False)


class CalDrag(QObject):
    """The card's drag: it watches the timeline and each to-do row and paints the preview on them."""

    #: (item, the day it showed on, :class:`~aura_mobile.core.drag.Target`)
    dropped = pyqtSignal(object, object, object)
    #: -1 / 1: show the day before / after (a hold at the hours' edge)
    step_day = pyqtSignal(int)
    #: -1 / 1: ‹ › (a hold on them)
    flip = pyqtSignal(int)

    def __init__(self, card: CalendarCard) -> None:
        super().__init__(card)
        self.card = card
        self.live: Live | None = None
        self._press: tuple[Item, str, date, float, QPointF] | None = None
        #: a release that ends a drag (or follows an Esc) must not click what is under it
        self._swallow = False
        self.chip: DragChip | None = None
        self._hold_key: str | None = None
        self._hold = QTimer(self)
        self._hold.setInterval(FLIP_MS)
        self._hold.timeout.connect(self._held)
        self._scroll = QTimer(self)
        self._scroll.setInterval(16)
        self._scroll.timeout.connect(self._autoscroll)
        line = card.timeline
        line.setMouseTracking(True)
        line.installEventFilter(self)

    def watch(self, row: QWidget) -> None:
        """Let a to-do row (:class:`~aura_mobile.ui.calview.TaskRow`) be dragged into the hours or onto a day."""
        row.installEventFilter(self)

    # -- presses ---------------------------------------------------------------------------------------------

    def _hit(self, obj: QObject, pos: QPointF) -> tuple[Item, str, date, float] | None:
        line = self.card.timeline
        if obj is line:
            for item, rect in reversed(line.blocks()):
                if rect.contains(pos) and item.start is not None:
                    short = rect.height() < line.SHORT
                    mode = "stretch" if not short and pos.y() > rect.bottom() - HANDLE_PX else "move"
                    grab = max(0.0, line.minute_of(pos.y()) - d.start_minute(item))
                    return item, mode, item.start.date(), grab
            return None
        item = getattr(obj, "item", None)
        if isinstance(item, Item):
            return item, "move", line.day, min(15.0, item.minutes / 2)
        return None

    def eventFilter(self, obj: QObject, event: QEvent) -> bool:
        kind = event.type()
        if kind == QEvent.Type.MouseButtonPress and event.button() == Qt.MouseButton.LeftButton:
            hit = self._hit(obj, event.position())
            self._press = (*hit, event.globalPosition()) if hit and d.can_drag(hit[0]) else None
            self._swallow = False
        elif kind == QEvent.Type.MouseMove:
            if self.live is not None:
                self.aim(event.globalPosition())
                return True
            if self._press is not None and event.buttons() & Qt.MouseButton.LeftButton:
                item, mode, day, grab, at = self._press
                if (event.globalPosition() - at).manhattanLength() > MOUSE_SLOP:
                    self.begin(item, mode, day, grab, event.globalPosition())
                    return True
            elif obj is self.card.timeline and not event.buttons():
                self._hover(event.position())
        elif kind == QEvent.Type.MouseButtonRelease:
            self._press = None
            if self.live is not None:
                self.end(True)
                return True
            if self._swallow:
                self._swallow = False
                return True
        return False

    def _hover(self, pos: QPointF) -> None:
        """The pointer says what a press would do: stretch, move, or add."""
        hit = self._hit(self.card.timeline, pos)
        shape = Qt.CursorShape.PointingHandCursor
        if hit is not None and d.can_drag(hit[0]):
            shape = Qt.CursorShape.SizeVerCursor if hit[1] == "stretch" else Qt.CursorShape.OpenHandCursor
        self.card.timeline.setCursor(shape)

    # -- the drag ----------------------------------------------------------------------------------------------

    def begin(self, item: Item, mode: str, day: date, grab: float, pos: QPointF) -> None:
        self._press = None
        self.live = Live(item, mode, day, grab, pos)
        self.card.timeline.lifted = item.key
        window = self.card.window()
        if self.chip is None or self.chip.parentWidget() is not window:
            self.chip = DragChip(self, window)
        self.chip.show()
        self.chip.raise_()
        if self.chip.isVisible():
            self.chip.grabMouse(QCursor(Qt.CursorShape.ClosedHandCursor))
            self.chip.grabKeyboard()
        self._scroll.start()
        self.aim(pos)

    def aim(self, pos: QPointF) -> None:
        """Where the drag would land with the pointer at ``pos`` (global)."""
        live = self.live
        if live is None:
            return
        card, item = self.card, live.item
        target: d.Target | None = None
        over: date | None = None
        side = 0
        hold: str | None = None
        at = pos.toPoint()
        grid_at = card.grid.mapFromGlobal(at)
        over_grid = card.grid.rect().contains(grid_at)
        day = card.grid.date_at(grid_at.x(), grid_at.y()) if over_grid and live.mode == "move" else None
        flip = self._flipper(at) if live.mode == "move" else 0
        port = card.hours.viewport()
        if day is not None:
            over = day
            target = d.Target(day, d.start_minute(item), item.minutes)
        elif flip:
            hold = f"flip:{flip}"
        elif port.rect().contains(port.mapFromGlobal(at)):
            line = card.timeline
            minute = line.minute_of(line.mapFromGlobal(at).y())
            start = d.start_minute(item)
            if live.mode == "stretch" and start is not None:
                target = d.Target(live.day, start, d.stretch_to(minute, start))
            else:
                target = d.Target(line.day, d.drop_start(minute, live.grab, item.minutes), item.minutes)
                x = port.mapFromGlobal(at).x()
                side = -1 if x < SIDE_PX else 1 if x > port.width() - SIDE_PX else 0
                hold = f"side:{side}" if side else None
        live.pos, live.target, live.over_day, live.side = pos, target, over, side
        self._hold_on(hold)
        self._show()

    def _flipper(self, at: QPoint) -> int:
        for n, button in ((-1, self.card.prev), (1, self.card.next)):
            if button.isVisible() and button.rect().contains(button.mapFromGlobal(at)):
                return n
        return 0

    def end(self, drop: bool) -> None:
        live = self.live
        self._press = None
        self._hold_on(None)
        self._scroll.stop()
        if live is None:
            return
        self.live = None
        self._swallow = not drop
        if self.chip is not None:
            self.chip.releaseMouse()
            self.chip.releaseKeyboard()
            self.chip.hide()
        line = self.card.timeline
        line.lifted, line.preview = None, None
        line.update()
        self.card.grid.over = None
        self.card.grid.update()
        if drop and live.target is not None:
            self.dropped.emit(live.item, live.day, live.target)

    # -- what it looks like -----------------------------------------------------------------------------------

    def _show(self) -> None:
        live = self.live
        if live is None:
            return
        line, grid, target = self.card.timeline, self.card.grid, live.target
        on_day = target is not None and target.day == line.day and target.start is not None
        line.preview = (live.item, target) if on_day else None
        line.lifted = live.item.key
        line.update()
        if grid.over != live.over_day:
            grid.over = live.over_day
            grid.update()
        chip = self.chip
        if chip is None:
            return
        where = d.target_text(target) if target is not None else "Let go here to put it back"
        chip.setText(f"<b>{_escape(live.item.title)}</b><br>{_escape(where)}")
        chip.adjustSize()
        parent = chip.parentWidget()
        spot = parent.mapFromGlobal(live.pos.toPoint()) + QPoint(14, 14)
        x = min(spot.x(), parent.width() - chip.width() - 4)
        y = spot.y() if spot.y() + chip.height() < parent.height() - 4 else spot.y() - chip.height() - 28
        chip.move(max(4, x), max(4, y))

    # -- holds and scrolling ------------------------------------------------------------------------------------

    def _hold_on(self, key: str | None) -> None:
        """Flip after :data:`FLIP_MS` of holding on the same spot, then again every :data:`FLIP_MS`."""
        if key == self._hold_key:
            return
        self._hold_key = key
        self._hold.stop()
        if key:
            self._hold.start()

    def _held(self) -> None:
        live, key = self.live, self._hold_key
        if live is None or not key:
            return
        what, n = key.split(":")
        (self.flip if what == "flip" else self.step_day).emit(int(n))
        self.aim(live.pos)  # the day under the pointer changed

    def _autoscroll(self) -> None:
        """Scroll the hours while the pointer rests near their top or bottom, faster the deeper into the band."""
        live = self.live
        if live is None:
            return
        port = self.card.hours.viewport()
        at = port.mapFromGlobal(live.pos.toPoint())
        if not 0 <= at.x() <= port.width():
            return
        up = SCROLL_PX - at.y()
        down = at.y() - (port.height() - SCROLL_PX)
        into = -up if up > 0 and at.y() > -SCROLL_PX else down if down > 0 and at.y() < port.height() + SCROLL_PX else 0
        if not into:
            return
        bar = self.card.hours.verticalScrollBar()
        before = bar.value()
        step = max(1, round(SCROLL_STEP * min(SCROLL_PX, abs(into)) / SCROLL_PX))
        bar.setValue(before + (step if into > 0 else -step))
        if bar.value() != before:
            self.aim(live.pos)


def _escape(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
