"""The Home calendar, after the PC's Calendar day view and its add-task panel.

- :class:`DayGrid`: a week (or the month) of days. Tap a day to show it. Dots mark days with events or open tasks.
- :class:`DayTimeline`: the chosen day as an hour gutter with coloured blocks, events and timed tasks side by side
  when they overlap, and the red line at now. Tap a block to edit it, an empty hour to add a task there.
- :class:`TaskRow`: a task without a time: the round check, the title, what list it is on. Tap to edit.
- :class:`ItemSheet`: add or edit a task or an event (title, day, time, length; done and delete when editing).

Colours follow the PC: desk work amber, meetings green, recovery purple, tasks blue.
"""

from __future__ import annotations

from datetime import date, datetime, time, timedelta

from PyQt6.QtCore import QRectF, Qt, QTime, pyqtSignal
from PyQt6.QtGui import QColor, QFont, QPainter, QPen
from PyQt6.QtWidgets import (
    QAbstractButton,
    QFrame,
    QHBoxLayout,
    QLineEdit,
    QScrollArea,
    QSizePolicy,
    QVBoxLayout,
    QWidget,
)

from aura_mobile.core import agenda
from aura_mobile.core.agenda import Item
from aura_mobile.ui import icons, kit, layers
from aura_mobile.ui import tokens as t
from aura_mobile.ui.widgets import label, set_shown

WEEKDAYS = "MTWTFSS"
#: an event's kind -> its colour; anything else is desk work
KIND_COLOUR = {"meeting": t.RECOVERY, "recovery": t.PURPLE}
DESK_COLOUR = t.STRESS


def look_colour(look: str) -> QColor:
    if look == "task":
        return kit.colour("accent")
    return QColor(KIND_COLOUR.get(look, DESK_COLOUR))


def day_title(day: date, today: date) -> str:
    """"Today", "Tomorrow", "Yesterday" or "Fri 24 Oct"."""
    word = {0: "Today", 1: "Tomorrow", -1: "Yesterday"}.get((day - today).days)
    return word or f"{day:%a} {day.day} {day:%b}"


def span_text(item: Item) -> str:
    if item.start is None or item.end is None:
        return ""
    return f"{item.start:%H:%M}–{item.end:%H:%M}"


def _font(px: int, bold: bool = False) -> QFont:
    f = QFont(t.FONT)
    f.setPixelSize(px)
    f.setWeight(QFont.Weight.DemiBold if bold else QFont.Weight.Normal)
    return f


# -- the days -------------------------------------------------------------------------------------------------


class DayGrid(QWidget):
    """Seven columns, Monday first: one row (week) or the whole month. ``picked`` sends the tapped date."""

    picked = pyqtSignal(object)
    HEAD = 20
    CELL = 44

    def __init__(self) -> None:
        super().__init__()
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.anchor = self.selected = self.today = date.today()
        self.month_mode = False
        self.marks: dict[date, set[str]] = {}
        self.setFixedHeight(self.HEAD + self.CELL)

    def days(self) -> list[date]:
        return agenda.month_grid(self.anchor) if self.month_mode else agenda.week_of(self.anchor)

    def set_state(self, *, anchor: date, selected: date, today: date, month_mode: bool,
                  marks: dict[date, set[str]]) -> None:
        self.anchor, self.selected, self.today, self.month_mode, self.marks = anchor, selected, today, month_mode, marks
        self.setFixedHeight(self.HEAD + len(self.days()) // 7 * self.CELL)
        self.update()

    def date_at(self, x: float, y: float) -> date | None:
        if y < self.HEAD or self.width() <= 0:
            return None
        days = self.days()
        i = int((y - self.HEAD) // self.CELL) * 7 + min(6, int(x // (self.width() / 7)))
        return days[i] if 0 <= i < len(days) else None

    def mouseReleaseEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton:
            d = self.date_at(event.position().x(), event.position().y())
            if d is not None:
                event.accept()
                self.picked.emit(d)
                return
        super().mouseReleaseEvent(event)

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        pal = t.current()
        col = self.width() / 7
        p.setFont(_font(t.SMALL, bold=True))
        p.setPen(t.qcolor(pal.label3))
        for c, letter in enumerate(WEEKDAYS):
            p.drawText(QRectF(c * col, 0, col, self.HEAD), Qt.AlignmentFlag.AlignCenter, letter)
        for i, d in enumerate(self.days()):
            cx = (i % 7 + 0.5) * col
            cy = self.HEAD + (i // 7) * self.CELL + 19
            chosen, is_today = d == self.selected, d == self.today
            in_month = not self.month_mode or d.month == self.anchor.month
            disc = QRectF(cx - 16, cy - 16, 32, 32)
            if chosen:
                p.setPen(Qt.PenStyle.NoPen)
                p.setBrush(t.qcolor(pal.go))
                p.drawEllipse(disc)
            elif is_today:
                p.setPen(QPen(t.qcolor(pal.accent), 1.6))
                p.setBrush(Qt.BrushStyle.NoBrush)
                p.drawEllipse(disc)
            ink = pal.go_ink if chosen else pal.accent if is_today else pal.label if in_month else pal.label3
            p.setFont(_font(t.ROW, bold=chosen or is_today))
            p.setPen(t.qcolor(ink))
            p.drawText(disc, Qt.AlignmentFlag.AlignCenter, str(d.day))
            dots = [k for k in ("event", "task") if k in self.marks.get(d, ())]
            p.setPen(Qt.PenStyle.NoPen)
            for n, k in enumerate(dots):
                x = cx + (n - (len(dots) - 1) / 2) * 7
                p.setBrush(QColor(DESK_COLOUR) if k == "event" else t.qcolor(pal.accent))
                p.drawEllipse(QRectF(x - 2.5, cy + 18, 5, 5))


class DayTimeline(QWidget):
    """One day as its 24 hours, the PC's day view on a phone. It sits in a :class:`HoursView`, which scrolls it."""

    #: an item's key ("task:<id>" / "event:<id>")
    item_clicked = pyqtSignal(str)
    #: minutes after midnight of the empty half hour tapped
    slot_clicked = pyqtSignal(int)
    HOUR = 40
    GUTTER = 46
    PAD = 8

    def __init__(self) -> None:
        super().__init__()
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.day = date.today()
        self.items: list[Item] = []
        self.now: datetime | None = None
        self.first, self.last = 0, 24
        self.setFixedHeight(2 * self.PAD + (self.last - self.first) * self.HOUR)

    def set_items(self, day: date, items: list[Item], now: datetime | None) -> None:
        """``now`` draws the red line (pass it only for today)."""
        self.day, self.items, self.now = day, [i for i in items if i.start is not None], now
        self.update()

    def _y(self, moment: datetime) -> float:
        return self._y_of(moment.hour * 60 + moment.minute)

    def _y_of(self, minutes: int) -> float:
        return self.PAD + (minutes - self.first * 60) * self.HOUR / 60

    def focus_y(self) -> float:
        """Where the box should open: the first thing planned (on today, the first not over yet), else an hour
        before now, else 08:00."""
        ahead = [i for i in self.items if self.now is None or (i.end is not None and i.end > self.now)]
        if self.items:
            first = min(i.start for i in (ahead or self.items) if i.start is not None)
            return self._y(first) - self.HOUR / 2
        if self.now is not None:
            return self._y(self.now) - self.HOUR
        return self._y_of(agenda.DAY_FROM * 60) - self.PAD

    def blocks(self) -> list[tuple[Item, QRectF]]:
        lanes = agenda.lanes(self.items)
        left = self.GUTTER + 4
        width = max(40.0, self.width() - left - 2)
        out = []
        for item in self.items:
            assert item.start is not None and item.end is not None
            lane, count = lanes.get(item.key, (0, 1))
            top = self._y(item.start)
            end = item.end if item.end.date() == item.start.date() else item.start.replace(hour=23, minute=59)
            bottom = max(top + 22, self._y(end))
            w = width / count
            out.append((item, QRectF(left + lane * w, top + 1, w - 3, bottom - top - 2)))
        return out

    def minutes_at(self, y: float) -> int:
        raw = self.first * 60 + (y - self.PAD) * 60 / self.HOUR
        return max(0, min(24 * 60 - 30, int(raw // 30) * 30))

    def mouseReleaseEvent(self, event) -> None:
        if event.button() != Qt.MouseButton.LeftButton:
            super().mouseReleaseEvent(event)
            return
        pos = event.position()
        event.accept()
        for item, rect in reversed(self.blocks()):
            if rect.contains(pos):
                self.item_clicked.emit(item.key)
                return
        if pos.x() >= self.GUTTER:
            self.slot_clicked.emit(self.minutes_at(pos.y()))

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        pal = t.current()
        p.setFont(_font(t.SMALL))
        right = Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter
        for h in range(self.first, self.last + 1):
            y = self.PAD + (h - self.first) * self.HOUR
            if h < 24:
                p.setPen(t.qcolor(pal.label3))
                p.drawText(QRectF(0, y - 8, self.GUTTER - 8, 16), right, f"{h:02d}:00")
            p.setPen(QPen(t.qcolor(pal.sep), 1))
            p.drawLine(int(self.GUTTER), int(y), self.width(), int(y))
        for item, rect in self.blocks():
            self._block(p, item, rect, pal)
        if self.now is not None and self.first <= self.now.hour < self.last:
            y = self._y(self.now)
            p.setPen(QPen(t.qcolor(pal.bad), 1.6))
            p.drawLine(int(self.GUTTER), int(y), self.width(), int(y))
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(t.qcolor(pal.bad))
            p.drawEllipse(QRectF(self.GUTTER - 4, y - 4, 8, 8))

    def _block(self, p: QPainter, item: Item, rect: QRectF, pal: t.Palette) -> None:
        tone = look_colour(item.look)
        soft = QColor(tone)
        soft.setAlpha(36 if item.done else 52)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(soft)
        p.drawRoundedRect(rect, 8, 8)
        p.setBrush(tone)
        p.drawRoundedRect(QRectF(rect.left(), rect.top(), 4, rect.height()), 2, 2)
        x = rect.left() + 10
        if item.kind == "task":
            ring = QRectF(x, rect.top() + 6, 12, 12)
            if item.done:
                p.setBrush(tone)
                p.drawEllipse(ring)
                p.drawPixmap(int(ring.left()) + 1, int(ring.top()) + 1,
                             icons.pixmap("check", QColor("white"), 10, stroke=2.6))
            else:
                p.setPen(QPen(tone, 1.5))
                p.setBrush(Qt.BrushStyle.NoBrush)
                p.drawEllipse(ring)
            x += 17
        title_font = _font(t.SUB, bold=True)
        title_font.setStrikeOut(item.done)
        p.setFont(title_font)
        p.setPen(t.qcolor(pal.label3 if item.done else pal.label))
        text_w = rect.right() - x - 6
        short = rect.height() < 38  # no room for the time line: put the start after the title
        text = f"{item.title} · {item.start:%H:%M}" if short and item.start is not None else item.title
        title = p.fontMetrics().elidedText(text, Qt.TextElideMode.ElideRight, int(text_w))
        p.drawText(QRectF(x, rect.top() + 3, text_w, 18), Qt.AlignmentFlag.AlignVCenter, title)
        if not short:
            p.setFont(_font(t.SMALL))
            p.setPen(t.qcolor(pal.label2))
            p.drawText(QRectF(x, rect.top() + 20, text_w, 16), Qt.AlignmentFlag.AlignVCenter, span_text(item))


class HoursView(QScrollArea):
    """The day's hours in a box of the card's height. It opens where something is planned (:meth:`DayTimeline.
    focus_y`) when the day changes or its first items arrive, and keeps the user's place on a reload. An empty day
    says so in a pill in the middle of the box, over no hour line."""

    def __init__(self) -> None:
        super().__init__()
        self.setProperty("class", "hours")
        self.setFrameShape(QFrame.Shape.NoFrame)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setWidgetResizable(True)
        self.line = DayTimeline()
        self.setWidget(self.line)
        self.empty = label("Nothing planned · tap an hour to add", "calempty")
        self.empty.setParent(self)
        self.empty.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)
        self.empty.hide()
        self._keys: set[str] = set()
        self._fresh = True
        #: where to scroll once the range allows it (the range grows only after the next layout)
        self._target: int | None = None
        self.verticalScrollBar().rangeChanged.connect(self._apply)

    def show_day(self, day: date, items: list[Item], now: datetime | None) -> None:
        keys = {i.key for i in items}
        jump = self._fresh or day != self.line.day or (keys and not self._keys)
        self._fresh = False
        self.line.set_items(day, items, now)
        self._keys = keys
        set_shown(self.empty, not self.line.items)
        self._place_empty()
        if jump:
            self.scroll_to(self.line.focus_y())

    def scroll_to(self, y: float) -> None:
        self._target = max(0, int(y))
        self._apply()

    def _apply(self, *_range: int) -> None:
        if self._target is None:
            return
        bar = self.verticalScrollBar()
        bar.setValue(min(self._target, bar.maximum()))
        if bar.maximum() >= self._target:
            self._target = None

    def _place_empty(self) -> None:
        self.empty.adjustSize()
        box = self.viewport().geometry()
        left, room = box.x() + DayTimeline.GUTTER, box.width() - DayTimeline.GUTTER
        width = min(self.empty.width(), room - 16)  # never over the hour labels
        self.empty.resize(width, self.empty.height())
        self.empty.move(left + (room - width) // 2, box.y() + (box.height() - self.empty.height()) // 2)
        self.empty.raise_()

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self._place_empty()


# -- tasks without a time ---------------------------------------------------------------------------------------


class CheckMark(QAbstractButton):
    """The round check in front of a task: an outline, or filled with a white tick."""

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


LIST_WORD = {"today": "Today", "week": "This week", "month": "This month"}


def task_sub(item: Item, day: date, today: date, tz) -> str:
    """What sits under a task's title: "Overdue · Thu 23", "Anytime · This week", or nothing."""
    task = item.raw
    when, _at = agenda.task_when(task, tz)
    if item.done:
        return "Done"
    if when is not None and when < today and day == today:
        return f"Overdue · {when:%a} {when.day}"
    if when is None:
        return f"Anytime · {LIST_WORD.get(str(task.get('horizon')), 'Today')}"
    return ""


class TaskRow(kit.Clickable):
    """A task without a time. The check ticks it; anywhere else opens the editor."""

    def __init__(self, item: Item, sub: str = "") -> None:
        super().__init__("taskrow")
        self.item = item
        self.setMinimumHeight(48)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(10, 4, 12, 4)
        lay.setSpacing(10)
        self.check = CheckMark("Mark not done" if item.done else "Mark done")
        self.check.setChecked(item.done)
        lay.addWidget(self.check)
        text = QVBoxLayout()
        text.setSpacing(0)
        self.title = label(item.title, "taskdone" if item.done else "rowtitle-strong")
        text.addWidget(self.title)
        self.sub = label(sub, "warn" if sub.startswith("Overdue") else "rowsub")
        set_shown(self.sub, bool(sub))
        text.addWidget(self.sub)
        lay.addLayout(text, 1)
        lay.addWidget(kit.Glyph("chevron", 14, "label3", stroke=2.4))
        self.setToolTip("Tap to edit")

    def text(self) -> str:
        return self.title.text()


class QuickAdd(QFrame):
    """A field in a rounded pill: type a task and press Enter (or Add)."""

    submitted = pyqtSignal()

    def __init__(self, placeholder: str) -> None:
        super().__init__()
        self.setProperty("class", "taskrow")
        self.setMinimumHeight(48)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(14, 4, 8, 4)
        lay.setSpacing(10)
        lay.addWidget(kit.Glyph("plus", 20, "accent", stroke=2.2))
        self.edit = QLineEdit()
        self.edit.setProperty("class", "plain")
        self.edit.setPlaceholderText(placeholder)
        self.edit.returnPressed.connect(self.submitted)
        lay.addWidget(self.edit, 1)
        self.add = kit.text_button("Add", "go")
        self.add.clicked.connect(self.submitted)
        self.add.hide()
        lay.addWidget(self.add)
        self.edit.textChanged.connect(lambda text: set_shown(self.add, bool(text.strip())))


# -- the card ---------------------------------------------------------------------------------------------------


class CalendarCard(QFrame):
    """The month with Week | Month and ‹ ›, the days, then the chosen day: its title, Today (on another day) and
    a big "+ Add", the hours (a box of their own that scrolls) and the to-do list with a quick-add field. The page
    fills it (:meth:`HomePage`) and sizes it (:meth:`fit_to`)."""

    #: the hours box never gets less than two and a half hours
    MIN_HOURS = 5 * DayTimeline.HOUR // 2

    def __init__(self) -> None:
        super().__init__()
        self.setProperty("class", "calcard")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(14, 14, 14, 16)
        lay.setSpacing(10)

        head = QHBoxLayout()
        head.setSpacing(6)
        self.month = label("", "calmonth")
        head.addWidget(self.month, 1)
        self.mode = kit.Segmented(["Week", "Month"])
        self.mode.setFixedWidth(132)
        head.addWidget(self.mode)
        self.prev = kit.IconButton("back", "round", size=16, tip="Earlier")
        self.next = kit.IconButton("chevron", "round", size=16, tip="Later")
        for b in (self.prev, self.next):
            head.addWidget(b)
        lay.addLayout(head)

        self.grid = DayGrid()
        lay.addWidget(self.grid)
        line = QFrame()
        line.setProperty("class", "hair")
        line.setFixedHeight(1)
        lay.addWidget(line)

        dayhead = QHBoxLayout()
        dayhead.setSpacing(8)
        words = QVBoxLayout()
        words.setSpacing(0)
        self.day_title = label("", "calday")
        self.day_count = label("", "rowsub")
        words.addWidget(self.day_title)
        words.addWidget(self.day_count)
        dayhead.addLayout(words, 1)
        self.today = kit.text_button("Today", "chip", tip="Back to today")
        self.today.hide()
        dayhead.addWidget(self.today, 0, Qt.AlignmentFlag.AlignVCenter)
        self.add = kit.text_button("+  Add", "go", tip="Add a task or an event")
        self.add.setMinimumWidth(92)
        dayhead.addWidget(self.add, 0, Qt.AlignmentFlag.AlignVCenter)
        lay.addLayout(dayhead)

        self.hours = HoursView()
        self.hours.setFixedHeight(5 * DayTimeline.HOUR)
        self.timeline = self.hours.line
        lay.addWidget(self.hours)

        self.todo_head = QWidget()
        todo_row = QHBoxLayout(self.todo_head)
        todo_row.setContentsMargins(0, 0, 0, 0)
        todo_row.addWidget(label("To do", "calsub"), 1)
        self.more = kit.text_button("", "link")
        self.more.hide()
        todo_row.addWidget(self.more)
        lay.addWidget(self.todo_head)
        self.todo = QVBoxLayout()
        self.todo.setSpacing(8)
        lay.addLayout(self.todo)
        self.quick = QuickAdd("Add a task")
        lay.addWidget(self.quick)

    def fit_to(self, total: int) -> None:
        """Make the whole card about ``total`` px tall: the hours box gets what the rest leaves (at least
        :attr:`MIN_HOURS`), so a long to-do list or the month grid makes the card taller instead."""
        self.layout().invalidate()
        rest = self.layout().sizeHint().height() - self.hours.minimumHeight()
        self.hours.setFixedHeight(max(self.MIN_HOURS, total - rest))

    def clear_todo(self) -> None:
        for i in reversed(range(self.todo.count())):
            w = self.todo.itemAt(i).widget()
            if w is not None:
                self.todo.takeAt(i)
                w.hide()
                w.deleteLater()

    def task_rows(self) -> list[TaskRow]:
        out = []
        for i in range(self.todo.count()):
            w = self.todo.itemAt(i).widget()
            if isinstance(w, TaskRow):
                out.append(w)
        return out


# -- add / edit ---------------------------------------------------------------------------------------------------


class DateStepper(QFrame):
    """‹ Fri 24 Oct ›: a day at a time."""

    changed = pyqtSignal(object)

    def __init__(self, day: date) -> None:
        super().__init__()
        self.setProperty("class", "stepper")
        self._day = day
        lay = QHBoxLayout(self)
        lay.setContentsMargins(2, 2, 2, 2)
        lay.setSpacing(2)
        self.minus = kit.IconButton("back", "stepbtn", key="label", size=14, tip="Day before")
        self.minus.clicked.connect(lambda: self.set_day(self._day - timedelta(days=1), emit=True))
        self.out = label("", "stepval")
        self.out.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.out.setMinimumWidth(96)
        self.plus = kit.IconButton("chevron", "stepbtn", key="label", size=14, tip="Day after")
        self.plus.clicked.connect(lambda: self.set_day(self._day + timedelta(days=1), emit=True))
        lay.addWidget(self.minus)
        lay.addWidget(self.out)
        lay.addWidget(self.plus)
        self._render()

    def day(self) -> date:
        return self._day

    def set_day(self, day: date, *, emit: bool = False) -> None:
        self._day = day
        self._render()
        if emit:
            self.changed.emit(day)

    def _render(self) -> None:
        self.out.setText(f"{self._day:%a} {self._day.day} {self._day:%b}")


class ItemSheet(layers.Sheet):
    """Add or edit a task or an event.

    A task has a title and, optionally, a day and a time on it (no day: "Anytime", on today's list). An event always
    has a day, a start and a length. Editing a task also offers Done / Not done and Delete; an event offers Delete.
    A task placed by a calendar block (``locked``) keeps its time: only its title changes here.
    After ``exec()``: ``deleted`` or ``toggle_done`` when one of those closed it, else :meth:`values`.
    """

    def __init__(self, parent: QWidget | None, *, today: date, day: date, kind: str = "task",
                 item: Item | None = None, at: time | None = None, minutes: int = 30, dated: bool | None = None,
                 locked: bool = False) -> None:
        editing = item is not None
        heading = "New" if not editing else "Task" if kind == "task" else "Event"
        super().__init__(parent, heading, done="Save" if editing else "Add", cancel="Cancel")
        self.kind = kind
        self.today = today
        self.locked = locked
        self.deleted = False
        self.toggle_done = False

        self.kinds = kit.Segmented(["Task", "Event"], 0 if kind == "task" else 1)
        self.kinds.changed.connect(self._kind_changed)
        set_shown(self.kinds, not editing)
        self.body.addWidget(self.kinds)

        form = kit.Group(sheet=True)
        self.title_edit = QLineEdit(item.title if item else "")
        self.title_edit.setProperty("class", "plain")
        self.title_edit.setPlaceholderText("Title")
        self.title_edit.textChanged.connect(self._valid)
        self.title_edit.returnPressed.connect(self.accept)
        title_row = QFrame()
        title_row.setProperty("class", "row")
        title_row.setMinimumHeight(t.ROW_MIN)
        row = QHBoxLayout(title_row)
        row.setContentsMargins(16, 4, 16, 4)
        row.addWidget(self.title_edit)
        form.add(title_row)
        self.body.addWidget(form)

        when = kit.Group(sheet=True)
        if dated is None:
            dated = day != today or kind == "event" or at is not None
        self.has_day = kit.Switch(dated, tip="Put the task on a day")
        self.has_day.toggled.connect(self._sync)
        self.day_switch_row = when.add(kit.Row("Date", icon="cal", accent=t.BLUE, trailing=self.has_day))
        self.date = DateStepper(day)
        self.date_row = when.add(kit.Row("Day", trailing=self.date))
        self.has_time = kit.Switch(at is not None, tip="Give the task a time")
        self.has_time.toggled.connect(self._sync)
        self.time_switch_row = when.add(kit.Row("Time", icon="clock", accent=t.STRESS, trailing=self.has_time))
        start = at or time(min(23, datetime.now().hour + 1), 0)
        self.time = kit.time_pill(QTime(start.hour, start.minute))
        self.time_row = when.add(kit.Row("Starts", trailing=self.time))
        self.length = kit.Stepper(minutes, 15, 480, 15, "{} min")
        self.length_row = when.add(kit.Row("Length", trailing=self.length))
        self.body.addWidget(when)
        set_shown(when, not locked)
        self.locked_note = label("Its time comes from its calendar block.", "hint")
        set_shown(self.locked_note, locked)
        self.body.addWidget(self.locked_note)

        self.more = kit.Group(sheet=True)
        self.done_row: kit.Row | None = None
        if editing and kind == "task":
            self.done_row = self.more.add(kit.Row("Mark not done" if item.done else "Mark done", icon="check",
                                                  accent=t.RECOVERY, kind="link"))
            self.done_row.clicked.connect(self._toggle_done)
        if editing:
            gone = self.more.add(kit.Row(f"Delete {kind}", icon="trash", accent=t.WORKOUT, kind="danger"))
            gone.clicked.connect(self._delete)
        set_shown(self.more, editing)
        self.body.addWidget(self.more)
        self._sync()
        self._valid()

    def _kind_changed(self, index: int) -> None:
        self.kind = "event" if index == 1 else "task"
        if self.kind == "event":
            self.has_day.setChecked(True)
        self._sync()

    def _sync(self) -> None:
        task = self.kind == "task"
        set_shown(self.day_switch_row, task)
        set_shown(self.time_switch_row, task)
        dated = not task or self.has_day.isChecked()
        timed = not task or (dated and self.has_time.isChecked())
        set_shown(self.date_row, dated)
        set_shown(self.time_switch_row, task and dated)
        set_shown(self.time_row, timed)
        set_shown(self.length_row, timed)

    def _valid(self) -> None:
        self.done_button.setEnabled(bool(self.title_edit.text().strip()))

    def _toggle_done(self) -> None:
        self.toggle_done = True
        self.accept_quietly()

    def _delete(self) -> None:
        if layers.Alert.ask(self, f"Delete this {self.kind}?", "It goes from every device.", "Delete", danger=True):
            self.deleted = True
            self.accept_quietly()

    def accept_quietly(self) -> None:
        """Close as accepted without a title (Done and Delete don't need one)."""
        super().accept()

    def accept(self) -> None:
        if self.title_edit.text().strip():
            super().accept()

    def values(self) -> tuple[str, str, date | None, time | None, int]:
        """(kind, title, day or None, time or None, minutes)."""
        dated = self.kind == "event" or self.has_day.isChecked()
        timed = self.kind == "event" or (dated and self.has_time.isChecked())
        qt = self.time.time()
        return (self.kind, self.title_edit.text().strip(), self.date.day() if dated else None,
                time(qt.hour(), qt.minute()) if timed else None, self.length.value())
